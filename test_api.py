import urllib.request
import json
import urllib.error

API_KEY = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"

models = [
    "gemini-3.6-flash",
    "gemini-3.1-pro-preview",
    "gemini-1.5-flash",
    "gemini-1.5-pro",
    "gemini-2.5-flash",
    "gemini-2.0-flash-exp"
]

for model in models:
    url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={API_KEY}"
    req_data = {
        "contents": [
            {
                "role": "user",
                "parts": [{"text": "Hello"}]
            }
        ]
    }
    
    req = urllib.request.Request(url, data=json.dumps(req_data).encode('utf-8'), headers={'Content-Type': 'application/json'})
    
    try:
        response = urllib.request.urlopen(req)
        print(f"Model {model}: SUCCESS")
    except urllib.error.HTTPError as e:
        err_body = e.read().decode('utf-8')
        print(f"Model {model}: ERROR {e.code} - {err_body}")
    except Exception as e:
        print(f"Model {model}: EXCEPTION {e}")

