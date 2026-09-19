import json

setup_msg = {
    "setup": {
        "model": "models/gemini-2.5-flash",
        "generationConfig": {
            "responseModalities": ["AUDIO"]
        },
        "tools": [
            {
                "functionDeclarations": [
                    {
                        "name": "performAndroidAction",
                        "description": "Perform a UI action on the Android device. Supported actions: OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME, CLEAR_TEXT",
                        "parameters": {
                            "type": "OBJECT",
                            "properties": {
                                "action": {
                                    "type": "STRING",
                                    "description": "The type of action to perform (e.g., OPEN_APP, TAP, TYPE, SCROLL, BACK, HOME)"
                                },
                                "param": {
                                    "type": "STRING",
                                    "description": "The target or value for the action (e.g., 'YouTube', 'Search button', 'Hello world', 'UP')"
                                }
                            },
                            "required": ["action", "param"]
                        }
                    },
                    {
                        "name": "callApi",
                        "description": "Call an external API or Hugging Face model.",
                        "parameters": {
                            "type": "OBJECT",
                            "properties": {
                                "apiId": {
                                    "type": "STRING",
                                    "description": "The ID of the API (e.g., 'open-meteo-v1', 'frankfurter-currency-v1', 'hf-whisper', 'hf-vit')"
                                },
                                "params": {
                                    "type": "STRING",
                                    "description": "JSON string of parameters for the API"
                                }
                            },
                            "required": ["apiId", "params"]
                        }
                    }
                ]
            }
        ]
    }
}
print(json.dumps(setup_msg, indent=2))
