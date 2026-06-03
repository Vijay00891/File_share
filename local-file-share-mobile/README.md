# Local File Share Mobile

Native Android app that starts a local HTTP server on the phone so nearby devices on the same Wi-Fi can upload and download files.

## Features

- Start and stop the local server from the app.
- Show and copy the phone's LAN URL.
- Pick files from the phone and add them to the shared folder.
- Other devices can open the URL in a browser to upload, download, refresh, and delete files.
- Files are stored in the app's external files directory.

## Build

Open this folder in Android Studio:

```text
outputs/local-file-share-mobile
```

Then run the `app` configuration on a device.

The project uses Android Gradle Plugin `8.7.3`, `compileSdk 35`, and Java source.

## Use

1. Connect the phone and the other device to the same Wi-Fi.
2. Open the app and tap `Start server`.
3. Open the displayed address, for example `http://192.168.0.107:3478`, on the other device.
4. Upload or download files locally.

If the URL does not open from another device, check that the devices are on the same network and that the network allows device-to-device traffic.
