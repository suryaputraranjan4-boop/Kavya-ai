import urllib.request
import json
import base64

key = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
url = f"https://generativelanguage.googleapis.com/v1alpha/models/gemini-3.1-flash-tts-preview:generateContent?key={key}"
data = {
    "contents": [{"parts": [{"text": "Hello, how are you?"}]}],
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
        resp_json = json.loads(res_body)
        parts = resp_json.get('candidates', [{}])[0].get('content', {}).get('parts', [])
        for part in parts:
            if 'inlineData' in part:
                b64_data = part['inlineData']['data']
                mime_type = part['inlineData']['mimeType']
                with open('output_audio.raw', 'wb') as f:
                    f.write(base64.b64decode(b64_data))
                print(f"Saved audio. Mime type: {mime_type}")
                break
except Exception as e:
    print(e)
