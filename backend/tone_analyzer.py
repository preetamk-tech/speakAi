import re
from typing import Tuple, Dict

# Keywords indicating a Story / Fairy Tale / Fiction (Hinglish, Devanagari, English)
STORY_KEYWORDS = [
    # Devanagari Hindi
    "एक बार", "एक था", "एक थी", "कहानी", "कथा", "राजा", "रानी", "जंगल", "शेर", "भालू",
    "चिड़िया", "नानी", "दादी", "परी", "राक्षस", "राजकुमार", "राजकुमारी", "दोस्त", "मित्र",
    "गांव में", "एक दिन", "शरारत", "जादुई", "महल", "शिकारी", "बुढ़िया", "लोमड़ी", "बिल्ली",
    # Hinglish
    "ek baar", "ek tha", "ek thi", "kahaani", "kahani", "raja", "rani", "jungla", "jungle",
    "sher", "bhaloo", "chidiya", "nani", "dadi", "parpota", "pari", "rakshas", "khoj",
    "sundar", "nanha", "munna", "raajkumar", "rajkumar", "rajkumari", "dost", "dosti",
    "samay ki baat", "gaon me", "ek din",
    # English
    "once upon a time", "long ago", "story", "chapter", "tale", "fable", "fairytale",
    "king", "queen", "prince", "princess", "forest", "magic", "dragon", "adventures",
    "suddenly", "whispered", "screamed", "smiled warmly", "little girl", "little boy"
]

# Keywords indicating an Official / Formal / Academic Document (Devanagari, Hinglish, English)
OFFICIAL_KEYWORDS = [
    # Devanagari Hindi
    "सूचना", "अधिनियम", "आदेश", "आवेदन", "कार्यालय", "पत्र", "विषय", "महोदय", "दिनांक",
    "सरकार", "अधिकारी", "विवरण", "रिपोर्ट", "प्रस्ताव", "संख्या", "नियम", "निर्देश", "पत्राचार",
    # Hinglish
    "suchna", "adinium", "adesh", "aavedan", "karyalay", "patr", "vishay", "karyakarta",
    "pramanit", "dinank", "sarkar", "adhikari", "samasya", "vivaran", "prastav", "sankhya",
    # English
    "notice", "circular", "official", "memorandum", "memo", "subject:", "regarding",
    "dear sir", "dear madam", "to whom it may concern", "terms and conditions",
    "report", "analysis", "table of contents", "executive summary", "regulation",
    "policy", "compliance", "signed by", "authorized", "effective date", "resolution"
]

def detect_document_tone(text: str, user_override: str = "auto") -> str:
    """
    Returns 'story' or 'official'.
    user_override can be 'auto', 'story', or 'official'.
    """
    if user_override in ["story", "official"]:
        return user_override

    text_lower = text[:4000].lower()

    story_score = sum(1 for kw in STORY_KEYWORDS if kw in text_lower)
    official_score = sum(1 for kw in OFFICIAL_KEYWORDS if kw in text_lower)

    # Narrative/Dialog heuristic: presence of quote dialogues "..." or Hindi quotes
    dialogue_quotes = len(re.findall(r'["“][^"”]+["”]', text_lower))
    if dialogue_quotes >= 2:
        story_score += 2

    if story_score > official_score:
        return "story"
    elif official_score > story_score:
        return "official"
    else:
        # Default fallback
        return "story" if dialogue_quotes > 0 else "official"

def prepare_speech_script(text: str, tone: str) -> Tuple[str, Dict[str, str]]:
    """
    Adjusts text and generates rate/pitch parameters for Edge-TTS.
    
    Story Mode (Nani style):
    - Rate: -12% (Slow, patient, warm storytelling pacing)
    - Pitch: +0Hz (gentle warmth)
    - Punctuation spacing for natural breath/pause feel.
    
    Official Mode:
    - Rate: +2% (Clear, brisk, authoritative)
    - Pitch: +0Hz (Neutral)
    """
    if tone == "story":
        # Add slight natural pause indicators by spacing commas, dashes, periods and poornaviram (।)
        processed_text = re.sub(r'([.?!।])\s*', r'\1 ... ', text)
        processed_text = re.sub(r'([,;])\s*', r'\1 , ', processed_text)
        processed_text = re.sub(r'\s+', ' ', processed_text).strip()
        
        tts_params = {
            "rate": "-12%",
            "pitch": "+0Hz",
            "volume": "+0%"
        }
        return processed_text, tts_params
    else:
        # Official / Formal
        processed_text = re.sub(r'\s+', ' ', text).strip()
        tts_params = {
            "rate": "+2%",
            "pitch": "+0Hz",
            "volume": "+0%"
        }
        return processed_text, tts_params
