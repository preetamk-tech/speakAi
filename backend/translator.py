import re
import logging
import requests
from typing import Optional

from gemini_engine import translate_with_gemini, is_gemini_configured

logger = logging.getLogger(__name__)

SUPPORTED_LANGUAGES = {
    "hi": "Hindi",
    "en": "English",
    "mr": "Marathi",
    "bn": "Bengali",
    "gu": "Gujarati",
    "ta": "Tamil",
    "te": "Telugu",
    "kn": "Kannada",
    "ml": "Malayalam",
    "ur": "Urdu"
}

TRANSLATE_URL = "https://translate.google.com/translate_a/single"
HEADERS = {
    "User-Agent": "AndroidTranslate/5.3.0.RC02.130475354-53000263 5.1 phone TRANSLATE_OPM5_TEST_1"
}

def _is_already_target_language(text: str, target: str) -> bool:
    sample = re.sub(r"[\s\d\W]+", "", text)
    if not sample:
        return True
    dev_count = len(re.findall(r"[\u0900-\u097F]", sample))
    latin_count = len(re.findall(r"[A-Za-z]", sample))
    if target == "en" and latin_count / len(sample) > 0.75:
        return True
    if target == "hi" and dev_count / len(sample) > 0.65:
        return True
    return False


def translate_chunk(text: str, target_lang: str = "hi") -> str:
    """
    Translates input text from any source language into the selected target language.
    Skips redundant calls when already in the target language, and uses Gemini
    for natural story-aware translation when configured.
    """
    if not text or not text.strip():
        return text

    target = target_lang.lower().strip()
    if target not in SUPPORTED_LANGUAGES:
        target = "hi"

    if _is_already_target_language(text, target):
        return text

    if is_gemini_configured():
        gem_out = translate_with_gemini(text, target)
        if gem_out:
            return gem_out

    params = {
        "client": "at",
        "sl": "auto",
        "tl": target,
        "dt": "t",
        "q": text
    }

    try:
        response = requests.get(TRANSLATE_URL, params=params, headers=HEADERS, timeout=8)
        if response.status_code == 200:
            data = response.json()
            # data[0] contains array of translated sentence fragments
            translated_fragments = []
            if data and isinstance(data, list) and len(data) > 0 and isinstance(data[0], list):
                for part in data[0]:
                    if part and len(part) > 0 and part[0]:
                        translated_fragments.append(part[0])
            
            translated_text = "".join(translated_fragments).strip()
            if translated_text:
                return translated_text
        else:
            logger.warning(f"Translation API returned status {response.status_code}. Using original text.")
    except Exception as e:
        logger.error(f"Error during translation to {target}: {e}")

    return text
