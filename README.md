# Kavya — Autonomous Android AI Assistant

Kavya is an intelligent, autonomous on-device and cloud-powered AI assistant for Android. It combines real-time vision perception, natural voice conversation, multi-provider reasoning (Gemini, OpenRouter, Hugging Face), and real Android Accessibility gesture automation to interact with installed applications and perform complex tasks autonomously.

---

## Architecture Overview

```
                        ┌──────────────────────────────────────────────────────────┐
                        │                     Kavya User Interface                  │
                        │            (Jetpack Compose · Material Design 3)          │
                        └──────────────────────────┬───────────────────────────────┘
                                                   │
                        ┌──────────────────────────▼───────────────────────────────┐
                        │                   Orchestrator & Agent                   │
                        │            (KavyaAgentViewModel · TaskPlanner)           │
                        └─────┬──────────────┬──────────────┬──────────────┬───────┘
                              │              │              │              │
      ┌───────────────────────▼┐  ┌──────────▼────────┐  ┌──▼──────────────▼────────┐
      │   Cloud AI Providers   │  │  Offline Engine   │  │   Perception & Hardware  │
      │ ────────────────────── │  │ ───────────────── │  │ ──────────────────────── │
      │ • Gemini 2.5 Flash/Pro │  │ • LiteRT-LM       │  │ • ScreenCaptureService   │
      │ • OpenRouter           │  │ • MediaPipe Task  │  │ • VisionPipeline         │
      │ • Hugging Face         │  │ • GGUF (LlamaCpp) │  │ • GeminiAudioPlayer      │
      └────────────────────────┘  └───────────────────┘  └──────────────────────────┘
                                             │
                        ┌────────────────────▼─────────────────────────────────────┐
                        │            Execution & Computer Use Engine               │
                        │        (KavyaAccessibilityService · 24 Real Tools)       │
                        └──────────────────────────────────────────────────────────┘
```

### Core Components

1. **Perception Layer (`com.example.services`, `com.example.visual`)**:
   - `ScreenCaptureService`: VirtualDisplay image reader capturing live screen frames.
   - `VisionPipeline`: Real-time screen frame processing with automated memory recycling.
   - `GeminiVisionAnalyzer`: Multimodal screen context inspection using Gemini 2.5.

2. **Reasoning & Planning Layer (`com.example.agent`, `com.example.ai`)**:
   - `KavyaAgentViewModel`: Main state machine driving multimodal conversations and autonomous task execution.
   - `TaskPlanner`: Multi-step reasoning decomposing high-level user goals into concrete actionable sub-tasks.
   - `KavyaToolRegistry`: 24 executable Android tools (open app, tap, scroll, swipe, text entry, web search, memory, etc.).

3. **Autonomous Touch & Computer Use (`com.example.services`)**:
   - `KavyaAccessibilityService`: Real Android Accessibility service providing node inspection and gesture dispatch.
   - `PrecisionComputerUseEngine`: Direct coordinate calculation and path dispatch via Android `AccessibilityService.dispatchGesture()`. Never uses simulated or fake taps.

4. **Voice & Audio Pipeline (`com.example.ai`)**:
   - `GeminiAudioPlayer`: Direct PCM/WAV speech playback with automatic temporary file cleanup and audio queue management.

---

## AI Providers & Model Registry

Kavya integrates multiple tiers of intelligence:

| Provider | Role | Models | Verification & Status |
| :--- | :--- | :--- | :--- |
| **Gemini** | Primary Brain & Vision | `gemini-2.5-flash`, `gemini-2.5-pro` | Cloud API via HTTPS · Verified live requests |
| **OpenRouter** | Secondary Partner | `openrouter/free`, `llama-3.3-70b`, `mistral-7b` | Cloud API with secure Bearer auth |
| **Hugging Face** | Specialized Resource | DistilBERT, RoBERTa Sentiment/SQuAD2, ViT | Inference API endpoints |
| **LiteRT-LM / MediaPipe** | Offline Reasoning | `.litertlm` (FlatBuffer `TFL3`), `.task` (ZIP) | On-device inference, 100% private |
| **Public APIs** | Real-time Utilities | Open-Meteo, Frankfurter FX, REST Countries | Free external REST services |

---

## Security & Secrets Management

- **API Keys**: All user-entered keys (Gemini, OpenRouter, Hugging Face) are stored in the device's hardware-backed **Android KeyStore** using AES-256-GCM encryption (`SecureStorage.kt`). Keys are masked in the UI and never written to cleartext logs.
- **Environment Configuration**: Pre-packaged placeholder strings (e.g. `MY_GEMINI_API_KEY`) are rejected by `AppPreferences.isPlaceholderKey()`.
- **Network Security**: Cleartext HTTP traffic is disabled globally. Local developer services (Docker Maps Scraper) are strictly scoped to `10.0.2.2` (Android emulator loopback) and `127.0.0.1`.

---

## Project Setup & Build

### Prerequisites
- **JDK**: Java Development Kit 17
- **Android SDK**: API Level 36 (`platforms;android-36`, `build-tools;36.0.0`)
- **Gradle**: 9.3+ (Kotlin DSL `.gradle.kts`)

### Local Development Setup
1. Copy the example environment configuration:
   ```bash
   cp .env.example .env
   ```
2. Open the project in Android Studio or build using Gradle:
   ```bash
   # Run source compilation check
   gradle :app:compileDebugKotlin

   # Run JVM and Robolectric unit tests
   gradle :app:testDebugUnitTest
   ```

---

## Testing & Quality Assurance

The project includes JVM unit tests and Robolectric instrumentation tests verifying critical user journeys without requiring a physical device or emulator.

To run all unit tests:
```bash
gradle :app:testDebugUnitTest
```

Key test suites:
- `ExampleRobolectricTest`: Activity launch, Compose hierarchy, and UI state verification.
- `GemmaModelFormatTest`: Structural verification of on-device model binaries (`TFL3` magic headers, ZIP tasks, file size validation).
- `TapPrecisionTest`: Accessibility gesture dispatch and screen coordinate accuracy.
- `ContextAwareRecoveryTest`: Error recovery across AI model fallbacks and tool executions.

---

## CI/CD Pipeline

The repository includes GitHub Actions workflows configured with strict security and testing gates:

- **`.github/workflows/android-tests.yml`**:
  - Automatically runs on all pushes and pull requests to `main` and feature branches.
  - Executes unit tests (`gradle :app:testDebugUnitTest`) and Kotlin source compilation (`gradle :app:compileDebugKotlin`).
  - **Does not build or export any APK files.**

- **`.github/workflows/build-apk.yml`**:
  - Configured strictly with `workflow_dispatch` (manual trigger only; never runs automatically on commit).
  - Enforces successful completion of all unit tests before any APK compilation step.

---

## Required Android Permissions

- `android.permission.RECORD_AUDIO`: Voice input capturing.
- `android.permission.FOREGROUND_SERVICE` & `FOREGROUND_SERVICE_MEDIA_PROJECTION`: Screen capture perception.
- `android.permission.SYSTEM_ALERT_WINDOW`: Floating assistant overlay UI.
- `android.permission.BIND_ACCESSIBILITY_SERVICE`: System-wide touch automation and screen reading.
- `android.permission.POST_NOTIFICATIONS`: System notifications.
