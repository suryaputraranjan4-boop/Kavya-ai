import os
import json
import asyncio
import websockets
from dotenv import load_dotenv

load_dotenv()
api_key = os.getenv("GEMINI_API_KEY")

async def test():
    uri = f"wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key={api_key}"
    async with websockets.connect(uri) as ws:
        setup_msg = {
            "setup": {
                "model": "models/gemini-2.0-flash-exp",
                "generationConfig": {
                    "responseModalities": ["TEXT"]
                }
            }
        }
        await ws.send(json.dumps(setup_msg))
        print("Setup sent:", setup_msg)
        
        while True:
            resp = await ws.recv()
            print("Received:", resp[:200])
            if "setupComplete" in resp:
                break
        
        msg = {
            "clientContent": {
                "turns": [{"role": "user", "parts": [{"text": "Say hello!"}]}],
                "turnComplete": True
            }
        }
        await ws.send(json.dumps(msg))
        print("Message sent")
        
        while True:
            resp = await ws.recv()
            print("Received:", resp[:200])
            if "turnComplete" in resp and '"turnComplete": true' in resp:
                break

asyncio.run(test())
