import io
import os
import re
import logging
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import List, Optional, Callable, Tuple
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
    "table of contents", "contents", "विषय-सूची", "विषय सूची",
    "विषय", "अनुक्रमणिका", "क्रम सूची", "index"
]

DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
NOISE_LINE_PATTERN = re.compile(r"(instapdf|downloaded\s+from|www\.|https?://|\.com\b|\.in\b|\.org\b)", re.I)
TOC_HEADER_PATTERN = re.compile(
    r"^(?:विषय(?:\s*[-–:]?\s*सूची)?|अनुक्रमणिका|क्रम\s*[-–]?\s*सूची|table\s+of\s+contents|contents|index)\s*:?$",
    re.I
)
TOC_NUMBERED_ITEM_PATTERN = re.compile(r"(?:^|\s)([०-९0-9]{1,3})\.(?=\s|$)")


def _to_int(num_str: str) -> int:
    return int(num_str.translate(DEVANAGARI_DIGITS))


def _heal_broken_indic_lines(text: str) -> str:
    """Re-attaches orphaned Indic combining marks / vowel signs wrapped onto new lines."""
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    return re.sub(
        r"[ \t]*\n[ \t]*([\u0900-\u0903\u093A-\u094F\u0951-\u0957\u0962-\u0963"
        r"\u0981-\u0983\u09BC-\u09CD\u0A81-\u0A83\u0ABC-\u0ACD\u0B82-\u0BCD\u0C00-\u0C03\u0C3E-\u0C4D]+)",
        r"\1",
        text
    )


def clean_raw_page_lines(text: str) -> str:
    """
    Removes scan watermarks, URLs, running page headers, and standalone unparenthesized
    page numbers while preserving line breaks and chapter markers like (1) or 1. Title.
    """
    if not text or not text.strip():
        return ""

    text = _heal_broken_indic_lines(text)
    raw_lines = [l.strip() for l in text.splitlines() if l.strip()]
    if not raw_lines:
        return ""

    kept = []
    total = len(raw_lines)
    for idx, l_strip in enumerate(raw_lines):
        if NOISE_LINE_PATTERN.search(l_strip):
            continue
        if re.match(r"^[०-९\d]{1,4}\s*[/|\\]\s*.{1,35}$", l_strip) or re.match(r"^.{1,35}\s*[/|\\]\s*[०-९\d]{1,4}$", l_strip):
            continue
        # Only strip unparenthesized standalone page numbers at top/bottom edge of page
        if idx < 2 or idx >= total - 2:
            if re.match(r"^(?:[-–—]\s*)?[०-९\d]{1,4}(?:\s*[-–—])?$", l_strip) or re.match(r"(?i)^page\s+\d{1,4}$", l_strip):
                continue
        kept.append(l_strip)

    return "\n".join(kept).strip()


def _unwrap_book_lines(text: str) -> str:
    """Re-joins hyphenated words and unwraps mid-sentence line breaks for fluid narration."""
    text = _heal_broken_indic_lines(text)
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
    """Strips URLs, page numbers, running headers, and stray scan noise cleanly for TTS narration."""
    if not text or not text.strip():
        return text

    text = _heal_broken_indic_lines(text)
    cleaned_lines = []
    for line in text.splitlines():
        l_strip = line.strip()
        if not l_strip:
            continue
        if NOISE_LINE_PATTERN.search(l_strip):
            continue
        if re.match(r"^[०-९\d]{1,4}$", l_strip):
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


def is_toc_page(
    page_text: str,
    page_index: int,
    total_pages: int,
    in_toc_continuation: bool = False
) -> bool:
    """Detects Index / Table of Contents pages near the beginning of a book."""
    if not page_text or not page_text.strip() or page_index >= min(12, total_pages):
        return False

    lines = [l.strip() for l in page_text.splitlines() if l.strip()]
    if not lines:
        return False

    # Never confuse normal narrative pages (many long full-sentence lines) with an Index page
    narrative_sentences = sum(1 for l in lines if len(l.split()) >= 10 and l.endswith(("।", ".")))
    if narrative_sentences >= 4:
        return False

    # Story pages starting with "(1)" are content, not TOC
    if re.match(r"^\(\s*[०-९0-9]{1,3}\s*\)$", lines[0]):
        return False

    for line in lines[:4]:
        if TOC_HEADER_PATTERN.match(line):
            return True
        low = line.lower()
        if any(kw == low or low.startswith(kw + " ") for kw in TOC_KEYWORDS):
            if len(lines) >= 4:
                return True

    numbered_matches = sum(len(TOC_NUMBERED_ITEM_PATTERN.findall(l)) for l in lines)
    numbered_tail_lines = sum(1 for l in lines if re.search(r"[०-९0-9]{1,4}\s*$", l))
    min_required = 2 if in_toc_continuation else 6
    if numbered_matches >= min_required and narrative_sentences <= 1:
        return True
    if len(lines) >= 6 and numbered_tail_lines / len(lines) > 0.6 and narrative_sentences <= 1:
        return True

    return False


def parse_toc_entries(index_pages: List[str]) -> List[Tuple[int, str]]:
    """
    Parses extracted Index / Table of Contents pages (including 2-column rows and wrapped titles)
    into a sorted list of (chapter_number, title).
    """
    entries: dict = {}
    last_num: Optional[int] = None

    for page in index_pages:
        for raw_line in page.splitlines():
            line = re.sub(r"\s+", " ", raw_line).strip()
            if not line or TOC_HEADER_PATTERN.match(line) or NOISE_LINE_PATTERN.search(line):
                continue

            matches = list(TOC_NUMBERED_ITEM_PATTERN.finditer(line))
            if matches:
                for i, m in enumerate(matches):
                    num = _to_int(m.group(1))
                    if num <= 0 or num > 500:
                        continue
                    seg_start = m.end()
                    seg_end = matches[i + 1].start() if i + 1 < len(matches) else len(line)
                    title = line[seg_start:seg_end].strip()
                    title = re.sub(r"(?:\.{2,}|\s{2,}|\t)\s*[०-९\d]{1,4}\s*$", "", title).strip()
                    title = title.strip(" .-–—|")
                    if title:
                        entries[num] = title
                        last_num = num
                    else:
                        last_num = None
            elif last_num is not None and len(line) <= 45 and not re.match(r"^[०-९\d]+$", line):
                # Append wrapped continuation line onto the previous entry (e.g. long story title)
                cont = re.sub(r"(?:\.{2,}|\s{2,}|\t)\s*[०-९\d]{1,4}\s*$", "", line).strip()
                if cont and last_num in entries and len(entries[last_num]) < 80:
                    entries[last_num] = f"{entries[last_num]} {cont}".strip()
                last_num = None
            else:
                last_num = None

    return sorted(entries.items(), key=lambda x: x[0])


def format_toc_text(entries: List[Tuple[int, str]], fallback_pages: Optional[List[str]] = None) -> str:
    """Formats parsed Index entries as clean line-by-line numbered text."""
    if entries:
        return "\n".join(f"{num}. {title}" for num, title in entries)

    if not fallback_pages:
        return ""

    clean_lines = []
    for p in fallback_pages:
        for raw_line in p.splitlines():
            line = re.sub(r"\s+", " ", raw_line).strip()
            if not line or TOC_HEADER_PATTERN.match(line) or NOISE_LINE_PATTERN.search(line):
                continue
            clean_lines.append(line)
    return "\n".join(clean_lines).strip()


def is_junk_front_matter_page(page_text: str, page_index: int, total_pages: int) -> bool:
    """Detects cover, copyright, publisher, and price pages so they are excluded from story audio."""
    if not page_text or not page_text.strip():
        return True

    clean = page_text.strip()
    words = clean.split()

    if page_index < min(6, total_pages) and len(words) < 35:
        # Don't drop a short page if it clearly starts a numbered chapter like (1) Title
        if not re.search(r"(?m)^(?:\(\s*[०-९\d]{1,3}\s*\)|chapter\s+\d+|अध्याय\s*[०-९\d]+)", clean, re.I):
            return True

    if page_index < min(8, total_pages) or page_index >= max(0, total_pages - 2):
        lower = clean.lower()
        hits = sum(1 for kw in JUNK_PAGE_KEYWORDS if kw.lower() in lower)
        has_pincode = bool(re.search(r"[०-९0-9]{6}", clean))
        if hits >= 2 or (hits >= 1 and has_pincode and len(words) < 90):
            return True

    return False


def separate_front_matter_and_index(raw_pages: List[str]) -> Tuple[Optional[str], List[Tuple[int, str]], List[str]]:
    """
    Separates front-matter junk, Index/TOC pages (if present), and story content pages.
    Returns (formatted_index_text_or_None, parsed_toc_entries, content_pages).
    """
    total = len(raw_pages)
    if total == 0:
        return None, [], []

    toc_pages: List[str] = []
    content_pages: List[str] = []
    scanning_front_toc = True

    for idx, page_txt in enumerate(raw_pages):
        if not page_txt or not page_txt.strip():
            continue

        if scanning_front_toc and total > 2 and is_toc_page(
            page_txt, idx, total, in_toc_continuation=(len(toc_pages) > 0 and len(content_pages) == 0)
        ):
            toc_pages.append(page_txt)
            continue

        if total > 2 and is_junk_front_matter_page(page_txt, idx, total):
            continue

        # Once real story content begins, stop treating later pages as front TOC
        if content_pages:
            scanning_front_toc = False

        content_pages.append(page_txt)

    if not content_pages:
        content_pages = [p for p in raw_pages if p and p.strip()]

    toc_entries = parse_toc_entries(toc_pages) if toc_pages else []
    index_text = format_toc_text(toc_entries, fallback_pages=toc_pages) if toc_pages else None
    if index_text and not index_text.strip():
        index_text = None

    return index_text, toc_entries, content_pages


def filter_book_pages(pages_text: List[str]) -> List[str]:
    """Filters non-content front-matter and index pages from multi-page books."""
    _, _, content_pages = separate_front_matter_and_index(pages_text)
    return content_pages


def _prepare_ocr_image(pil_img: Image.Image) -> Image.Image:
    """Upscales low-resolution page scans in memory-efficient grayscale before OCR."""
    img = pil_img.convert("L")
    w, h = img.size
    if w < 1200:
        img = img.resize((w * 2, h * 2), Image.Resampling.LANCZOS)
    return ImageEnhance.Contrast(img).enhance(1.35)


def extract_raw_pages_from_file(
    file_bytes: bytes,
    filename: str,
    progress_callback: Optional[Callable[[int, int], None]] = None
) -> List[str]:
    """
    Extracts page-by-page text preserving line boundaries, chapter markers, and TOC layout.
    """
    ext = Path(filename).suffix.lower()

    if ext in [".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff"]:
        image = _prepare_ocr_image(Image.open(io.BytesIO(file_bytes)))
        try:
            raw_text = pytesseract.image_to_string(image, lang="hin+eng+mar+guj+ben+tam+tel")
        except Exception:
            raw_text = pytesseract.image_to_string(image, lang="hin+eng")
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_raw_page_lines(raw_text)
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

        return [c for c in (clean_raw_page_lines(r) for r in results if r and r.strip()) if c]

    if ext in [".docx", ".doc"]:
        doc = Document(io.BytesIO(file_bytes))
        paragraphs = [p.text.strip() for p in doc.paragraphs if p.text.strip()]
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_raw_page_lines("\n".join(paragraphs))
        return [cleaned] if cleaned else []

    if ext in [".txt", ".md"]:
        raw_text = file_bytes.decode("utf-8", errors="ignore").strip()
        if progress_callback:
            progress_callback(1, 1)
        cleaned = clean_raw_page_lines(raw_text)
        return [cleaned] if cleaned else []

    raise ValueError(f"Unsupported file format: {ext}. Please upload a PDF, DOCX, TXT, or Image file.")


def extract_pages_from_file(
    file_bytes: bytes,
    filename: str,
    progress_callback: Optional[Callable[[int, int], None]] = None
) -> List[str]:
    """Extracts page-by-page speech-cleaned text from PDFs, DOCX, TXT, or Images."""
    raw_pages = extract_raw_pages_from_file(file_bytes, filename, progress_callback=progress_callback)
    return [c for c in (clean_text_for_speech(p) for p in raw_pages) if c]


def extract_text_from_file(
    file_bytes: bytes,
    filename: str,
    progress_callback: Optional[Callable[[int, int], None]] = None
) -> str:
    """Extracts and cleans full document text."""
    return "\n\n".join(extract_pages_from_file(file_bytes, filename, progress_callback=progress_callback))
