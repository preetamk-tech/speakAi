import re
from difflib import SequenceMatcher
from typing import List, Dict, Any, Optional, Tuple

from doc_parser import clean_text_for_speech, _heal_broken_indic_lines

DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")

EXPLICIT_CHAPTER_PATTERNS = [
    re.compile(r"(?i)^(?:chapter|ch\.?|act|part|scene|story)\s*[:\-]?\s*([0-9ivxlcdm]+)\b(.*)$"),
    re.compile(r"^(?:अध्याय|भाग|पाठ|प्रकरण|खंड|अंक|दृश्य|कहानी|कथा)\s*[:\-]?\s*([०-९0-9ivxlcdm]+)\b(.*)$"),
    re.compile(r"^(?:अथम|प्रथम|द्वित्तीय|द्वितीय|तृतीय|चतुर्थ|पंचम|पहला|दूसरा|तीसरा|चौथा)\s+(?:अंक|अक|अकः|दृश्य|भाग|खंड)\s*[:\-]?(.*)$"),
]

STANDALONE_NUM_PATTERN = re.compile(
    r"^(?:\(\s*([०-९0-9]{1,3})\s*\)|\[\s*([०-९0-9]{1,3})\s*\]|([०-९0-9]{1,3})\s*[\.\)])$"
)

SAMELINE_NUM_PATTERN = re.compile(
    r"^(?:\(\s*([०-९0-9]{1,3})\s*\)\s*|\[\s*([०-९0-9]{1,3})\s*\]\s*|([०-९0-9]{1,3})\.\s+)(.{2,75})$"
)


def _parse_num(s: Optional[str]) -> Optional[int]:
    if not s:
        return None
    ascii_s = s.translate(DEVANAGARI_DIGITS).strip()
    return int(ascii_s) if ascii_s.isdigit() else None


def _norm_for_match(s: str) -> str:
    """Normalizes Devanagari/Latin text lightly (including common OCR swaps) to match titles against Index."""
    s = (s or "").replace("र्ग", "ग").replace("िा", "हा").replace("िं", "हं")
    s = re.sub(r"[\u093C\u0901\u0902\s\W_]+", "", s, flags=re.UNICODE)
    return s.lower()


def _similarity(a: str, b: str) -> float:
    na, nb = _norm_for_match(a), _norm_for_match(b)
    if not na or not nb:
        return 0.0
    return SequenceMatcher(None, na, nb).ratio()


def _is_valid_title_candidate(line: str) -> bool:
    if not line:
        return False
    clean = line.strip()
    if len(clean) < 2 or len(clean) > 75:
        return False
    if clean.startswith(('"', "“", "‘", "'")):
        return False
    if clean.endswith((",", ";")):
        return False
    # Narrative full sentences with sentence-ending period/danda and many words are not titles
    if clean.endswith(("।", ".")) and len(clean.split()) >= 7:
        return False
    return True


def _resolve_wrapped_title(lines: List[str], start_j: int, expected_toc_title: Optional[str]) -> Tuple[str, int]:
    """
    Combines 1-2 short wrapped title lines (such as split Devanagari headings) cleanly,
    returning (combined_title, first_body_line_index).
    """
    first = lines[start_j].strip()
    title_parts = [first]
    body_idx = start_j + 1

    for offset in (1, 2):
        nxt_idx = start_j + offset
        if nxt_idx >= len(lines):
            break
        nxt = lines[nxt_idx].strip()
        if not nxt:
            body_idx = nxt_idx + 1
            break

        # Stop if next line clearly starts narrative prose or quotes or another chapter number
        if STANDALONE_NUM_PATTERN.match(nxt) or SAMELINE_NUM_PATTERN.match(nxt):
            break
        if nxt.startswith(('"', "“", "‘")) or len(nxt.split()) > 5 or nxt.endswith(("।", ".")):
            break

        combined_try = f"{' '.join(title_parts)} {nxt}".strip()
        if expected_toc_title:
            if _similarity(combined_try, expected_toc_title) > _similarity(" ".join(title_parts), expected_toc_title):
                title_parts.append(nxt)
                body_idx = nxt_idx + 1
                continue
            break
        elif len(nxt.split()) <= 2 and len(combined_try) <= 65:
            # Check if the line after nxt looks like real prose
            after_idx = nxt_idx + 1
            if after_idx < len(lines) and len(lines[after_idx].strip().split()) >= 5:
                title_parts.append(nxt)
                body_idx = nxt_idx + 1
                continue
            break
        else:
            break

    return " ".join(title_parts).strip(), body_idx


def _find_explicit_chapters(lines: List[str]) -> List[Dict[str, Any]]:
    matches = []
    for idx, raw in enumerate(lines):
        line = raw.strip()
        if not line or len(line) > 75:
            continue
        for pat in EXPLICIT_CHAPTER_PATTERNS:
            m = pat.match(line)
            if m:
                num = _parse_num(m.group(1)) if m.lastindex and m.lastindex >= 1 else None
                matches.append({
                    "start_line": idx,
                    "body_line": idx + 1,
                    "num": num,
                    "title": line,
                    "explicit": True,
                })
                break
    return matches


def _find_numbered_chapters(lines: List[str], toc_map: Dict[int, str]) -> List[Dict[str, Any]]:
    candidates = []
    i = 0
    n_lines = len(lines)

    while i < n_lines:
        line = lines[i].strip()
        if not line:
            i += 1
            continue

        # 1. Standalone marker like "(1)", "[1]", "1.", or bare "1" matching a TOC entry followed by title
        m_alone = STANDALONE_NUM_PATTERN.match(line)
        bare_toc_num = _parse_num(line) if (toc_map and re.match(r"^[०-९0-9]{1,3}$", line)) else None
        if m_alone or (bare_toc_num is not None and bare_toc_num in toc_map):
            num = bare_toc_num if bare_toc_num is not None else _parse_num(m_alone.group(1) or m_alone.group(2) or m_alone.group(3))
            if num is not None and 1 <= num <= 500:
                j = i + 1
                while j < n_lines and not lines[j].strip() and (j - i) <= 2:
                    j += 1
                if j < n_lines:
                    nxt = lines[j].strip()
                    if _is_valid_title_candidate(nxt) or num in toc_map:
                        raw_title, body_idx = _resolve_wrapped_title(lines, j, toc_map.get(num))
                        resolved_title = toc_map.get(num) or raw_title
                        candidates.append({
                            "start_line": i,
                            "body_line": body_idx,
                            "num": num,
                            "title": resolved_title,
                            "explicit": False,
                        })
                        i = body_idx
                        continue

        # 2. Same-line numbered heading like "(1) Title" or "1. Title"
        m_same = SAMELINE_NUM_PATTERN.match(line)
        if m_same:
            num = _parse_num(m_same.group(1) or m_same.group(2) or m_same.group(3))
            title_part = m_same.group(4).strip()
            if num is not None and 1 <= num <= 500 and _is_valid_title_candidate(title_part):
                resolved_title = toc_map.get(num) or title_part
                candidates.append({
                    "start_line": i,
                    "body_line": i + 1,
                    "num": num,
                    "title": resolved_title,
                    "explicit": False,
                })

        i += 1

    # Keep the longest strictly increasing sequence of chapter numbers (immune to stray outliers)
    max_toc_num = max(toc_map.keys()) + 5 if toc_map else 500
    valid = [
        c for c in candidates
        if c.get("num") is not None and c["num"] <= max_toc_num
    ]
    if not valid:
        return []

    n = len(valid)
    dp_len = [1] * n
    parent = [-1] * n
    for idx in range(n):
        for prev in range(idx):
            if valid[prev]["num"] < valid[idx]["num"] and dp_len[prev] + 1 > dp_len[idx]:
                dp_len[idx] = dp_len[prev] + 1
                parent[idx] = prev

    best_end = max(range(n), key=lambda k: dp_len[k])
    filtered = []
    cur = best_end
    while cur != -1:
        filtered.append(valid[cur])
        cur = parent[cur]
    filtered.reverse()
    return filtered


def _fill_missing_toc_gaps(
    lines: List[str],
    anchors: List[Dict[str, Any]],
    toc_entries: List[Tuple[int, str]]
) -> List[Dict[str, Any]]:
    """
    If OCR missed a number marker (e.g. '(42)') between two anchored chapters,
    locates the expected Index title in that exact range so no story gets swallowed.
    """
    if not anchors or not toc_entries:
        return anchors

    toc_nums = [num for num, _ in toc_entries]
    toc_map = dict(toc_entries)
    matched_nums = {a["num"] for a in anchors if a.get("num") is not None}
    missing = [n for n in toc_nums if n not in matched_nums]
    if not missing or len(missing) > len(toc_entries) * 0.5:
        return anchors

    augmented = list(anchors)
    for m_num in missing:
        expected_title = toc_map[m_num]
        prev_anchors = [a for a in augmented if (a.get("num") or 0) < m_num]
        next_anchors = [a for a in augmented if (a.get("num") or 0) > m_num]
        lo = prev_anchors[-1]["body_line"] if prev_anchors else 0
        hi = next_anchors[0]["start_line"] if next_anchors else len(lines)

        best_idx = -1
        best_body = -1
        best_score = 0.0
        for idx in range(lo, hi):
            cand_line = lines[idx].strip()
            if not _is_valid_title_candidate(cand_line):
                continue
            sc1 = _similarity(cand_line, expected_title)
            b_idx = idx + 1
            parts = [cand_line]
            for extra in (1, 2):
                if idx + extra < hi and len(lines[idx + extra].strip()) <= 35:
                    parts.append(lines[idx + extra].strip())
                    sc_multi = _similarity(" ".join(parts), expected_title)
                    if sc_multi > sc1:
                        sc1 = sc_multi
                        b_idx = idx + extra + 1
                else:
                    break
            if sc1 > best_score:
                best_score = sc1
                best_idx = idx
                best_body = b_idx

        if best_idx != -1 and best_score >= 0.58:
            augmented.append({
                "start_line": best_idx,
                "body_line": best_body,
                "num": m_num,
                "title": expected_title,
                "explicit": False,
            })
            augmented.sort(key=lambda a: a["start_line"])

    return augmented


def _format_display_title(ch_num: int, raw_title: str) -> str:
    clean = re.sub(r"\s+", " ", raw_title or "").strip()
    if not clean:
        return f"Chapter {ch_num}"
    if re.match(r"(?i)^(?:chapter|ch\.|अध्याय|प्रथम|द्वितीय|तृतीय|चतुर्थ|पंचम)", clean):
        return clean
    return f"Chapter {ch_num} ({clean})"


def _has_devanagari(text: str) -> bool:
    return bool(re.search(r"[\u0900-\u097F]", text or ""))


def _split_paragraphs_into_parts(text: str, default_book_title: str, words_per_part: int = 1500) -> List[Dict[str, Any]]:
    """Splits unsectioned books on paragraph/sentence boundaries rather than mid-sentence."""
    speech_text = clean_text_for_speech(text)
    paragraphs = [p.strip() for p in re.split(r"\n+", speech_text) if p.strip()]
    if not paragraphs:
        return []

    parts = []
    cur_paras: List[str] = []
    cur_words = 0

    for p in paragraphs:
        pw = len(p.split())
        if cur_paras and cur_words + pw > words_per_part:
            parts.append("\n\n".join(cur_paras))
            cur_paras = [p]
            cur_words = pw
        else:
            cur_paras.append(p)
            cur_words += pw

    if cur_paras:
        parts.append("\n\n".join(cur_paras))

    chapters = []
    for idx, content in enumerate(parts, start=1):
        chapters.append({
            "index": idx,
            "chapter_index": idx,
            "is_index": False,
            "title": f"{default_book_title} - Part {idx}",
            "content": content,
            "text": content,
            "word_count": len(content.split()),
        })
    return chapters


def split_into_chapters(
    text: str,
    default_book_title: str = "My Story",
    toc_entries: Optional[List[Tuple[int, str]]] = None,
    index_text: Optional[str] = None
) -> List[Dict[str, Any]]:
    """
    Segments document text into clean chapters using TOC/Index guidance when available,
    explicit Chapter/Adhyay headers, or numbered story boundaries. Keeps multi-page chapters
    together in a single chapter and prepends an Index item only when a real Index exists.
    """
    if not text or not text.strip():
        chapters: List[Dict[str, Any]] = []
        if index_text and index_text.strip():
            chapters.append({
                "index": 0,
                "chapter_index": 0,
                "is_index": True,
                "title": "Index",
                "content": index_text.strip(),
                "text": index_text.strip(),
                "word_count": len(index_text.split()),
                "clean_status": "ready",
            })
        return chapters

    healed_text = _heal_broken_indic_lines(text)
    healed_text = re.sub(r"([।.!?])\s*(\(\s*[०-९0-9]{1,3}\s*\))", r"\1\n\2", healed_text)
    lines = healed_text.splitlines()
    toc_map = dict(toc_entries or [])

    # Priority 1: Explicit Chapter / Act / Adhyay headings
    anchors = _find_explicit_chapters(lines)

    # Priority 2: Numbered section headings like "(1)\nTitle" or "1. Title"
    if len(anchors) < 2:
        numbered_anchors = _find_numbered_chapters(lines, toc_map)
        if len(numbered_anchors) >= 2:
            anchors = numbered_anchors

    if anchors and toc_entries:
        anchors = _fill_missing_toc_gaps(lines, anchors, toc_entries)

    chapters: List[Dict[str, Any]] = []
    if index_text and index_text.strip():
        chapters.append({
            "index": 0,
            "chapter_index": 0,
            "is_index": True,
            "title": "Index",
            "content": index_text.strip(),
            "text": index_text.strip(),
            "word_count": len(index_text.split()),
            "clean_status": "ready",
        })

    if len(anchors) >= 2:
        first_line = anchors[0]["start_line"]
        if first_line > 3:
            intro_raw = "\n".join(lines[:first_line]).strip()
            intro_clean = clean_text_for_speech(intro_raw)
            if len(intro_clean.split()) > 35:
                chapters.append({
                    "index": 1,
                    "chapter_index": 1,
                    "is_index": False,
                    "title": f"{default_book_title} - Introduction",
                    "content": intro_clean,
                    "text": intro_clean,
                    "word_count": len(intro_clean.split()),
                })

        has_intro = any(not c.get("is_index") for c in chapters)
        next_fallback_num = 2 if has_intro else 1

        for i, anchor in enumerate(anchors):
            body_start = anchor["body_line"]
            next_start = anchors[i + 1]["start_line"] if i + 1 < len(anchors) else len(lines)
            body_raw = "\n".join(lines[body_start:next_start]).strip()
            body_clean = clean_text_for_speech(body_raw)

            ch_num = anchor.get("num") if (anchor.get("num") and not has_intro) else next_fallback_num
            next_fallback_num += 1
            raw_title = anchor["title"]

            if not body_clean:
                body_clean = raw_title
            else:
                # Prepend story title cleanly if body does not already begin with it
                first_body_words = " ".join(body_clean.split()[:6])
                if _similarity(first_body_words, raw_title) < 0.72:
                    sep = "।\n\n" if _has_devanagari(raw_title) and not raw_title.endswith(("।", ".", "?", "!")) else "\n\n"
                    if not raw_title.endswith(("।", ".", "?", "!")) and not _has_devanagari(raw_title):
                        sep = ".\n\n"
                    body_clean = f"{raw_title}{sep}{body_clean}"

            display_title = _format_display_title(ch_num, raw_title)
            chapters.append({
                "index": ch_num,
                "chapter_index": ch_num,
                "is_index": False,
                "title": display_title,
                "content": body_clean,
                "text": body_clean,
                "word_count": len(body_clean.split()),
            })
        return chapters

    # Unsectioned long book: split cleanly on paragraph boundaries
    speech_full = clean_text_for_speech(healed_text)
    if len(speech_full.split()) > 2000:
        chapters.extend(_split_paragraphs_into_parts(healed_text, default_book_title))
        return chapters

    # Short single document
    first_meaningful = next((l.strip() for l in lines if l.strip()), default_book_title)
    if len(first_meaningful) > 60:
        first_meaningful = default_book_title

    chapters.append({
        "index": 1,
        "chapter_index": 1,
        "is_index": False,
        "title": _format_display_title(1, first_meaningful),
        "content": speech_full,
        "text": speech_full,
        "word_count": len(speech_full.split()),
    })
    return chapters
