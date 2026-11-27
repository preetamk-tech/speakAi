#!/bin/bash
# speakAi Server Start Script
cd "$(dirname "$0")/backend"
echo "Starting speakAi Server on http://localhost:8000..."
./venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000 --reload
