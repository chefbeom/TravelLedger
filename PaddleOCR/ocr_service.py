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
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, status
from PIL import Image, ImageOps


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
        self.rotation_mode = os.getenv("OCR_ROTATION_MODE", "off").strip().lower()
        self.original_accept_score = float(os.getenv("OCR_ORIGINAL_ACCEPT_SCORE", "180"))


settings = Settings()
app = FastAPI(title="Calen OCR Analysis Service", version="1.0.0")
ocr_instance = None
ocr_lock = threading.Lock()

RECEIPT_KEYWORDS = (
    "\uacb0\uc81c",
    "\uc2e0\uc6a9\uce74\ub4dc",
    "\uccb4\ud06c\uce74\ub4dc",
    "\uce74\ub4dc",
    "\uc601\uc218\uc99d",
    "\uc2b9\uc778",
    "\ud569\uacc4",
    "\ucd1d\uc561",
    "\uacfc\uc138",
    "\ubd80\uac00\uc138",
    "\ud310\ub9e4",
    "\uc0ac\uc5c5\uc790",
)


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


def to_plain_value(value):
    if hasattr(value, "tolist"):
        return value.tolist()
    return value


def box_to_bounds(box):
    value = to_plain_value(box)
    if not value:
        return None

    if (
        isinstance(value, (list, tuple))
        and len(value) == 4
        and all(isinstance(item, (int, float)) for item in value)
    ):
        x1, y1, x2, y2 = value
        return [float(x1), float(y1), float(x2), float(y2)]

    points = []
    for point in value:
        point_value = to_plain_value(point)
        if isinstance(point_value, (list, tuple)) and len(point_value) >= 2:
            points.append((float(point_value[0]), float(point_value[1])))

    if not points:
        return None

    xs = [point[0] for point in points]
    ys = [point[1] for point in points]
    return [min(xs), min(ys), max(xs), max(ys)]


def make_ocr_block(text, score=None, box=None):
    text = str(text or "").strip()
    if not text:
        return None

    bounds = box_to_bounds(box) if box is not None else None
    score_value = None
    try:
        score_value = float(score) if score is not None else None
    except (TypeError, ValueError):
        score_value = None

    block = {"text": text, "score": score_value}
    if bounds:
        x1, y1, x2, y2 = bounds
        block.update(
            {
                "box": [round(x1, 2), round(y1, 2), round(x2, 2), round(y2, 2)],
                "x1": x1,
                "y1": y1,
                "x2": x2,
                "y2": y2,
                "cx": (x1 + x2) / 2,
                "cy": (y1 + y2) / 2,
                "width": max(x2 - x1, 0),
                "height": max(y2 - y1, 0),
            }
        )
    return block


def iter_ocr_blocks(result):
    """Yield recognized text with confidence and bounding boxes when available."""
    if result is None:
        return

    if isinstance(result, Mapping):
        texts = result.get("rec_texts") or result.get("texts")
        if texts:
            scores = result.get("rec_scores") or result.get("scores") or []
            boxes = result.get("rec_boxes")
            if boxes is None:
                boxes = result.get("rec_polys") or result.get("dt_polys") or []
            for index, text in enumerate(texts):
                score = scores[index] if index < len(scores) else None
                box = boxes[index] if index < len(boxes) else None
                block = make_ocr_block(text, score, box)
                if block:
                    yield block
            return

        for value in result.values():
            yield from iter_ocr_blocks(value)
        return

    if isinstance(result, (list, tuple)):
        if (
            len(result) >= 2
            and isinstance(result[1], (list, tuple))
            and result[1]
            and isinstance(result[1][0], str)
        ):
            score = result[1][1] if len(result[1]) > 1 else None
            block = make_ocr_block(result[1][0], score, result[0])
            if block:
                yield block
            return

        for item in result:
            yield from iter_ocr_blocks(item)


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

            try:
                ocr_instance = PaddleOCR(
                    lang=settings.lang,
                    device=settings.device,
                    enable_mkldnn=settings.device == "cpu",
                    cpu_threads=settings.cpu_threads,
                    use_doc_orientation_classify=False,
                    use_doc_unwarping=False,
                    use_textline_orientation=False,
                )
            except (TypeError, ValueError):
                ocr_instance = PaddleOCR(
                    lang=settings.lang,
                    use_gpu=settings.device != "cpu",
                    enable_mkldnn=settings.device == "cpu",
                    cpu_threads=settings.cpu_threads,
                    use_angle_cls=False,
                    show_log=False,
                )
        return ocr_instance


def run_ocr(image_path):
    return [block["text"] for block in run_ocr_blocks(image_path)]


def run_ocr_blocks(image_path):
    ocr = load_ocr()
    with ocr_lock:
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            if hasattr(ocr, "predict"):
                result = ocr.predict(str(image_path))
            else:
                result = ocr.ocr(str(image_path), cls=False)
    return list(iter_ocr_blocks(result))


def get_rotation_degrees(image_path):
    if settings.rotation_mode == "all":
        return [0, 90, 180, 270]
    return [0]


def save_rotation_candidate(source_path, temp_dir, degrees):
    with Image.open(source_path) as image:
        normalized = ImageOps.exif_transpose(image)
        if degrees:
            normalized = normalized.rotate(degrees, expand=True)
        if normalized.mode not in {"RGB", "L"}:
            normalized = normalized.convert("RGB")
        candidate_path = temp_dir / f"ocr-rotation-{degrees}.png"
        normalized.save(candidate_path)
        return candidate_path


def get_ocr_quality(lines):
    raw_text = "\n".join(lines)
    return {
        "raw_text_length": len(raw_text),
        "hangul_count": len(re.findall(r"[\uac00-\ud7a3]", raw_text)),
        "amount_count": len(re.findall(r"(?:\d{1,3}(?:,\d{3})+|\b\d{4,}\b)", raw_text)),
        "date_count": len(re.findall(r"20\d{2}[.\-/\s]?\d{1,2}[.\-/\s]?\d{1,2}", raw_text)),
        "time_count": len(re.findall(r"\b(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?\b", raw_text)),
        "keyword_count": sum(raw_text.count(keyword) for keyword in RECEIPT_KEYWORDS),
        "noise_count": len(re.findall(r"[_\[\]{}<>|`~]", raw_text)),
        "latin_fragment_count": len(re.findall(r"[A-Za-z]{2,}", raw_text)),
        "meaningful_line_count": sum(1 for line in lines if len(line.strip()) >= 2),
    }


def score_ocr_lines(lines):
    raw_text = "\n".join(lines)
    if not raw_text.strip():
        return -1000

    quality = get_ocr_quality(lines)

    return (
        quality["keyword_count"] * 25
        + min(quality["amount_count"], 14) * 8
        + min(quality["date_count"], 3) * 18
        + min(quality["time_count"], 4) * 12
        + min(quality["hangul_count"], 320) * 0.25
        + min(quality["meaningful_line_count"], 60) * 1.25
        - min(quality["noise_count"], 120) * 0.7
        - min(quality["latin_fragment_count"], 30) * 1.5
    )


def should_accept_original(lines, score):
    quality = get_ocr_quality(lines)
    if not lines or score < settings.original_accept_score:
        return False

    has_transaction_shape = (
        quality["amount_count"] >= 1
        and (quality["keyword_count"] >= 2 or quality["date_count"] + quality["time_count"] >= 1)
    )
    noise_ratio = quality["noise_count"] / max(quality["raw_text_length"], 1)
    too_many_noise_marks = quality["noise_count"] > 12 and noise_ratio > 0.05
    too_many_latin_fragments = quality["latin_fragment_count"] > max(8, quality["meaningful_line_count"])

    return has_transaction_shape and not too_many_noise_marks and not too_many_latin_fragments


def predict_texts_with_blocks(image_path):
    best_lines = []
    best_blocks = []
    best_rotation = 0
    best_score = -1000

    with tempfile.TemporaryDirectory(prefix="calen-ocr-rotation-") as temp_name:
        temp_dir = Path(temp_name)
        rotation_degrees = get_rotation_degrees(image_path)
        for index, degrees in enumerate(rotation_degrees):
            candidate_path = save_rotation_candidate(image_path, temp_dir, degrees)
            blocks = run_ocr_blocks(candidate_path)
            lines = [block["text"] for block in blocks]
            score = score_ocr_lines(lines)
            if score > best_score:
                best_lines = lines
                best_blocks = blocks
                best_rotation = degrees
                best_score = score
            if (
                settings.rotation_mode == "auto"
                and index == 0
                and degrees == 0
                and should_accept_original(lines, score)
            ):
                break

    return best_lines, best_rotation, best_blocks


def predict_texts(image_path):
    lines, selected_rotation, _ = predict_texts_with_blocks(image_path)
    return lines, selected_rotation


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


def normalize_entry_amount(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        amount = abs(int(value))
        return amount if amount > 0 else None
    amount = parse_signed_amount(value)
    if amount is not None:
        amount = abs(amount)
        return amount if amount > 0 else None
    return normalize_amount(value)


def parse_signed_amount(value):
    if value is None:
        return None
    text = str(value).strip()
    sign = -1 if text.startswith("-") else 1
    digits = re.sub(r"[^0-9]", "", text)
    if not digits:
        return None
    return sign * int(digits)


def format_amount(value):
    amount = int(value or 0)
    sign = "-" if amount < 0 else ""
    return f"{sign}{abs(amount):,}"


def clean_ocr_text(text):
    return re.sub(r"\s+", " ", str(text or "").strip())


def normalize_pos_code(value):
    return clean_ocr_text(value).replace("P0S", "POS")


def normalize_item_name(value):
    text = clean_ocr_text(value).strip("*+ ")
    text = re.sub(r"카카오선물\s*\)?\s*", "", text)
    text = text.replace("올렉스트라", "옵션 엑스트라")
    text = text.replace("올엑스트라", "옵션 엑스트라")
    text = text.replace("옵션스트라", "옵션 엑스트라")
    text = re.sub(r"(\+\d)1$", r"\1", text)
    text = re.sub(r"\s+", " ", text).strip()
    if "2FOR6K" in text or text.startswith("행사_"):
        return "행사 할인"
    return text


def normalize_receipt_display_name(value):
    text = clean_ocr_text(value)
    text = text.replace("*카카오선물 )", "*카카오선물) ")
    text = text.replace("*카카오선물)", "*카카오선물) ")
    text = text.replace("+올렉스트라", "+옵션 엑스트라")
    text = text.replace("+올엑스트라", "+옵션 엑스트라")
    text = re.sub(r"(\+\d)1$", r"\1", text)
    return re.sub(r"\s+", " ", text).strip()


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


def parse_full_time(raw_text):
    match = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)(?::([0-5]\d))?\b", raw_text)
    if not match:
        return None
    seconds = match.group(3)
    return f"{int(match.group(1)):02d}:{match.group(2)}:{seconds}" if seconds else f"{int(match.group(1)):02d}:{match.group(2)}"


def parse_amount(raw_text):
    candidates = []
    for match in re.finditer(r"(?:KRW|W|₩)?\s*([0-9][0-9,]{2,})(?:\s*원)?", raw_text, re.IGNORECASE):
        amount = normalize_amount(match.group(1))
        if amount:
            candidates.append(amount)
    return max(candidates) if candidates else None


def parse_date(raw_text):
    current_year = date.today().year
    patterns = [
        r"(20\d{2})[.\-/\s년]+(\d{1,2})[.\-/\s월]+(\d{1,2})",
        r"(\d{2})[.\-/\s년]+(\d{1,2})[.\-/\s월]+(\d{1,2})",
        r"(20\d{2})(\d{2})(\d{2})",
        r"(\d{1,2})[.\-/\s월]+(\d{1,2})",
    ]
    for pattern in patterns:
        for match in re.finditer(pattern, raw_text):
            if len(match.groups()) == 3:
                year, month, day = match.groups()
                if len(year) == 2:
                    year = f"20{year}"
            else:
                year = current_year
                month, day = match.groups()
            try:
                return date(int(year), int(month), int(day)).isoformat()
            except ValueError:
                continue
    return None


def parse_amount(raw_text):
    candidates = []
    for match in re.finditer(r"(?:KRW|W|₩|￦)?\s*(-?[0-9][0-9,]{2,})", raw_text, re.IGNORECASE):
        amount = parse_signed_amount(match.group(1))
        if amount:
            candidates.append(amount)
    positive_candidates = [amount for amount in candidates if amount > 0]
    return max(positive_candidates) if positive_candidates else None


def block_score(block):
    score = block.get("score")
    return score if isinstance(score, (int, float)) else 0


def block_text(block):
    return str(block.get("text") or "")


def find_block(blocks, pattern, min_score=0.75):
    regex = re.compile(pattern)
    for block in blocks:
        if block_score(block) >= min_score and regex.search(block_text(block)):
            return block
    return None


def find_text(raw_text, pattern):
    match = re.search(pattern, raw_text)
    return clean_ocr_text(match.group(1)) if match else None


def find_nearby_order_number(blocks):
    label = find_block(blocks, r"주문번호")
    if not label:
        return None
    candidates = []
    for block in blocks:
        text = block_text(block)
        if not re.fullmatch(r"\d{2,6}", text):
            continue
        if block_score(block) < 0.85:
            continue
        if abs(block.get("cy", 0) - label.get("cy", 0)) > max(label.get("height", 0), 260):
            continue
        if block.get("cx", 0) <= label.get("cx", 0):
            continue
        candidates.append(block)
    if not candidates:
        return None
    candidates.sort(key=lambda block: (-block_score(block), -block.get("height", 0) * block.get("width", 0)))
    return block_text(candidates[0])


def find_store_name(blocks):
    order_label = find_block(blocks, r"주문번호", min_score=0.8)
    max_y = order_label.get("y1", 900) if order_label else 900
    candidates = []
    for block in blocks:
        text = block_text(block)
        if block_score(block) < 0.85 or block.get("cy", 99999) >= max_y:
            continue
        if not re.search(r"[가-힣]", text):
            continue
        if "주문서" in text or re.search(
            r"주문번호|영수증|결제|주소|사업자|대표|전화|주\s*소|상가|호실|카\s*드|체크|포인트|교환|환불|취소|지참|포장|가격|가능|불가|인증|시스템|이내|\d{1,2}월|\d{1,2}일",
            text,
        ):
            continue
        candidates.append(block)
    if not candidates:
        return None
    candidates.sort(key=lambda block: (-block_score(block), block.get("cy", 0)))
    return clean_ocr_text(block_text(candidates[0]))


def is_price_text(text):
    return re.fullmatch(r"-?\d{1,3}(?:,\d{3})*|-?\d+", clean_ocr_text(text)) is not None


def is_probable_amount(value):
    amount = parse_signed_amount(value)
    if amount is None:
        return False
    return -1_000_000 <= amount <= 1_000_000


def is_item_price_text(text):
    amount = parse_signed_amount(text)
    if amount is None:
        return False
    return amount == 0 or amount < 0 or amount >= 100


def is_identifier_like(text):
    compact = re.sub(r"[^0-9A-Za-z*]", "", str(text or ""))
    digit_count = len(re.findall(r"\d", compact))
    if "*" in compact and digit_count >= 4:
        return True
    if digit_count >= 8 and not re.search(r",", str(text or "")):
        return True
    return False


def is_excluded_item_line(text):
    compact = clean_ocr_text(text)
    if not compact:
        return True
    excluded_patterns = (
        r"주문서|주문번호|영수증|거래일시|결제일시|사업자번호|대표자?|주소|주\s*소|전화번호",
        r"교환/환불|환불|취소|가능|불가|문의|멤버십|다이소몰|고객명|포인트|카\s*드|체크카드|승인|과세|부\s*가\s*세",
        r"합계|판매|금액|단가|수량|상품명|포장|가격|전자|결제카드|인증기업|시스템|POS|Take[- ]?Out",
    )
    if any(re.search(pattern, compact, re.IGNORECASE) for pattern in excluded_patterns):
        return True
    if is_identifier_like(compact):
        return True
    if is_price_text(compact):
        return True
    if len(compact) <= 1:
        return True
    return False


def clean_item_candidate_name(text):
    cleaned = clean_ocr_text(text)
    cleaned = re.sub(r"\[[0-9]{4,}\]", " ", cleaned)
    cleaned = re.sub(r"\b[0-9]{6,}\b", " ", cleaned)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def is_valid_item_name(text):
    cleaned = clean_item_candidate_name(text)
    if not cleaned or is_excluded_item_line(cleaned):
        return False
    hangul_count = len(re.findall(r"[가-힣]", cleaned))
    latin_count = len(re.findall(r"[A-Za-z]", cleaned))
    bracket_noise = len(re.findall(r"[\[\]{}]", cleaned))
    if hangul_count == 0 and latin_count < 2:
        return False
    if bracket_noise and hangul_count < 2:
        return False
    if len(re.sub(r"[^가-힣A-Za-z0-9]", "", cleaned)) < 2:
        return False
    return True


def build_receipt_items(blocks):
    if not blocks:
        return []

    max_x = max((block.get("x2", 0) for block in blocks), default=0)
    start_block = (
        find_block(blocks, r"Take[- ]?Out", min_score=0.7)
        or find_block(blocks, r"상품명", min_score=0.7)
        or find_block(blocks, r"\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2}|\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2}", min_score=0.7)
    )
    start_y = start_block.get("cy", 0) if start_block else 0
    price_x_threshold = max_x * 0.73 if max_x else 0
    name_x_threshold = max_x * 0.68 if max_x else 99999

    price_blocks = []
    name_blocks = []
    for block in blocks:
        text = clean_ocr_text(block_text(block))
        if block.get("cy", 0) <= start_y:
            continue
        if block_score(block) < 0.85:
            continue
        if is_price_text(text) and is_item_price_text(text) and block.get("x1", 0) >= price_x_threshold:
            price_blocks.append(block)
            continue
        if block.get("x1", 0) >= name_x_threshold:
            continue
        if not re.search(r"[가-힣A-Za-z]", text):
            continue
        if not is_valid_item_name(text) or re.search(r"Take[- ]?Out|고객요청|일회용품", text):
            continue
        name_blocks.append(block)

    price_blocks.sort(key=lambda block: block.get("cy", 0))
    used_prices = set()
    items = []
    for name_block in sorted(name_blocks, key=lambda block: block.get("cy", 0)):
        candidate_prices = [
            (abs(price_block.get("cy", 0) - name_block.get("cy", 0)), index, price_block)
            for index, price_block in enumerate(price_blocks)
            if index not in used_prices and abs(price_block.get("cy", 0) - name_block.get("cy", 0)) <= 150
        ]
        if not candidate_prices:
            continue
        _, price_index, price_block = min(candidate_prices, key=lambda item: item[0])
        used_prices.add(price_index)

        raw_name = clean_item_candidate_name(block_text(name_block))
        item_name = normalize_item_name(raw_name)
        display_name = normalize_receipt_display_name(raw_name)
        price = parse_signed_amount(block_text(price_block))
        if not item_name or price is None:
            continue

        items.append(
            {
                "name": item_name,
                "displayName": display_name,
                "quantity": 1,
                "price": price,
            }
        )
    return items


def group_blocks_into_rows(blocks):
    sorted_blocks = sorted(
        [block for block in blocks if block.get("box")],
        key=lambda block: (block.get("cy", 0), block.get("x1", 0)),
    )
    rows = []
    for block in sorted_blocks:
        placed = False
        for row in rows:
            row_height = max(row.get("height", 0), block.get("height", 0), 1)
            if abs(block.get("cy", 0) - row["cy"]) <= row_height * 0.7:
                row["blocks"].append(block)
                row["cy"] = sum(item.get("cy", 0) for item in row["blocks"]) / len(row["blocks"])
                row["height"] = max(row["height"], block.get("height", 0))
                placed = True
                break
        if not placed:
            rows.append({"cy": block.get("cy", 0), "height": block.get("height", 0), "blocks": [block]})

    for row in rows:
        row["blocks"].sort(key=lambda block: block.get("x1", 0))
        row["text"] = " ".join(clean_ocr_text(block_text(block)) for block in row["blocks"])
    return rows


def dedupe_items(items):
    deduped = []
    seen = set()
    for item in items:
        key = (item.get("name"), item.get("price"))
        if not item.get("name") or key in seen:
            continue
        seen.add(key)
        deduped.append(item)
    return deduped


def build_receipt_items_from_rows(blocks):
    rows = group_blocks_into_rows(blocks)
    start_block = (
        find_block(blocks, r"Take[- ]?Out", min_score=0.7)
        or find_block(blocks, r"상품명", min_score=0.7)
        or find_block(blocks, r"\d{4}[.\-/]\d{1,2}[.\-/]\d{1,2}|\d{2}[.\-/]\d{1,2}[.\-/]\d{1,2}", min_score=0.7)
    )
    start_y = start_block.get("cy", 0) if start_block else 0
    items = []
    for row in rows:
        if row.get("cy", 0) <= start_y:
            continue
        row_blocks = row["blocks"]
        price_candidates = []
        name_parts = []
        for block in row_blocks:
            text = clean_ocr_text(block_text(block))
            if is_price_text(text) and is_item_price_text(text) and block.get("x1", 0) > 900:
                price_candidates.append((block.get("x1", 0), parse_signed_amount(text)))
            elif is_valid_item_name(text):
                name_parts.append(clean_item_candidate_name(text))
        if not name_parts or not price_candidates:
            continue
        price_candidates.sort(key=lambda item: item[0])
        price = price_candidates[-1][1]
        raw_name = clean_item_candidate_name(" ".join(name_parts))
        item_name = normalize_item_name(raw_name)
        if not is_valid_item_name(item_name):
            continue
        items.append(
            {
                "name": item_name,
                "displayName": normalize_receipt_display_name(raw_name),
                "quantity": 1,
                "price": price,
            }
        )
    return dedupe_items(items)


def merge_receipt_items(primary_items, fallback_items):
    if len(fallback_items) > len(primary_items):
        primary_items, fallback_items = fallback_items, primary_items
    result = list(primary_items)
    seen_names = {item.get("name") for item in result}
    for item in fallback_items:
        if item.get("name") not in seen_names:
            result.append(item)
            seen_names.add(item.get("name"))
    return dedupe_items(result)


def get_line_amounts(line):
    amounts = []
    for match in re.finditer(r"-?\d{1,3}(?:,\d{3})+|-?\d{3,6}", str(line or "")):
        value = match.group(0)
        if is_probable_amount(value) and not is_identifier_like(value):
            amounts.append(parse_signed_amount(value))
    return amounts


def extract_total_amount(blocks, lines, items):
    label_priority = (
        (r"승인금액|결제금액|받을금액|합계금액|판매\s*합계|판매합계", 100),
        (r"합계", 70),
    )
    excluded_context = re.compile(r"과세|부\s*가\s*세|포인트|잔액|승인번호|사업자|전화|문의|카드번호|바코드")
    candidates = []

    for index, line in enumerate(lines):
        window = " ".join(lines[max(0, index - 2) : min(len(lines), index + 3)])
        for pattern, priority in label_priority:
            if not re.search(pattern, window):
                continue
            if excluded_context.search(window) and priority < 100:
                continue
            for amount in get_line_amounts(line):
                candidates.append((priority + 5, amount))
            for nearby_line in lines[index + 1 : min(len(lines), index + 5)]:
                for amount in get_line_amounts(nearby_line):
                    candidates.append((priority, amount))

    amount_blocks = [
        block
        for block in blocks
        if is_price_text(block_text(block)) and is_probable_amount(block_text(block)) and not is_identifier_like(block_text(block))
    ]
    label_blocks = [
        block
        for block in blocks
        if re.search(r"승인금액|결제금액|받을금액|합계금액|판매|합계", block_text(block))
    ]
    for label in label_blocks:
        label_text = block_text(label)
        priority = 100 if re.search(r"승인금액|결제금액|받을금액|합계금액|판매", label_text) else 70
        if re.search(r"과세|부\s*가\s*세|포인트|승인번호", label_text):
            continue
        for amount_block in amount_blocks:
            y_distance = abs(amount_block.get("cy", 0) - label.get("cy", 0))
            x_is_right = amount_block.get("cx", 0) >= label.get("cx", 0)
            if y_distance <= max(label.get("height", 0), amount_block.get("height", 0), 1) * 1.5 and x_is_right:
                candidates.append((priority + 10, parse_signed_amount(block_text(amount_block))))
            elif 0 < amount_block.get("cy", 0) - label.get("cy", 0) <= 260 and x_is_right:
                candidates.append((priority, parse_signed_amount(block_text(amount_block))))

    if candidates:
        candidates = [(priority, amount) for priority, amount in candidates if amount is not None and amount > 0]
        if candidates:
            candidates.sort(key=lambda item: (item[0], item[1]), reverse=True)
            return candidates[0][1]

    item_total = sum(item["price"] for item in items if isinstance(item.get("price"), int))
    return item_total if item_total > 0 else parse_amount("\n".join(lines))


def format_receipt_structured_text(receipt):
    lines = []
    if receipt.get("order_number"):
        lines.append(f"주문번호 : {receipt['order_number']}")
        lines.append("")
    if receipt.get("datetime"):
        pos_suffix = f" ({receipt['pos_code']})" if receipt.get("pos_code") else ""
        lines.append(f"결제일시: {receipt['datetime']}{pos_suffix}")
    if receipt.get("store_address"):
        lines.append(f"매장주소: {receipt['store_address']}")
    if receipt.get("business_number") or receipt.get("representative"):
        business = f"사업자번호: {receipt.get('business_number') or ''}".strip()
        if receipt.get("representative"):
            business = f"{business}   대표: {receipt['representative']}"
        lines.append(business)
    if receipt.get("store_phone") or receipt.get("customer_center_phone"):
        lines.append("")
    if receipt.get("store_phone"):
        lines.append(f"매장 : {receipt['store_phone']}")
    if receipt.get("customer_center_phone"):
        center = f"고객센터문의 : {receipt['customer_center_phone']}"
        if receipt.get("customer_center_hours"):
            center = f"{center} ({receipt['customer_center_hours']})"
        lines.append(center)
    if receipt.get("customer_request") or receipt.get("takeout"):
        lines.append("")
    if receipt.get("customer_request"):
        lines.append("고객요청")
        lines.append(receipt["customer_request"])
        lines.append("")
    if receipt.get("takeout"):
        lines.append("[Take-Out]")
        lines.append("")
    for item in receipt.get("items") or []:
        lines.append(item.get("displayName") or item.get("name") or "")
        if item.get("price") is not None:
            if item.get("price", 0) < 0:
                lines.append(f"        {format_amount(item['price'])}")
            else:
                lines.append(f"  {item.get('quantity') or ''}     {format_amount(item['price'])}")
        lines.append("")
    return "\n".join(lines).strip()


def parse_receipt_layout(blocks, lines):
    raw_text = "\n".join(lines)
    compact_text = clean_ocr_text(raw_text)
    if not re.search(
        r"주문번호|결제일시|거래일시|사업자번호|합계금액|승인금액|판매\s*합계|상품명|카드/간편결제|Take[- ]?Out",
        compact_text,
    ):
        return None

    date_value = parse_date(compact_text)
    time_value = parse_time(compact_text)
    display_time_value = parse_full_time(compact_text) or time_value
    pos_code = (
        find_text(compact_text, r"\((P[O0]S[^)]*)\)")
        or find_text(compact_text, r"영수증[:：]?\s*(P[O0]S\s*[0-9\-]+)")
        or find_text(compact_text, r"\[?(P[O0]S\s*[0-9]+)\]?")
    )
    store_address = (
        find_text(compact_text, r"매장주소[:：]?\s*(.+?)(?:\s+사업자번호|$)")
        or find_text(compact_text, r"주\s*소[:：]?\s*(.+?)(?:\s+전화번호|\s+거래일시|\s+pos|$)")
    )
    business_number = find_text(compact_text, r"사업자번호[:：]?\s*([0-9]{3}-[0-9]{2}-[0-9]{5})")
    representative = find_text(compact_text, r"대표자?[:：]?\s*([가-힣A-Za-z]+)")
    store_phone = (
        find_text(compact_text, r"매장\s*[:：]\s*([0-9]{2,4}-[0-9]{3,4}-[0-9]{4})")
        or find_text(compact_text, r"전화번호[:：]?\s*([0-9\-]{8,14})")
    )
    center_match = re.search(
        r"고객센터문의\s*[:：]?\s*([0-9]{2,4}-[0-9]{3,4}-[0-9]{4})\s*\(?([0-9:~\-]+)?\)?",
        compact_text,
    )
    customer_center_phone = clean_ocr_text(center_match.group(1)) if center_match else None
    customer_center_hours = clean_ocr_text(center_match.group(2)) if center_match and center_match.group(2) else None
    coordinate_items = build_receipt_items(blocks)
    row_items = build_receipt_items_from_rows(blocks)
    items = merge_receipt_items(coordinate_items, row_items)
    total_amount = extract_total_amount(blocks, lines, items)

    receipt = {
        "order_number": find_nearby_order_number(blocks),
        "datetime": f"{date_value} {display_time_value}" if date_value and display_time_value else date_value,
        "date": date_value,
        "time": time_value,
        "display_time": display_time_value,
        "pos_code": normalize_pos_code(pos_code) if pos_code else None,
        "store_name": find_store_name(blocks),
        "store_address": store_address,
        "business_number": business_number,
        "representative": representative,
        "store_phone": store_phone,
        "customer_center_phone": customer_center_phone,
        "customer_center_hours": customer_center_hours,
        "customer_request": "일회용품 X" if re.search(r"일회용품\s*X", compact_text, re.IGNORECASE) else None,
        "takeout": bool(re.search(r"Take[- ]?Out", compact_text, re.IGNORECASE)),
        "items": items,
    }
    receipt["structuredText"] = format_receipt_structured_text(receipt)
    receipt["totalAmount"] = total_amount

    has_core_fields = (receipt.get("datetime") or receipt.get("business_number")) and (
        receipt.get("items") or receipt.get("totalAmount") or receipt.get("business_number")
    )
    return receipt if has_core_fields else None


def build_receipt_heuristic(receipt):
    line_items = [
        {
            "itemName": item.get("name"),
            "quantity": item.get("quantity") or 1,
            "unit": None,
            "price": item.get("price"),
        }
        for item in receipt.get("items") or []
        if item.get("name")
    ]
    title = receipt.get("store_name") or (line_items[0]["itemName"] if line_items else "Receipt")
    memo = ", ".join(item["itemName"] for item in line_items if item.get("itemName")) or title
    return {
        "entryDate": receipt.get("date"),
        "entryTime": receipt.get("time"),
        "entryType": "EXPENSE",
        "title": title,
        "memo": memo,
        "amount": receipt.get("totalAmount"),
        "vendor": receipt.get("store_name"),
        "paymentMethodText": None,
        "categoryGroupName": None,
        "categoryDetailName": None,
        "categoryText": None,
        "lineItems": line_items,
        "confidence": 0.78 if line_items else 0.55,
        "warnings": [],
        "receipt": receipt,
    }


def parse_payment_capture_amount(text):
    match = re.search(r"([+-]?)\s*([0-9][0-9,]*)\s*원", clean_ocr_text(text))
    if not match:
        return None
    amount = int(re.sub(r"[^0-9]", "", match.group(2)))
    if amount <= 0:
        return None
    entry_type = "EXPENSE" if match.group(1) == "-" else "INCOME"
    return {"amount": amount, "entryType": entry_type, "signedAmount": -amount if entry_type == "EXPENSE" else amount}


def parse_korean_month_day(text, default_year=None):
    match = re.search(r"(\d{1,2})\s*월\s*(\d{1,2})\s*일", clean_ocr_text(text))
    if not match:
        return None
    year = int(default_year or date.today().year)
    try:
        return date(year, int(match.group(1)), int(match.group(2))).isoformat()
    except ValueError:
        return None


def extract_capture_month(lines):
    for line in lines:
        match = re.fullmatch(r"\s*(\d{1,2})\s*월\s*", str(line or ""))
        if match:
            month = int(match.group(1))
            if 1 <= month <= 12:
                return month
    return None


def is_capture_noise_text(text):
    compact = clean_ocr_text(text)
    if not compact:
        return True
    if compact in {"W", "Q", "검색"}:
        return True
    if re.fullmatch(r"[일월화수목금토]", compact):
        return True
    if re.fullmatch(r"\d{1,2}", compact) or re.fullmatch(r"\d{1,2}\s*월", compact):
        return True
    if re.fullmatch(r"[+-]\s*\d{1,3}(?:,\d{3})*", compact):
        return True
    return False


def normalize_capture_memo(text):
    memo = clean_ocr_text(text)
    memo = memo.replace("｜", "|").replace("→", " -> ")
    memo = re.sub(r"\b([A-Z]{3,})I([A-Z][a-z]+\.com)\b", r"\1 | \2", memo)
    memo = re.sub(r"\s*\|\s*", " | ", memo)
    memo = re.sub(r"\s*->\s*", " -> ", memo)
    memo = re.sub(r"\s+", " ", memo).strip()
    return memo


def normalize_capture_title(memo):
    normalized = normalize_capture_memo(memo)
    if not normalized:
        return "거래내역"
    title = re.split(r"\s*(?:\||->)\s*", normalized, maxsplit=1)[0].strip()
    title = re.sub(r"^([A-Z]{2,})([A-Z][A-Za-z]+\.com)$", r"\1", title)
    title = re.sub(r"\s+", " ", title).strip()
    return title[:80] or "거래내역"


def build_capture_calendar_summaries(blocks, month, first_transaction_y):
    if not month:
        return []
    day_blocks = []
    for block in blocks:
        text = clean_ocr_text(block_text(block))
        if not re.fullmatch(r"\d{1,2}", text):
            continue
        day = int(text)
        if not 1 <= day <= 31:
            continue
        if block.get("y1", 0) < 350 or block.get("y1", 0) >= first_transaction_y:
            continue
        day_blocks.append((day, block))

    summaries = []
    for day, day_block in day_blocks:
        income = 0
        expense = 0
        for amount_block in blocks:
            text = clean_ocr_text(block_text(amount_block))
            if not re.fullmatch(r"[+-]\s*\d{1,3}(?:,\d{3})*", text):
                continue
            if not day_block.get("y2", 0) <= amount_block.get("cy", 0) <= first_transaction_y:
                continue
            if abs(amount_block.get("cx", 0) - day_block.get("cx", 0)) > 90:
                continue
            signed_amount = parse_signed_amount(text)
            if signed_amount is None:
                continue
            if signed_amount >= 0:
                income += signed_amount
            else:
                expense += abs(signed_amount)
        if income or expense:
            summaries.append({"day": day, "month": month, "income": income, "expense": expense})
    return summaries


def infer_capture_prefix_date(blocks, lines, prefix_entries, first_transaction_y):
    if not prefix_entries:
        return None
    month = extract_capture_month(lines)
    if not month:
        return None
    income = sum(entry.get("amount") or 0 for entry in prefix_entries if entry.get("entryType") == "INCOME")
    expense = sum(entry.get("amount") or 0 for entry in prefix_entries if entry.get("entryType") == "EXPENSE")
    if not income and not expense:
        return None

    summaries = build_capture_calendar_summaries(blocks, month, first_transaction_y)
    exact_matches = [
        summary for summary in summaries if summary["income"] == income and summary["expense"] == expense
    ]
    candidates = exact_matches or sorted(
        summaries,
        key=lambda summary: abs(summary["income"] - income) + abs(summary["expense"] - expense),
    )[:1]
    if not candidates:
        return None
    selected = candidates[0]
    try:
        return date(date.today().year, selected["month"], selected["day"]).isoformat()
    except ValueError:
        return None


def make_capture_entry(amount_info, memo, entry_date):
    normalized_memo = normalize_capture_memo(memo)
    title = normalize_capture_title(normalized_memo)
    return {
        "entryDate": entry_date,
        "entryTime": None,
        "entryType": amount_info["entryType"],
        "title": title,
        "memo": normalized_memo or title,
        "amount": amount_info["amount"],
        "vendor": title,
        "paymentMethodText": None,
        "categoryGroupName": None,
        "categoryDetailName": None,
        "categoryText": None,
        "lineItems": [],
        "confidence": 0.72 if normalized_memo else 0.58,
        "warnings": [],
    }


def format_payment_capture_structured_text(capture):
    lines = ["거래내역 캡처"]
    previous_date = None
    for entry in capture.get("entries") or []:
        entry_date = entry.get("entryDate")
        if entry_date and entry_date != previous_date:
            lines.append("")
            lines.append(entry_date)
            previous_date = entry_date
        sign = "-" if entry.get("entryType") == "EXPENSE" else "+"
        amount = format_amount(entry.get("amount"))
        memo = entry.get("memo") or entry.get("title") or "거래내역"
        lines.append(f"{sign}{amount}원 {memo}")
    return "\n".join(lines).strip()


def parse_payment_capture_layout(blocks, lines):
    rows = group_blocks_into_rows(blocks)
    amount_row_indexes = [
        index for index, row in enumerate(rows) if parse_payment_capture_amount(row.get("text"))
    ]
    if len(amount_row_indexes) < 2:
        return None

    entries = []
    prefix_indexes = []
    current_date = None
    first_transaction_y = rows[amount_row_indexes[0]].get("cy", 999999)
    for index, row in enumerate(rows):
        row_text = clean_ocr_text(row.get("text"))
        parsed_date = parse_korean_month_day(row_text)
        if parsed_date:
            current_date = parsed_date
            continue

        amount_info = parse_payment_capture_amount(row_text)
        if not amount_info:
            continue

        memo_parts = []
        for next_row in rows[index + 1 :]:
            next_text = clean_ocr_text(next_row.get("text"))
            if next_row.get("cy", 0) - row.get("cy", 0) > 180 and memo_parts:
                break
            if parse_korean_month_day(next_text) or parse_payment_capture_amount(next_text):
                break
            if is_capture_noise_text(next_text):
                continue
            if re.search(r"[가-힣A-Za-z]", next_text):
                memo_parts.append(next_text)
            if next_row.get("cy", 0) - row.get("cy", 0) > 150:
                break

        entry = make_capture_entry(amount_info, " ".join(memo_parts), current_date)
        if not current_date:
            prefix_indexes.append(len(entries))
        entries.append(entry)

    if len(entries) < 2:
        return None

    prefix_entries = [entries[index] for index in prefix_indexes]
    inferred_date = infer_capture_prefix_date(blocks, lines, prefix_entries, first_transaction_y)
    if inferred_date:
        for index in prefix_indexes:
            entries[index]["entryDate"] = inferred_date

    capture = {"entries": entries, "entryCount": len(entries)}
    capture["structuredText"] = format_payment_capture_structured_text(capture)
    return capture


def build_payment_capture_heuristic(capture):
    entries = capture.get("entries") if isinstance(capture, dict) else None
    if entries:
        return dict(entries[0])
    return {
        "entryDate": None,
        "entryTime": None,
        "entryType": "EXPENSE",
        "title": "거래내역",
        "memo": None,
        "amount": None,
        "vendor": None,
        "paymentMethodText": None,
        "categoryGroupName": None,
        "categoryDetailName": None,
        "categoryText": None,
        "lineItems": [],
        "confidence": 0.2,
        "warnings": ["payment_capture_entries_not_found"],
    }


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


def heuristic_parse(lines, receipt=None):
    if receipt:
        return build_receipt_heuristic(receipt)

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


def normalize_document_type(value):
    normalized = str(value or "AUTO").strip().upper().replace("-", "_")
    if normalized in {"RECEIPT", "PAYMENT_CAPTURE", "AUTO"}:
        return normalized
    return "AUTO"


def normalize_entry_type(value):
    normalized = str(value or "").strip().upper()
    if normalized in {"INCOME", "수입", "입금", "수익", "DEPOSIT", "CREDIT", "PLUS", "+"}:
        return "INCOME"
    return "EXPENSE"


def infer_entry_type_from_amount(value):
    text = clean_ocr_text(value)
    if text.startswith("+"):
        return "INCOME"
    if text.startswith("-"):
        return "EXPENSE"
    return None


def normalize_schema_string(value, max_length=None):
    if value is None:
        return None
    text = clean_ocr_text(value)
    if not text:
        return None
    return text[:max_length] if max_length else text


def normalize_schema_date(value):
    if value is None:
        return None
    if isinstance(value, date):
        return value.isoformat()
    text = clean_ocr_text(value)
    if not text:
        return None
    if re.fullmatch(r"20\d{2}-\d{2}-\d{2}", text):
        try:
            year, month, day = text.split("-")
            return date(int(year), int(month), int(day)).isoformat()
        except ValueError:
            return None
    return parse_date(text)


def normalize_schema_time(value):
    if value is None:
        return None
    text = clean_ocr_text(value)
    if not text:
        return None
    return parse_time(text)


def normalize_schema_number(value):
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)):
        return value
    text = clean_ocr_text(value)
    if not text:
        return None
    try:
        return float(text) if "." in text else int(re.sub(r"[^0-9-]", "", text))
    except ValueError:
        return None


def normalize_schema_confidence(value):
    number = normalize_schema_number(value)
    if number is None:
        return None
    return max(0, min(float(number), 1))


def normalize_schema_warnings(value):
    if value is None:
        return []
    if isinstance(value, list):
        warnings = value
    else:
        warnings = [value]
    result = []
    seen = set()
    for item in warnings:
        warning = normalize_schema_string(item, 80)
        if warning and warning not in seen:
            seen.add(warning)
            result.append(warning)
    return result


def normalize_schema_line_item(item):
    if isinstance(item, str):
        item_name = normalize_schema_string(item, 160)
        if not item_name:
            return None
        return {"itemName": item_name, "quantity": None, "unit": None, "price": None}
    if not isinstance(item, dict):
        return None
    item_name = normalize_schema_string(
        item.get("itemName") or item.get("name") or item.get("productName") or item.get("title"),
        160,
    )
    if not item_name:
        return None
    quantity = normalize_schema_number(item.get("quantity") or item.get("count") or item.get("qty"))
    price = normalize_entry_amount(item.get("price") or item.get("amount") or item.get("total"))
    return {
        "itemName": item_name,
        "quantity": quantity,
        "unit": normalize_schema_string(item.get("unit"), 30),
        "price": price,
    }


def normalize_schema_entry(entry):
    if not isinstance(entry, dict):
        return None
    raw_line_items = (
        entry.get("lineItems")
        or entry.get("items")
        or entry.get("products")
        or entry.get("purchasedItems")
        or []
    )
    line_items = [
        normalized_item
        for normalized_item in (normalize_schema_line_item(item) for item in raw_line_items)
        if normalized_item
    ]
    title = normalize_schema_string(
        entry.get("title")
        or entry.get("vendor")
        or entry.get("merchant")
        or entry.get("storeName")
        or entry.get("store")
        or (line_items[0]["itemName"] if line_items else None),
        120,
    )
    memo = normalize_schema_string(entry.get("memo") or entry.get("description") or entry.get("note"), 800)
    vendor = normalize_schema_string(entry.get("vendor") or title, 120)
    raw_amount = entry.get("amount") or entry.get("totalAmount") or entry.get("total")
    amount = normalize_entry_amount(raw_amount)
    raw_entry_type = entry.get("entryType") or entry.get("type") or entry.get("direction")
    entry_type = normalize_entry_type(raw_entry_type or infer_entry_type_from_amount(raw_amount))
    warnings = normalize_schema_warnings(entry.get("warnings"))
    if amount is None:
        warnings.append("amount_not_found")

    return {
        "entryDate": normalize_schema_date(entry.get("entryDate") or entry.get("date") or entry.get("transactionDate")),
        "entryTime": normalize_schema_time(entry.get("entryTime") or entry.get("time") or entry.get("transactionTime")),
        "entryType": entry_type,
        "title": title or "거래내역",
        "memo": memo,
        "amount": amount,
        "vendor": vendor,
        "paymentMethodText": None,
        "categoryGroupName": None,
        "categoryDetailName": None,
        "categoryText": None,
        "lineItems": line_items,
        "confidence": normalize_schema_confidence(entry.get("confidence")),
        "warnings": sorted(set(warnings)),
    }


def normalize_llm_schema(parsed, document_type):
    if not isinstance(parsed, dict):
        return None

    normalized_document_type = normalize_document_type(parsed.get("documentType") or document_type)
    raw_entries = None
    for key in ("entries", "transactions", "parsedEntries", "suggestedEntries"):
        if isinstance(parsed.get(key), list):
            raw_entries = parsed.get(key)
            break
    if raw_entries is None:
        raw_entries = [parsed]

    entries = [
        normalized_entry
        for normalized_entry in (normalize_schema_entry(entry) for entry in raw_entries)
        if normalized_entry
    ]
    result = {
        "schemaVersion": "ledger-ocr-v1",
        "documentType": normalized_document_type,
        "entries": entries,
    }
    if entries:
        result.update(entries[0])
    return result


LEDGER_OCR_JSON_SCHEMA = {
    "schemaVersion": "ledger-ocr-v1",
    "documentType": "RECEIPT|PAYMENT_CAPTURE",
    "entries": [
        {
            "entryDate": "YYYY-MM-DD|null",
            "entryTime": "HH:mm|null",
            "entryType": "EXPENSE|INCOME",
            "title": "merchant or transaction title",
            "memo": "item names or transaction description",
            "amount": "positive number|null",
            "vendor": "merchant or null",
            "paymentMethodText": None,
            "categoryGroupName": None,
            "categoryDetailName": None,
            "categoryText": None,
            "lineItems": [{"itemName": "name", "quantity": 1, "unit": None, "price": "positive number|null"}],
            "confidence": "0..1",
            "warnings": [],
        }
    ],
}


def build_llm_prompt(raw_text, document_type):
    document_guidance = {
        "RECEIPT": (
            "The image is a receipt for one transaction. "
            "Prefer the final paid total and return one entry."
        ),
        "PAYMENT_CAPTURE": (
            "The image is a payment history capture. It may contain multiple transactions. "
            "Split every visible transaction into the entries array."
        ),
        "AUTO": (
            "Detect whether this is a one-transaction receipt or a payment history capture. "
            "If multiple transactions are visible, split them into the entries array."
        ),
    }.get(document_type, "")
    return (
        "You parse Korean transaction image OCR text into strict JSON for a household ledger. "
        f"Document type hint: {document_type}. {document_guidance} "
        "Return JSON only, with exactly this canonical schema shape: "
        f"{json.dumps(LEDGER_OCR_JSON_SCHEMA, ensure_ascii=False)}. "
        "Use schemaVersion ledger-ocr-v1. Extract only transaction date, time, title/store name, "
        "total amount, and purchased item names. Each amount must be a positive number; use entryType "
        "EXPENSE or INCOME for direction instead of negative amounts. "
        "When the OCR text is already arranged like a receipt, preserve that reading order and keep item prices "
        "paired with the item on the same visual row. "
        "Do not extract payment method or ledger categories; always set paymentMethodText, categoryGroupName, "
        "categoryDetailName, and categoryText to null. "
        "Use title for the merchant/store or transaction title. Use memo only for purchased item names, "
        "joined in reading order when there are multiple items. Put the same purchased items into lineItems. "
        "For payment captures, split every visible transaction into one entries item. "
        "Use paid totals, not subtotals. Do not invent category IDs.\n\nOCR text:\n"
        f"{raw_text[:6000]}"
    )


def call_openai_compatible(raw_text, document_type):
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
                {"role": "user", "content": build_llm_prompt(raw_text, document_type)},
            ],
        },
    )
    response.raise_for_status()
    data = response.json()
    return data["choices"][0]["message"]["content"]


def call_ollama(raw_text, document_type):
    response = requests.post(
        f"{settings.llm_base_url}/api/chat",
        timeout=settings.llm_timeout_seconds,
        json={
            "model": settings.llm_model,
            "stream": False,
            "format": "json",
            "messages": [
                {"role": "system", "content": "Return valid JSON only."},
                {"role": "user", "content": build_llm_prompt(raw_text, document_type)},
            ],
        },
    )
    response.raise_for_status()
    data = response.json()
    return data.get("message", {}).get("content", "")


def parse_with_llm(raw_text, document_type):
    if settings.llm_provider not in {"openai", "ollama"} or not settings.llm_base_url:
        return None, None
    try:
        if settings.llm_provider == "ollama":
            content = call_ollama(raw_text, document_type)
        else:
            content = call_openai_compatible(raw_text, document_type)
        parsed = extract_json_object(content)
        if isinstance(parsed, dict):
            normalized = normalize_llm_schema(parsed, document_type)
            if normalized:
                return normalized, None
        return None, "llm_invalid_json"
    except requests.RequestException:
        return None, "llm_request_failed"
    except (KeyError, TypeError, ValueError):
        return None, "llm_response_unexpected"


def merge_single_entry(heuristic, entry_result, llm_warning=None):
    parsed = dict(heuristic)
    warnings = list(parsed.get("warnings") or [])
    if isinstance(entry_result, dict):
        for key, value in entry_result.items():
            if value not in (None, "", []):
                parsed[key] = value
    if llm_warning:
        warnings.append(llm_warning)
    parsed["amount"] = normalize_entry_amount(parsed.get("amount"))
    parsed["entryType"] = normalize_entry_type(parsed.get("entryType"))
    parsed["warnings"] = sorted(set(str(item) for item in warnings if item))
    return parsed


def extract_llm_entries(llm_result):
    if not isinstance(llm_result, dict):
        return []

    for key in ("entries", "transactions", "parsedEntries", "suggestedEntries"):
        entries = llm_result.get(key)
        if isinstance(entries, list):
            return [entry for entry in entries if isinstance(entry, dict)]

    return [llm_result]


def merge_parsed_entries(heuristic, llm_result, llm_warning):
    entries = extract_llm_entries(llm_result)
    if not entries:
        return [merge_single_entry(heuristic, None, llm_warning)]
    return [
        merge_single_entry(heuristic if index == 0 else {}, entry, llm_warning if index == 0 else None)
        for index, entry in enumerate(entries)
    ]


def merge_payment_capture_entries(capture, llm_result, llm_warning):
    base_entries = capture.get("entries") if isinstance(capture, dict) else []
    if not base_entries:
        return merge_parsed_entries(build_payment_capture_heuristic(capture), llm_result, llm_warning)

    llm_entries = extract_llm_entries(llm_result)
    merged_entries = []
    for index, base_entry in enumerate(base_entries):
        llm_entry = llm_entries[index] if index < len(llm_entries) else None
        merged_entries.append(merge_single_entry(base_entry, llm_entry, llm_warning if index == 0 else None))
    return merged_entries


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
async def analyze(
    file: UploadFile = File(...),
    documentType: str = Form("AUTO"),
    _: None = Depends(require_api_key),
):
    started_at = time.perf_counter()
    document_type = normalize_document_type(documentType)
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
        lines, selected_rotation, blocks = predict_texts_with_blocks(temp_path)
        ocr_ms = int((time.perf_counter() - ocr_started_at) * 1000)

        raw_ocr_text = "\n".join(lines)
        payment_capture = parse_payment_capture_layout(blocks, lines)
        receipt = None if document_type == "PAYMENT_CAPTURE" else parse_receipt_layout(blocks, lines)
        use_payment_capture = (
            document_type == "PAYMENT_CAPTURE"
            or (
                document_type == "AUTO"
                and payment_capture
                and len(payment_capture.get("entries") or []) >= 2
                and (
                    not receipt
                    or re.search(r"검색|→|->|계좌|네이버|토스|\d{1,2}\s*월\s*\d{1,2}\s*일", raw_ocr_text)
                )
            )
        )

        if use_payment_capture and payment_capture:
            raw_text = payment_capture.get("structuredText") or raw_ocr_text
            heuristic = build_payment_capture_heuristic(payment_capture)
        else:
            raw_text = receipt.get("structuredText") if receipt else raw_ocr_text
            heuristic = heuristic_parse(lines, receipt)

        llm_started_at = time.perf_counter()
        llm_document_type = "PAYMENT_CAPTURE" if use_payment_capture else document_type
        llm_result, llm_warning = parse_with_llm(raw_text, llm_document_type)
        llm_ms = int((time.perf_counter() - llm_started_at) * 1000)
        if use_payment_capture and payment_capture:
            parsed_entries = merge_payment_capture_entries(payment_capture, llm_result, llm_warning)
        else:
            parsed_entries = merge_parsed_entries(heuristic, llm_result, llm_warning)
        parsed = parsed_entries[0] if parsed_entries else merge_single_entry(heuristic, None, llm_warning)
        if use_payment_capture:
            detected_document_type = "PAYMENT_CAPTURE"
        elif receipt:
            detected_document_type = "RECEIPT"
        else:
            detected_document_type = normalize_document_type(
                llm_result.get("documentType") if isinstance(llm_result, dict) else document_type
            )

        return {
            "ok": True,
            "documentType": detected_document_type,
            "rawText": raw_text,
            "rawOcrText": raw_ocr_text,
            "ocrBlocks": [
                {
                    "text": block.get("text"),
                    "score": block.get("score"),
                    "box": block.get("box"),
                }
                for block in blocks
            ],
            "receipt": receipt,
            "paymentCapture": payment_capture,
            "parsed": parsed,
            "parsedEntries": parsed_entries,
            "timing": {
                "ocrMs": ocr_ms,
                "llmMs": llm_ms,
                "totalMs": int((time.perf_counter() - started_at) * 1000),
                "ocrRotationDegrees": selected_rotation,
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
