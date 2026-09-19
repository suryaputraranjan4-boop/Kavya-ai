KEY=$(grep GEMINI_API_KEY .env | cut -d '=' -f2)
curl -s -o resp.json -w "%{http_code}" -X POST -H "Content-Type: application/json" -d '{
  "contents": [
    {
      "role": "user",
      "parts": [{"text": "Hello world"}]
    }
  ],
  "generationConfig": {
    "responseModalities": ["AUDIO"],
    "speechConfig": {
      "voiceConfig": {
        "prebuiltVoiceConfig": {
          "voiceName": "Aoede"
        }
      }
    }
  }
}' "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$KEY"
cat resp.json
