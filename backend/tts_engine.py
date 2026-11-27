import asyncio
import edge_tts
from typing import Dict, Optional

VOICE_MAP = {
    "hi": {  # Hindi
        "female": "hi-IN-SwaraNeural",
        "male": "hi-IN-MadhurNeural",
        "label": "Hindi (हिन्दी)"
    },
    "en": {  # English (Indian Accent)
        "female": "en-IN-NeerjaExpressiveNeural",
        "male": "en-IN-PrabhatNeural",
        "label": "English (Indian)"
    },
    "mr": {  # Marathi
        "female": "mr-IN-AarohiNeural",
        "male": "mr-IN-ManoharNeural",
        "label": "Marathi (मराठी)"
    },
    "bn": {  # Bengali
        "female": "bn-IN-TanishaaNeural",
        "male": "bn-IN-BashkarNeural",
        "label": "Bengali (বাংলা)"
    },
    "gu": {  # Gujarati
        "female": "gu-IN-DhwaniNeural",
        "male": "gu-IN-NiranjanNeural",
        "label": "Gujarati (ગુજરાતી)"
    },
    "ta": {  # Tamil
        "female": "ta-IN-PallaviNeural",
        "male": "ta-IN-ValluvarNeural",
        "label": "Tamil (தமிழ்)"
    },
    "te": {  # Telugu
        "female": "te-IN-ShrutiNeural",
        "male": "te-IN-MohanNeural",
        "label": "Telugu (తెలుగు)"
    },
    "kn": {  # Kannada
        "female": "kn-IN-SapnaNeural",
        "male": "kn-IN-GaganNeural",
        "label": "Kannada (ಕನ್ನಡ)"
    },
    "ml": {  # Malayalam
        "female": "ml-IN-SobhanaNeural",
        "male": "ml-IN-MidhunNeural",
        "label": "Malayalam (മലയാളം)"
    },
    "ur": {  # Urdu
        "female": "ur-IN-GulNeural",
        "male": "ur-IN-SalmanNeural",
        "label": "Urdu (اردو)"
    }
}

def get_voice(language: str = "hi", gender: str = "female") -> str:
    lang = language.lower()
    gend = gender.lower()
    
    if lang not in VOICE_MAP:
        lang = "hi"
    if gend not in ["female", "male"]:
        gend = "female"
        
    return VOICE_MAP[lang][gend]

async def convert_text_to_audio(
    text: str,
    output_path: str,
    voice: str,
    rate: str = "+0%",
    pitch: str = "+0Hz",
    volume: str = "+0%"
) -> str:
    """
    Synthesizes speech using edge-tts asynchronously and writes to output_path.
    """
    communicate = edge_tts.Communicate(
        text=text,
        voice=voice,
        rate=rate,
        pitch=pitch,
        volume=volume
    )
    await communicate.save(output_path)
    return output_path
