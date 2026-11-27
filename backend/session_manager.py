import os
import time
import shutil
import uuid
import asyncio
import logging
from pathlib import Path
from typing import Dict, List, Optional, Any

from chunker import split_into_sentence_chunks
from translator import translate_chunk
from tone_analyzer import detect_document_tone, prepare_speech_script
from tts_engine import get_voice, convert_text_to_audio

logger = logging.getLogger(__name__)

AUDIO_ROOT = Path(__file__).parent / "generated_audio"
AUDIO_ROOT.mkdir(parents=True, exist_ok=True)

class SessionManager:
    def __init__(self):
        self.sessions: Dict[str, Dict[str, Any]] = {}
        self.active_tasks: Dict[str, asyncio.Task] = {}
        self.cleanup_task: Optional[asyncio.Task] = None

    def start_background_cleanup(self):
        """Starts periodic cleanup task if not already running."""
        if not self.cleanup_task or self.cleanup_task.done():
            self.cleanup_task = asyncio.create_task(self._periodic_purge_worker())

    async def _periodic_purge_worker(self):
        """Auto-purges temporary audio files older than 20 minutes to maintain zero server storage."""
        while True:
            try:
                await asyncio.sleep(300)  # Check every 5 minutes
                self.purge_expired_sessions(max_age_seconds=1200)  # 20 minutes
            except Exception as e:
                logger.error(f"Error during periodic purge: {e}")

    def purge_expired_sessions(self, max_age_seconds: int = 1200):
        now = time.time()
        for session_dir in AUDIO_ROOT.iterdir():
            if session_dir.is_dir():
                try:
                    if (now - session_dir.stat().st_mtime) > max_age_seconds:
                        shutil.rmtree(session_dir, ignore_errors=True)
                        session_id = session_dir.name
                        if session_id in self.sessions:
                            del self.sessions[session_id]
                        logger.info(f"Purged expired temporary session: {session_id}")
                except Exception as err:
                    logger.warning(f"Could not purge directory {session_dir}: {err}")

    async def create_session(
        self,
        text: str,
        filename: str,
        target_language: str = "hi",
        gender: str = "female",
        mode: str = "auto"
    ) -> Dict[str, Any]:
        self.start_background_cleanup()

        session_id = uuid.uuid4().hex
        session_dir = AUDIO_ROOT / session_id
        session_dir.mkdir(parents=True, exist_ok=True)

        # 1. Chunk document
        chunks_text = split_into_sentence_chunks(text, max_chunk_chars=500)
        total_chunks = len(chunks_text)
        if total_chunks == 0:
            chunks_text = [text]
            total_chunks = 1

        # 2. Detect tone
        detected_tone = detect_document_tone(text, user_override=mode)
        voice = get_voice(language=target_language, gender=gender)

        # 3. Initialize chunk data
        chunk_records = []
        for i, c_text in enumerate(chunks_text):
            chunk_records.append({
                "index": i,
                "status": "pending",
                "original_text": c_text,
                "translated_text": "",
                "audio_url": f"/api/audio/{session_id}/chunk_{i}.mp3",
                "audio_path": str(session_dir / f"chunk_{i}.mp3"),
                "error": None
            })

        self.sessions[session_id] = {
            "session_id": session_id,
            "filename": filename,
            "total_chunks": total_chunks,
            "target_language": target_language,
            "gender": gender,
            "voice": voice,
            "detected_tone": detected_tone,
            "chunks": chunk_records,
            "completed_chunks": 0
        }

        # 4. Process Chunk 0 IMMEDIATELY so the user can start listening within seconds
        await self._process_single_chunk(session_id, 0)

        # 5. Launch background processor for remaining chunks (1, 2, 3...)
        if total_chunks > 1:
            task = asyncio.create_task(self._process_remaining_chunks(session_id))
            self.active_tasks[session_id] = task

        return self.get_session_summary(session_id)

    async def _process_single_chunk(self, session_id: str, index: int):
        session = self.sessions.get(session_id)
        if not session or index >= len(session["chunks"]):
            return

        chunk = session["chunks"][index]
        chunk["status"] = "processing"

        try:
            # Step A: Translate if needed
            translated = translate_chunk(chunk["original_text"], session["target_language"])
            chunk["translated_text"] = translated

            # Step B: Prepare tone pacing
            script, params = prepare_speech_script(translated, session["detected_tone"])

            # Step D: Synthesize narration audio
            await convert_text_to_audio(
                text=script,
                output_path=chunk["audio_path"],
                voice=session["voice"],
                rate=params.get("rate", "+0%"),
                pitch=params.get("pitch", "+0Hz"),
                volume=params.get("volume", "+0%")
            )

            chunk["status"] = "ready"
            session["completed_chunks"] += 1
        except Exception as e:
            logger.error(f"Error processing chunk {index} in session {session_id}: {e}")
            chunk["status"] = "error"
            chunk["error"] = str(e)

    async def _process_remaining_chunks(self, session_id: str):
        session = self.sessions.get(session_id)
        if not session:
            return

        total = session["total_chunks"]
        for i in range(1, total):
            if session_id not in self.sessions:
                break
            await self._process_single_chunk(session_id, i)
            await asyncio.sleep(0.05)

    def get_session_summary(self, session_id: str) -> Optional[Dict[str, Any]]:
        session = self.sessions.get(session_id)
        if not session:
            return None

        tone_label = (
            "Nani Storytelling Mode (Warm & Expressive)"
            if session["detected_tone"] == "story"
            else "Official Notice Mode (Clear & Professional)"
        )

        return {
            "session_id": session_id,
            "filename": session["filename"],
            "total_chunks": session["total_chunks"],
            "completed_chunks": session["completed_chunks"],
            "target_language": session["target_language"],
            "gender": session["gender"],
            "detected_tone": session["detected_tone"],
            "tone_label": tone_label,
            "chunk_0": session["chunks"][0]
        }

    def get_chunk(self, session_id: str, index: int) -> Optional[Dict[str, Any]]:
        session = self.sessions.get(session_id)
        if not session or index < 0 or index >= len(session["chunks"]):
            return None
        return session["chunks"][index]

session_manager = SessionManager()
