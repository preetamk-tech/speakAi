import io
import os
import re
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import List, Optional, Callable
from pypdf import PdfReader
from docx import Document
from PIL import Image, ImageEnhance
import pytesseract

logger = logging.getLogger(__name__)

TESSERACT_CANDIDATES = [
    "/opt/homebrew/bin/tesseract",
    "/usr/local/bin/tesseract",
    "/usr/bin/tesseract"
]
for _p in TESSERACT_CANDIDATES:
    if os.path.exists(_p):
        pytesseract.pytesseract.tesseract_cmd = _p
        break

JUNK_PAGE_KEYWORDS = [
    "©", "copyright", "all rights reserved", "isbn", "printed at", "published by",
    "प्रथम संस्करण", "द्वितीय संस्करण", "तृतीय संस्करण", "संस्करण", "मूल्य",
    "प्रकाशक", "मुद्रक", "प्रेस", "प्रतिलिप्यधिकार", "सर्वाधिकार सुरक्षित",
    "अनुमति प्राप्त करना आवश्यक", "मंचन, प्रसारण"
]

TOC_KEYWORDS = [
    "table of contents", "contents", "विषय-सूची", "विषय सूची", "अनुक्रमणिका", "क्रम सूची"
]


def _unwrap_book_lines(text: str) -> str:
    """Re-joins hyphenated words and unwraps mid-sentence line breaks for fluid narration."""
    text = re.sub(r"([\u0900-\u097FA-Za-z]+)-\s*\n\s*([\u0900-\u097FA-Za-z]+)", r"\1\2", text)

    lines = [l.strip() for l in text.splitlines()]
    merged = []
    buf = ""
    for line in lines:
        if not line:
            if buf:
                merged.append(buf)
                buf = ""
            continue

        is_short_cue = len(line.split()) <= 3 and not line.endswith(("।", ".", "?", "!"))
        if is_short_cue and buf and buf.endswith(("।", ".", "?", "!", ":")):
            merged.append(buf)
            buf = line
            continue

        if not buf:
            buf = line
        elif buf.endswith(("।", ".", "?", "!", ":")):
            merged.append(buf)
            buf = line
        else:
            buf = buf + " " + line

    if buf:
        merged.append(buf)
    return "\n".join(merged)


def clean_text_for_speech(text: str) -> str:
    """Strips URLs, page numbers, running headers, and stray scan noise cleanly."""
    if not text or not text.strip():
        return text

    cleaned_lines = []
    for line in text.splitlines():
        l_strip = line.strip()
        if not l_strip:
            continue
        if re.search(r"(instapdf|downloaded|www\.|\.com|\.in|\.org|http)", l_strip, re.I):
            continue
        if re.match(r"^\(?[०-९\d]+\)?$", l_strip):
            continue
        if re.match(r"^[०-९\d]{1,4}\s*[/|\\]\s*.{1,35}$", l_strip) or re.match(r"^.{1,35}\s*[/|\\]\s*[०-९\d]{1,4}$", l_strip):
            continue
        cleaned_lines.append(l_strip)

    text = _unwrap_book_lines("\n".join(cleaned_lines))
    text = re.sub(r"(?<!\S)[|+*>~^_]{1,3}(?!\S)", " ", text)
    text = re.sub(r"\s+\|\s+", "। ", text)
    text = re.sub(r"\s+([।.,!?])", r"\1", text)
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def is_junk_front_matter_page(page_text: str, page_index: int, total_pages: int) -> bool:
    """Detects cover, copyright, publisher, price, and TOC pages so they are excluded."""
    if not page_text or not page_text.strip():
        return True

    clean = page_text.strip()
    words = clean.split()

    if page_index < min(6, total_pages) and len(words) < 35:
        return True

    if page_index < min(8, total_pages) or page_index >= max(0, total_pages - 2):
        lower = clean.lower()
        hits = sum(1 for kw in JUNK_PAGE_KEYWORDS if kw.lower() in lower)
        has_pincode = bool(re.search(r"[०-९0-9]{6}", clean))
        if hits >= 2 or (hits >= 1 and has_pincode and len(words) < 90):
            return True

    lines = [l.strip() for l in clean.splitlines() if l.strip()]
    if lines:
        first_lines = " ".join(lines[:3]).lower()
        is_toc_header = any(kw in first_lines for kw in TOC_KEYWORDS)
        numbered_tail_lines = sum(1 for l in lines if re.search(r"[०-९0-9]{1,4}\s*$", l))
        if is_toc_header or (len(lines) >= 5 and numbered_tail_lines / len(lines) > 0.6):
            return True

    return False


def filter_book_pages(pages_text: List[str]) -> List[str]:
    """Filters non-content front-matter and index pages from multi-page books."""
    total = len(pages_text)
    if total <= 2:
        return [p for p in pages_text if p and p.strip()]

    filtered = [
        page_txt for idx, page_txt in enumerate(pages_text)
        if not is_junk_front_matter_page(page_txt, idx, total)
    ]
    return filtered if filtered else [p for p in pages_text if p and p.strip()]


def _prepare_ocr_image(pil_img: Image.Image) -> Image.Image:
    """Upscales low-resolution page scans in memory-efficient grayscale before OCR."""
    img = pil_img.convert("L")
    w, h = img.size
    if w < 1200:
        img = img.resize((w * 2, h * 2), Image.Resampling.LANCZOS)
    return ImageEnhance.Contrast(img).enhance(1.35)


def extract_pages_from_file(
    file_bytes: bytes,
    filename: str,
    progress_callback: Optional[Callable[[int, int], None]] = None
) -> List[str]:
    """Extracts page-by-page text from PDFs, DOCX, TXT, or Images with progress reporting."""
    ext = Path(filename).suffix.lower()

    if ext in [".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff"]:
        image = _prepare_ocr_image(Image.open(io.BytesIO(file_bytes)))
        try:
            raw_text = pytesseract.image_to_string(image, lang="hin+eng+mar+guj+ben+tam+tel")
        except Exception:
            raw_text = pytesseract.image_to_string(image, lang="hin+eng")
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_text_for_speech(raw_text)
        return [cleaned] if cleaned else []

    if ext == ".pdf":
        reader = PdfReader(io.BytesIO(file_bytes))
        total_pages = len(reader.pages)
        results = [""] * total_pages
        ocr_tasks = []
        done_count = 0

        for i, page in enumerate(reader.pages):
            page_text = page.extract_text()
            if page_text and len(page_text.strip()) > 30:
                results[i] = page_text.strip()
                done_count += 1
            else:
                imgs = [img.data for img in page.images]
                if imgs:
                    ocr_tasks.append((i, imgs))
                else:
                    done_count += 1

        if progress_callback:
            progress_callback(done_count, max(1, total_pages))

        if ocr_tasks:
            def _ocr_page(item):
                idx, img_bytes_list = item
                parts = []
                for b in img_bytes_list:
                    try:
                        pil_img = _prepare_ocr_image(Image.open(io.BytesIO(b)))
                        txt = pytesseract.image_to_string(pil_img, lang="hin", config="--psm 6")
                        if txt.strip():
                            parts.append(txt.strip())
                    except Exception:
                        pass
                return idx, "\n".join(parts)

            workers = 2 if (os.environ.get("RENDER") or os.environ.get("PORT")) else min(6, os.cpu_count() or 4)
            with ThreadPoolExecutor(max_workers=workers) as executor:
                futures = [executor.submit(_ocr_page, task) for task in ocr_tasks]
                for fut in as_completed(futures):
                    idx, txt = fut.result()
                    results[idx] = txt
                    done_count += 1
                    if progress_callback:
                        progress_callback(done_count, max(1, total_pages))

        return [c for c in (clean_text_for_speech(r) for r in results if r and r.strip()) if c]

    if ext in [".docx", ".doc"]:
        doc = Document(io.BytesIO(file_bytes))
        paragraphs = [p.text.strip() for p in doc.paragraphs if p.text.strip()]
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_text_for_speech("\n\n".join(paragraphs))
        return [cleaned] if cleaned else []

    if ext in [".txt", ".md"]:
        raw_text = file_bytes.decode("utf-8", errors="ignore").strip()
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_text_for_speech(raw_text)
        return [cleaned] if cleaned else []

    raise ValueError(f"Unsupported file format: {ext}. Please upload a PDF, DOCX, TXT, or Image file.")


def extract_text_from_file(
    file_bytes: bytes,
    filename: str,
    progress_callback: Optional[Callable[[int, int], None]] = None
) -> str:
    """Extracts and cleans full document text."""
    return "\n\n".join(extract_pages_from_file(file_bytes, filename, progress_callback=progress_callback))
