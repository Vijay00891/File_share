# Local File Share

Share files with nearby devices on the same local network.

## Run

```powershell
npm start
```

The app runs at:

```text
http://localhost:3478
```

The terminal also prints one or more network addresses, such as:

```text
http://192.168.0.107:3478
```

Open that network address from another phone, tablet, or computer connected to the same Wi-Fi.

## Notes

- Uploaded files are saved in `shared-files`.
- The default port is `3478`.
- To use another port:

```powershell
$env:PORT=8080; npm start
```

- Windows Firewall may ask whether to allow Node.js on private networks. Allow it for other local devices to connect.
