const WebSocket = require('ws');
require('dotenv').config();

const apiKey = process.env.GEMINI_API_KEY;
const uri = `wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=${apiKey}`;

const ws = new WebSocket(uri);

ws.on('open', () => {
    console.log("Connected");
    const setup = {
        setup: {
            model: "models/gemini-2.0-flash-exp"
        }
    };
    ws.send(JSON.stringify(setup));
});

ws.on('message', (data) => {
    const str = data.toString();
    console.log("Received:", str.substring(0, 300));
    if (str.includes("setupComplete")) {
        const msg = {
            clientContent: {
                turns: [{ role: "user", parts: [{ text: "Say hello!" }] }],
                turnComplete: true
            }
        };
        ws.send(JSON.stringify(msg));
    }
    if (str.includes("turnComplete")) {
        setTimeout(() => ws.close(), 1000);
    }
});

ws.on('error', console.error);
ws.on('close', () => console.log("Closed"));
