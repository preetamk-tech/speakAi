import time
import uuid
import asyncio
import logging
from typing import Dict, Any, List, Optional

from gemini_engine import clean_chapter_with_gemini

logger = logging.getLogger(__name__)


class PolishJobManager:
    def __init__(self):
        self.jobs: Dict[str, Dict[str, Any]] = {}

    def _purge_expired(self, max_age_seconds: int = 1200):
        """Removes stale background polish jobs after 20 minutes."""
        now = time.time()
        expired = [jid for jid, j in self.jobs.items() if (now - j.get("created_at", now)) > max_age_seconds]
        for jid in expired:
            self.jobs.pop(jid, None)

    def create_polish_job(self, book_title: str, chapters: List[Dict[str, Any]]) -> str:
        """
        Creates a background chapter-cleaning queue for Chapters 2..N
        (Chapter 1 is already cleaned before upload returns).
        """
        self._purge_expired()
        job_id = uuid.uuid4().hex

        chapter_states = {}
        queue = []
        for ch in chapters:
            idx = ch["index"]
            status = ch.get("clean_status", "pending")
            chapter_states[idx] = {
                "chapter_index": idx,
                "title": ch["title"],
                "raw_content": ch["content"] if status != "ready" else "",
                "cleaned_content": "",
                "status": status,
                "delivered": (status == "ready")
            }
            if status != "ready":
                queue.append(idx)

        self.jobs[job_id] = {
            "polish_job_id": job_id,
            "book_title": book_title,
            "chapters": chapter_states,
            "queue": queue,
            "total_chapters": len(chapters),
            "completed_count": sum(1 for c in chapter_states.values() if c["status"] == "ready"),
            "active_chapter": None,
            "created_at": time.time(),
            "done": len(queue) == 0
        }

        if queue:
            asyncio.create_task(self._worker_loop(job_id))

        return job_id

    def prioritize_chapter(self, job_id: str, chapter_index: int) -> Optional[Dict[str, Any]]:
        """
        Moves the requested chapter to the front of the cleaning queue when a user
        opens a middle or last chapter directly while background work is still running.
        """
        job = self.jobs.get(job_id)
        if not job:
            return None

        ch = job["chapters"].get(chapter_index)
        if not ch:
            return None

        if ch["status"] == "pending":
            if chapter_index in job["queue"]:
                job["queue"].remove(chapter_index)
            job["queue"].insert(0, chapter_index)
            logger.info(f"Prioritized Chapter {chapter_index} in polish job {job_id}")

        return {
            "chapter_index": chapter_index,
            "status": ch["status"],
            "active_chapter": job.get("active_chapter")
        }

    def get_status(self, job_id: str) -> Optional[Dict[str, Any]]:
        """
        Returns current background progress and delivers newly cleaned chapters
        so the Android app can persist them to local SQLite and free server memory.
        """
        job = self.jobs.get(job_id)
        if not job:
            return None

        newly_ready = []
        statuses = {}

        for idx, ch in job["chapters"].items():
            statuses[str(idx)] = ch["status"]
            if ch["status"] == "ready" and not ch["delivered"] and ch["cleaned_content"]:
                newly_ready.append({
                    "chapter_index": idx,
                    "title": ch["title"],
                    "content": ch["cleaned_content"]
                })
                ch["delivered"] = True
                # Free cleaned text from server RAM right after delivery
                ch["cleaned_content"] = ""
                ch["raw_content"] = ""

        all_delivered = all(ch["delivered"] for ch in job["chapters"].values())
        payload = {
            "polish_job_id": job_id,
            "done": job["done"],
            "total_chapters": job["total_chapters"],
            "completed_count": job["completed_count"],
            "active_chapter": job.get("active_chapter"),
            "chapter_statuses": statuses,
            "newly_ready": newly_ready
        }

        if job["done"] and all_delivered:
            self.jobs.pop(job_id, None)

        return payload

    async def _worker_loop(self, job_id: str):
        loop = asyncio.get_running_loop()

        while True:
            job = self.jobs.get(job_id)
            if not job or not job["queue"]:
                if job:
                    job["active_chapter"] = None
                    job["done"] = True
                break

            idx = job["queue"].pop(0)
            ch = job["chapters"].get(idx)
            if not ch or ch["status"] == "ready":
                continue

            ch["status"] = "processing"
            job["active_chapter"] = idx

            try:
                raw_text = ch["raw_content"]
                title = ch["title"]
                book_title = job["book_title"]

                cleaned = await loop.run_in_executor(
                    None,
                    clean_chapter_with_gemini,
                    raw_text,
                    book_title,
                    title
                )

                ch["cleaned_content"] = cleaned
                ch["raw_content"] = ""
                ch["status"] = "ready"
                job["completed_count"] += 1
            except Exception as e:
                logger.error(f"Polish error on Ch {idx} in {job_id}: {e}")
                # Fallback gracefully to raw normalized text so chapter never stalls
                ch["cleaned_content"] = ch["raw_content"]
                ch["raw_content"] = ""
                ch["status"] = "ready"
                job["completed_count"] += 1

            await asyncio.sleep(0.05)


polish_job_manager = PolishJobManager()
