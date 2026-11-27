import time
import uuid
import asyncio
import logging
from pathlib import Path
from typing import Dict, Any, Optional

from doc_parser import (
    extract_raw_pages_from_file,
    clean_text_for_speech,
    separate_front_matter_and_index,
    parse_toc_entries,
    format_toc_text,
)
from chapter_parser import split_into_chapters
from gemini_engine import (
    extract_text_from_image_with_gemini,
    clean_chapter_with_gemini,
    clean_index_with_gemini,
    is_gemini_configured,
)
from polish_job_manager import polish_job_manager

logger = logging.getLogger(__name__)

IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp", ".bmp", ".tiff"}
MIME_BY_EXT = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".webp": "image/webp",
    ".bmp": "image/bmp",
    ".tiff": "image/tiff",
}


class ParseJobManager:
    def __init__(self):
        self.jobs: Dict[str, Dict[str, Any]] = {}

    def _purge_old_jobs(self, max_age_seconds: int = 3600):
        """Removes stale or long-completed parse jobs safely without losing background uploads."""
        now = time.time()
        expired = []
        for jid, j in self.jobs.items():
            completed_at = j.get("completed_at")
            if completed_at and (now - completed_at) > 900:
                expired.append(jid)
            elif (now - j.get("created_at", now)) > max_age_seconds:
                expired.append(jid)
        for jid in expired:
            self.jobs.pop(jid, None)

    def create_job(
        self,
        file_bytes: Optional[bytes],
        filename: str,
        raw_text: Optional[str] = None,
        mode: str = "book"
    ) -> str:
        self._purge_old_jobs()
        job_id = uuid.uuid4().hex
        self.jobs[job_id] = {
            "job_id": job_id,
            "status": "processing",
            "progress": 5,
            "stage": "Initializing document processor...",
            "eta_seconds": 0,
            "error": None,
            "result": None,
            "created_at": time.time(),
            "started_at": time.time()
        }

        asyncio.create_task(self._run_job(job_id, file_bytes, filename, raw_text, mode))
        return job_id

    def get_job_status(self, job_id: str) -> Optional[Dict[str, Any]]:
        job = self.jobs.get(job_id)
        if not job:
            return None

        payload = {
            "job_id": job["job_id"],
            "status": job["status"],
            "progress": job["progress"],
            "stage": job["stage"],
            "eta_seconds": job["eta_seconds"],
            "error": job["error"],
            "result": job["result"]
        }

        if job["status"] in ("completed", "error"):
            job.setdefault("completed_at", time.time())

        return payload

    async def _run_job(
        self,
        job_id: str,
        file_bytes: Optional[bytes],
        filename: str,
        raw_text: Optional[str],
        mode: str
    ):
        job = self.jobs.get(job_id)
        if not job:
            return

        loop = asyncio.get_running_loop()

        try:
            book_title = Path(filename).stem if filename else "Document"
            ext = Path(filename).suffix.lower() if filename else ""

            def sync_extract_and_prepare():
                start_t = time.time()

                def on_ocr_progress(done: int, total: int):
                    if total <= 0:
                        return
                    pct = 10 + int((done / total) * 65)
                    elapsed = max(0.1, time.time() - start_t)
                    eta = 0
                    if done > 0 and done < total:
                        rate = elapsed / done
                        eta = int(round(rate * (total - done))) + 1
                    job["progress"] = min(75, pct)
                    job["stage"] = f"Reading page {done} of {total}..."
                    job["eta_seconds"] = eta

                # Fast path for Quick Read image via Gemini Vision if configured
                if mode == "quick" and file_bytes is not None and ext in IMAGE_EXTS and is_gemini_configured():
                    job["progress"] = 35
                    job["stage"] = "Reading image carefully..."
                    job["eta_seconds"] = 2
                    vision_text = extract_text_from_image_with_gemini(
                        file_bytes,
                        mime_type=MIME_BY_EXT.get(ext, "image/png")
                    )
                    if vision_text and vision_text.strip():
                        return {
                            "mode": "quick",
                            "book_title": book_title,
                            "chapters": [{
                                "index": 1,
                                "chapter_index": 1,
                                "title": book_title,
                                "content": vision_text.strip(),
                                "text": vision_text.strip(),
                                "word_count": len(vision_text.split()),
                                "clean_status": "ready"
                            }]
                        }

                if file_bytes is not None:
                    pages = extract_raw_pages_from_file(file_bytes, filename, progress_callback=on_ocr_progress)
                elif raw_text and raw_text.strip():
                    pages = [raw_text.strip()]
                else:
                    raise ValueError("Please upload a document, photo, or provide text.")

                if not pages:
                    raise ValueError("No readable text found in document or photo.")

                if mode == "quick":
                    job["progress"] = 90
                    job["stage"] = "Refining sentences for clear narration..."
                    job["eta_seconds"] = 1
                    full_quick_text = clean_text_for_speech("\n\n".join(pages).strip())
                    polished = clean_chapter_with_gemini(full_quick_text, book_title=book_title, chapter_title=book_title)
                    return {
                        "mode": "quick",
                        "book_title": book_title,
                        "chapters": [{
                            "index": 1,
                            "chapter_index": 1,
                            "is_index": False,
                            "title": book_title,
                            "content": polished,
                            "text": polished,
                            "word_count": len(polished.split()),
                            "clean_status": "ready"
                        }]
                    }

                job["progress"] = 80
                job["stage"] = "Extracting index & filtering non-content pages..."
                job["eta_seconds"] = 3

                index_text, toc_entries, content_pages = separate_front_matter_and_index(pages)
                full_text = "\n".join(content_pages).strip()
                if not full_text:
                    raise ValueError("No readable story content remained after filtering.")

                if index_text and is_gemini_configured():
                    job["progress"] = 83
                    job["stage"] = "Formatting book index..."
                    clean_idx = clean_index_with_gemini(index_text)
                    parsed_clean_toc = parse_toc_entries([clean_idx]) if clean_idx else []
                    if parsed_clean_toc:
                        toc_entries = parsed_clean_toc
                        index_text = format_toc_text(parsed_clean_toc)
                    elif clean_idx:
                        index_text = clean_idx

                job["progress"] = 86
                job["stage"] = "Detecting chapters & preparing Chapter 1..."
                job["eta_seconds"] = 2

                chapters = split_into_chapters(
                    full_text,
                    default_book_title=book_title,
                    toc_entries=toc_entries,
                    index_text=index_text
                )
                if not chapters:
                    raise ValueError("Could not segment chapters from document.")

                # Clean the first actual story chapter immediately; keep Index ready as formatted text
                first_story = next((ch for ch in chapters if not ch.get("is_index")), None)
                for ch in chapters:
                    if ch.get("is_index"):
                        ch["clean_status"] = "ready"
                    elif ch is first_story:
                        ch1_clean = clean_chapter_with_gemini(
                            ch["content"],
                            book_title=book_title,
                            chapter_title=ch["title"]
                        )
                        ch["content"] = ch1_clean
                        ch["text"] = ch1_clean
                        ch["word_count"] = len(ch1_clean.split())
                        ch["clean_status"] = "ready"
                    else:
                        ch["clean_status"] = "pending"

                return {
                    "mode": "book",
                    "book_title": book_title,
                    "chapters": chapters
                }

            prep = await loop.run_in_executor(None, sync_extract_and_prepare)

            if prep["mode"] == "quick":
                result = {
                    "success": True,
                    "mode": "quick",
                    "title": prep["book_title"],
                    "total_chapters": 1,
                    "chapters": prep["chapters"]
                }
            else:
                chapters = prep["chapters"]
                has_pending = any(ch.get("clean_status") == "pending" for ch in chapters)
                polish_job_id = ""
                if has_pending:
                    polish_job_id = polish_job_manager.create_polish_job(prep["book_title"], chapters)

                actual_chapter_count = sum(1 for ch in chapters if not ch.get("is_index"))
                result = {
                    "success": True,
                    "mode": "book",
                    "title": prep["book_title"],
                    "book_title": prep["book_title"],
                    "total_chapters": actual_chapter_count if actual_chapter_count > 0 else len(chapters),
                    "polish_job_id": polish_job_id,
                    "chapters": chapters
                }

            job["progress"] = 100
            job["stage"] = "Completed"
            job["eta_seconds"] = 0
            job["result"] = result
            job["status"] = "completed"
            job["completed_at"] = time.time()

        except Exception as e:
            logger.error(f"Parse job {job_id} failed: {e}")
            job["error"] = str(e)
            job["status"] = "error"
            job["completed_at"] = time.time()
            job["eta_seconds"] = 0


parse_job_manager = ParseJobManager()
