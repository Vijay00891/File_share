# Local Share (Android)

Native Android app for sharing files without the internet.

## What it does

- **Share to desktop** – starts a small web server on the phone. Open the shown address in a computer's browser (same Wi-Fi) to upload and download files.
- **Share to phone → Send** – creates a direct Wi-Fi link (Wi-Fi Direct) that the other phone joins. Optional *High-speed transfer* asks for the 5 GHz band and falls back to 2.4 GHz if the phone refuses.
- **Share to phone → Receive** – finds nearby senders, joins the one you tap, then lets you save the sender's files and send files back.
- **QR code** – the sender screen shows a QR code; the receiver can tap *Scan QR code* to connect without searching. The desktop screen shows a QR code of its address too.
- **Runs in the background** – once a share is running it keeps going after you leave the app, with an ongoing notification that has a *Stop sharing* button. Reopening the app returns to the live share.
- **Light and dark** – the app and the web page follow the device's theme.
- **Built-in picker** – browse phone storage (with image thumbnails) or pick installed apps (with icons). Needs the "All files access" permission.

Files received are saved in `Download/LocalShare` when file access is allowed, otherwise in the app's own folder.

## Things to know

- Phone-to-phone sharing needs Android 10+ and Wi-Fi Direct on both phones.
- Android 10+ does not let apps switch Wi-Fi on silently; the app opens the system Wi-Fi panel instead.
- Before Android 13, finding nearby phones needs the Location permission and Location switched on.
- The direct link's password is derived from its name so the receiver can join with one tap. It is convenient, not private: anyone nearby with this app can join while you are sending.
- Installed apps are shared as their base APK only; apps installed as split APKs may not install from that file alone.
- "All files access" is fine for a sideloaded APK but restricted on Google Play.

## Build

Uses Android Gradle Plugin `8.7.3`, `compileSdk 35`, Java. The only library is ZXing `core` for QR codes.

```text
gradle assembleDebug
```

The web UI in `../public` is bundled into the APK automatically at build time.
