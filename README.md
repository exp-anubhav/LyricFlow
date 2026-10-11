# LyricFlow

LyricFlow is an Android project for an automatic, system-wide synchronized lyrics overlay.

## Phase 1
- MediaSession playback detection through Android's NotificationListenerService.
- Automatic track title/artist/playback-state updates.
- Floating overlay foundation.
- Jetpack Compose UI.

## Build
Open the project in Android Studio. Let Gradle sync, then run the `app` configuration on an Android 8.0+ device/emulator.

On the phone:
1. Open LyricFlow.
2. Enable Notification Access for LyricFlow.
3. Allow "Display over other apps".
4. Start the overlay.
5. Play a song in a player that exposes Android media controls.

## Important
This is the first working foundation, not the finished product. The next implementation layers are:
1. robust active-session switching without duplicate callbacks,
2. lyrics provider integration,
3. LRC parsing and time synchronization,
4. song-change debounce/cache,
5. polished animated Compose overlay,
6. optional recognition fallback for sources that do not expose useful media metadata.
