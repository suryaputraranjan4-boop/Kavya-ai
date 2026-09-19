import urllib.request
import json
import os

key = os.popen("grep GEMINI_API_KEY .env | cut -d '=' -f2").read().strip()
if not key:
    print("No API key")
    exit(1)

url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key={key}"
data = {
    "contents": [{"parts": [{"text": "say hello"}]}],
    "generationConfig": {
        "responseModalities": ["AUDIO"],
        "speechConfig": {
            "voiceConfig": {
                "prebuiltVoiceConfig": {
                    "voiceName": "Kore"
                }
            }
        }
    }
}

req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers={'Content-Type': 'application/json'})
try:
    with urllib.request.urlopen(req) as resp:
        print(resp.status)
        res_body = resp.read().decode('utf-8')
        print(res_body[:200])
except urllib.error.HTTPError as e:
    print(e.code)
    print(e.read().decode('utf-8'))
