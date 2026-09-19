import urllib.request
import json

key = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"

url = f"https://generativelanguage.googleapis.com/v1alpha/models/gemini-3.1-pro-preview:generateContent?key={key}"
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
        print("Success! Got response.")
except urllib.error.HTTPError as e:
    print(e.code)
    print(e.read().decode('utf-8'))
