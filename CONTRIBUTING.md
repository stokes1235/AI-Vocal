# Contributing to AI-Vocal

Thank you for your interest in AI-Vocal! This project is an open-source Android app that reads visual-novel-style on-screen text aloud using on-device OCR and offline TTS.

## Before you start

- Read the [README](README.md) for project overview and model setup.
- Review [LICENSE](LICENSE) (MIT) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for bundled model licenses.
- Search [existing issues](https://github.com/stokes1235/AI-Vocal/issues) before opening a duplicate.

## Ways to contribute

You can help by:

- Reporting bugs with reproducible steps
- Improving OCR correction rules or voice-mapping UX
- Fixing Android compatibility issues (overlay, MediaProjection, foreground services)
- Adding tests for pure Kotlin logic (e.g. `VoiceMatcher`, OCR text rules)
- Improving documentation and setup instructions

Please avoid drive-by refactors unrelated to your issue or PR.

## Development setup

### Requirements

- Android Studio (latest stable recommended)
- JDK version matching `app/build.gradle.kts`
- An Android device or emulator for instrumented tests involving capture/overlay

### Clone and build

```bash
git clone https://github.com/stokes1235/AI-Vocal.git
cd AI-Vocal
