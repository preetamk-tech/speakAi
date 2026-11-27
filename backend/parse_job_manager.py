import time
import uuid
import asyncio
import logging
from pathlib import Path
from typing import Dict, Any, Optional

from doc_parser import extract_pages_from_file, clean_text_for_speech, filter_book_pages
from chapter_parser import split_into_chapters
from gemini_engine import extract_text_from_image_with_gemini, clean_chapter_with_gemini, is_gemini_configured
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

    def _purge_old_jobs(self, max_age_seconds: int = 900):
        """Removes completed/stale parse jobs after 15 minutes to keep zero server footprint."""
        now = time.time()
        expired = [jid for jid, j in self.jobs.items() if (now - j.get("created_at", now)) > max_age_seconds]
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
            self.jobs.pop(job_id, None)

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
                    pages = extract_pages_from_file(file_bytes, filename, progress_callback=on_ocr_progress)
                elif raw_text and raw_text.strip():
                    pages = [clean_text_for_speech(raw_text.strip())]
                else:
                    raise ValueError("Please upload a document, photo, or provide text.")

                if not pages:
                    raise ValueError("No readable text found in document or photo.")

                job["progress"] = 80
                job["stage"] = "Filtering non-content pages & organizing..."
                job["eta_seconds"] = 3

                if mode == "book" and len(pages) > 2:
                    cleaned_pages = filter_book_pages(pages)
                else:
                    cleaned_pages = pages

                full_text = "\n\n".join(cleaned_pages).strip()
                if not full_text:
                    raise ValueError("No readable story content remained after filtering.")

                if mode == "quick":
                    job["progress"] = 90
                    job["stage"] = "Refining sentences for clear narration..."
                    job["eta_seconds"] = 1
                    polished = clean_chapter_with_gemini(full_text, book_title=book_title, chapter_title=book_title)
                    return {
                        "mode": "quick",
                        "book_title": book_title,
                        "chapters": [{
                            "index": 1,
                            "chapter_index": 1,
                            "title": book_title,
                            "content": polished,
                            "text": polished,
                            "word_count": len(polished.split()),
                            "clean_status": "ready"
                        }]
                    }

                job["progress"] = 85
                job["stage"] = "Detecting chapters & preparing Chapter 1..."
                job["eta_seconds"] = 2

                chapters = split_into_chapters(full_text, default_book_title=book_title)
                if not chapters:
                    raise ValueError("Could not segment chapters from document.")

                # Clean Chapter 1 immediately so playback is instant with clean sentences
                ch1 = chapters[0]
                ch1_clean = clean_chapter_with_gemini(
                    ch1["content"],
                    book_title=book_title,
                    chapter_title=ch1["title"]
                )
                ch1["content"] = ch1_clean
                ch1["text"] = ch1_clean
                ch1["word_count"] = len(ch1_clean.split())
                ch1["clean_status"] = "ready"

                # Mark remaining chapters as pending for immediate background refinement
                for ch in chapters[1:]:
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
                polish_job_id = ""
                if len(chapters) > 1:
                    polish_job_id = polish_job_manager.create_polish_job(prep["book_title"], chapters)

                result = {
                    "success": True,
                    "mode": "book",
                    "title": prep["book_title"],
                    "book_title": prep["book_title"],
                    "total_chapters": len(chapters),
                    "polish_job_id": polish_job_id,
                    "chapters": chapters
                }

            job["progress"] = 100
            job["stage"] = "Completed"
            job["eta_seconds"] = 0
            job["result"] = result
            job["status"] = "completed"

        except Exception as e:
            logger.error(f"Parse job {job_id} failed: {e}")
            job["error"] = str(e)
            job["status"] = "error"
            job["eta_seconds"] = 0


parse_job_manager = ParseJobManager()
