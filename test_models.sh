#!/bin/bash
API_KEY=$(grep GEMINI_API_KEY app/local.properties | cut -d'=' -f2 || echo "")
if [ -z "$API_KEY" ]; then
    API_KEY=$(grep GEMINI_API_KEY app/.env | cut -d'=' -f2 || echo "")
fi

curl "https://generativelanguage.googleapis.com/v1beta/models?key=${API_KEY}" > models.json
grep "name" models.json | grep gemini
