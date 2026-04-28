# Calen OCR Analysis Service

This folder contains the clean service files for the household receipt OCR flow.
Local caches, virtual environments, logs, sample images, and real receipt images
are ignored by Git.

## Purpose

The Calen backend sends receipt or payment-history images to this private service.
The service runs PaddleOCR, optionally asks a local Gemma-compatible LLM to parse
the text, and returns a ledger-entry suggestion. The frontend still requires the
user to review and save the entry manually.

## Run

```bash
python -m venv ocr_env
source ocr_env/bin/activate
pip install -r requirements.txt

export OCR_API_KEY="change-this-long-random-key"
export OCR_HOST="127.0.0.1"
export OCR_PORT="8765"
export OCR_ROTATION_MODE="auto"
python ocr_service.py
```

For a private OCR machine, bind `OCR_HOST` to a private LAN address and allow only
the Calen backend host through the firewall.
`OCR_ROTATION_MODE=auto` reads the original image first. If that result already
looks usable, the service keeps it as-is; otherwise it tries rotated receipt
candidates and chooses the most receipt-like OCR result. Use `off` only if OCR
latency matters more than rotated photo quality.

## Optional LLM parsing

Without an LLM, the service returns a conservative heuristic parse. To enable
Gemma or another local model, expose it through one of these supported APIs:

```bash
# OpenAI-compatible chat completions
export LLM_PROVIDER="openai"
export LLM_BASE_URL="http://127.0.0.1:11434/v1"
export LLM_MODEL="gemma2:2b"

# Ollama chat API
export LLM_PROVIDER="ollama"
export LLM_BASE_URL="http://127.0.0.1:11434"
export LLM_MODEL="gemma2:2b"
```

Do not expose this service directly to browsers or the public internet.
