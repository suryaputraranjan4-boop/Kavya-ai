import urllib.request
import json
import urllib.error

API_KEY = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
model = "gemini-3.6-flash"

url = f"https://generativelanguage.googleapis.com/v1alpha/models/{model}:generateContent?key={API_KEY}"
req_data = {
    "contents": [{"role": "user", "parts": [{"text": "Hello"}]}],
    "generationConfig": {
        "responseModalities": ["AUDIO"],
        "speechConfig": {
            "voiceConfig": {
                "prebuiltVoiceConfig": {"voiceName": "Aoede"}
            }
        }
    }
}
req = urllib.request.Request(url, data=json.dumps(req_data).encode('utf-8'), headers={'Content-Type': 'application/json'})
try:
    response = urllib.request.urlopen(req)
    print(f"Model {model} Audio: SUCCESS")
except urllib.error.HTTPError as e:
    err_body = e.read().decode('utf-8')
    print(f"Model {model} Audio: ERROR {e.code} - {err_body}")
