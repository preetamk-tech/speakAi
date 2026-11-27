import os
import re
import json
import base64
import logging
import requests
from pathlib import Path
from typing import Optional

from doc_parser import clean_text_for_speech

logger = logging.getLogger(__name__)

# Load environment variables from backend/.env if present
ENV_PATH = Path(__file__).parent / ".env"


def _load_env_file():
    if not ENV_PATH.exists():
        return
    try:
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            k = k.strip()
            v = v.strip().strip('"').strip("'")
            if k:
                os.environ[k] = v
    except Exception as e:
        logger.warning(f"Could not read .env file: {e}")


_load_env_file()

FALLBACK_MODELS = [
    "gemini-3.1-flash-lite-preview",
    "gemini-3-flash-preview",
    "gemini-3.8-flash",
    "gemini-flash-latest"
]


def get_gemini_api_key() -> str:
    _load_env_file()
    return os.environ.get("GEMINI_API_KEY", "").strip()


def get_gemini_model() -> str:
    _load_env_file()
    return os.environ.get("GEMINI_MODEL", "gemini-3.1-flash-lite-preview").strip() or "gemini-3.1-flash-lite-preview"


def is_gemini_configured() -> bool:
    key = get_gemini_api_key()
    return bool(key and not key.startswith("PASTE_YOUR_"))


def _post_gemini(
    parts: list,
    temperature: float = 0.15,
    timeout: int = 90,
    response_mime_type: Optional[str] = None
) -> Optional[str]:
    api_key = get_gemini_api_key()
    if not api_key or api_key.startswith("PASTE_YOUR_"):
        return None

    primary = get_gemini_model()
    candidates_models = [primary] + [m for m in FALLBACK_MODELS if m != primary]

    gen_config = {"temperature": temperature}
    if response_mime_type:
        gen_config["responseMimeType"] = response_mime_type

    payload = {
        "contents": [{"parts": parts}],
        "generationConfig": gen_config
    }

    for model in candidates_models:
        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={api_key}"
        try:
            resp = requests.post(url, json=payload, timeout=timeout)
            if resp.status_code == 200:
                data = resp.json()
                candidates = data.get("candidates", [])
                if candidates:
                    content_parts = candidates[0].get("content", {}).get("parts", [])
                    text_out = "".join(p.get("text", "") for p in content_parts).strip()
                    if text_out:
                        return text_out
            else:
                logger.warning(f"Gemini model {model} HTTP {resp.status_code}: {resp.text[:200]}")
                if resp.status_code in (404, 429, 500, 503):
                    continue
                break
        except Exception as e:
            logger.warning(f"Gemini request failed on {model}: {e}")
            continue
    return None


def extract_text_from_image_with_gemini(image_bytes: bytes, mime_type: str = "image/png") -> Optional[str]:
    """
    Uses Gemini Vision to read text directly from screenshots, photos, or scanned pages
    with human-level Devanagari & English accuracy.
    """
    if not is_gemini_configured():
        return None

    b64 = base64.b64encode(image_bytes).decode("utf-8")
    prompt = (
        "Read and transcribe ALL visible story/document text from this image accurately.\n"
        "RULES:\n"
        "1. Preserve the exact original language (Hindi, Marathi, Gujarati, English, etc.).\n"
        "2. Fix broken line breaks so sentences flow naturally.\n"
        "3. Ignore watermarks, UI icons, battery/time status bars, or page numbers.\n"
        "4. Return ONLY the clean readable text, with no extra commentary."
    )

    parts = [
        {"text": prompt},
        {"inline_data": {"mime_type": mime_type, "data": b64}}
    ]
    return _post_gemini(parts, temperature=0.1, timeout=45)


def _clean_block_with_gemini(block_text: str, book_title: str = "", chapter_title: str = "") -> Optional[str]:
    prompt = (
        "You are an expert Hindi and Indian-language literary proofreader preparing a book chapter for voice narration.\n"
        f"Book: {book_title or 'Document'} | Section: {chapter_title or 'Chapter'}\n\n"
        "TASK:\n"
        "Fix all OCR typos, broken matras, wrong words inferred from context (for example: "
        "'मूत्र बादल सरकार' -> 'मूल बादल सरकार', 'रमजान' -> 'श्मशान' in cremation context, "
        "'राने की आवाज' -> 'रोने की आवाज', 'लडकी'/'नडकी' -> 'लड़की', 'यध' -> 'वर्ष'), "
        "and fix broken line wraps & punctuation (।, ?, !) so every sentence reads smoothly aloud.\n\n"
        "STRICT RULES:\n"
        "1. Keep 100% of the original story, dialogues, character names, and meaning intact.\n"
        "2. Do NOT summarize, skip, shorten, or translate into another language.\n"
        "3. Remove leftover stray page numbers or OCR junk symbols.\n"
        "4. Output ONLY the corrected chapter text — no intro or explanation.\n\n"
        f"RAW TEXT:\n{block_text}\n\nCORRECTED TEXT:"
    )

    out = _post_gemini([{"text": prompt}], temperature=0.1, timeout=90)
    if out and len(out) >= len(block_text) * 0.45:
        return out.strip()
    return None


def clean_chapter_with_gemini(text: str, book_title: str = "", chapter_title: str = "") -> str:
    """
    Repairs OCR typos, context errors, and sentence breaks for a chapter via Gemini,
    falling back to the local cleaner if the API key is missing or unreachable.
    """
    base_clean = clean_text_for_speech(text)
    if not is_gemini_configured() or not base_clean.strip():
        return base_clean

    # Split large chapters into ~5500-char paragraph-aligned blocks so Gemini never truncates output
    paragraphs = [p.strip() for p in base_clean.split("\n") if p.strip()]
    blocks = []
    cur = []
    cur_len = 0
    for p in paragraphs:
        if cur_len + len(p) > 5500 and cur:
            blocks.append("\n".join(cur))
            cur = [p]
            cur_len = len(p)
        else:
            cur.append(p)
            cur_len += len(p) + 1
    if cur:
        blocks.append("\n".join(cur))

    cleaned_blocks = []
    for blk in blocks:
        res = _clean_block_with_gemini(blk, book_title=book_title, chapter_title=chapter_title)
        cleaned_blocks.append(res if res else blk)

    return "\n\n".join(cleaned_blocks).strip()


def clean_index_with_gemini(raw_index_text: str) -> str:
    """
    Repairs OCR typos in extracted Table of Contents entries while strictly keeping
    the numbered 'N. Title' line-by-line structure intact.
    """
    if not raw_index_text or not raw_index_text.strip():
        return ""
    if not is_gemini_configured():
        return raw_index_text.strip()

    prompt = (
        "You are an expert Indian-language proofreader fixing OCR typos in a book's Table of Contents (Index).\n"
        "TASK:\n"
        "Fix OCR spelling mistakes and broken Devanagari/English characters in each numbered title "
        "(e.g. '3. र्गधा और धोबी' -> '3. गधा और धोबी', '18. दो बबल्ल्लयों की किानी' -> '18. दो बिल्लियों की कहानी').\n\n"
        "STRICT RULES:\n"
        "1. Keep the exact same numbering and order: one entry per line as '<number>. <clean title>'.\n"
        "2. Do NOT add, merge, translate, or drop any entry.\n"
        "3. Output ONLY the corrected numbered list, nothing else.\n\n"
        f"RAW INDEX:\n{raw_index_text.strip()}\n\nCORRECTED INDEX:"
    )

    out = _post_gemini([{"text": prompt}], temperature=0.1, timeout=45)
    if out:
        cleaned_lines = [l.strip() for l in out.splitlines() if l.strip()]
        raw_lines = [l.strip() for l in raw_index_text.splitlines() if l.strip()]
        if len(cleaned_lines) >= max(1, int(len(raw_lines) * 0.7)):
            return "\n".join(cleaned_lines)
    return raw_index_text.strip()


def translate_with_gemini(text: str, target_language: str) -> Optional[str]:
    """Translates narration text naturally while preserving storytelling emotion."""
    if not is_gemini_configured() or not text.strip():
        return None

    lang_names = {
        "hi": "Hindi", "mr": "Marathi", "gu": "Gujarati",
        "bn": "Bengali", "ta": "Tamil", "te": "Telugu",
        "kn": "Kannada", "ml": "Malayalam", "ur": "Urdu", "en": "English"
    }
    lang_label = lang_names.get(target_language, target_language)

    prompt = (
        f"Translate the following story passage into natural, expressive {lang_label} suitable for audio narration. "
        "If the text is already in that language, simply polish punctuation for smooth speaking. "
        "Return ONLY the translated text.\n\n"
        f"{text}"
    )
    return _post_gemini([{"text": prompt}], temperature=0.2, timeout=30)
