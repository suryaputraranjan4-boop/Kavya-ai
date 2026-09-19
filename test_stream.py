import urllib.request
import json
import os

api_key = os.getenv("GEMINI_API_KEY", "")
# if I run this in the container, it doesn't have the api key, but I don't need to run it, I just need to write the android code.
