import re
from typing import List

def split_into_sentence_chunks(text: str, max_chunk_chars: int = 500) -> List[str]:
    """
    Splits document text into coherent chunks suitable for continuous playback.
    Respects paragraph boundaries and sentence boundaries (. ! ? ।).
    """
    raw_paragraphs = [p.strip() for p in text.split("\n") if p.strip()]
    if not raw_paragraphs:
        return []

    chunks = []
    current_chunk = []
    current_length = 0

    sentence_split_regex = re.compile(r'(?<=[.?!।])\s+')

    for paragraph in raw_paragraphs:
        sentences = sentence_split_regex.split(paragraph)
        for sentence in sentences:
            sentence = sentence.strip()
            if not sentence:
                continue

            # If adding this sentence exceeds chunk size limit, flush current chunk
            if current_length + len(sentence) > max_chunk_chars and current_chunk:
                chunks.append(" ".join(current_chunk))
                current_chunk = [sentence]
                current_length = len(sentence)
            else:
                current_chunk.append(sentence)
                current_length += len(sentence)

        # After finishing a paragraph, if we have accumulated a reasonable chunk size (>=250 chars),
        # flush it so paragraphs stay naturally segmented
        if current_length >= 250 and current_chunk:
            chunks.append(" ".join(current_chunk))
            current_chunk = []
            current_length = 0

    if current_chunk:
        chunks.append(" ".join(current_chunk))

    return chunks
