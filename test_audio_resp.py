import urllib.request
import json

key = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
url = f"https://generativelanguage.googleapis.com/v1alpha/models/gemini-3.6-flash:generateContent?key={key}"
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
        print(res_body[:1000])
except Exception as e:
    print(e)
