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
- **Numbers as digits**: "twenty five" becomes 25, with handling for years, times, phone numbers
  and decimals (`Numbers.kt`, covered by unit tests).
- **Dictionary**: "heard -> meant" correction rules applied on every engine, plus an uncommon-words
  list used by the cloud engine and AI cleanup.
- **History** of recent dictations, kept on the phone.
- **VoiceInk import**: `tools/import_voiceink.py` copies VoiceInk's replacements and vocabulary from
  the Mac to the phone over USB, adding only entries that are new.

The API key is entered in the app and stored only in the app's private storage on the phone.

## Install without building

Download `talktype.apk` from the [latest release](https://github.com/sappermarine/talktype/releases/latest)
on the phone and open it (Android will ask you to allow installs from your browser). It needs a
64-bit Android phone running Android 9 or newer.

Because it was not installed from an app store, Android blocks the accessibility switch at first:
open Settings > Apps > TalkType, tap the three-dot menu and choose "Allow restricted settings".

The release build is signed with a debug key. To install a build you compiled yourself over it,
uninstall the release build first.

## Build and install

Needs JDK 17 and the Android SDK (platform 35). Point `JAVA_HOME` at the JDK and create
`local.properties` with `sdk.dir=<path to Android SDK>`.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-time setup

In the app: allow the microphone, turn on "TalkType dictation bubble" under
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
