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
const MAX_UPLOAD_BYTES = 1024 * 1024 * 1024;

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

function getLocalAddresses() {
  const addresses = [];
  for (const entries of Object.values(os.networkInterfaces())) {
    for (const entry of entries || []) {
      if (entry.family === "IPv4" && !entry.internal) {
        addresses.push(`http://${entry.address}:${PORT}`);
      }
    }
  }
  return addresses;
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
  const length = Number(req.headers["content-length"] || 0);

  if (!uploadName) {
    json(res, 400, { error: "Missing file name" });
    return;
  }

  if (length > MAX_UPLOAD_BYTES) {
    json(res, 413, { error: "File is larger than the 1 GB limit" });
    return;
  }

  const originalName = decodeURIComponent(uploadName);
  const { filename, target } = await uniquePath(originalName);
  const temp = `${target}.${crypto.randomUUID()}.uploading`;
  const out = fs.createWriteStream(temp, { flags: "wx" });
  let received = 0;
  let tooLarge = false;

  req.on("data", (chunk) => {
    received += chunk.length;
    if (received > MAX_UPLOAD_BYTES) {
      tooLarge = true;
      req.destroy();
      out.destroy();
    }
  });

  req.pipe(out);

  out.on("finish", async () => {
    if (tooLarge) return;
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

  out.on("error", async () => {
    await fsp.rm(temp, { force: true }).catch(() => {});
    if (!res.headersSent) json(res, 500, { error: "Upload failed" });
  });

  req.on("error", async () => {
    await fsp.rm(temp, { force: true }).catch(() => {});
    if (!res.headersSent) {
      json(res, tooLarge ? 413 : 500, {
        error: tooLarge ? "File is larger than the 1 GB limit" : "Upload interrupted"
      });
    }
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
  http.createServer(route).listen(PORT, "0.0.0.0", () => {
    console.log("Local File Share is running");
    console.log(`  This device: http://localhost:${PORT}`);
    for (const address of getLocalAddresses()) console.log(`  Network:     ${address}`);
  });
});
