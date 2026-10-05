/**
 * QRCode — Self-contained QR Code generator (byte mode, EC level M)
 * Usage: QRCode.toCanvas(canvasElement, "text", { foreground, background })
 */
const QRCode = (() => {
  "use strict";

  /* ===== GF(256) with primitive polynomial 0x11D ===== */
  const EXP = new Array(512);
  const LOG = new Array(256);
  {
    let x = 1;
    for (let i = 0; i < 255; i++) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x >= 256) x ^= 0x11d;
    }
    for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  }
  const gfMul = (a, b) => (a && b ? EXP[LOG[a] + LOG[b]] : 0);

  /* ===== Reed-Solomon error correction ===== */
  const _genCache = {};
  function rsGenPoly(n) {
    if (_genCache[n]) return _genCache[n];
    let g = [1];
    for (let i = 0; i < n; i++) {
      const ng = new Array(g.length + 1).fill(0);
      for (let j = 0; j < g.length; j++) {
        ng[j] ^= g[j];
        ng[j + 1] ^= gfMul(g[j], EXP[i]);
      }
      g = ng;
    }
    return (_genCache[n] = g);
  }

  function rsEncode(data, nec) {
    const gen = rsGenPoly(nec);
    const ecc = new Array(nec).fill(0);
    for (const b of data) {
      const f = b ^ ecc[0];
      for (let j = 0; j < nec - 1; j++) ecc[j] = ecc[j + 1];
      ecc[nec - 1] = 0;
      for (let j = 0; j < nec; j++) ecc[j] ^= gfMul(f, gen[j + 1]);
    }
    return ecc;
  }

  /* ===== Version parameters (EC level M) ===== */
  // [totalCodewords, ecPerBlock, g1Count, g1Data, g2Count, g2Data]
  const V = [
    null,
    [26, 10, 1, 16, 0, 0],       // v1
    [44, 16, 1, 28, 0, 0],       // v2
    [70, 26, 1, 44, 0, 0],       // v3
    [100, 18, 2, 32, 0, 0],      // v4
    [134, 24, 2, 43, 0, 0],      // v5
    [172, 16, 4, 27, 0, 0],      // v6
    [196, 18, 4, 31, 0, 0],      // v7
    [242, 22, 2, 38, 2, 39],     // v8
    [292, 22, 3, 36, 2, 37],     // v9
    [346, 26, 4, 43, 1, 44],     // v10
  ];

  // Byte-mode capacity per version at EC level M
  const CAP = [0, 14, 26, 42, 62, 84, 106, 122, 152, 180, 213];

  // Alignment pattern center positions
  const AP = [
    null, null,
    [6, 18], [6, 22], [6, 26], [6, 30], [6, 34],
    [6, 22, 38], [6, 24, 42], [6, 26, 46], [6, 28, 50],
  ];

  /* ===== Data encoding (byte mode) ===== */
  function pushBits(arr, val, count) {
    for (let i = count - 1; i >= 0; i--) arr.push((val >> i) & 1);
  }

  function encodeData(bytes, ver) {
    const vd = V[ver];
    const totalData = vd[2] * vd[3] + vd[4] * vd[5];
    const bits = [];

    pushBits(bits, 0b0100, 4);                          // byte mode indicator
    pushBits(bits, bytes.length, ver <= 9 ? 8 : 16);    // character count
    for (const b of bytes) pushBits(bits, b, 8);         // data bytes

    // terminator + byte-align
    pushBits(bits, 0, Math.min(4, totalData * 8 - bits.length));
    while (bits.length & 7) bits.push(0);

    // convert bit array to byte array
    const out = [];
    for (let i = 0; i < bits.length; i += 8) {
      let v = 0;
      for (let j = 0; j < 8; j++) v = (v << 1) | (bits[i + j] || 0);
      out.push(v);
    }

    // pad codewords
    const pad = [236, 17];
    let pi = 0;
    while (out.length < totalData) { out.push(pad[pi]); pi ^= 1; }
    return out;
  }

  /* ===== Error correction & interleaving ===== */
  function fullEncode(data, ver) {
    const [, nec, g1c, g1d, g2c, g2d] = V[ver];
    const dBlk = [], eBlk = [];
    let off = 0;
    for (let i = 0; i < g1c; i++) {
      const b = data.slice(off, off + g1d); dBlk.push(b); eBlk.push(rsEncode(b, nec)); off += g1d;
    }
    for (let i = 0; i < g2c; i++) {
      const b = data.slice(off, off + g2d); dBlk.push(b); eBlk.push(rsEncode(b, nec)); off += g2d;
    }

    const res = [];
    const mx = Math.max(g1d, g2d || 0);
    for (let i = 0; i < mx; i++) for (const b of dBlk) if (i < b.length) res.push(b[i]);
    for (let i = 0; i < nec; i++) for (const b of eBlk) res.push(b[i]);
    return res;
  }

  /* ===== Matrix construction ===== */
  function buildMatrix(ver, cw) {
    const sz = ver * 4 + 17;
    const mod = Array.from({ length: sz }, () => new Uint8Array(sz));
    const rsv = Array.from({ length: sz }, () => new Uint8Array(sz));

    // Helper
    const set = (r, c, dark) => {
      if (r >= 0 && r < sz && c >= 0 && c < sz) { mod[r][c] = dark ? 1 : 0; rsv[r][c] = 1; }
    };

    // Finder patterns (7x7 + separator)
    const finder = (r0, c0) => {
      for (let r = -1; r <= 7; r++)
        for (let c = -1; c <= 7; c++)
          set(r0 + r, c0 + c,
            (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
            (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
            (r >= 2 && r <= 4 && c >= 2 && c <= 4));
    };
    finder(0, 0);
    finder(0, sz - 7);
    finder(sz - 7, 0);

    // Alignment patterns
    const ap = AP[ver];
    if (ap)
      for (const ar of ap)
        for (const ac of ap) {
          if (ar < 9 && ac < 9) continue;
          if (ar < 9 && ac > sz - 9) continue;
          if (ar > sz - 9 && ac < 9) continue;
          for (let r = -2; r <= 2; r++)
            for (let c = -2; c <= 2; c++)
              set(ar + r, ac + c, Math.abs(r) === 2 || Math.abs(c) === 2 || (!r && !c));
        }

    // Timing patterns
    for (let i = 8; i < sz - 8; i++) {
      if (!rsv[6][i]) set(6, i, !(i & 1));
      if (!rsv[i][6]) set(i, 6, !(i & 1));
    }

    // Dark module
    set(ver * 4 + 9, 8, true);

    // Reserve format info areas (will be filled later)
    for (let i = 0; i < 9; i++) { rsv[8][i] = 1; rsv[i][8] = 1; }
    for (let i = sz - 8; i < sz; i++) rsv[8][i] = 1;
    for (let i = sz - 7; i < sz; i++) rsv[i][8] = 1;

    // Reserve version info areas (v >= 7)
    if (ver >= 7)
      for (let i = 0; i < 6; i++)
        for (let j = sz - 11; j < sz - 8; j++) { rsv[i][j] = 1; rsv[j][i] = 1; }

    // Place data codewords in zigzag pattern
    let bi = 0;
    const tb = cw.length * 8;
    let col = sz - 1, up = true;
    while (col >= 0) {
      if (col === 6) { col--; continue; }
      for (let i = 0; i < sz; i++) {
        const row = up ? sz - 1 - i : i;
        for (let dx = 0; dx <= 1; dx++) {
          const cc = col - dx;
          if (cc < 0 || rsv[row][cc]) continue;
          if (bi < tb) mod[row][cc] = (cw[bi >> 3] >> (7 - (bi & 7))) & 1;
          bi++;
        }
      }
      col -= 2;
      up = !up;
    }

    return { mod, rsv, sz };
  }

  /* ===== Mask patterns ===== */
  const MF = [
    (r, c) => !((r + c) & 1),
    (r)    => !(r & 1),
    (r, c) => c % 3 === 0,
    (r, c) => (r + c) % 3 === 0,
    (r, c) => !(((r >> 1) + Math.floor(c / 3)) & 1),
    (r, c) => !((r * c) % 2 + (r * c) % 3),
    (r, c) => !(((r * c) % 2 + (r * c) % 3) & 1),
    (r, c) => !(((r + c) % 2 + (r * c) % 3) & 1),
  ];

  function applyMask(m, mi) {
    const fn = MF[mi];
    for (let r = 0; r < m.sz; r++)
      for (let c = 0; c < m.sz; c++)
        if (!m.rsv[r][c] && fn(r, c)) m.mod[r][c] ^= 1;
  }

  /* ===== Penalty scoring ===== */
  function penalty(m) {
    let s = 0;
    const n = m.sz;

    // Rule 1: runs of same color (rows & cols)
    for (let r = 0; r < n; r++) {
      let cnt = 1;
      for (let c = 1; c < n; c++) {
        if (m.mod[r][c] === m.mod[r][c - 1]) cnt++;
        else { if (cnt >= 5) s += cnt - 2; cnt = 1; }
      }
      if (cnt >= 5) s += cnt - 2;
    }
    for (let c = 0; c < n; c++) {
      let cnt = 1;
      for (let r = 1; r < n; r++) {
        if (m.mod[r][c] === m.mod[r - 1][c]) cnt++;
        else { if (cnt >= 5) s += cnt - 2; cnt = 1; }
      }
      if (cnt >= 5) s += cnt - 2;
    }

    // Rule 2: 2×2 blocks of same color
    for (let r = 0; r < n - 1; r++)
      for (let c = 0; c < n - 1; c++) {
        const v = m.mod[r][c];
        if (v === m.mod[r][c + 1] && v === m.mod[r + 1][c] && v === m.mod[r + 1][c + 1]) s += 3;
      }

    // Rule 3: finder-like patterns (1011101 0000 or reverse)
    for (let r = 0; r < n; r++)
      for (let c = 0; c <= n - 11; c++) {
        const g = (i) => m.mod[r][c + i];
        if (g(0) && !g(1) && g(2) && g(3) && g(4) && !g(5) && g(6) && !g(7) && !g(8) && !g(9) && !g(10)) s += 40;
        if (!g(0) && !g(1) && !g(2) && !g(3) && g(4) && !g(5) && g(6) && g(7) && g(8) && !g(9) && g(10)) s += 40;
      }
    for (let c = 0; c < n; c++)
      for (let r = 0; r <= n - 11; r++) {
        const g = (i) => m.mod[r + i][c];
        if (g(0) && !g(1) && g(2) && g(3) && g(4) && !g(5) && g(6) && !g(7) && !g(8) && !g(9) && !g(10)) s += 40;
        if (!g(0) && !g(1) && !g(2) && !g(3) && g(4) && !g(5) && g(6) && g(7) && g(8) && !g(9) && g(10)) s += 40;
      }

    // Rule 4: dark module proportion
    let dk = 0;
    for (let r = 0; r < n; r++) for (let c = 0; c < n; c++) dk += m.mod[r][c];
    const pct = (dk * 100) / (n * n);
    const p5 = Math.floor(pct / 5) * 5;
    s += (Math.min(Math.abs(p5 - 50), Math.abs(p5 + 5 - 50)) / 5) * 10;

    return s;
  }

  /* ===== Format information (EC level M, BCH(15,5)) ===== */
  function computeFormat(mask) {
    const d = mask; // EC level M = 00, so data = (0 << 3) | mask
    let v = d << 10;
    for (let i = 4; i >= 0; i--) if (v & (1 << (i + 10))) v ^= 0x537 << i;
    return ((d << 10) | v) ^ 0x5412;
  }

  function placeFormat(m, mask) {
    const f = computeFormat(mask);
    const n = m.sz;
    // Copy 1: around top-left finder
    const r1 = [8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0];
    const c1 = [0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8];
    // Copy 2: bottom-left + top-right
    const r2 = [n-1, n-2, n-3, n-4, n-5, n-6, n-7, 8, 8, 8, 8, 8, 8, 8, 8];
    const c2 = [8, 8, 8, 8, 8, 8, 8, n-8, n-7, n-6, n-5, n-4, n-3, n-2, n-1];
    for (let i = 0; i < 15; i++) {
      const b = (f >> i) & 1;
      m.mod[r1[i]][c1[i]] = b;
      m.mod[r2[i]][c2[i]] = b;
    }
  }

  /* ===== Version information (v >= 7, BCH(18,6)) ===== */
  function placeVersion(m, ver) {
    if (ver < 7) return;
    let v = ver << 12;
    for (let i = 5; i >= 0; i--) if (v & (1 << (i + 12))) v ^= 0x1f25 << i;
    const info = (ver << 12) | v;
    const n = m.sz;
    for (let i = 0; i < 18; i++) {
      const b = (info >> i) & 1;
      m.mod[Math.floor(i / 3)][n - 11 + (i % 3)] = b;
      m.mod[n - 11 + (i % 3)][Math.floor(i / 3)] = b;
    }
  }

  /* ===== Deep clone ===== */
  function cloneMatrix(m) {
    return {
      mod: m.mod.map((r) => Uint8Array.from(r)),
      rsv: m.rsv.map((r) => Uint8Array.from(r)),
      sz: m.sz,
    };
  }

  /* ===== Public API ===== */
  function generate(text) {
    const bytes = new TextEncoder().encode(text);
    let ver = 0;
    for (let v = 1; v <= 10; v++) if (bytes.length <= CAP[v]) { ver = v; break; }
    if (!ver) throw new Error("Text too long (max " + CAP[10] + " bytes at EC-M v1-10)");

    const data = encodeData(bytes, ver);
    const cw = fullEncode(data, ver);
    const base = buildMatrix(ver, cw);

    let bestScore = Infinity, bestM = null;
    for (let mi = 0; mi < 8; mi++) {
      const m = cloneMatrix(base);
      applyMask(m, mi);
      placeFormat(m, mi);
      placeVersion(m, ver);
      const sc = penalty(m);
      if (sc < bestScore) { bestScore = sc; bestM = m; }
    }
    return bestM;
  }

  function toCanvas(canvas, text, opts = {}) {
    const m = generate(text);
    const cell = opts.cellSize || Math.floor(Math.min(canvas.width, canvas.height) / (m.sz + 8));
    const padX = Math.floor((canvas.width - m.sz * cell) / 2);
    const padY = Math.floor((canvas.height - m.sz * cell) / 2);
    const fg = opts.foreground || "#000000";
    const bg = opts.background || "#ffffff";

    const ctx = canvas.getContext("2d");
    ctx.fillStyle = bg;
    ctx.fillRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = fg;
    for (let r = 0; r < m.sz; r++)
      for (let c = 0; c < m.sz; c++)
        if (m.mod[r][c]) ctx.fillRect(padX + c * cell, padY + r * cell, cell, cell);
  }

  return { generate, toCanvas };
})();
