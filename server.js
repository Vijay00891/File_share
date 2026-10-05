const http = require("http");
const fs = require("fs");
const fsp = require("fs/promises");
const os = require("os");
const path = require("path");
const crypto = require("crypto");
const { URL } = require("url");

const PORT = Number(process.env.PORT || 3478);
const ROOT = __dirname;
const PUBLIC_DIR = path.join(ROOT, "public");
const STORAGE_DIR = path.join(ROOT, "shared-files");

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon"
};

function json(res, status, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": Buffer.byteLength(body)
  });
  res.end(body);
}

const VIRTUAL_NAME = /vethernet|wsl|docker|virtual|vmware|vbox|hyper-v|loopback|tailscale|zerotier|bluetooth/i;

// Lower score = more likely to be the address other devices on the Wi-Fi can reach.
function addressScore(name, ip) {
  if (VIRTUAL_NAME.test(name)) return 100;
  if (ip.startsWith("192.168.")) return 0;
  if (ip.startsWith("10.")) return 1;
  if (/^172.(1[6-9]|2d|3[01])./.test(ip)) return 2;
  if (ip.startsWith("169.254.")) return 90;
  return 3;
}

function getLocalAddresses() {
  const found = [];
  for (const [name, entries] of Object.entries(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) {
        found.push({ score: addressScore(name, entry.address), url: `http://${entry.address}:${PORT}` });
      }
    }
  }
  return found.sort((x, y) => x.score - y.score).map((item) => item.url);
}

function safeName(name) {
  const base = path.basename(name || "file");
  const cleaned = base.replace(/[<>:"/\\|?*\x00-\x1f]/g, "_").trim();
  return cleaned || "file";
}

async function uniquePath(originalName) {
  const parsed = path.parse(safeName(originalName));
  let candidate = `${parsed.name}${parsed.ext}`;
  let target = path.join(STORAGE_DIR, candidate);
  let counter = 1;

  while (true) {
    try {
      await fsp.access(target);
      candidate = `${parsed.name} (${counter})${parsed.ext}`;
      target = path.join(STORAGE_DIR, candidate);
      counter += 1;
    } catch {
      return { filename: candidate, target };
    }
  }
}

function fileUrl(filename) {
  return `/download/${encodeURIComponent(filename)}`;
}

async function listFiles() {
  await fsp.mkdir(STORAGE_DIR, { recursive: true });
  const names = await fsp.readdir(STORAGE_DIR);
  const files = await Promise.all(
    names.map(async (name) => {
      const fullPath = path.join(STORAGE_DIR, name);
      const stat = await fsp.stat(fullPath);
      if (!stat.isFile()) return null;
      return {
        name,
        size: stat.size,
        modified: stat.mtime.toISOString(),
        url: fileUrl(name)
      };
    })
  );

  return files.filter(Boolean).sort((a, b) => new Date(b.modified) - new Date(a.modified));
}

function sendStatic(req, res, pathname) {
  const requested = pathname === "/" ? "/index.html" : pathname;
  const resolved = path.resolve(PUBLIC_DIR, `.${decodeURIComponent(requested)}`);

  if (!resolved.startsWith(PUBLIC_DIR)) {
    json(res, 403, { error: "Forbidden" });
    return;
  }

  fs.createReadStream(resolved)
    .on("error", () => json(res, 404, { error: "Not found" }))
    .once("open", () => {
      const type = MIME[path.extname(resolved).toLowerCase()] || "application/octet-stream";
      res.writeHead(200, { "content-type": type });
    })
    .pipe(res);
}

async function receiveUpload(req, res) {
  const nameHeader = req.headers["x-file-name"];
  const uploadName = Array.isArray(nameHeader) ? nameHeader[0] : nameHeader;

  if (!uploadName) {
    json(res, 400, { error: "Missing file name" });
    return;
  }

  // No size limit: the body is streamed straight to disk.
  const { filename, target } = await uniquePath(decodeURIComponent(uploadName));
  const temp = `${target}.${crypto.randomUUID()}.uploading`;
  const out = fs.createWriteStream(temp, { flags: "wx" });
  let received = 0;
  let failed = false;

  const fail = async (status, message) => {
    if (failed) return;
    failed = true;
    out.destroy();
    await fsp.rm(temp, { force: true }).catch(() => {});
    if (!res.headersSent) json(res, status, { error: message });
  };

  req.on("data", (chunk) => {
    received += chunk.length;
  });
  req.on("error", () => fail(500, "Upload interrupted"));
  req.on("aborted", () => fail(499, "Upload cancelled"));
  out.on("error", () => fail(500, "Upload failed"));

  req.pipe(out);

  out.on("finish", async () => {
    if (failed) return;
    await fsp.rename(temp, target);
    json(res, 201, {
      file: {
        name: filename,
        size: received,
        modified: new Date().toISOString(),
        url: fileUrl(filename)
      }
    });
  });
}

async function downloadFile(res, rawName) {
  const filename = safeName(decodeURIComponent(rawName));
  const fullPath = path.join(STORAGE_DIR, filename);

  try {
    const stat = await fsp.stat(fullPath);
    if (!stat.isFile()) throw new Error("Not a file");
    res.writeHead(200, {
      "content-type": "application/octet-stream",
      "content-length": stat.size,
      "content-disposition": `attachment; filename*=UTF-8''${encodeURIComponent(filename)}`
    });
    fs.createReadStream(fullPath).pipe(res);
  } catch {
    json(res, 404, { error: "File not found" });
  }
}

async function deleteFile(res, rawName) {
  const filename = safeName(decodeURIComponent(rawName));
  await fsp.rm(path.join(STORAGE_DIR, filename), { force: true });
  json(res, 200, { ok: true });
}

async function route(req, res) {
  const url = new URL(req.url, `http://${req.headers.host || "localhost"}`);

  try {
    if (req.method === "GET" && url.pathname === "/api/info") {
      json(res, 200, { port: PORT, addresses: getLocalAddresses() });
      return;
    }

    if (req.method === "GET" && url.pathname === "/api/files") {
      json(res, 200, { files: await listFiles() });
      return;
    }

    if (req.method === "POST" && url.pathname === "/api/upload") {
      await receiveUpload(req, res);
      return;
    }

    if (req.method === "GET" && url.pathname.startsWith("/download/")) {
      await downloadFile(res, url.pathname.slice("/download/".length));
      return;
    }

    if (req.method === "DELETE" && url.pathname.startsWith("/api/files/")) {
      await deleteFile(res, url.pathname.slice("/api/files/".length));
      return;
    }

    if (req.method === "GET") {
      sendStatic(req, res, url.pathname);
      return;
    }

    json(res, 405, { error: "Method not allowed" });
  } catch (error) {
    json(res, 500, { error: error.message || "Server error" });
  }
}

fsp.mkdir(STORAGE_DIR, { recursive: true }).then(() => {
  const server = http.createServer(route);
  // Disable timeouts so very large uploads are never cut off.
  server.requestTimeout = 0;
  server.headersTimeout = 0;
  server.timeout = 0;
  server.listen(PORT, "0.0.0.0", () => {
    console.log("Local File Share is running");
    console.log(`  This device: http://localhost:${PORT}`);
    for (const address of getLocalAddresses()) console.log(`  Network:     ${address}`);
  });
});
