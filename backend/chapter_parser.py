import re
from typing import List, Dict, Any

EXPLICIT_CHAPTER_PATTERNS = [
    # English chapter patterns (require a chapter/part number)
    re.compile(r'(?i)^(?:chapter|ch\.?|act|part|scene)\s+[0-9ivxlcdm]+\b.*$', re.MULTILINE),
    # Devanagari chapter patterns (require a number or numeral)
    re.compile(r'^(?:अध्याय|भाग|पाठ|प्रकरण|खंड|अंक|दृश्य)\s*[:\-]?\s*[०-९0-9ivxlcdm]+\b.*$', re.MULTILINE),
    # Dramatic acts: प्रथम अंक, द्वितीय अंक, तृतीय अंक, etc.
    re.compile(r'^(?:अथम|प्रथम|द्वित्तीय|द्वितीय|तृतीय|चतुर्थ|पंचम|पहला|दूसरा|तीसरा|चौथा)\s+(?:अंक|अक|अकः|दृश्य|भाग|खंड)\s*[:\-]?.*$', re.MULTILINE),
]

NUMBERED_HEADING_PATTERN = re.compile(
    r'^(?:\(\s*[०-९0-9]+\s*\)|[०-९0-9]+\.)\s+[^/|।,;]{3,50}(?<![,;।|])$',
    re.MULTILINE
)


def _find_matches(lines: List[str], patterns: List[re.Pattern]) -> List[tuple]:
    matches = []
    for idx, line in enumerate(lines):
        line_clean = line.strip()
        if not line_clean or len(line_clean) > 65:
            continue
        for pat in patterns:
            if pat.match(line_clean):
                matches.append((idx, line_clean))
                break
    return matches


def split_into_chapters(text: str, default_book_title: str = "My Story") -> List[Dict[str, Any]]:
    """
    Analyzes document text and segments it into distinct chapters.
    Prioritizes explicit Chapter/Act headings over numbered headings,
    and falls back to natural 1500-word parts for unsectioned long books.
    """
    if not text or not text.strip():
        return []

    lines = text.splitlines()

    # Priority 1: Explicit Chapter / Act / Adhyay headings
    chapter_indices = _find_matches(lines, EXPLICIT_CHAPTER_PATTERNS)

    # Priority 2: Numbered section titles (e.g. "1. Title", "(2) Title")
    if len(chapter_indices) < 2:
        numbered_indices = _find_matches(lines, [NUMBERED_HEADING_PATTERN])
        if len(numbered_indices) >= 2:
            chapter_indices = numbered_indices

    if len(chapter_indices) >= 2:
        chapters = []
        first_idx = chapter_indices[0][0]
        if first_idx > 3:
            intro_content = "\n".join(lines[:first_idx]).strip()
            if len(intro_content.split()) > 30:
                chapters.append({
                    "index": 1,
                    "chapter_index": 1,
                    "title": f"{default_book_title} - Introduction",
                    "content": intro_content,
                    "text": intro_content,
                    "word_count": len(intro_content.split())
                })

        for i in range(len(chapter_indices)):
            start_line, title = chapter_indices[i]
            end_line = chapter_indices[i + 1][0] if i + 1 < len(chapter_indices) else len(lines)
            chapter_content = "\n".join(lines[start_line:end_line]).strip()
            ch_num = len(chapters) + 1

            chapters.append({
                "index": ch_num,
                "chapter_index": ch_num,
                "title": title,
                "content": chapter_content,
                "text": chapter_content,
                "word_count": len(chapter_content.split())
            })
        return chapters

    # Unsectioned long document: split into comfortable ~1500-word chapters
    words = text.split()
    if len(words) > 2000:
        words_per_part = 1500
        chapters = []
        total_parts = (len(words) + words_per_part - 1) // words_per_part
        for p in range(total_parts):
            part_words = words[p * words_per_part : (p + 1) * words_per_part]
            part_content = " ".join(part_words)
            part_title = f"{default_book_title} - Part {p + 1}"
            chapters.append({
                "index": p + 1,
                "chapter_index": p + 1,
                "title": part_title,
                "content": part_content,
                "text": part_content,
                "word_count": len(part_words)
            })
        return chapters

    # Short single document
    first_meaningful_line = next((l.strip() for l in lines if l.strip()), default_book_title)
    if len(first_meaningful_line) > 50:
        first_meaningful_line = default_book_title

    return [{
        "index": 1,
        "chapter_index": 1,
        "title": first_meaningful_line,
        "content": text.strip(),
        "text": text.strip(),
        "word_count": len(words)
    }]
