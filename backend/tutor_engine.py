import json
import logging
import re
from typing import Any, Dict, List

from gemini_engine import _post_gemini, is_gemini_configured

logger = logging.getLogger(__name__)


def _parse_json_response(raw_text: str) -> Dict[str, Any]:
    """Extracts JSON object safely from model output even if wrapped in markdown fences."""
    if not raw_text:
        return {}
    clean = raw_text.strip()
    clean = re.sub(r"^```(?:json)?\s*", "", clean)
    clean = re.sub(r"\s*```$", "", clean)
    try:
        data = json.loads(clean)
        if isinstance(data, dict):
            return data
    except Exception:
        match = re.search(r"\{[\s\S]*\}", clean)
        if match:
            try:
                data = json.loads(match.group(0))
                if isinstance(data, dict):
                    return data
            except Exception:
                pass
    return {}


def generate_tutor_turn(
    user_message: str,
    history: List[Dict[str, str]],
    topic: str = "Free Talk",
    level: str = "simple"
) -> Dict[str, Any]:
    """
    Generates a friendly, simple-English conversational reply along with gentle
    grammar/Hinglish correction for the learner.
    """
    msg = (user_message or "").strip()
    if not msg:
        return {
            "corrected": "",
            "tip": "",
            "better_word": "",
            "reply": "Hi! Tell me anything about your day. We can chat in simple English.",
            "spoken_text": "Hi! Tell me anything about your day. We can chat in simple English."
        }

    if not is_gemini_configured():
        return {
            "corrected": "",
            "tip": "Set GEMINI_API_KEY on the server for live tutor feedback.",
            "better_word": "",
            "reply": f"I heard you say: {msg}. Tell me more about it!",
            "spoken_text": "I heard you. Tell me more about it!"
        }

    recent_turns = history[-8:] if history else []
    history_lines = []
    for item in recent_turns:
        role = "Student" if item.get("role") == "user" else "Tutor"
        txt = (item.get("text") or "").strip()
        if txt:
            history_lines.append(f"{role}: {txt}")
    history_block = "\n".join(history_lines) if history_lines else "(Conversation just started)"

    is_simple = (level or "simple").lower() != "medium"
    vocab_rule = (
        "Use VERY SIMPLE everyday English words only (A1-A2 beginner level: short words like go, went, tired, happy, late, nice, fun, hard, easy, try). "
        "NEVER use difficult, literary, or formal words. Keep each sentence under 12 words."
        if is_simple else
        "Use clear, natural, everyday conversational Indian English (B1 level). Avoid heavy academic or rare words."
    )

    prompt = (
        "You are a warm, friendly Indian English speaking partner and tutor.\n"
        f"Current Topic: {topic or 'Free Talk'}\n"
        f"Language Rule: {vocab_rule}\n\n"
        "STUDENT INPUT HANDLING:\n"
        "1. The student may speak broken English, mixed Hindi and English (Hinglish, e.g., 'Aaj main late ho gaya'), or Devanagari Hindi.\n"
        "2. Understand what they mean naturally and respond like a kind friend.\n"
        "3. Grammar & Hinglish Correction:\n"
        "   - If the student used Hindi/Hinglish words OR made a grammar mistake, write the clean, simple natural English version in 'corrected', and write ONE very short, encouraging hint in 'tip' (in easy English or simple Hinglish, max 14 words).\n"
        "   - If the student's sentence was already natural and correct English, keep 'corrected' as \"\" and 'tip' as \"\". Do not invent nitpicks.\n"
        "4. Optionally fill 'better_word' with one easy word upgrade (e.g. 'very tired -> exhausted') only when genuinely useful, else \"\".\n"
        "5. Write your conversational response in 'reply' (1 to 2 short sentences reacting warmly + 1 easy follow-up question to keep the random chat going).\n"
        "6. Write 'spoken_text' as clean plain English ONLY (no emojis, quotes, or brackets) that sounds natural when read aloud:\n"
        "   - If 'corrected' is non-empty, start gently with: \"You can say: <corrected>. \" followed by <reply>.\n"
        "   - Otherwise set 'spoken_text' equal to <reply>.\n\n"
        f"RECENT CHAT:\n{history_block}\n\n"
        f"STUDENT JUST SAID:\n{msg}\n\n"
        "Return ONLY valid JSON with exact keys:\n"
        '{"corrected": "...", "tip": "...", "better_word": "...", "reply": "...", "spoken_text": "..."}'
    )

    raw_out = _post_gemini(
        [{"text": prompt}],
        temperature=0.35,
        timeout=30,
        response_mime_type="application/json"
    )
    if not raw_out:
        raw_out = _post_gemini([{"text": prompt}], temperature=0.35, timeout=30)

    parsed = _parse_json_response(raw_out or "")
    reply = (parsed.get("reply") or "").strip()
    corrected = (parsed.get("corrected") or "").strip()
    tip = (parsed.get("tip") or "").strip()
    better_word = (parsed.get("better_word") or "").strip()
    spoken_text = (parsed.get("spoken_text") or "").strip()

    if not reply:
        reply = "That sounds nice! Tell me a little more about it."
    if not spoken_text:
        spoken_text = f"You can say: {corrected}. {reply}" if corrected else reply

    # Strip stray formatting characters so TTS reads cleanly
    spoken_text = re.sub(r"[*_`#<>|]", " ", spoken_text)
    spoken_text = re.sub(r"\s+", " ", spoken_text).strip()

    return {
        "corrected": corrected,
        "tip": tip,
        "better_word": better_word,
        "reply": reply,
        "spoken_text": spoken_text
    }
