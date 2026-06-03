const dropzone = document.querySelector("#dropzone");
const fileInput = document.querySelector("#fileInput");
const chooseButton = document.querySelector("#chooseButton");
const queue = document.querySelector("#queue");
const filesEl = document.querySelector("#files");
const statusEl = document.querySelector("#status");
const addressesEl = document.querySelector("#addresses");
const refreshButton = document.querySelector("#refreshButton");
const addressTile = document.querySelector("#addressTile");

const icons = {
  copy: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M8 8h10v12H8zM6 16H4V4h12v2" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>',
  trash: '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M10 11v6m4-6v6M6 7l1 14h10l1-14M9 7l1-4h4l1 4" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>'
};

function formatBytes(bytes) {
  if (bytes === 0) return "0 B";
  const units = ["B", "KB", "MB", "GB"];
  const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  return `${(bytes / 1024 ** index).toFixed(index ? 1 : 0)} ${units[index]}`;
}

function formatDate(value) {
  return new Intl.DateTimeFormat([], {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit"
  }).format(new Date(value));
}

function button(label, className, html) {
  const el = document.createElement("button");
  el.className = className;
  el.type = "button";
  el.title = label;
  el.setAttribute("aria-label", label);
  el.innerHTML = html || label;
  return el;
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
}

function drawAddressTileFallback(text) {
  const ctx = addressTile.getContext("2d");
  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, addressTile.width, addressTile.height);
  ctx.fillStyle = "#17202e";
  ctx.font = "700 14px system-ui";
  ctx.textAlign = "center";
  ctx.fillText("Open address", 90, 78);
  ctx.font = "12px system-ui";
  for (const [index, chunk] of text.match(/.{1,20}/g).entries()) {
    ctx.fillText(chunk, 90, 100 + index * 16);
  }
}

function drawAddressTile(text) {
  const ctx = addressTile.getContext("2d");
  const cells = 29;
  const size = addressTile.width / cells;
  let seed = 0;
  for (const char of text) seed = (seed * 31 + char.charCodeAt(0)) >>> 0;

  ctx.fillStyle = "#fff";
  ctx.fillRect(0, 0, addressTile.width, addressTile.height);
  ctx.fillStyle = "#17202e";

  const finder = (x, y) => {
    ctx.fillRect(x * size, y * size, size * 7, size * 7);
    ctx.fillStyle = "#fff";
    ctx.fillRect((x + 1) * size, (y + 1) * size, size * 5, size * 5);
    ctx.fillStyle = "#17202e";
    ctx.fillRect((x + 2) * size, (y + 2) * size, size * 3, size * 3);
  };

  finder(1, 1);
  finder(21, 1);
  finder(1, 21);

  for (let y = 0; y < cells; y += 1) {
    for (let x = 0; x < cells; x += 1) {
      const inFinder = (x < 9 && y < 9) || (x > 19 && y < 9) || (x < 9 && y > 19);
      if (inFinder) continue;
      seed ^= seed << 13;
      seed ^= seed >>> 17;
      seed ^= seed << 5;
      if ((seed & 7) < 3) ctx.fillRect(x * size, y * size, Math.ceil(size), Math.ceil(size));
    }
  }
}

async function loadInfo() {
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
    const copyBtn = button("Copy address", "icon-button copy-button", icons.copy);
    copyBtn.addEventListener("click", () => copy(address));
    row.append(link, copyBtn);
    addressesEl.append(row);
  });

  try {
    drawAddressTile(addresses[0]);
  } catch {
    drawAddressTileFallback(addresses[0]);
  }
}

async function loadFiles() {
  const res = await fetch("/api/files");
  const data = await res.json();
  filesEl.replaceChildren();

  if (!data.files.length) {
    const empty = document.createElement("div");
    empty.className = "empty";
    empty.textContent = "No files shared yet.";
    filesEl.append(empty);
    return;
  }

  data.files.forEach((file) => {
    const row = document.createElement("article");
    row.className = "file-row";

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
    const download = document.createElement("a");
    download.className = "ghost";
    download.href = file.url;
    download.textContent = "Download";
    const remove = button("Delete file", "icon-button delete-button", icons.trash);
    remove.addEventListener("click", async () => {
      await fetch(`/api/files/${encodeURIComponent(file.name)}`, { method: "DELETE" });
      await loadFiles();
    });
    actions.append(download, remove);
    row.append(main, actions);
    filesEl.append(row);
  });
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
    if (event.lengthComputable) item.progress.value = Math.round((event.loaded / event.total) * 100);
  });
  xhr.addEventListener("load", async () => {
    item.progress.value = xhr.status < 400 ? 100 : 0;
    item.detail.textContent = xhr.status < 400 ? "Uploaded" : "Upload failed";
    if (xhr.status < 400) await loadFiles();
    setTimeout(() => item.row.remove(), 2200);
  });
  xhr.addEventListener("error", () => {
    item.detail.textContent = "Upload failed";
  });
  xhr.send(file);
}

function handleFiles(list) {
  [...list].forEach(upload);
}

chooseButton.addEventListener("click", () => fileInput.click());
fileInput.addEventListener("change", () => handleFiles(fileInput.files));
refreshButton.addEventListener("click", loadFiles);

["dragenter", "dragover"].forEach((eventName) => {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.add("dragging");
  });
});

["dragleave", "drop"].forEach((eventName) => {
  dropzone.addEventListener(eventName, (event) => {
    event.preventDefault();
    dropzone.classList.remove("dragging");
  });
});

dropzone.addEventListener("drop", (event) => handleFiles(event.dataTransfer.files));
dropzone.addEventListener("keydown", (event) => {
  if (event.key === "Enter" || event.key === " ") fileInput.click();
});

loadInfo();
loadFiles();
