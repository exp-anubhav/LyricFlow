# LyricFlow 🎵

**System-Wide Synchronized Lyrics for Android**

LyricFlow is an Android application designed to detect currently playing music and display synchronized lyrics in a floating overlay. Follow along with your favorite songs without constantly switching between apps.

The project combines Android media-session monitoring, lyrics retrieval, timestamp-based synchronization, and a floating overlay interface to create a convenient lyrics experience.

[**⬇️ Download the Latest Release**](https://github.com/exp-anubhav/LyricFlow/releases/latest)

---

## ✨ Features

- **Automatic Music Detection** - Detects track information exposed through Android's media session and playback notifications.
- **Synchronized Lyrics** - Displays timestamped lyrics and highlights lines according to playback position when synchronized lyrics are available.
- **Floating Lyrics Overlay** - View lyrics over other applications while listening to music.
- **Background Monitoring** - Uses Android's notification-listener and media-session mechanisms to monitor supported playback sources.
- **Lyrics Provider Integration** - Retrieves lyrics through supported providers, including LRCLIB and the YouTube Music lyrics provider.
- **Overlay Controls** - Start or dismiss the lyrics overlay to manage your viewing experience.
- **Android-Native Interface** - Built with Kotlin and Jetpack Compose.

*Lyrics availability, language, accuracy, and synchronization depend on the track, metadata, provider, and playback source.*

## 📱 Compatibility

- Android 8.0 (Oreo) or later, subject to device compatibility.
- An internet connection may be required to retrieve lyrics.
- A supported music player that exposes useful playback metadata.
- Notification access and display-over-other-apps permission for the relevant features.

## 🚀 Installation

### Option 1: Download the APK

1. Open the [**Latest Releases**](https://github.com/exp-anubhav/LyricFlow/releases/latest) page.
2. Download the `app-release.apk` file from the release's **Assets** section.
3. Open the downloaded APK on your Android device.
4. If prompted, allow installation from the browser or file manager you used to download it.
5. Complete the installation and open LyricFlow.

> **Security note:** Install APKs only from sources you trust. Android may display a warning when installing an application outside the Play Store.

### Option 2: Build from Source

**Prerequisites**

- Android Studio
- A compatible JDK
- Android SDK and Android Gradle Plugin versions compatible with the project

**Steps**

1. Clone the repository:

   ```bash
   git clone https://github.com/exp-anubhav/LyricFlow.git
   ```

2. Open the cloned project in Android Studio.
3. Allow Gradle synchronization to complete.
4. Connect an Android device or start an emulator.
5. Run the `app` configuration.

For a release build, use Android Studio's **Build → Generate Signed Bundle / APK** workflow and sign the APK with your own keystore.

## ⚙️ Getting Started

After installing LyricFlow:

1. Open the app.
2. Enable **Notification Access** for LyricFlow when prompted.
3. Grant **Display over other apps** permission.
4. Start the lyrics overlay.
5. Open a supported music player and play a song.
6. If lyrics are available, LyricFlow can display them in the floating overlay.

Permission names and settings screens may vary across Android versions and device manufacturers.

## 🛠️ Technology Stack

| Technology | Purpose |
|---|---|
| Kotlin | Android application development |
| Jetpack Compose | Native UI |
| Android MediaSession APIs | Playback state and track metadata |
| NotificationListenerService | Media notification monitoring |
| Android overlay APIs | Floating lyrics interface |
| LRCLIB | Lyrics retrieval |
| YouTube Music lyrics provider | Additional lyrics retrieval |

## 🔍 Troubleshooting

**Lyrics not found**

- Check your internet connection.
- Verify that the song title and artist metadata are correct.
- Try another song or playback source.
- Some songs may not have available synchronized lyrics.

**Overlay does not appear**

- Check the display-over-other-apps permission.
- Open LyricFlow and start the overlay again.
- Check whether your device restricts background activity.

**Music is not detected**

- Ensure notification access is enabled.
- Play music in an application that exposes Android media controls.
- Check whether the playback notification contains track information.

**Lyrics are out of sync**

- Try another lyrics source or track version when available.
- Different recordings, remixes, and live versions may have different timestamps.

## 🗺️ Project Status

LyricFlow is an actively developed personal Android project. The current release provides the core music-detection, lyrics-retrieval, and floating-overlay experience.

Some tracks and playback sources may not work perfectly. Further improvements may focus on metadata matching, lyrics-provider reliability, synchronization, performance, and user experience.

## 🔒 Privacy and Permissions

LyricFlow uses notification access to identify supported media playback and overlay permission to display lyrics above other applications.

Grant only the permissions needed for the features you use. Review the source code and project documentation if you want to understand the implementation.

## 🤝 Contributing

Contributions, bug reports, and suggestions are welcome.

1. Fork the repository.
2. Create a feature branch.
3. Make and test your changes.
4. Open a pull request describing your changes.

For bug reports, include your Android version, playback source, expected behavior, and actual behavior. Avoid sharing personal data or sensitive device logs.

## 📄 License

No license has been specified in this README. Check the repository for a `LICENSE` file before reusing or redistributing the code.

## 👨‍💻 Author

**Anubhav Bansal**

GitHub: [@exp-anubhav](https://github.com/exp-anubhav)

**Repository:** [exp-anubhav/LyricFlow](https://github.com/exp-anubhav/LyricFlow)

---

*Made for music lovers who want to follow every line without leaving the app they're using.*
