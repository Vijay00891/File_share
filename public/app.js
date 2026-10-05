const $ = (selector) => document.querySelector(selector);
const dropzone = $("#dropzone");
const fileInput = $("#fileInput");
const queue = $("#queue");
const filesEl = $("#files");
const statusEl = $("#status");
const addressesEl = $("#addresses");
const countEl = $("#count");
const searchEl = $("#search");
const toastEl = $("#toast");

const icons = {
  copy: '<svg viewBox="0 0 24 24"><rect x="9" y="9" width="11" height="11" rx="2"/><path d="M5 15V6a2 2 0 0 1 2-2h9"/></svg>',
  trash: '<svg viewBox="0 0 24 24"><path d="M4 7h16M10 11v6m4-6v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>',
  down: '<svg viewBox="0 0 24 24"><path d="M12 4v12m0 0 4-4m-4 4-4-4M5 20h14"/></svg>'
};

let allFiles = [];
let toastTimer;

function toast(message) {
  toastEl.textContent = message;
  toastEl.classList.add("show");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl.classList.remove("show"), 2200);
}

function formatBytes(bytes) {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** i).toFixed(i ? 1 : 0)} ${units[i]}`;
}

function formatDate(value) {
  return new Intl.DateTimeFormat([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function el(tag, className, html) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (html) node.innerHTML = html;
  return node;
}

function iconButton(label, html, className = "") {
  const btn = el("button", `btn icon ${className}`, html);
  btn.type = "button";
  btn.title = label;
  btn.setAttribute("aria-label", label);
  return btn;
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    const input = document.createElement("input");
    input.value = text;
    document.body.append(input);
    input.select();
    document.execCommand("copy");
    input.remove();
  }
  toast("Address copied");
}

function drawQr(text) {
  const canvas = $("#qr");
  try {
    QRCode.toCanvas(canvas, text, { foreground: "#131a2e", background: "#ffffff" });
  } catch {
    canvas.parentElement.hidden = true;
  }
}

async function loadInfo() {
  try {
    const info = await (await fetch("/api/info")).json();
    // Opened from another device: its own address is the right one. Opened on this
    // machine (localhost): show the best LAN address instead.
    const onLocalhost = ["localhost", "127.0.0.1", "[::1]"].includes(location.hostname);
    const address = onLocalhost && info.addresses && info.addresses.length
      ? info.addresses[0]
      : `${location.protocol}//${location.host}`;
    statusEl.className = "status online";
    statusEl.lastElementChild.textContent = "Online";
    addressesEl.replaceChildren();
    const row = el("div", "address-row");
    const link = el("a");
    link.href = address;
    link.textContent = address;
    const btn = iconButton("Copy address", icons.copy);
    btn.addEventListener("click", () => copy(address));
    row.append(link, btn);
    addressesEl.append(row);
    drawQr(address);
  } catch {
    statusEl.className = "status offline";
    statusEl.lastElementChild.textContent = "Offline";
  }
}

function renderFiles() {
  const term = searchEl.value.trim().toLowerCase();
  const files = allFiles.filter((f) => f.name.toLowerCase().includes(term));
  countEl.textContent = allFiles.length;
  filesEl.replaceChildren();

  if (!files.length) {
    filesEl.append(el("div", "empty", allFiles.length ? "No files match your search." : "No files shared yet. Drop something above."));
    return;
  }

  files.forEach((file) => {
    const row = el("article", "file-row");
    const ext = (file.name.includes(".") ? file.name.split(".").pop() : "file").slice(0, 4).toUpperCase();
    const badge = el("div", "file-badge");
    badge.textContent = ext;

    const main = el("div", "file-main");
    const link = el("a");
    link.href = file.url;
    link.textContent = file.name;
    link.title = file.name;
    const meta = el("p", "file-meta");
    meta.textContent = `${formatBytes(file.size)} · ${formatDate(file.modified)}`;
    main.append(link, meta);

    const actions = el("div", "file-actions");
    const download = el("a", "btn", `${icons.down}<span>Download</span>`);
    download.href = file.url;
    const remove = iconButton("Delete file", icons.trash, "danger");
    remove.addEventListener("click", async () => {
      if (!confirm(`Delete "${file.name}"?`)) return;
      await fetch(`/api/files/${encodeURIComponent(file.name)}`, { method: "DELETE" });
      toast("File deleted");
      loadFiles();
    });
    actions.append(download, remove);
    row.append(badge, main, actions);
    filesEl.append(row);
  });
}

async function loadFiles() {
  try {
    allFiles = (await (await fetch("/api/files")).json()).files || [];
  } catch {
    allFiles = [];
  }
  renderFiles();
}

function queueRow(file) {
  const row = el("div", "queue-row");
  const top = el("div", "queue-top");
  const name = el("strong");
  name.textContent = file.name;
  const info = el("span");
  info.textContent = `0% · ${formatBytes(file.size)}`;
  top.append(name, info);
  const bar = el("div", "bar", "<i></i>");
  row.append(top, bar);
  queue.prepend(row);
  return { row, info, fill: bar.firstChild, size: file.size };
}

// Uploads are streamed as the raw request body, so there is no size limit.
function upload(file) {
  const item = queueRow(file);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/upload");
  xhr.setRequestHeader("x-file-name", encodeURIComponent(file.name));
  xhr.upload.addEventListener("progress", (event) => {
    if (!event.lengthComputable) return;
    const pct = Math.round((event.loaded / event.total) * 100);
    item.fill.style.width = `${pct}%`;
    item.info.textContent = `${pct}% · ${formatBytes(event.loaded)} / ${formatBytes(event.total)}`;
  });
  const finish = (ok) => {
    item.row.classList.add(ok ? "done" : "failed");
    item.fill.style.width = "100%";
    item.info.textContent = ok ? "Uploaded" : "Upload failed";
    if (ok) loadFiles();
    setTimeout(() => item.row.remove(), ok ? 2500 : 6000);
  };
  xhr.addEventListener("load", () => finish(xhr.status < 400));
  xhr.addEventListener("error", () => finish(false));
  xhr.send(file);
}

function handleFiles(list) {
  [...list].forEach(upload);
}

dropzone.addEventListener("click", () => fileInput.click());
dropzone.addEventListener("keydown", (e) => {
  if (e.key === "Enter" || e.key === " ") {
    e.preventDefault();
    fileInput.click();
  }
});
fileInput.addEventListener("change", () => {
  handleFiles(fileInput.files);
  fileInput.value = "";
});
$("#refreshButton").addEventListener("click", () => {
  loadFiles();
  toast("Refreshed");
});
searchEl.addEventListener("input", renderFiles);

["dragenter", "dragover"].forEach((name) =>
  dropzone.addEventListener(name, (e) => {
    e.preventDefault();
    dropzone.classList.add("dragging");
  })
);
["dragleave", "drop"].forEach((name) =>
  dropzone.addEventListener(name, (e) => {
    e.preventDefault();
    dropzone.classList.remove("dragging");
  })
);
dropzone.addEventListener("drop", (e) => handleFiles(e.dataTransfer.files));

loadInfo();
loadFiles();
setInterval(loadFiles, 8000);
