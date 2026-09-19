import urllib.request
import json

key = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
models = ["gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts", "gemini-omni-1.1-flash", "gemini-omni-flash-preview"]

for m in models:
    url = f"https://generativelanguage.googleapis.com/v1alpha/models/{m}:generateContent?key={key}"
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
            res_body = resp.read().decode('utf-8')
            if 'inlineData' in res_body:
                print(f"{m} SUCCESS has inlineData")
            else:
                print(f"{m} SUCCESS NO inlineData")
    except urllib.error.HTTPError as e:
        print(f"{m} FAILED: {e.code}")
