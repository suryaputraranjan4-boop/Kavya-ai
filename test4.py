import urllib.request
import json
import os
import re

key = os.environ.get('GEMINI_API_KEY')
if not key:
    with open('/app/.env', 'r') as f:
        pass # Not applicable

url = f"https://generativelanguage.googleapis.com/v1alpha/models/gemini-3.6-flash:generateContent?key={key}"
print(url)
