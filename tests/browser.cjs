"use strict";
// Headless system Chromium over the DevTools protocol (Node built-ins only), serving fixture pages
// under the site's origin. Real Chromium focus, layout and key events; not proof of Android
// WebView, the TV keyboard or the live site. CHROMIUM=/path/to/chromium overrides /usr/bin/chromium.
const test = require("node:test");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");

const ORIGIN = "https://plus.thegreatcourses.com";
const CHROMIUM = process.env.CHROMIUM || "/usr/bin/chromium";
const ASSET_DIR = path.join(__dirname, "..", "app", "src", "main", "assets");

const KEYS = {
  Enter: { code: "Enter", windowsVirtualKeyCode: 13, text: "\r" },
  ArrowLeft: { code: "ArrowLeft", windowsVirtualKeyCode: 37 },
  ArrowUp: { code: "ArrowUp", windowsVirtualKeyCode: 38 },
  ArrowRight: { code: "ArrowRight", windowsVirtualKeyCode: 39 },
  ArrowDown: { code: "ArrowDown", windowsVirtualKeyCode: 40 },
};

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function connect(url) {
  const ws = new WebSocket(url);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let nextId = 1;
  const pending = new Map();
  const handlers = new Map();
  ws.onmessage = (message) => {
    const data = JSON.parse(message.data);
    if (data.id && pending.has(data.id)) {
      const { resolve, reject } = pending.get(data.id);
      pending.delete(data.id);
      if (data.error) reject(new Error(JSON.stringify(data.error)));
      else resolve(data.result);
    } else if (data.method) {
      for (const handler of handlers.get(data.method) || []) handler(data.params);
    }
  };
  const on = (method, handler) => handlers.set(method, [...(handlers.get(method) || []), handler]);
  return {
    on,
    once: (method, timeoutMs = 5000) => new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error(`timed out waiting for ${method}`)), timeoutMs);
      const handler = (params) => {
        clearTimeout(timer);
        handlers.set(method, handlers.get(method).filter((h) => h !== handler));
        resolve(params);
      };
      on(method, handler);
    }),
    send: (method, params = {}) => new Promise((resolve, reject) => {
      const id = nextId++;
      pending.set(id, { resolve, reject });
      ws.send(JSON.stringify({ id, method, params }));
    }),
    close: () => ws.close(),
  };
}

/**
 * Registers browser start/stop hooks for the calling test file and returns helpers.
 * `pages` maps site-origin paths to HTML; `assets` are injected in order after each load,
 * like the host does.
 */
function setup({ pages, assets }) {
  const sources = assets.map((name) => fs.readFileSync(path.join(ASSET_DIR, name), "utf8"));
  let browser;
  let userDataDir;
  let cdp;

  async function start() {
    userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), "courses-tv-test-"));
    browser = spawn(CHROMIUM, [
      "--headless=new", "--remote-debugging-port=0", `--user-data-dir=${userDataDir}`,
      "--no-first-run", "--no-default-browser-check", "--window-size=960,540",
      // Back must reload the document as observed in the TV WebView, not restore it from bfcache.
      "--disable-features=BackForwardCache",
      // D-pad keys move focus spatially, as in the TV WebView.
      "--enable-spatial-navigation", "about:blank",
    ], { stdio: ["ignore", "ignore", "pipe"] });
    const wsUrl = await new Promise((resolve, reject) => {
      let stderr = "";
      const timer = setTimeout(() => reject(new Error(`Chromium did not start:\n${stderr}`)), 15000);
      browser.stderr.on("data", (chunk) => {
        stderr += chunk;
        const match = /DevTools listening on (ws:\/\/\S+)/.exec(stderr);
        if (match) { clearTimeout(timer); resolve(match[1]); }
      });
      browser.on("exit", (code) => reject(new Error(`Chromium exited ${code}:\n${stderr}`)));
    });
    const port = new URL(wsUrl).port;
    const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
    cdp = await connect(targets.find((t) => t.type === "page").webSocketDebuggerUrl);
    await cdp.send("Fetch.enable", { patterns: [{ urlPattern: "*" }] });
    cdp.on("Fetch.requestPaused", ({ requestId, request }) => {
      const url = new URL(request.url);
      const body = url.origin === ORIGIN ? pages[url.pathname] : undefined;
      if (body === undefined) {
        cdp.send("Fetch.failRequest", { requestId, errorReason: "BlockedByClient" });
        return;
      }
      cdp.send("Fetch.fulfillRequest", {
        requestId,
        responseCode: 200,
        // no-store keeps the page out of the back/forward cache, like the live Magento pages.
        responseHeaders: [{ name: "Content-Type", value: "text/html" }, { name: "Cache-Control", value: "no-store" }],
        body: Buffer.from(body).toString("base64"),
      });
    });
    // A foreground TV app has page focus; without this, focus/focusin events never fire headless.
    await cdp.send("Emulation.setFocusEmulationEnabled", { enabled: true });
    await cdp.send("Page.enable");
    await cdp.send("Runtime.enable");
  }

  async function evaluate(expression) {
    const { result, exceptionDetails } = await cdp.send("Runtime.evaluate", {
      expression, returnByValue: true, awaitPromise: true,
    });
    if (exceptionDetails) throw new Error(exceptionDetails.exception?.description || exceptionDetails.text);
    return result.value;
  }

  // Like the host: inject the assets once the new document has loaded.
  async function settleAndInject() {
    await cdp.once("Page.loadEventFired");
    for (const source of sources) await evaluate(source);
    await delay(50);
  }

  async function open(pathname) {
    const loaded = settleAndInject();
    await cdp.send("Page.navigate", { url: ORIGIN + pathname });
    await loaded;
  }

  async function press(key) {
    const info = KEYS[key];
    await cdp.send("Input.dispatchKeyEvent", { type: "keyDown", key, ...info });
    await cdp.send("Input.dispatchKeyEvent", { type: "keyUp", key, code: info.code, windowsVirtualKeyCode: info.windowsVirtualKeyCode });
    await delay(80);
  }

  test.before(start);
  test.after(() => {
    cdp?.close();
    browser?.kill();
    if (userDataDir) fs.rmSync(userDataDir, { recursive: true, force: true });
  });

  return {
    sources,
    evaluate,
    settleAndInject,
    open,
    press,
    delay,
    activeId: () => evaluate("document.activeElement.id"),
  };
}

module.exports = { setup };
