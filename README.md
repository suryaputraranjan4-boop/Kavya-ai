# 🤖 JARVIS Voice Assistant - Complete Tutorial Playlist (Parts 1 to 7)

[![License: Custom](https://img.shields.io/badge/License-Tech_GPTx_Attribution-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-purple.svg)](https://kotlinlang.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.9-brightgreen.svg)](https://gradle.org)
[![Releases](https://img.shields.io/badge/APK_Releases-Part_1_to_7-orange.svg)](https://github.com/techgptx/Playlist/releases)

Welcome to the official source code repository for the **JARVIS Android AI Assistant Series** by **Tech GPTx**!
Every part in this playlist is organized into its own self-contained, ready-to-run Android Studio project.

---

## 📜 LICENSE & MANDATORY VIDEO ATTRIBUTION (MUST READ)

> [!IMPORTANT]
> **100% Mandatory Shoutout Policy:**
> If you use this source code, fork this repository, or build any video, tutorial, Reel, Short, livestream, or course based on this project, **you MUST give 100% visible and verbal credit/shoutout to Tech GPTx**.
>
> 1. **In Video Spoken Audio:** Explicit verbal mention: *"Credit to Tech GPTx for the JARVIS AI Android source code / tutorial."*
> 2. **In Description (Top 3 lines):** *"Original Project by Tech GPTx: https://github.com/techgptx/Playlist"*
> 3. **On-Screen Display:** Visible watermark or on-screen text crediting **Tech GPTx**.
> 
> Read the full legal terms in the [LICENSE](LICENSE) file. Removing attribution or failing to provide a shoutout is a direct license violation.

---

## 📲 Direct APK Downloads (Parts 1 to 7)

Don't want to compile from source right now? Pre-built APKs for Parts 1 to 7 are available in the **[GitHub Releases](https://github.com/techgptx/Playlist/releases)** section:

| Part | Name | Features Highlight | APK Download |
|---|---|---|---|
| **Part 1** | JARVIS-Part-1 | Gemini Live Bidi WebSocket, PCM Streaming, 3D Canvas Orb | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 2** | JARVIS-Part-2 | Vosk Offline Hotword Manager, Foreground Services | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 3** | JARVIS-Part-3 | Offline Wake Word, Accessibility Engine, Memory Room DB | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 4** | JARVIS-Part-4 | Ultra Low-Latency Audio Pipeline, Natural Barge-In | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 5** | JARVIS-Part-5 | WhatsApp & YouTube System Automations, UI Polish | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 6** | JARVIS-Part-6 | Real-time Live Screen-Sharing Vision & Reasoning | [Download APK](https://github.com/techgptx/Playlist/releases) |
| **Part 7** | JARVIS-Part-7 | Agentic Workflows, 17 Floating Overlays, Sound FX | [Download APK](https://github.com/techgptx/Playlist/releases) |

---

## 📁 Repository Structure

```
Playlist/
├── Part 1/    # Foundation: Gemini Live Bidi WebSocket, PCM Audio Streaming & Canvas Orb
├── Part 2/    # Offline Vosk Hotword Manager & Standby Background Service
├── Part 3/    # Accessibility Engine, Automation Commands & Room Memory DB
├── Part 4/    # Low-Latency Audio, Natural Turn-Taking & System Integration
├── Part 5/    # Advanced Accessibility Controls, YouTube/WhatsApp Automations & UI Polish
├── Part 6/    # Screen-Sharing Vision, Real-Time Multimodal Reasoning & Bugfixes
└── Part 7/    # Agentic Workflows, Floating Overlays, Sound FX & Production Ready Engine
```

---

## 🚀 How to Run Any Part

1. **Open in Android Studio**:
   - Open Android Studio (Ladybug / Koala or newer recommended).
   - Click **Open** and select the folder of the part you want to test (e.g. `Playlist/Part 1` or `Playlist/Part 7`).
2. **Configure Local SDK**:
   - Android Studio will automatically create `local.properties` pointing to your local Android SDK.
   - If building from command-line, copy `local.properties.example` to `local.properties` and verify your SDK path.
3. **Set Gemini API Key**:
   - Launch the app on your Android device or emulator.
   - Open **Settings** within the app and paste your Google Gemini API key.
4. **Build & Run**:
   - Run `./gradlew assembleDebug` or click **Run (Shift + F10)** in Android Studio.

---

## 🧹 Cleaned & GitHub-Ready
- **Zero bloat**: Local build caches, compiler artifacts (`.gradle/`, `.kotlin/`, `build/`), and dead files have been thoroughly cleaned.
- **No file > 100 MB**: 100% compliant with GitHub push limits (no LFS errors).
- **Safe & Sanitized**: Developer-specific machine paths in `local.properties` removed in favor of clean templates.

Happy coding! Build your own JARVIS! 🎙️⚡  
*Follow & Subscribe to **Tech GPTx** for more AI & Android tutorials!*
