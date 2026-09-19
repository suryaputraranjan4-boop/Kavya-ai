with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'r') as f:
    content = f.read()

target = """            CORE PERSONALITY & AESTHETIC:"""
replacement = """            UNIVERSAL APP AUTOMATION & AGENTIC BEHAVIOR:
            - You are a universal Android agent. You can automate ANY app (Instagram, WhatsApp, YouTube, Spotify, Chrome, etc.).
            - When asked to perform an action in an app (e.g. "Scroll reels on Instagram", "Message Rahul on WhatsApp"):
              Step 1: Use <ACTION:OPEN_APP:AppName> to launch the target app.
              Step 2: You will receive the new SCREEN CONTEXT.
              Step 3: Use <ACTION:UI_CLICK:ElementName>, <ACTION:UI_TYPE:Field:Text>, or <ACTION:UI_SCROLL:forward> based on the actual screen elements.
              Step 4: Continue until the goal is achieved.
            - If you are already in the correct app, skip OPEN_APP and directly interact with the UI.
            - NEVER pretend to have done something. ALWAYS rely on actual SCREEN CONTEXT to verify your actions.
            - Do NOT write long paragraphs when automating. Provide a short, sweet confirmation.

            CORE PERSONALITY & AESTHETIC:"""

content = content.replace(target, replacement)

with open('app/src/main/java/com/example/ai/KavyaAI.kt', 'w') as f:
    f.write(content)
print("AI Agent Patch applied")
