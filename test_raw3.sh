#!/bin/bash
API_KEY="AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
curl -s -H 'Content-Type: application/json' \
-d '{"contents": [{"role":"user", "parts": [{"text": "Say hello!"}]}], "generationConfig": {"responseModalities": ["AUDIO"], "speechConfig": {"voiceConfig": {"prebuiltVoiceConfig": {"voiceName": "Kore"}}}}}' \
"https://generativelanguage.googleapis.com/v1beta/models/gemini-3.1-flash-tts-preview:generateContent?key=$API_KEY" > raw_response3.json
cat raw_response3.json | grep -o "mimeType"
