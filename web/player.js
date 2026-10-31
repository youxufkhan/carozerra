import { GIFEncoder, quantize, applyPalette } from "./gifenc.esm.js";

// Bundled clip names, grouped for browsing. Animation data loads on demand.
// "movie1-10"/"6-10" etc. are the original 8 shipped with the desktop app; everything
// else (alt_*, still_*, meter_*, color_*) came from community-contributed found_discs/.
const BUILTIN_CLIPS = {
  Movies: [
    "movie1.lkd",
    "movie2.lkd",
    "movie3.lkd",
    "movie4.lkd",
    "movie5.lkd",
    "movie6.lkd",
    "movie7.lkd",
    "movie8_f.lkd",
    "movie9_f.lkd",
    "movie10_f.lkd",
    "alt_airship.lkd",
    "alt_diverdolphins.lkd",
    "alt_dragonrider.lkd",
    "alt_metropolis.lkd",
    "alt_waterboard.lkd",
    "alt_wildlife.lkd",
    "alt_wrc.lkd",
  ],
  Backgrounds: ["alt_bgv1.lkd", "alt_bgv2.lkd", "alt_bgv3.lkd", "alt_bgv4.lkd"],
  Stills: [
    "still_bgp01.lkd",
    "still_bgp02.lkd",
    "still_bgp03.lkd",
    "still_bgp04.lkd",
    "still_bgp05.lkd",
    "still_bgp06.lkd",
    "still_bgp07.lkd",
    "still_bgp08.lkd",
    "still_bgp09.lkd",
    "still_bgp_f_10.lkd",
    "still_bgp_f_11.lkd",
    "still_bgp_f_12.lkd",
    "still_bgp_f_13.lkd",
    "still_bgp_f_14.lkd",
    "still_bgp_f_15.lkd",
    "still_bgp_f_16.lkd",
    "still_bgp_f_17.lkd",
    "still_bgp_f_18.lkd",
    "alt_still_bgp_e1.lkd",
    "alt_still_bgp_e2.lkd",
    "alt_still_bgp_e3.lkd",
    "alt_still_bgp_e4.lkd",
    "alt_still_bgp_e5.lkd",
  ],
  "Level Meters": [
    "meter_level01.lkd",
    "meter_level02.lkd",
    "meter_level03.lkd",
    "meter_level04.lkd",
    "meter_level05.lkd",
    "meter_level06_f.lkd",
    "meter_level07_f.lkd",
    "alt_meter_li01.lkd",
    "alt_meter_li02.lkd",
    "alt_meter_li03.lkd",
    "alt_meter_li04.lkd",
    "alt_meter_li05.lkd",
    "alt_meter_li06.lkd",
    "alt_meter_li07.lkd",
    "alt_meter_li08.lkd",
  ],
  Color: [
    "color_01_nightcruising.lkd",
    "color_02_greatbarrierreef.lkd",
    "color_03_redplanet.lkd",
    "color_04_island.lkd",
    "color_05_firedragon.lkd",
    "color_06_racingcart.lkd",
    "color_07_motogp.lkd",
    "color_still_bgp01.lkd",
    "color_still_bgp02.lkd",
    "color_still_bgp03.lkd",
    "color_still_bgp04.lkd",
    "color_still_bgp05.lkd",
    "color_still_bgp06.lkd",
    "color_still_bgp07.lkd",
    "color_still_bgp08.lkd",
    "color_still_bgp09.lkd",
    "color_still_bgp10.lkd",
    "color_bgv01.lkd",
    "color_bgv02.lkd",
    "color_bgv03.lkd",
    "color_bgv04.lkd",
    "color_bgv05.lkd",
    "color_bgv06.lkd",
    "color_bgv07.lkd",
  ],
};
const ALL_CLIPS = Object.values(BUILTIN_CLIPS).flat();
function categoryOf(name) {
  for (const [cat, names] of Object.entries(BUILTIN_CLIPS))
    if (names.includes(name)) return cat;
  return "Custom";
}

const $ = (s) => document.querySelector(s);
const oel = $("#oel"),
  sc = $("#screen"),
  gl = $("#glow");
const sctx = sc.getContext("2d"),
  gctx = gl.getContext("2d");
sctx.imageSmoothingEnabled = false;
gctx.imageSmoothingEnabled = false;

let strip = null,
  FW = 256,
  FH = 64,
  N = 60,
  cur = 0,
  playing = true,
  fps = 16,
  lastT = 0,
  currentName = null;
const loaded = {};

function u32(dv, o) {
  return dv.getUint32(o, true);
}

async function decodeLKD(buf) {
  const dv = new DataView(buf);
  const magic = String.fromCharCode(
    dv.getUint8(0),
    dv.getUint8(1),
    dv.getUint8(2),
    dv.getUint8(3),
  );
  if (magic !== "zLKD") throw new Error("not a zLKD file");
  const frameCount = u32(dv, 16);
  const ds = new DecompressionStream("gzip");
  const tarBuf = await new Response(
    new Blob([buf.slice(20)]).stream().pipeThrough(ds),
  ).arrayBuffer();
  const t = new Uint8Array(tarBuf);
  const size = parseInt(
    new TextDecoder().decode(t.subarray(124, 136)).replace(/\0.*/, "").trim(),
    8,
  );
  const bmp = tarBuf.slice(512, 512 + size);
  return { frameCount, canvas: bmpToCanvas(bmp) };
}

function bmpToCanvas(bmp) {
  const dv = new DataView(bmp);
  const dataOff = u32(dv, 10),
    w = dv.getInt32(18, true),
    h = dv.getInt32(22, true),
    bpp = dv.getUint16(28, true);
  if (bpp !== 24) throw new Error("expected 24bpp BMP, got " + bpp);
  const bottomUp = h > 0,
    H = Math.abs(h),
    stride = (w * 3 + 3) & ~3,
    px = new Uint8Array(bmp);
  const cv = document.createElement("canvas");
  cv.width = w;
  cv.height = H;
  const ctx = cv.getContext("2d"),
    img = ctx.createImageData(w, H),
    o = img.data;
  for (let y = 0; y < H; y++) {
    const srcRow = bottomUp ? H - 1 - y : y,
      base = dataOff + srcRow * stride;
    for (let x = 0; x < w; x++) {
      const s = base + x * 3,
        d = (y * w + x) * 4;
      o[d] = px[s + 2];
      o[d + 1] = px[s + 1];
      o[d + 2] = px[s];
      o[d + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return cv;
}

function draw() {
  if (!strip) return;
  const sy = cur * FH;
  sctx.clearRect(0, 0, FW, FH);
  sctx.drawImage(strip, 0, sy, FW, FH, 0, 0, FW, FH);
  gctx.clearRect(0, 0, FW, FH);
  gctx.drawImage(strip, 0, sy, FW, FH, 0, 0, FW, FH);
  $("#scrub").value = cur;
  $("#scrubv").textContent = `${cur} / ${N - 1}`;
}
function tick(ts) {
  requestAnimationFrame(tick);
  if (!playing || !strip) return;
  if (ts - lastT >= 1000 / fps) {
    lastT = ts;
    cur = (cur + 1) % N;
    draw();
  }
}

function useStrip(canvas, frameCount, name) {
  strip = canvas;
  N = frameCount;
  FH = canvas.height / frameCount;
  FW = canvas.width;
  currentName = name;
  sc.width = gl.width = FW;
  sc.height = gl.height = FH;
  sctx.imageSmoothingEnabled = gctx.imageSmoothingEnabled = false;
  cur = 0;
  $("#scrub").max = N - 1;
  $("#meta").textContent = `${name} · ${FW} × ${FH} · ${N} frames`;
  $("#currentTitle").textContent = prettyName(name);
  oel.setAttribute("aria-label", `${prettyName(name)} animation`);
  $("#status").textContent = "";
  setActiveChip(name);
  draw();
}

async function loadBuf(buf, name) {
  try {
    const { frameCount, canvas } = await decodeLKD(buf);
    loaded[name] = { canvas, frameCount, category: categoryOf(name) };
    return true;
  } catch (e) {
    $("#status").textContent =
      "Could not open " +
      name +
      ": " +
      e.message +
      (typeof DecompressionStream === "undefined"
        ? " (browser lacks DecompressionStream — use Chrome/Firefox)"
        : "");
    return false;
  }
}
async function loadFile(file, seq) {
  if (exportBusy) return;
  try {
    const buf = await file.arrayBuffer();
    if (exportBusy) return;
    // A custom file with a built-in name must not replace a bundled clip.
    let name = file.name;
    if (ALL_CLIPS.includes(name)) name = `custom_${name}`;
    if (await loadBuf(buf, name)) {
      if (exportBusy) return;
      loaded[name].category = "Custom";
      if (seq === selectSeq) {
        requestedName = name;
        const L = loaded[name];
        useStrip(L.canvas, L.frameCount, name);
        showCategory("Custom");
      } else {
        showCategory(browsedCategory);
      }
    }
  } catch (err) {
    $("#status").textContent = `Could not open ${file.name}: ${err.message}`;
  }
}

// Posters are small separate images. Full clips load only when selected or
// deliberately previewed, sharing one cache and one in-flight request per clip.
const pending = {};
function ensureLoaded(name) {
  if (loaded[name]) return Promise.resolve(loaded[name]);
  if (!pending[name]) {
    pending[name] = fetch(`clips/${name}`)
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`);
        return r.arrayBuffer();
      })
      .then(decodeLKD)
      .then(
        ({ frameCount, canvas }) =>
          (loaded[name] = { canvas, frameCount, category: categoryOf(name) }),
      )
      .catch((err) => {
        delete pending[name];
        throw err;
      }); // drop it so a later attempt can retry
  }
  return pending[name];
}

let selectSeq = 0,
  requestedName = null;
async function selectClip(name) {
  if (exportBusy) return;
  const seq = ++selectSeq;
  requestedName = name;
  $("#status").textContent = `Loading ${prettyName(name)}…`;
  try {
    const L = await ensureLoaded(name);
    if (seq !== selectSeq || exportBusy) return;
    useStrip(L.canvas, L.frameCount, name);
  } catch (err) {
    if (seq === selectSeq) {
      requestedName = currentName;
      $("#status").textContent =
        `Could not load ${name}: ${err.message}. Choose another clip or try again.`;
      setActiveChip(currentName);
    }
  }
}

// drop the category prefix (the section heading already says it) and .lkd, so
// "color_01_nightcruising.lkd" captions as "01 nightcruising" — full name stays in the tooltip
const prettyName = (name) =>
  name
    .replace(/\.lkd$/i, "")
    .replace(/^(alt|still|meter|color)_/, "")
    .replace(/_/g, " ")
    .replace(/^movie(\d+)/, "Movie $1")
    .replace(/^./, (s) => s.toUpperCase());

const chipEls = {};
function setActiveChip(active) {
  for (const [n, el] of Object.entries(chipEls)) {
    const selected = n === active;
    el.classList.toggle("active", selected);
    el.setAttribute("aria-pressed", String(selected));
    el.querySelector(".playing-tag").hidden = !selected;
  }
}

// The tile exists before its clip does — caption and click target are available
// straight away, and hydrateChip() fills in the artwork once the data arrives.
function makeChip(name) {
  const el = document.createElement("button");
  el.type = "button";
  el.disabled = exportBusy;
  el.className = "chip pending";
  el.dataset.name = name;
  el.title = name;
  const cv = document.createElement("canvas");
  cv.className = "thumb";
  cv.width = 124;
  cv.height = 34; // placeholder box, replaced at hydration
  const cap = document.createElement("span");
  cap.className = "cap";
  const label = document.createElement("span");
  label.className = "clip-name";
  label.textContent = prettyName(name);
  const tag = document.createElement("span");
  tag.className = "playing-tag";
  tag.textContent = "Playing";
  tag.hidden = true;
  cap.append(label, tag);
  cv.setAttribute("aria-hidden", "true");
  el.setAttribute("aria-label", `${prettyName(name)} (${name})`);
  el.append(cv, cap);
  el.onclick = () => selectClip(name);
  return el;
}

function hydrateChip(el) {
  const name = el.dataset.name,
    cv = el.querySelector("canvas.thumb");
  const ctx = cv.getContext("2d");
  cv.width = 256;
  cv.height = 64;
  ctx.imageSmoothingEnabled = false;
  let poster = null,
    clip = null,
    raf = null,
    previewWanted = false;
  function drawPoster() {
    ctx.clearRect(0, 0, cv.width, cv.height);
    if (poster) ctx.drawImage(poster, 0, 0, cv.width, cv.height);
  }
  function fail() {
    el.classList.remove("pending");
    el.classList.add("failed");
    el.title = `${name}: preview unavailable. Select to try playing.`;
  }
  if (ALL_CLIPS.includes(name)) {
    const img = new Image();
    img.onload = () => {
      poster = img;
      el.classList.remove("pending");
      if (!raf) drawPoster();
    };
    img.onerror = fail;
    img.src = `previews/${name.replace(/\.lkd$/i, ".png")}`;
  } else {
    clip = loaded[name];
    const fh = clip.canvas.height / clip.frameCount;
    cv.width = clip.canvas.width;
    cv.height = fh;
    ctx.imageSmoothingEnabled = false;
    const frame = Math.floor(clip.frameCount / 2);
    poster = document.createElement("canvas");
    poster.width = cv.width;
    poster.height = fh;
    poster
      .getContext("2d")
      .drawImage(clip.canvas, 0, frame * fh, cv.width, fh, 0, 0, cv.width, fh);
    el.classList.remove("pending");
    drawPoster();
  }
  async function startPreview() {
    if (
      previewWanted ||
      matchMedia("(prefers-reduced-motion: reduce)").matches ||
      !matchMedia("(hover: hover)").matches
    )
      return;
    previewWanted = true;
    try {
      clip = clip || (await ensureLoaded(name));
      if (!previewWanted || !el.isConnected || clip.frameCount < 2) return;
      let frame = 0,
        last = 0;
      const fh = clip.canvas.height / clip.frameCount;
      cv.width = clip.canvas.width;
      cv.height = fh;
      ctx.imageSmoothingEnabled = false;
      const step = (ts) => {
        if (!previewWanted || !el.isConnected) {
          stopPreview();
          return;
        }
        if (ts - last >= 1000 / 12) {
          last = ts;
          frame = (frame + 1) % clip.frameCount;
          ctx.clearRect(0, 0, cv.width, fh);
          ctx.drawImage(
            clip.canvas,
            0,
            frame * fh,
            cv.width,
            fh,
            0,
            0,
            cv.width,
            fh,
          );
        }
        raf = requestAnimationFrame(step);
      };
      raf = requestAnimationFrame(step);
    } catch {
      previewWanted = false;
    }
  }
  function stopPreview() {
    previewWanted = false;
    if (raf) {
      cancelAnimationFrame(raf);
      raf = null;
    }
    drawPoster();
  }
  el.onmouseenter = startPreview;
  el.onfocus = startPreview;
  el.onmouseleave = stopPreview;
  el.onblur = stopPreview;
  el.stopPreview = stopPreview;
}

// Fetch posters only when their tiles enter the viewport.
const chipObserver = new IntersectionObserver(
  (entries, obs) => {
    for (const e of entries)
      if (e.isIntersecting) {
        obs.unobserve(e.target);
        hydrateChip(e.target);
      }
  },
  { rootMargin: "0px" },
);

let browsedCategory = "Movies";
function categories() {
  const custom = Object.keys(loaded).filter(
    (n) => loaded[n].category === "Custom",
  );
  return [
    ...Object.entries(BUILTIN_CLIPS),
    ...(custom.length ? [["Custom", custom]] : []),
  ];
}
function showCategory(category) {
  browsedCategory = category;
  const nav = $("#categories");
  nav.replaceChildren();
  for (const [cat] of categories()) {
    const button = document.createElement("button");
    button.type = "button";
    button.textContent = cat;
    button.setAttribute("aria-pressed", String(cat === category));
    button.onclick = () => {
      showCategory(cat);
      [...nav.children].find((el) => el.textContent === cat)?.focus();
    };
    nav.append(button);
  }
  renderChips(currentName);
}
function renderChips(active) {
  const box = $("#files");
  box.innerHTML = "";
  chipObserver.disconnect();
  for (const [k, el] of Object.entries(chipEls)) {
    el.stopPreview?.();
    delete chipEls[k];
  }
  box.setAttribute("aria-label", `${browsedCategory} animations`);
  const names =
    categories().find(([cat]) => cat === browsedCategory)?.[1] || [];
  for (const name of names) {
    const el = makeChip(name);
    chipEls[name] = el;
    box.append(el);
    chipObserver.observe(el);
  }
  setActiveChip(active);
}
function navigateClip(delta) {
  if (exportBusy) return;
  const names = categories().flatMap(([, names]) => names);
  const i = names.indexOf(requestedName || currentName);
  const name = names[(Math.max(0, i) + delta + names.length) % names.length];
  const category = loaded[name]?.category || categoryOf(name);
  if (category !== browsedCategory) showCategory(category);
  selectClip(name);
}

// ---- export: GIF (baked glow) + WebM recording, sharing one glow-baking routine ----
const EXPORT_SCALE = 4; // matches decode.py's default --scale 4
let upCv, blurCv;
function ensureScratch(w, h) {
  if (!upCv) {
    upCv = document.createElement("canvas");
    blurCv = document.createElement("canvas");
  }
  if (upCv.width !== w || upCv.height !== h) {
    upCv.width = blurCv.width = w;
    upCv.height = blurCv.height = h;
  }
}
// port of decode.py's apply_glow(): nearest-neighbor upscale, then (if glowOn) a
// blurred+brightened copy screen-blended over it, plus per-row scanline darkening.
function bakeFrame(outCtx, frameIdx, glowOn, scale = EXPORT_SCALE) {
  const outW = FW * scale,
    outH = FH * scale;
  ensureScratch(outW, outH);
  const upCtx = upCv.getContext("2d");
  upCtx.imageSmoothingEnabled = false;
  upCtx.clearRect(0, 0, outW, outH);
  upCtx.drawImage(strip, 0, frameIdx * FH, FW, FH, 0, 0, outW, outH);

  outCtx.clearRect(0, 0, outW, outH);
  if (!glowOn) {
    outCtx.drawImage(upCv, 0, 0);
    return;
  }

  const blurCtx = blurCv.getContext("2d");
  const radius = Math.max(1.5, scale * 0.9);
  blurCtx.clearRect(0, 0, outW, outH);
  blurCtx.filter = `blur(${radius}px) brightness(135%)`;
  blurCtx.drawImage(upCv, 0, 0);
  blurCtx.filter = "none";

  outCtx.globalCompositeOperation = "source-over";
  outCtx.drawImage(upCv, 0, 0);
  outCtx.globalCompositeOperation = "screen";
  outCtx.drawImage(blurCv, 0, 0);
  outCtx.globalCompositeOperation = "source-over";

  if (scale >= 2) {
    outCtx.fillStyle = "rgba(0,0,0,0.45)"; // alpha-composited black@0.45 == src*0.55, matches decode.py's scanline darken
    for (let y = 0; y < outH; y += scale) outCtx.fillRect(0, y, outW, 1);
  }
}

function downloadBlob(blob, filename) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

let exportBusy = false;
function setExportBusy(busy) {
  exportBusy = busy;
  if (busy) {
    ++selectSeq;
    requestedName = currentName;
  }
  for (const selector of [
    "#expGif",
    "#play",
    "#scrub",
    "#fps",
    "#fx",
    "#previous",
    "#next",
    "#file",
    "[data-open-file]",
    "#files button",
  ]) {
    document.querySelectorAll(selector).forEach((el) => (el.disabled = busy));
  }
  $("#expWebm").disabled = busy || !webmSupported;
  $("#files").setAttribute("aria-busy", String(busy));
}

async function exportGif() {
  if (!strip) return;
  setExportBusy(true);
  try {
    const glowOn = $("#fx").checked,
      stem = (currentName || "clip").replace(/\.lkd$/i, "");
    const scale = EXPORT_SCALE,
      outW = FW * scale,
      outH = FH * scale,
      delay = Math.round(1000 / fps);
    const bakeCv = document.createElement("canvas");
    bakeCv.width = outW;
    bakeCv.height = outH;
    const bakeCtx = bakeCv.getContext("2d");

    const frames = [];
    for (let i = 0; i < N; i++) {
      bakeFrame(bakeCtx, i, glowOn, scale);
      frames.push(bakeCtx.getImageData(0, 0, outW, outH).data);
      $("#expStatus").textContent = `encoding… ${i + 1}/${N}`;
      await new Promise((r) => requestAnimationFrame(r));
    }

    // one shared palette, sampled from every 5th frame — avoids inter-frame flicker
    // and bounds memory (no need to concat every frame for these simple, low-color clips)
    const sampleIdx = [];
    for (let i = 0; i < frames.length; i += 5) sampleIdx.push(i);
    const sampleBuf = new Uint8Array(sampleIdx.length * outW * outH * 4);
    let off = 0;
    for (const i of sampleIdx) {
      sampleBuf.set(frames[i], off);
      off += frames[i].length;
    }
    const palette = quantize(sampleBuf, 256);

    const gif = GIFEncoder();
    for (let i = 0; i < frames.length; i++) {
      const index = applyPalette(frames[i], palette);
      gif.writeFrame(index, outW, outH, { palette, delay, repeat: 0 });
      $("#expStatus").textContent = `writing… ${i + 1}/${N}`;
    }
    gif.finish();
    downloadBlob(
      new Blob([gif.bytes()], { type: "image/gif" }),
      `${stem}_${glowOn ? "glow" : "clean"}.gif`,
    );
    $("#expStatus").textContent = "GIF saved.";
  } finally {
    setExportBusy(false);
  }
}

function pickWebmMime() {
  if (!window.MediaRecorder) return null;
  return ["video/webm;codecs=vp9", "video/webm;codecs=vp8", "video/webm"].find(
    (m) => MediaRecorder.isTypeSupported(m),
  );
}

async function exportWebm() {
  if (!strip) return;
  const mime = pickWebmMime();
  if (!mime) {
    $("#expStatus").textContent =
      "⚠ WebM recording not supported in this browser";
    return;
  }

  setExportBusy(true);
  const wasPlaying = playing;
  playing = false;
  try {
    const glowOn = $("#fx").checked,
      stem = (currentName || "clip").replace(/\.lkd$/i, "");
    const scale = EXPORT_SCALE,
      outW = FW * scale,
      outH = FH * scale;
    const recCv = document.createElement("canvas");
    recCv.width = outW;
    recCv.height = outH;
    const recCtx = recCv.getContext("2d");
    bakeFrame(recCtx, 0, glowOn, scale);

    const stream = recCv.captureStream(fps);
    const chunks = [];
    const rec = new MediaRecorder(stream, { mimeType: mime });
    rec.ondataavailable = (e) => {
      if (e.data.size) chunks.push(e.data);
    };
    const stopped = new Promise((resolve) => {
      rec.onstop = resolve;
    });

    rec.start();
    const tickMs = 1000 / fps;
    await new Promise((resolve) => {
      let recCur = 0;
      const step = () => {
        recCur++;
        $("#expStatus").textContent =
          `recording… ${(recCur / fps).toFixed(1)}s`;
        if (recCur >= N) {
          setTimeout(() => {
            rec.stop();
            resolve();
          }, tickMs);
          return;
        } // one extra tick so captureStream's sampler catches the last frame
        bakeFrame(recCtx, recCur, glowOn, scale);
        setTimeout(step, tickMs);
      };
      setTimeout(step, tickMs);
    });
    await stopped;

    downloadBlob(
      new Blob(chunks, { type: "video/webm" }),
      `${stem}_${glowOn ? "glow" : "clean"}.webm`,
    );
    $("#expStatus").textContent = "WebM saved.";
  } finally {
    playing = wasPlaying;
    setExportBusy(false);
  }
}

const webmSupported = !!(
  window.MediaRecorder &&
  HTMLCanvasElement.prototype.captureStream &&
  pickWebmMime()
);
if (!webmSupported) {
  $("#expWebm").disabled = true;
  $("#webmSupport").textContent =
    "WebM is unavailable in this browser. Use GIF instead.";
}
$("#expGif").onclick = () =>
  exportGif().catch((e) => {
    console.error(e);
    $("#expStatus").textContent = "⚠ export failed: " + e.message;
    setExportBusy(false);
  });
$("#expWebm").onclick = () =>
  exportWebm().catch((e) => {
    console.error(e);
    $("#expStatus").textContent = "⚠ export failed: " + e.message;
    setExportBusy(false);
  });

function updatePlayButton() {
  $("#playLabel").textContent = playing ? "Pause" : "Play";
  $("#play use").setAttribute("href", playing ? "#icon-pause" : "#icon-play");
}
$("#play").onclick = () => {
  playing = !playing;
  updatePlayButton();
};
$("#fps").oninput = (e) => {
  fps = +e.target.value;
  $("#fpsv").textContent = fps + " fps";
};
$("#fx").onchange = (e) => oel.classList.toggle("fx", e.target.checked);
$("#scrub").oninput = (e) => {
  cur = +e.target.value;
  playing = false;
  updatePlayButton();
  draw();
};
$("#previous").onclick = () => navigateClip(-1);
$("#next").onclick = () => navigateClip(1);
$("#viewToggle").onclick = (e) => {
  const full = $("#stage").classList.toggle("full-stereo");
  e.currentTarget.querySelector("span").textContent = full
    ? "View animation"
    : "View full stereo";
  e.currentTarget.setAttribute("aria-pressed", String(full));
};

// ←/→ step through the gallery. ↑/↓ are reserved for prev/next unit skin (ROADMAP) —
// unimplemented until more than one faceplate exists.
const isFormEl = (el) =>
  !!el &&
  (/^(INPUT|SELECT|TEXTAREA)$/.test(el.tagName) || el.isContentEditable);
document.addEventListener("keydown", (e) => {
  const d = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
  if (!d || e.ctrlKey || e.metaKey || e.altKey) return;
  if (isFormEl(e.target) || isFormEl(document.activeElement)) return; // the Speed/Frame sliders answer arrows natively — never steal them
  if (exportBusy) return; // swapping strip/FW/FH mid-export would corrupt it
  if (
    document.querySelector("dialog[open]") ||
    e.target.closest("details, #categories")
  )
    return;
  e.preventDefault();
  navigateClip(d);
});

// ---- android download --------------------------------------------------------
// The APK ships in prereleases, which /releases/latest skips, so point the button
// at the newest release page that actually has one. Falls back to /releases.
const androidDl = $("#androidDl");
fetch("https://api.github.com/repos/youxufkhan/carozerra/releases?per_page=10")
  .then((r) => (r.ok ? r.json() : []))
  .then((rs) => {
    const r = rs.find((r) =>
      r.assets.some((a) => a.name.endsWith("_android.apk")),
    );
    if (r) {
      androidDl.href = r.html_url;
      $("[data-android]").href = r.html_url;
    }
  })
  .catch(() => {});
androidDl.addEventListener("click", () => {
  if (window.gtag)
    gtag("event", "android_download", { link_url: androidDl.href });
});
$("[data-android]").addEventListener("click", (e) => {
  if (window.gtag)
    gtag("event", "android_download", { link_url: e.currentTarget.href });
});
document.querySelectorAll("#support, [data-support]").forEach((link) =>
  link.addEventListener("click", (e) => {
    if (window.gtag)
      gtag("event", "support_click", { link_url: e.currentTarget.href });
  }),
);

// ---- changelog modal -------------------------------------------------------
// CHANGELOG.md is fetched at click time rather than duplicated into this page,
// so it can't drift from the file we actually maintain. It's staged into web/
// at deploy time alongside the clips (see .github/workflows/pages.yml).
const CHANGELOG_URL =
  "https://github.com/youxufkhan/carozerra/blob/main/CHANGELOG.md";

const safeHref = (u) => {
  try {
    const p = new URL(u, location.href).protocol;
    return p === "http:" || p === "https:" || p === "mailto:" ? u : "#";
  } catch {
    return "#";
  }
};

function renderInline(text, parent) {
  const re = /`([^`]+)`|\[([^\]]+)\]\(([^)\s]+)\)/g;
  let last = 0,
    m;
  while ((m = re.exec(text))) {
    if (m.index > last) parent.append(text.slice(last, m.index));
    if (m[1] != null) {
      const c = document.createElement("code");
      c.textContent = m[1];
      parent.append(c);
    } else {
      const a = document.createElement("a");
      a.href = safeHref(m[3]);
      a.textContent = m[2];
      a.target = "_blank";
      a.rel = "noopener noreferrer";
      parent.append(a);
    }
    last = re.lastIndex;
  }
  if (last < text.length) parent.append(text.slice(last));
}

// just enough markdown for our own changelog — headings, bullet lists, paragraphs,
// inline code and links — built as DOM nodes, never innerHTML
function mdToDom(md) {
  const frag = document.createDocumentFragment();
  let ul = null,
    para = [];
  const flushPara = () => {
    if (!para.length) return;
    const p = document.createElement("p");
    renderInline(para.join(" "), p);
    frag.append(p);
    para = [];
  };
  for (const raw of md.split("\n")) {
    const line = raw.trim();
    const h = /^(#{1,6})\s+(.*)$/.exec(line),
      li = /^[-*]\s+(.*)$/.exec(line);
    if (li) {
      flushPara();
      if (!ul) {
        ul = document.createElement("ul");
        frag.append(ul);
      }
      const el = document.createElement("li");
      renderInline(li[1], el);
      ul.append(el);
      continue;
    }
    ul = null;
    if (h) {
      flushPara();
      if (h[1].length === 1) continue; // the file's own "# Changelog" — the modal header already says it
      const el = document.createElement("h" + Math.min(h[1].length + 1, 6)); // demote one level: the page's <h1> is the title
      renderInline(h[2], el);
      frag.append(el);
      continue;
    }
    if (!line) {
      flushPara();
      continue;
    }
    para.push(line);
  }
  flushPara();
  return frag;
}

const clog = $("#clog"),
  clogBody = $("#clogBody");
let clogLoaded = false;

async function openChangelog() {
  clog.showModal();
  $("#clogClose").focus();
  if (clogLoaded) return;
  clogBody.textContent = "loading…";
  try {
    const r = await fetch("CHANGELOG.md", { cache: "no-cache" });
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    const md = await r.text();
    clogBody.textContent = "";
    clogBody.append(mdToDom(md));
    clogLoaded = true;
  } catch (err) {
    // absent on a bare file:// open, or a local preview that skipped the copy step
    clogBody.textContent = "";
    const p = document.createElement("p");
    const a = document.createElement("a");
    a.href = CHANGELOG_URL;
    a.target = "_blank";
    a.rel = "noopener noreferrer";
    a.textContent = "Read it on GitHub";
    p.append(`Couldn't load the changelog here (${err.message}). `, a, ".");
    clogBody.append(p);
  }
}
const closeChangelog = () => clog.close();

$("#clogOpen").onclick = openChangelog;
$("#clogClose").onclick = closeChangelog;
clog.onclick = (e) => {
  if (e.target === clog) closeChangelog();
}; // backdrop only, not the sheet
clog.addEventListener("close", () => $("#clogOpen").focus());
const about = $("#about");
let aboutOpener = null;
document.querySelectorAll("[data-about]").forEach(
  (button) =>
    (button.onclick = () => {
      aboutOpener = button.closest("#mobileMenu")
        ? $("#mobileMenu > summary")
        : button;
      $("#mobileMenu").open = false;
      about.showModal();
    }),
);
about.addEventListener("close", () => aboutOpener?.focus());
$("[data-close-about]").onclick = () => about.close();
about.onclick = (e) => {
  if (e.target === about) about.close();
};
for (const details of document.querySelectorAll("details")) {
  details.addEventListener("keydown", (e) => {
    if (e.key === "Escape") {
      details.open = false;
      details.querySelector("summary").focus();
      e.preventDefault();
    }
  });
  details.addEventListener("toggle", () => {
    if (details.open) {
      document.querySelectorAll("details").forEach((other) => {
        if (other !== details) other.open = false;
      });
    }
  });
}
document.addEventListener("click", (e) => {
  for (const details of document.querySelectorAll("details[open]"))
    if (!details.contains(e.target)) details.open = false;
});

const drop = $("#drop"),
  fileInput = $("#file");
document.querySelectorAll("[data-open-file]").forEach(
  (button) =>
    (button.onclick = () => {
      if (!exportBusy) fileInput.click();
    }),
);
async function loadFiles(files) {
  if (exportBusy) return;
  const seq = ++selectSeq;
  for (const file of files) await loadFile(file, seq);
  fileInput.value = "";
}
fileInput.onchange = (e) => loadFiles([...e.target.files]);
["dragover", "dragenter"].forEach((ev) =>
  drop.addEventListener(ev, (e) => {
    e.preventDefault();
    drop.classList.add("over");
  }),
);
["dragleave", "drop"].forEach((ev) =>
  drop.addEventListener(ev, (e) => {
    e.preventDefault();
    drop.classList.remove("over");
  }),
);
drop.addEventListener("drop", (e) => loadFiles([...e.dataTransfer.files]));

requestAnimationFrame(tick);

// paint the gallery immediately, then fetch only the first clip — the rest
// arrive as their tiles scroll into view (see chipObserver)
showCategory("Movies");
selectClip(ALL_CLIPS[0]);
