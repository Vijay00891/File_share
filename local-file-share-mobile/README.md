# Local Share (Android)

Native Android app for sharing files without the internet.

## What it does

- **Share to desktop** – starts a small web server on the phone. Open the shown address in a computer's browser (same Wi-Fi) to upload and download files.
- **Share to phone → Send** – turns on a hotspot on the sender (Wi-Fi does not need to be on) and announces it over Bluetooth so only phones running this app can see it. *Turn on 5 GHz for high speed* puts the link on 5 GHz: on Android 16+ that is the hotspot itself; on Android 10–15, where apps cannot choose a hotspot's band, it runs over Wi-Fi Direct and needs Wi-Fi on.
- **Share to phone → Receive** – lists nearby senders, joins the one you tap (Android shows one confirmation for a hotspot), then lets you save the sender's files and send files back.
- **QR code** – the sender screen shows a QR code; the receiver can tap *Scan QR code* to connect without searching. The desktop screen shows a QR code of its address too.
- **Runs in the background** – once a share is running it keeps going after you leave the app, with an ongoing notification that has a *Stop sharing* button. Reopening the app returns to the live share.
- **Light and dark** – the app and the web page follow the device's theme.
- **Built-in picker** – browse phone storage (with image thumbnails) or pick installed apps (with icons). Needs the "All files access" permission.

Files received are saved in `Download/LocalShare` when file access is allowed, otherwise in the app's own folder.

## Things to know

- Phone-to-phone sharing needs Android 10+ on both phones. Appearing in the nearby list needs Bluetooth on; the QR code works without it.
- Android picks the hotspot's name. Its band can only be chosen on Android 16+, and a hotspot started on a chosen band has no password (the public API has no way to set one).
- Where Android does not let an app change a setting itself (Wi-Fi, Bluetooth, Location), the app explains why, opens the right system screen, and continues on its own once the setting is on.
- Android 10+ does not let apps switch Wi-Fi on silently; the app opens the system Wi-Fi panel instead.
- Before Android 13, finding nearby phones needs the Location permission and Location switched on.
- The direct link's password is derived from its name so the receiver can join with one tap. It is convenient, not private: anyone nearby with this app can join while you are sending.
- Installed apps are shared as their base APK only; apps installed as split APKs may not install from that file alone.
- "All files access" is fine for a sideloaded APK but restricted on Google Play.

## Build

Uses Android Gradle Plugin `8.7.3`, `compileSdk 35`, Java. The only library is ZXing `core` for QR codes.

```text
gradle assembleDebug
gradle testDebugUnitTest   # logic tests: announcements, QR links, real transfers through the server
```

The web UI in `../public` is bundled into the APK automatically at build time.
