# YtDesk for Android

A native Android companion to the YtDesk desktop app. Analyze supported YouTube URLs, inspect detected video resolutions, choose MP4 or MP3 output, track progress, cancel a download, and save finished files under `Downloads/YtDesk`.

Use the app only for content you have rights and permission to save. It does not bypass DRM, private videos, or access controls. Follow YouTube's Terms of Service and applicable law.

## Requirements

- Android 10 (API 29) or newer
- Android Studio with Android SDK Platform 35
- JDK 17 or newer
- Internet connection for analysis and media transfers

## Build and run

1. Open this repository's folder in Android Studio and let Gradle sync.
2. Connect an Android 10+ device or start an emulator.
3. Run the `app` configuration, or use `./gradlew assembleDebug` from this folder.
4. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

To make a distributable APK or Android App Bundle, use **Build → Generate Signed Bundle / APK** and sign with your own release key. Do not commit signing credentials. Android Studio can install the debug APK on a connected device.

## Features

- Validates standard YouTube watch, Shorts, live, embed, and `youtu.be` URLs
- Retrieves metadata and available video formats dynamically using yt-dlp
- Merges video and audio streams with FFmpeg; converts audio to MP3
- Shows transfer percentage and estimated time, with cancellation
- Writes output through Android's Downloads media collection without broad storage permissions
- Keeps a local recent-download list and setting for embedded metadata
- Supports Android's Share action to pass a YouTube link into YtDesk

Downloads currently run while the app is active. Android may stop an in-progress transfer if it terminates the app; a persistent background download service is not included yet.

## Third-party software and license

The Android build uses [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.1, including its FFmpeg module. That dependency is licensed under GPL-3.0; the license is included in [`LICENSE`](LICENSE). See [`NOTICE.md`](NOTICE.md) for attribution and additional dependencies. Review and comply with all applicable licenses before redistributing this app.
