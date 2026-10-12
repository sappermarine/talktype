# TalkType

Voice-to-text for Android: a floating mic bubble that types what you say into whatever text field
you are in. A personal alternative to Wispr Flow / VoiceInk, built for a Samsung Galaxy S24 Ultra.

## Features

- **Floating bubble** over any app while the keyboard is open. Tap to talk, tap again to insert.
  Drag to move. Long-press to switch engine (blue dot = cloud); long-press while recording to discard.
- **On-device engine**: Parakeet or Whisper models via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx),
  downloaded inside the app. Audio never leaves the phone.
- **Cloud engine**: any OpenAI-compatible API. Defaults to Groq (`whisper-large-v3-turbo`).
- **AI cleanup** (optional): an LLM removes filler words and fixes punctuation. Defaults to
  `openai/gpt-oss-120b` on Groq.
- **Dictionary**: "heard -> meant" correction rules applied on every engine, plus an uncommon-words
  list used by the cloud engine and AI cleanup.
- **History** of recent dictations, kept on the phone.
- **VoiceInk import**: `tools/import_voiceink.py` copies VoiceInk's replacements and vocabulary from
  the Mac to the phone over USB, adding only entries that are new.

The API key is entered in the app and stored only in the app's private storage on the phone.

## Build and install

Needs JDK 17 and the Android SDK (platform 35). `gradle.properties` points at Homebrew's
`openjdk@17`; create `local.properties` with `sdk.dir=<path to Android SDK>`.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then in the app: allow the microphone, turn on "TalkType dictation bubble" under
Settings > Accessibility > Installed apps, and download a model (or add an API key and pick Cloud).
Reinstalling can switch the accessibility toggle off; turn it back on if the bubble disappears.

On Samsung phones also tap Allow next to "Stay awake in the background". Without it the system
freezes the app a few seconds after the bubble hides and the bubble stops appearing.

## Code map

| File | What it does |
|---|---|
| `DictationService.kt` | Accessibility service: bubble overlay, recording flow, text insertion |
| `BubbleView.kt` | Draws the bubble states |
| `Audio.kt` | Mic capture, WAV encoding, splitting long audio |
| `Transcribe.kt` | On-device recognizer, cloud client, cleanup, correction rules |
| `ModelStore.kt` | Model catalog and resumable downloads |
| `MainActivity.kt` | Settings screen (Compose) |
| `Prefs.kt`, `History.kt` | Settings and dictation history storage |

`app/libs/sherpa-onnx-1.13.8.aar` is the prebuilt library from the sherpa-onnx GitHub releases.
