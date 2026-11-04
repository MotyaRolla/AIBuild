#!/usr/bin/env node
/**
 * Screenshot a live-view URL with headless Chrome over CDP.
 *
 * Chrome's --screenshot/--virtual-time-budget never fires on this page because the page holds an open
 * SSE connection forever, so the load event never "settles". Here the page is driven explicitly:
 * navigate, wait a fixed wall-clock time, capture.
 *
 *   node shot.mjs http://127.0.0.1:8787/live/<token>/ /tmp/live-shot.png [waitMs] [width] [height]
 */
import { spawn } from "node:child_process";
import { writeFileSync, mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const [url, out = "/tmp/live-shot.png", waitMs = "6000", width = "1600", height = "1000"] = process.argv.slice(2);
if (!url) {
  console.error("usage: node shot.mjs <url> [out.png] [waitMs] [width] [height]");
  process.exit(2);
}

const CHROME = process.env.CHROME || "/opt/google/chrome/chrome";
const port = 9222 + Math.floor(Math.random() * 500);
const profile = mkdtempSync(join(tmpdir(), "live-shot-"));

const chrome = spawn(CHROME, [
  "--headless=new",
  "--disable-gpu",
  "--no-sandbox",
  "--hide-scrollbars",
  "--disable-dev-shm-usage",
  "--force-color-profile=srgb",
  "--remote-debugging-port=" + port,
  "--user-data-dir=" + profile,
  "--window-size=" + width + "," + height,
  "about:blank",
], { stdio: ["ignore", "ignore", "pipe"] });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function wsUrl() {
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch("http://127.0.0.1:" + port + "/json/version");
      const j = await res.json();
      if (j.webSocketDebuggerUrl) return j.webSocketDebuggerUrl;
    } catch { /* chrome is still starting */ }
    await sleep(100);
  }
  throw new Error("chrome did not open a debugging port");
}

/** Minimal CDP client: send, resolve on the matching id, ignore events. */
function client(socket) {
  let id = 0;
  const pending = new Map();
  const events = [];
  socket.addEventListener("message", (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { resolve, reject } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
    } else if (msg.method) {
      events.push(msg);
    }
  });
  return {
    events,
    send(method, params = {}, sessionId) {
      const mid = ++id;
      return new Promise((resolve, reject) => {
        pending.set(mid, { resolve, reject });
        socket.send(JSON.stringify({ id: mid, method, params, sessionId }));
        setTimeout(() => {
          if (pending.has(mid)) {
            pending.delete(mid);
            reject(new Error("timeout: " + method));
          }
        }, 30000);
      });
    },
  };
}

const main = async () => {
  const ws = new WebSocket(await wsUrl());
  await new Promise((res, rej) => {
    ws.addEventListener("open", res, { once: true });
    ws.addEventListener("error", rej, { once: true });
  });
  const cdp = client(ws);

  const { targetInfos } = await cdp.send("Target.getTargets");
  const page = targetInfos.find((t) => t.type === "page");
  const { sessionId } = await cdp.send("Target.attachToTarget", { targetId: page.targetId, flatten: true });

  await cdp.send("Page.enable", {}, sessionId);
  await cdp.send("Runtime.enable", {}, sessionId);
  await cdp.send("Log.enable", {}, sessionId);
  await cdp.send("Emulation.setDeviceMetricsOverride",
    { width: +width, height: +height, deviceScaleFactor: 1, mobile: false }, sessionId);

  await cdp.send("Page.navigate", { url }, sessionId);
  await sleep(+waitMs);

  // Surface page errors in the terminal, so a broken script is visible and not just a blank shot.
  const errors = cdp.events
    .filter((e) => e.method === "Runtime.exceptionThrown")
    .map((e) => e.params.exceptionDetails?.exception?.description || e.params.exceptionDetails?.text);
  if (errors.length) console.error("page errors:\n" + errors.join("\n---\n"));

  const { result } = await cdp.send("Runtime.evaluate", {
    expression: `JSON.stringify({
      title: document.title,
      conn: document.getElementById('connText')?.textContent,
      stage: document.getElementById('stageTitle')?.textContent,
      pct: document.getElementById('stagePct')?.textContent,
      stats: document.getElementById('stats')?.textContent,
      parts: [...document.querySelectorAll('.part')].map(p => p.textContent.trim()),
      codeLines: (document.getElementById('code')?.textContent || '').split('\\n').length,
      codeHead: (document.getElementById('code')?.textContent || '').split('\\n').slice(0, 3),
      timeline: [...document.querySelectorAll('#timeline .row .name')].map(n => n.textContent),
      log: [...document.querySelectorAll('#log .ln')].map(n => n.textContent).slice(-6),
      empty: getComputedStyle(document.getElementById('empty')).display,
      canvas: (() => { const c = document.getElementById('scene'); return c ? c.width + 'x' + c.height : 'none'; })(),
      painted: (() => {
        // is anything actually drawn? sample the centre of the canvas for non-background pixels
        const c = document.getElementById('scene');
        if (!c) return 0;
        const g = c.getContext('2d');
        const d = g.getImageData(0, 0, c.width, c.height).data;
        let n = 0;
        for (let i = 0; i < d.length; i += 40) if (d[i] > 26 || d[i+1] > 30 || d[i+2] > 36) n++;
        return n;
      })(),
    })`,
    returnByValue: true,
  }, sessionId);
  console.log(result.value);

  const shot = await cdp.send("Page.captureScreenshot", { format: "png", captureBeyondViewport: false }, sessionId);
  writeFileSync(out, Buffer.from(shot.data, "base64"));
  console.error("wrote " + out);

  chrome.kill("SIGKILL");
  process.exit(0);
};

main().catch((e) => {
  console.error("failed: " + e.message);
  chrome.kill("SIGKILL");
  process.exit(1);
});
