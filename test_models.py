import urllib.request
import json
key = "AQ.Ab8RN6KuNKiCaQU6gZZFs9HvIT3_G80GPrG-PIW7ocJtiF2zdg"
url = f"https://generativelanguage.googleapis.com/v1alpha/models?key={key}"
try:
    with urllib.request.urlopen(url) as resp:
        res_body = resp.read().decode('utf-8')
        data = json.loads(res_body)
        for m in data.get('models', []):
            if 'AUDIO' in m.get('supportedGenerationMethods', []) or 'generateContent' in m.get('supportedGenerationMethods', []):
                print(f"{m.get('name')} - {m.get('supportedGenerationMethods')}")
except Exception as e:
    print(e)
