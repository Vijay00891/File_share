const dropzone = document.querySelector("#dropzone");
const fileInput = document.querySelector("#fileInput");
const chooseButton = document.querySelector("#chooseButton");
const queue = document.querySelector("#queue");
const filesEl = document.querySelector("#files");
const statusEl = document.querySelector("#status");
const addressesEl = document.querySelector("#addresses");
const refreshButton = document.querySelector("#refreshButton");
const addressTile = document.querySelector("#addressTile");
const themeToggle = document.querySelector("#themeToggle");
const toastContainer = document.querySelector("#toastContainer");
const fileCountEl = document.querySelector("#fileCount");

const icons = {
  copy: '<svg viewBox="0 0 24 24" fill="none"><rect x="9" y="9" width="13" height="13" rx="2" stroke="currentColor" stroke-width="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" stroke="currentColor" stroke-width="2"/></svg>',
  trash: '<svg viewBox="0 0 24 24" fill="none"><polyline points="3 6 5 6 21 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><line x1="10" y1="11" x2="10" y2="17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/><line x1="14" y1="11" x2="14" y2="17" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  download: '<svg viewBox="0 0 24 24" fill="none"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><polyline points="7 10 12 15 17 10" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><line x1="12" y1="15" x2="12" y2="3" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  file: '<svg viewBox="0 0 24 24" fill="none"><path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/><polyline points="13 2 13 9 20 9" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
};

function showToast(message, type = "success") {
  const toast = document.createElement("div");
  toast.className = `toast toast-${type}`;
  toast.textContent = message;
  toastContainer.append(toast);
  setTimeout(() => {
    toast.classList.add("toast-exit");
    toast.addEventListener("animationend", () => toast.remove());
  }, 3000);
}

function initTheme() {
  const saved = localStorage.getItem("filedrop-theme");
  if (saved === "dark" || (!saved && window.matchMedia("(prefers-color-scheme: dark)").matches)) {
    document.documentElement.setAttribute("data-theme", "dark");
  }
}

themeToggle.addEventListener("click", () => {
  const isDark = document.documentElement.getAttribute("data-theme") === "dark";
  if (isDark) {
    document.documentElement.removeAttribute("data-theme");
    localStorage.setItem("filedrop-theme", "light");
  } else {
    document.documentElement.setAttribute("data-theme", "dark");
    localStorage.setItem("filedrop-theme", "dark");
  }
});
initTheme();

function formatBytes(bytes) {
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

function formatDate(value) {
  return new Intl.DateTimeFormat([], { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" }).format(new Date(value));
}

function button(label, className, html) {
  const el = document.createElement("button");
  el.className = className;
  el.type = "button";
  el.title = label;
  el.innerHTML = html || label;
  return el;
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text);
    showToast("Address copied!");
  } catch {
    const input = document.createElement("input");
    input.value = text;
    document.body.append(input);
    input.select();
    document.execCommand("copy");
    input.remove();
    showToast("Address copied!");
  }
}

function drawAddressTile(text) {
  const isDark = document.documentElement.getAttribute("data-theme") === "dark";
  const fg = isDark ? "#ffffff" : "#1a1d26";
  const bg = isDark ? "#1e1f28" : "#ffffff";
  if (typeof QRCode !== 'undefined') {
    QRCode.toCanvas(addressTile, text, { foreground: fg, background: bg, cellSize: 5 });
  }
}

themeToggle.addEventListener("click", () => {
  const currentAddr = addressesEl.querySelector("a")?.href;
  if (currentAddr) setTimeout(() => drawAddressTile(currentAddr), 10);
});

async function loadInfo() {
  try {
    const res = await fetch("/api/info");
    const info = await res.json();
    const current = `${location.protocol}//${location.host}`;
    const addresses = info.addresses.length ? info.addresses : [current];
    statusEl.classList.add("online");
    statusEl.lastElementChild.textContent = "Online";
    addressesEl.replaceChildren();
    addresses.forEach((address) => {
      const row = document.createElement("div");
      row.className = "address-row";
      const link = document.createElement("a");
      link.href = address;
      link.textContent = address;
      const copyBtn = button("Copy address", "btn-icon", icons.copy);
      copyBtn.addEventListener("click", () => copy(address));
      row.append(link, copyBtn);
      addressesEl.append(row);
    });
    drawAddressTile(addresses[0]);
  } catch {
    statusEl.lastElementChild.textContent = "Offline";
  }
}

async function loadFiles() {
  try {
    const res = await fetch("/api/files");
    const data = await res.json();
    filesEl.replaceChildren();
    fileCountEl.textContent = data.files.length ? `${data.files.length} file${data.files.length === 1 ? "" : "s"}` : "No files yet";
    if (!data.files.length) {
      const empty = document.createElement("div");
      empty.className = "empty";
      empty.innerHTML = `<div class="empty-icon">${icons.file}</div><h3>No files shared yet</h3><p>Upload files using the panel above to get started</p>`;
      filesEl.append(empty);
      return;
    }
    data.files.forEach((file) => {
      const row = document.createElement("article");
      row.className = "file-row";
      const icon = document.createElement("div");
      icon.className = "file-icon";
      icon.innerHTML = icons.file;
      const main = document.createElement("div");
      main.className = "file-main";
      const link = document.createElement("a");
      link.href = file.url;
      link.textContent = file.name;
      const meta = document.createElement("p");
      meta.className = "file-meta";
      meta.textContent = `${formatBytes(file.size)} · ${formatDate(file.modified)}`;
      main.append(link, meta);
      const actions = document.createElement("div");
      actions.className = "file-actions";
      const downloadBtn = document.createElement("a");
      downloadBtn.className = "btn btn-ghost";
      downloadBtn.href = file.url;
      downloadBtn.innerHTML = `${icons.download} Download`;
      const removeBtn = button("Delete file", "btn-icon delete", icons.trash);
      removeBtn.addEventListener("click", async () => {
        await fetch(`/api/files/${encodeURIComponent(file.name)}`, { method: "DELETE" });
        showToast(`"${file.name}" deleted`);
        await loadFiles();
      });
      actions.append(downloadBtn, removeBtn);
      row.append(icon, main, actions);
      filesEl.append(row);
    });
  } catch {
    showToast("Failed to load files", "error");
  }
}

function queueRow(file) {
  const row = document.createElement("div");
  row.className = "queue-row";
  const main = document.createElement("div");
  main.className = "queue-main";
  const name = document.createElement("strong");
  name.textContent = file.name;
  const detail = document.createElement("p");
  detail.textContent = formatBytes(file.size);
  main.append(name, detail);
  const progress = document.createElement("progress");
  progress.max = 100;
  progress.value = 0;
  row.append(main, progress);
  queue.prepend(row);
  return { row, detail, progress };
}

function upload(file) {
  const item = queueRow(file);
  const xhr = new XMLHttpRequest();
  xhr.open("POST", "/api/upload");
  xhr.setRequestHeader("x-file-name", encodeURIComponent(file.name));
  xhr.upload.addEventListener("progress", (event) => {
    if (event.lengthComputable) {
      const pct = Math.round((event.loaded / event.total) * 100);
      item.progress.value = pct;
      item.detail.textContent = `${formatBytes(event.loaded)} / ${formatBytes(event.total)} — ${pct}%`;
    }
  });
  xhr.addEventListener("load", async () => {
    if (xhr.status < 400) {
      item.progress.value = 100;
      item.detail.textContent = "✓ Uploaded";
      showToast(`"${file.name}" uploaded successfully`);
      await loadFiles();
    } else {
      item.detail.textContent = "✗ Upload failed";
      showToast(`Failed to upload "${file.name}"`, "error");
    }
    setTimeout(() => item.row.remove(), 2500);
  });
  xhr.addEventListener("error", () => {
    item.detail.textContent = "✗ Upload failed";
  });
  xhr.send(file);
}

function handleFiles(list) { [...list].forEach(upload); }
chooseButton.addEventListener("click", () => fileInput.click());
dropzone.addEventListener("click", (e) => { if (e.target !== chooseButton && !chooseButton.contains(e.target)) fileInput.click(); });
fileInput.addEventListener("change", () => { handleFiles(fileInput.files); fileInput.value = ""; });
refreshButton.addEventListener("click", () => { loadFiles(); showToast("Files refreshed"); });
["dragenter", "dragover"].forEach((eventName) => { dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.add("dragging"); }); });
["dragleave", "drop"].forEach((eventName) => { dropzone.addEventListener(eventName, (event) => { event.preventDefault(); dropzone.classList.remove("dragging"); }); });
dropzone.addEventListener("drop", (event) => handleFiles(event.dataTransfer.files));
dropzone.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") fileInput.click(); });

loadInfo();
loadFiles();
