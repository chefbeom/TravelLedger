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
        r"(20\d{2})(\d{2})(\d{2})",
        r"(\d{1,2})[.\-/\s월]+(\d{1,2})",
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
        if "주문서" in text or re.search(r"주문번호|결제|주소|사업자", text):
            continue
        candidates.append(block)
    if not candidates:
        return None
    candidates.sort(key=lambda block: (-block_score(block), block.get("cy", 0)))
    return clean_ocr_text(block_text(candidates[0]))


def is_price_text(text):
    return re.fullmatch(r"-?\d{1,3}(?:,\d{3})*|-?\d+", clean_ocr_text(text)) is not None


def build_receipt_items(blocks):
    if not blocks:
        return []

    max_x = max((block.get("x2", 0) for block in blocks), default=0)
    takeout_block = find_block(blocks, r"Take[- ]?Out", min_score=0.7)
    start_y = takeout_block.get("cy", 0) if takeout_block else 0
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
        if is_price_text(text) and block.get("x1", 0) >= price_x_threshold:
            price_blocks.append(block)
            continue
        if block.get("x1", 0) >= name_x_threshold:
            continue
        if not re.search(r"[가-힣A-Za-z]", text):
            continue
        if re.search(r"Take[- ]?Out|고객요청|일회용품", text):
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

        raw_name = clean_ocr_text(block_text(name_block))
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
    if not re.search(r"주문번호|결제일시|사업자번호|Take[- ]?Out", compact_text):
        return None

    date_value = parse_date(compact_text)
    time_value = parse_time(compact_text)
    display_time_value = parse_full_time(compact_text) or time_value
    pos_code = find_text(compact_text, r"\((P[O0]S[^)]*)\)")
    store_address = find_text(compact_text, r"매장주소[:：]?\s*(.+?)(?:\s+사업자번호|$)")
    business_number = find_text(compact_text, r"사업자번호[:：]?\s*([0-9]{3}-[0-9]{2}-[0-9]{5})")
    representative = find_text(compact_text, r"대표[:：]?\s*([가-힣A-Za-z]+)")
    store_phone = find_text(compact_text, r"매장\s*[:：]\s*([0-9]{2,4}-[0-9]{3,4}-[0-9]{4})")
    center_match = re.search(
        r"고객센터문의\s*[:：]?\s*([0-9]{2,4}-[0-9]{3,4}-[0-9]{4})\s*\(?([0-9:~\-]+)?\)?",
        compact_text,
    )
    customer_center_phone = clean_ocr_text(center_match.group(1)) if center_match else None
    customer_center_hours = clean_ocr_text(center_match.group(2)) if center_match and center_match.group(2) else None
    items = build_receipt_items(blocks)

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
    receipt["totalAmount"] = sum(item["price"] for item in items if isinstance(item.get("price"), int)) or None

    has_core_fields = receipt.get("datetime") and (receipt.get("items") or receipt.get("business_number"))
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
    if normalized in {"INCOME", "수입", "DEPOSIT"}:
        return "INCOME"
    return "EXPENSE"


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
        "Return only JSON with these top-level keys: documentType AUTO|RECEIPT|PAYMENT_CAPTURE, "
        "entries array. Extract only transaction date, time, title/store name, total amount, and purchased item names. "
        "Each entry must have: entryDate YYYY-MM-DD or null, entryTime HH:mm or null, "
        "entryType EXPENSE or INCOME, title, memo, amount number or null, vendor, paymentMethodText, "
        "categoryGroupName, categoryDetailName, categoryText, lineItems array of "
        "{itemName, quantity, unit, price}, confidence 0..1, warnings array. "
        "When the OCR text is already arranged like a receipt, preserve that reading order and keep item prices "
        "paired with the item on the same visual row. "
        "Do not extract payment method or ledger categories; always set paymentMethodText, categoryGroupName, "
        "categoryDetailName, and categoryText to null. "
        "Use title for the merchant/store or transaction title. Use memo only for purchased item names, "
        "joined in reading order when there are multiple items. Put the same purchased items into lineItems. "
        "For backward compatibility also include the first entry fields at the top level. "
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
            return parsed, None
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
    parsed["amount"] = normalize_amount(parsed.get("amount"))
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
        receipt = parse_receipt_layout(blocks, lines)
        raw_text = receipt.get("structuredText") if receipt else raw_ocr_text
        heuristic = heuristic_parse(lines, receipt)
        llm_started_at = time.perf_counter()
        llm_result, llm_warning = parse_with_llm(raw_text, document_type)
        llm_ms = int((time.perf_counter() - llm_started_at) * 1000)
        parsed_entries = merge_parsed_entries(heuristic, llm_result, llm_warning)
        parsed = parsed_entries[0] if parsed_entries else merge_single_entry(heuristic, None, llm_warning)
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
