import os
import uuid
from pathlib import Path
from fastapi import FastAPI, UploadFile, File, Form, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

import asyncio
from doc_parser import (
    extract_text_from_file,
    extract_raw_pages_from_file,
    clean_text_for_speech,
    separate_front_matter_and_index,
)
from parse_job_manager import parse_job_manager
from polish_job_manager import polish_job_manager
from gemini_engine import clean_chapter_with_gemini
from chapter_parser import split_into_chapters
from tone_analyzer import detect_document_tone
from translator import translate_chunk
from tts_engine import get_voice, convert_text_to_audio, VOICE_MAP
from session_manager import session_manager, AUDIO_ROOT

app = FastAPI(title="speakAi - Document & Screenshot Narrator")

# Enable CORS for web and mobile clients
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

@app.post("/api/parse/start")
async def start_parse_job(
    file: UploadFile = File(None),
    raw_text: str = Form(None),
    pages_json: str = Form(None),
    book_title: str = Form(None),
    mode: str = Form("book")
):
    """
    Starts an async document parsing job with real-time progress and ETA tracking.
    Accepts file upload, raw_text, or pre-extracted pages_json from on-device OCR.
    """
    filename = book_title or "Document.txt"
    file_bytes = None

    if file and file.filename:
        filename = file.filename
        file_bytes = await file.read()
    elif not ((raw_text and raw_text.strip()) or (pages_json and pages_json.strip())):
        raise HTTPException(status_code=400, detail="Please upload a document, photo, or provide text.")

    job_id = parse_job_manager.create_job(
        file_bytes=file_bytes,
        filename=filename,
        raw_text=raw_text,
        pages_json=pages_json,
        mode=mode
    )
    return {
        "success": True,
        "job_id": job_id
    }


@app.get("/api/parse/status/{job_id}")
def get_parse_status(job_id: str):
    """Returns real-time progress %, stage message, ETA seconds, and result once complete."""
    status = parse_job_manager.get_job_status(job_id)
    if not status:
        raise HTTPException(status_code=404, detail="Parse job not found or expired.")
    return status


@app.post("/api/parse/chapters")
async def parse_document_chapters(
    file: UploadFile = File(None),
    raw_text: str = Form(None),
    mode: str = Form("book")
):
    """
    Synchronous fallback endpoint that parses, cleans, and structures documents.
    """
    text = ""
    filename = "Document"
    index_text = None
    toc_entries = []

    if file and file.filename:
        filename = Path(file.filename).stem
        file_bytes = await file.read()
        try:
            pages = extract_raw_pages_from_file(file_bytes, file.filename)
            if mode == "book":
                index_text, toc_entries, pages = separate_front_matter_and_index(pages)
            text = "\n".join(pages)
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"File parsing error: {str(e)}")
    elif raw_text and raw_text.strip():
        text = raw_text.strip()
    else:
        raise HTTPException(status_code=400, detail="Please upload a document or provide text.")

    if mode == "quick":
        speech_text = clean_text_for_speech(text)
        return {
            "success": True,
            "title": filename,
            "book_title": filename,
            "total_chapters": 1,
            "chapters": [{
                "index": 1,
                "chapter_index": 1,
                "is_index": False,
                "title": filename,
                "content": speech_text,
                "text": speech_text,
                "word_count": len(speech_text.split())
            }]
        }

    chapters = split_into_chapters(
        text,
        default_book_title=filename,
        toc_entries=toc_entries,
        index_text=index_text
    )
    actual_chapters = sum(1 for ch in chapters if not ch.get("is_index"))

    return {
        "success": True,
        "title": filename,
        "book_title": filename,
        "total_chapters": actual_chapters if actual_chapters > 0 else len(chapters),
        "chapters": chapters
    }

@app.get("/api/polish/{polish_job_id}/status")
def get_polish_status(polish_job_id: str):
    """Returns status of background chapter cleaning and delivers newly ready chapters."""
    status = polish_job_manager.get_status(polish_job_id)
    if not status:
        raise HTTPException(status_code=404, detail="Polish job not found or already completed.")
    return status


@app.post("/api/polish/{polish_job_id}/prioritize/{chapter_index}")
def prioritize_polish_chapter(polish_job_id: str, chapter_index: int):
    """Bumps a middle/last chapter to the front of the cleaning queue when opened by the user."""
    res = polish_job_manager.prioritize_chapter(polish_job_id, chapter_index)
    if not res:
        raise HTTPException(status_code=404, detail="Polish job or chapter not found.")
    return res


@app.post("/api/polish/chapter")
async def polish_single_chapter_fallback(
    raw_text: str = Form(...),
    book_title: str = Form("Book"),
    chapter_title: str = Form("Chapter")
):
    """Fallback endpoint to clean a single unpolished chapter if its background job expired."""
    if not raw_text.strip():
        raise HTTPException(status_code=400, detail="Chapter text is required.")
    loop = asyncio.get_running_loop()
    cleaned = await loop.run_in_executor(
        None,
        clean_chapter_with_gemini,
        raw_text,
        book_title,
        chapter_title
    )
    return {
        "success": True,
        "content": cleaned
    }


@app.post("/api/chapter/synthesize_full")
async def synthesize_full_chapter(
    raw_text: str = Form(...),
    language: str = Form("hi"),
    gender: str = Form("female"),
    mode: str = Form("auto")
):
    """
    Synthesizes full chapter audio for offline download on client device.
    Temporary file is auto-purged within 20 minutes.
    """
    clean_text = clean_text_for_speech(raw_text.strip())
    if not clean_text:
        raise HTTPException(status_code=400, detail="Text is required.")

    loop = asyncio.get_running_loop()
    spoken_text = await loop.run_in_executor(None, translate_chunk, clean_text, language)

    filename = f"full_{uuid.uuid4().hex[:10]}.mp3"
    out_path = AUDIO_ROOT / filename
    voice = get_voice(language=language, gender=gender)
    rate = "-12%" if mode == "story" or detect_document_tone(spoken_text) == "story" else "+0%"

    await convert_text_to_audio(
        text=spoken_text,
        output_path=str(out_path),
        voice=voice,
        rate=rate
    )

    return {
        "success": True,
        "audio_url": f"/api/audio_direct/{filename}"
    }

@app.get("/api/audio_direct/{filename}")
def get_direct_audio(filename: str):
    path = AUDIO_ROOT / filename
    if not path.exists():
        raise HTTPException(status_code=404, detail="Audio file not found")
    return FileResponse(path, media_type="audio/mpeg", filename=filename)

@app.get("/api/voices")
def list_voices():
    """Returns available language options and supported voices."""
    return {"languages": VOICE_MAP}

@app.post("/api/session/start")
async def start_session(
    file: UploadFile = File(None),
    raw_text: str = Form(None),
    gender: str = Form("female"),
    language: str = Form("hi"),
    mode: str = Form("auto")
):
    """
    Initializes a new document or screenshot narration session.
    Extracts text from PDFs, Word docs, TXT, or Image screenshots via OCR.
    """
    text = ""
    filename = "Pasted_Text.txt"

    if file and file.filename:
        filename = file.filename
        file_bytes = await file.read()
        try:
            text = extract_text_from_file(file_bytes, filename)
        except Exception as e:
            raise HTTPException(status_code=400, detail=f"File parsing error: {str(e)}")
    elif raw_text and raw_text.strip():
        text = clean_text_for_speech(raw_text.strip())
    else:
        raise HTTPException(status_code=400, detail="Please upload a PDF, Word doc, Image screenshot, or enter text.")

    if not text.strip():
        raise HTTPException(status_code=400, detail="The file contains no readable text or image OCR could not detect characters.")

    summary = await session_manager.create_session(
        text=text,
        filename=filename,
        target_language=language,
        gender=gender,
        mode=mode
    )

    return summary

@app.get("/api/session/{session_id}/status")
def get_session_status(session_id: str):
    summary = session_manager.get_session_summary(session_id)
    if not summary:
        raise HTTPException(status_code=404, detail="Session not found")
    return summary

@app.get("/api/session/{session_id}/chunk/{index}")
def get_chunk(session_id: str, index: int):
    chunk = session_manager.get_chunk(session_id, index)
    if not chunk:
        raise HTTPException(status_code=404, detail="Chunk not found")
    return chunk

@app.get("/api/audio/{session_id}/{filename}")
def get_session_audio(session_id: str, filename: str):
    path = AUDIO_ROOT / session_id / filename
    if not path.exists():
        raise HTTPException(status_code=404, detail="Audio file not ready or not found")
    return FileResponse(path, media_type="audio/mpeg", filename=filename)

@app.get("/")
def health():
    return {"status": "ok", "service": "speakAi API"}
