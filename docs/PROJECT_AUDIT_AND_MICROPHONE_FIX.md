# Kavya AI — Project Audit and Microphone Fix

Audit baseline: `main` at `3b22f2772a6f54a398b301b98e234c3d3aa9986a`  
Change branch: `fix/microphone-state-and-audit`

## Scope and preservation rules

- Keep the existing Compose UI, navigation, colors, layouts, and visual assets unchanged.
- Do not remove existing feature modules or assets as part of this repair.
- Treat feature presence in source as different from a verified, end-to-end working feature.
- Device-only behavior (microphone capture, Android foreground-service restrictions, screen projection, camera hardware, and audio routing) still requires testing on a physical Android device.

## Repository snapshot

The project is an Android/Kotlin/Jetpack Compose app with a native C++/JNI component and a Gradle build. The tracked tree contains 217 files at the audit baseline. The default branch is `main`.

## Microphone findings

### Root causes found

1. The Home screen treated `MIC_STARTING` as an enabled/listening state, while the Chat screen only treated `MIC_ACTIVE` and `MIC_SLEEPING` as enabled. During startup, the same microphone button could therefore show and act on opposite states depending on the screen.
2. The microphone service returned `START_STICKY` but did not check the saved microphone preference before starting after Android recreated it. A service restart could therefore reopen capture even after the user had turned the microphone off.
3. Stop was always queued on the main-thread handler. A fast OFF/ON interaction could enqueue a stop behind a new start request and leave the state out of sync.
4. Repeated start requests could send additional start commands to an already-running service.

### Changes in this branch

- Added one shared `EngineState.isListening` definition; it includes STARTING, ACTIVE, and SLEEPING.
- Updated Home and Chat microphone controls to use the same state definition. No visual layout or styling changes were made.
- Made service start requests idempotent while still sending a start command after a rapid stop, so an instance that is tearing down cannot swallow the next ON request.
- Guarded old service teardown so a superseded instance cannot overwrite the new instance's shared microphone state.
- Made the service honor the saved OFF preference when started or recreated.
- Rechecked the saved preference immediately before opening a recognition session.
- Applied stop synchronously when already on the main thread and guarded instance cleanup.
- Added unit tests for the shared microphone-state mapping.

## Existing capabilities visible in the tracked source

| Area | Source evidence | Audit status |
|---|---|---|
| Android UI | Jetpack Compose screens, navigation, theme, voice orb | Present; UI intentionally untouched |
| Voice input / wake phrase | `KavyaMicrophoneEngine` using Android `SpeechRecognizer` | Present; real-device wake-word reliability not verified |
| Voice output | `KavyaVoiceEngine`, `GeminiAudioPlayer` | Present; provider/network playback requires device/API-key test |
| AI providers | Gemini, OpenRouter, provider/model registries, public API provider | Partially present; each provider needs integration tests and credentials |
| Offline LLM | Gemma offline provider, Gemma model manager, llama.cpp JNI/CMake | Present; model download, ABI, memory, and inference performance require device validation |
| Screen capture | MediaProjection service and screen-share manager | Capture pipeline present; continuous remote video transport not established by this source audit |
| Accessibility / touch actions | Accessibility service, TapEngine, ScrollEngine, visual touch controller | Present; permissions and gesture behavior need device testing |
| Chat history / memory | Room chat and memory DAOs, memory manager, OKF memory modules | Present; persistence/recovery needs test execution |
| Multi-step task execution | TaskPlanner, WorkflowEngine, TaskStateMachine, tests | Present; end-to-end execution needs device testing |
| PMX/MMD avatar runtime | No PMX/VMD model asset or dedicated PMX/MMD renderer found in tracked paths | Not evidenced in this repository snapshot |
| External MCP client/server integration | No dedicated MCP transport/client module found in tracked paths | Not evidenced; tool registry alone is not proof of MCP protocol support |
| Camera capture pipeline | Camera permission is declared, but no dedicated camera capture module was found in tracked paths | Not evidenced as an implemented flow |
| WebSocket streaming | No dedicated WebSocket transport module found in tracked paths | Not evidenced; OkHttp dependency alone is not proof of WebSocket streaming |

## Additional runtime glitch repairs

- Fixed the OpenRouter selection path so non-streaming chat requests use the selected OpenRouter provider instead of silently falling through to Gemini.
- Fixed OpenRouter streaming's nested quota acquisition: it previously called the quota-managed `chat()` path from inside another quota-managed request, causing the duplicate-request guard to reject the nested call.
- Wrapped the full streaming provider-routing path in quota cleanup so early returns from offline mode, OpenRouter, and missing-key fallback do not leak active-request slots.
- Replaced the short 32-bit prompt/history quota key with a SHA-256 key incorporating recent conversation, screen context, memory context, proactive mode, and provider selection to reduce false duplicate collisions.
- Reconciled active-request counts after stale request cleanup and corrected daily quota reset so it is independent of the minute window.
- Made suspending voice playback cancel its audio job/player when the 90-second safety timeout or caller cancellation occurs; generation checks prevent older in-flight TTS requests from starting playback after Stop or a newer turn.
- Added a playback generation guard so late MediaPlayer callbacks cannot restart audio after stop/interrupt; temporary audio files are deleted on completion, error, and stop.
- Released intermediate padded screen-capture bitmaps and temporary scaled bitmaps used by visual frame hashing/compression.
- Removed duplicate coroutine imports from the ViewModel.
- Pinned the Android NDK and installed the matching NDK/CMake packages in CI to make native builds reproducible.

## Build and validation

- Source-level changes are committed to this branch for review.
- Pull-request source validation uses `.github/workflows/android-tests.yml` and runs `gradle :app:testDebugUnitTest :app:lintDebug --stacktrace` only. It does not assemble or upload an APK.
- APK creation is isolated in `.github/workflows/build-apk.yml` and is manual-dispatch only. Do not dispatch it unless the user explicitly asks for an APK.
- The source-check workflow has been added for the current PR head; its final result must be checked before calling the branch CI-verified.
- Physical-device testing is still required for microphone, audio routing, foreground-service lifecycle, and MediaProjection behavior.
- Before release, run `gradle :app:testDebugUnitTest` and `gradle :app:assembleDebug`, then verify repeated ON/OFF taps, app relaunch, service recreation, permission denial/regrant, wake phrase, speech playback interruption, and screen navigation on a physical Android device.

## Next implementation priorities

1. Run the Gradle unit tests and debug APK build; fix any build failures before further integration.
2. Validate the microphone service on-device, including rapid taps and Android process/service recreation.
3. Implement or integrate the missing PMX/MMD runtime, external MCP protocol client, and camera capture flow only after selecting compatible libraries, model formats, and permission UX.
4. Decide and implement an actual screen-stream transport if remote continuous video is required.
5. Add provider-specific integration tests and offline-model device benchmarks.
