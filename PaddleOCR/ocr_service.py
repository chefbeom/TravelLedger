import json
import os
import re
import secrets
import tempfile
import threading
import time
import warnings
from collections.abc import Mapping
from datetime import date
from pathlib import Path

import requests
import uvicorn
from fastapi import Depends, FastAPI, File, Header, HTTPException, UploadFile, status


class Settings:
    def __init__(self):
        self.api_key = os.getenv("OCR_API_KEY", "change-me")
        self.host = os.getenv("OCR_HOST", "127.0.0.1")
        self.port = int(os.getenv("OCR_PORT", "8765"))
        self.lang = os.getenv("OCR_LANG", "korean")
        self.device = os.getenv("OCR_DEVICE", "cpu")
        self.cpu_threads = int(os.getenv("OCR_CPU_THREADS", "12"))
        self.max_upload_bytes = int(os.getenv("OCR_MAX_UPLOAD_BYTES", str(10 * 1024 * 1024)))
        self.llm_provider = os.getenv("LLM_PROVIDER", "none").strip().lower()
        self.llm_base_url = os.getenv("LLM_BASE_URL", "").strip().rstrip("/")
        self.llm_api_key = os.getenv("LLM_API_KEY", "").strip()
        self.llm_model = os.getenv("LLM_MODEL", "gemma:2b")
        self.llm_timeout_seconds = float(os.getenv("LLM_TIMEOUT_SECONDS", "30"))


settings = Settings()
app = FastAPI(title="Calen OCR Analysis Service", version="1.0.0")
ocr_instance = None
ocr_lock = threading.Lock()


def iter_texts(result):
    """Yield recognized text from PaddleOCR 2.x and 3.x result shapes."""
    if result is None:
        return

    if isinstance(result, Mapping):
        for key in ("rec_texts", "texts"):
            texts = result.get(key)
            if texts:
                for text in texts:
                    yield str(text)
                return

        for value in result.values():
            yield from iter_texts(value)
        return

    if isinstance(result, (list, tuple)):
        if (
            len(result) >= 2
            and isinstance(result[1], (list, tuple))
            and result[1]
            and isinstance(result[1][0], str)
        ):
            yield result[1][0]
            return

        for item in result:
            yield from iter_texts(item)


def load_ocr():
    global ocr_instance
    if ocr_instance is not None:
        return ocr_instance

    with ocr_lock:
        if ocr_instance is not None:
            return ocr_instance

        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            from paddleocr import PaddleOCR

            ocr_instance = PaddleOCR(
                lang=settings.lang,
                device=settings.device,
                enable_mkldnn=settings.device == "cpu",
                cpu_threads=settings.cpu_threads,
                use_doc_orientation_classify=False,
                use_doc_unwarping=False,
                use_textline_orientation=False,
            )
        return ocr_instance


def predict_texts(image_path):
    ocr = load_ocr()
    with ocr_lock:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            result = ocr.predict(str(image_path))
    return [text.strip() for text in iter_texts(result) if str(text).strip()]


def normalize_amount(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return int(value) if value > 0 else None
    digits = re.sub(r"[^0-9]", "", str(value))
    if not digits:
        return None
    amount = int(digits)
    return amount if amount > 0 else None


def parse_date(raw_text):
    current_year = date.today().year
    patterns = [
        r"(20\d{2})[.\-/년\s]+(\d{1,2})[.\-/월\s]+(\d{1,2})",
        r"(\d{1,2})[.\-/월\s]+(\d{1,2})[일\s]",
    ]
    for pattern in patterns:
        match = re.search(pattern, raw_text)
        if not match:
            continue
        if len(match.groups()) == 3:
            year, month, day = match.groups()
        else:
            year = current_year
            month, day = match.groups()
        try:
            return date(int(year), int(month), int(day)).isoformat()
        except ValueError:
            continue
    return None


def parse_time(raw_text):
    match = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", raw_text)
    if not match:
        return None
    return f"{int(match.group(1)):02d}:{match.group(2)}"


def parse_amount(raw_text):
    candidates = []
    for match in re.finditer(r"(?:KRW|W|₩)?\s*([0-9][0-9,]{2,})(?:\s*원)?", raw_text, re.IGNORECASE):
        amount = normalize_amount(match.group(1))
        if amount:
            candidates.append(amount)
    return max(candidates) if candidates else None


def parse_vendor(lines):
    ignored = {"합계", "총액", "승인", "카드", "일시", "금액", "부가세", "거래"}
    for line in lines[:8]:
        compact = re.sub(r"\s+", "", line)
        if len(compact) < 2:
            continue
        if any(word in compact for word in ignored):
            continue
        if normalize_amount(compact):
            continue
        return line[:80]
    return None


def heuristic_parse(lines):
    raw_text = "\n".join(lines)
    vendor = parse_vendor(lines)
    amount = parse_amount(raw_text)
    warnings = []
    if not amount:
        warnings.append("amount_not_found")
    return {
        "entryDate": parse_date(raw_text),
        "entryTime": parse_time(raw_text),
        "entryType": "EXPENSE",
        "title": vendor or "Receipt",
        "memo": vendor,
        "amount": amount,
        "vendor": vendor,
        "paymentMethodText": None,
        "categoryGroupName": None,
        "categoryDetailName": None,
        "categoryText": None,
        "lineItems": [],
        "confidence": 0.45 if amount else 0.2,
        "warnings": warnings,
    }


def extract_json_object(text):
    if not text:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end <= start:
        return None
    try:
        return json.loads(text[start : end + 1])
    except json.JSONDecodeError:
        return None


def build_llm_prompt(raw_text):
    return (
        "You parse Korean receipt OCR text into strict JSON for a household ledger. "
        "Return only JSON with these keys: entryDate YYYY-MM-DD or null, "
        "entryTime HH:mm or null, entryType EXPENSE, title, memo, amount number or null, "
        "vendor, paymentMethodText, categoryGroupName, categoryDetailName, categoryText, "
        "lineItems array of {itemName, quantity, unit, price}, confidence 0..1, warnings array. "
        "Use the final paid total as amount. Do not invent category IDs.\n\nOCR text:\n"
        f"{raw_text[:6000]}"
    )


def call_openai_compatible(raw_text):
    url = f"{settings.llm_base_url}/chat/completions"
    if not settings.llm_base_url.endswith("/v1"):
        url = f"{settings.llm_base_url}/v1/chat/completions"
    headers = {"Content-Type": "application/json"}
    if settings.llm_api_key:
        headers["Authorization"] = f"Bearer {settings.llm_api_key}"
    response = requests.post(
        url,
        headers=headers,
        timeout=settings.llm_timeout_seconds,
        json={
            "model": settings.llm_model,
            "temperature": 0,
            "messages": [
                {"role": "system", "content": "Return valid JSON only."},
                {"role": "user", "content": build_llm_prompt(raw_text)},
            ],
        },
    )
    response.raise_for_status()
    data = response.json()
    return data["choices"][0]["message"]["content"]


def call_ollama(raw_text):
    response = requests.post(
        f"{settings.llm_base_url}/api/chat",
        timeout=settings.llm_timeout_seconds,
        json={
            "model": settings.llm_model,
            "stream": False,
            "format": "json",
            "messages": [
                {"role": "system", "content": "Return valid JSON only."},
                {"role": "user", "content": build_llm_prompt(raw_text)},
            ],
        },
    )
    response.raise_for_status()
    data = response.json()
    return data.get("message", {}).get("content", "")


def parse_with_llm(raw_text):
    if settings.llm_provider not in {"openai", "ollama"} or not settings.llm_base_url:
        return None, None
    try:
        if settings.llm_provider == "ollama":
            content = call_ollama(raw_text)
        else:
            content = call_openai_compatible(raw_text)
        parsed = extract_json_object(content)
        if isinstance(parsed, dict):
            return parsed, None
        return None, "llm_invalid_json"
    except requests.RequestException:
        return None, "llm_request_failed"
    except (KeyError, TypeError, ValueError):
        return None, "llm_response_unexpected"


def merge_parsed(heuristic, llm_result, llm_warning):
    parsed = dict(heuristic)
    warnings = list(parsed.get("warnings") or [])
    if isinstance(llm_result, dict):
        for key, value in llm_result.items():
            if value not in (None, "", []):
                parsed[key] = value
    if llm_warning:
        warnings.append(llm_warning)
    parsed["amount"] = normalize_amount(parsed.get("amount"))
    parsed["entryType"] = "EXPENSE"
    parsed["warnings"] = sorted(set(str(item) for item in warnings if item))
    return parsed


async def require_api_key(x_ocr_api_key: str = Header(default="")):
    if not settings.api_key or not secrets.compare_digest(x_ocr_api_key, settings.api_key):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid OCR API key.")


@app.get("/health")
def health():
    return {
        "ok": True,
        "ocrLoaded": ocr_instance is not None,
        "llmProvider": settings.llm_provider,
    }


@app.post("/analyze")
async def analyze(file: UploadFile = File(...), _: None = Depends(require_api_key)):
    started_at = time.perf_counter()
    content = await file.read(settings.max_upload_bytes + 1)
    if not content:
        raise HTTPException(status_code=status.HTTP_400_BAD_REQUEST, detail="Image file is empty.")
    if len(content) > settings.max_upload_bytes:
        raise HTTPException(status_code=status.HTTP_413_REQUEST_ENTITY_TOO_LARGE, detail="Image file is too large.")

    suffix = Path(file.filename or "receipt.jpg").suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".webp", ".bmp"}:
        suffix = ".jpg"

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(delete=False, suffix=suffix) as temp_file:
            temp_file.write(content)
            temp_path = Path(temp_file.name)

        ocr_started_at = time.perf_counter()
        lines = predict_texts(temp_path)
        ocr_ms = int((time.perf_counter() - ocr_started_at) * 1000)

        raw_text = "\n".join(lines)
        heuristic = heuristic_parse(lines)
        llm_started_at = time.perf_counter()
        llm_result, llm_warning = parse_with_llm(raw_text)
        llm_ms = int((time.perf_counter() - llm_started_at) * 1000)
        parsed = merge_parsed(heuristic, llm_result, llm_warning)

        return {
            "ok": True,
            "rawText": raw_text,
            "parsed": parsed,
            "timing": {
                "ocrMs": ocr_ms,
                "llmMs": llm_ms,
                "totalMs": int((time.perf_counter() - started_at) * 1000),
            },
        }
    finally:
        if temp_path:
            try:
                temp_path.unlink(missing_ok=True)
            except OSError:
                pass


if __name__ == "__main__":
    uvicorn.run("ocr_service:app", host=settings.host, port=settings.port, reload=False)
