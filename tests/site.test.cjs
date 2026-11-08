"use strict";
// Real-browser suite for app/src/main/assets/site.js (see browser.cjs for what this proves).
// Fixtures are minimal stand-ins for the site's cookie bar, cookie settings and sign-in dialog, with
// the selectors site.js relies on.
// Run: node --test tests/
const test = require("node:test");
const assert = require("node:assert/strict");
const { setup } = require("./browser.cjs");

const STYLE = `<style>
  body { margin: 0; font: 16px sans-serif; }
  button, a { display: inline-block; margin: 8px; padding: 8px; }
  .modal-popup { display: none; position: fixed; left: 0; right: 0; background: #eee; }
  .modal-popup._show { display: block; }
  #collapseSign { display: none; position: fixed; inset: 40px; background: #fff; z-index: 50; }
  #collapseSign[aria-hidden="false"] { display: block; }
</style>`;

const SIGN_IN = `
  <button role="button" aria-label="Sign In" id="sign-in"
    onclick="setTimeout(() => collapseSign.setAttribute('aria-hidden', 'false'), 150)">Sign In</button>
  <div id="collapseSign" role="dialog" aria-hidden="true">
    <button aria-label="Close sign-in dialog" id="close"
      onclick="collapseSign.setAttribute('aria-hidden', 'true')">×</button>
    <input id="email" type="email"><input id="pass" type="password" inputmode="text">
    <a id="forgot" href="/forgot">Forgot password</a>
    <button id="send2" type="submit">Sign In</button>
  </div>`;

const PAGES = {
  "/consent": `<!doctype html>${STYLE}
    <button id="page" onclick="window.pageClicks = (window.pageClicks || 0) + 1">Page</button>
    ${SIGN_IN}
    <div role="alertdialog" id="bar" class="modal-popup _show amgdprcookie-bar-container" style="bottom: 0; z-index: 10">
      <span tabindex="0" class="amgdprcookie-focus-guard"></span>
      <a href="/cookies">Cookie Policy</a>
      <button id="accept" class="amgdprcookie-button -allow">Accept All</button>
      <button id="settings" class="amgdprcookie-button -settings">Cookie Settings</button>
    </div>
    <div role="dialog" id="groups" class="modal-popup amgdprcookie-groups-modal" style="top: 20px; z-index: 1052">
      <button class="action-close" style="display: none">Close</button>
      <div class="amgdprcookie-toggle-cookie" id="essential">
        <input type="checkbox" checked disabled style="visibility: hidden"><label>Essential</label></div>
      <div class="amgdprcookie-toggle-cookie" id="targeting">
        <input id="targeting-box" type="checkbox" style="visibility: hidden"><label>Targeting</label></div>
      <div class="amgdprcookie-toggle-cookie" id="analytics">
        <input id="analytics-box" type="checkbox" style="visibility: hidden"><label>Analytics</label></div>
      <a href="#">More Information</a>
      <button id="done" class="amgdprcookie-done" type="button">Done</button>
    </div>
    <script>
      const closeBanner = () => { bar.classList.remove("_show"); groups.classList.remove("_show"); };
      accept.onclick = () => { window.accepted = true; closeBanner(); };
      settings.onclick = () => setTimeout(() => groups.classList.add("_show"), 100);
      done.onclick = () => { window.saved = true; closeBanner(); };
      document.getElementById("page").focus();
    </script>`,
  "/sign-in": `<!doctype html>${STYLE}<button id="page">Page</button>${SIGN_IN}
    <script>
      window.defaultPrevented = [];
      addEventListener("keydown", (event) => defaultPrevented.push(event.defaultPrevented));
      window.inputModeOnFocus = [];
      addEventListener("focusin", (event) => inputModeOnFocus.push(event.target.getAttribute("inputmode")));
    </script>`,
};

const { sources, evaluate, open, press, delay, activeId } = setup({ pages: PAGES, assets: ["site.js"] });
const [ASSET] = sources;
const status = () => evaluate("window.__coursesTvSite.status()");

test("installs only on the site's HTTPS origin, once", async () => {
  await open("/sign-in");
  const first = await evaluate("window.__coursesTvSite");
  await evaluate(ASSET);
  assert.deepEqual(await evaluate("window.__coursesTvSite"), first);
  assert.equal(await evaluate(`document.querySelectorAll("style").length`), 2, "focus style added once");
  assert.equal(await evaluate(`(() => { const location = { protocol: "https:", hostname: "evil.example" };
    const window = {}; ${ASSET}; return typeof window.__coursesTvSite; })()`), "undefined");
});

test("cookie bar: the first key moves focus into the bar without activating the page", async () => {
  await open("/consent");
  assert.equal(await status(), "consent");
  await press("Enter");
  assert.equal(await evaluate("window.pageClicks || 0"), 0);
  assert.equal(await activeId(), "accept");
});

test("cookie bar: the D-pad moves between its buttons and Select activates the focused one", async () => {
  await open("/consent");
  await press("ArrowDown");
  await press("ArrowRight");
  assert.equal(await activeId(), "settings");
  await press("ArrowRight");
  assert.equal(await activeId(), "settings", "stays inside the bar at its edge");
  await press("ArrowLeft");
  assert.equal(await activeId(), "accept");
  await press("Enter");
  assert.equal(await evaluate("window.accepted"), true, "Accept All works from the remote");
  assert.equal(await status(), "ready");
});

test("cookie settings: focus enters the dialog, toggles switch, essential is skipped, Done saves", async () => {
  await open("/consent");
  await press("ArrowDown");
  await press("ArrowRight");
  await press("Enter");
  await delay(500);
  assert.equal(await activeId(), "targeting", "focus moved into the dialog, past the essential group");
  await press("ArrowDown");
  assert.equal(await activeId(), "analytics");
  await press("Enter");
  assert.deepEqual(await evaluate(`["analytics-box", "targeting-box"].map((id) => document.getElementById(id).checked)`),
    [true, false], "only the focused group switched");
  await press("ArrowDown");
  assert.equal(await activeId(), "done");
  await press("ArrowRight");
  assert.equal(await activeId(), "done", "the bar behind the dialog is not reachable");
  await press("Enter");
  assert.equal(await evaluate("window.saved"), true);
  assert.equal(await evaluate("window.accepted"), undefined);
});

test("cookie settings opens from the remote while the sign-in dialog is open behind the banner", async () => {
  await open("/consent");
  await evaluate(`collapseSign.setAttribute("aria-hidden", "false")`);
  await press("ArrowDown");
  await press("ArrowRight");
  assert.equal(await activeId(), "settings");
  await press("Enter");
  await delay(500);
  assert.equal(await activeId(), "targeting", "the settings dialog opened and took focus");
});

test("status: banner before dialog before Sign In button", async () => {
  await open("/consent");
  await evaluate(`collapseSign.setAttribute("aria-hidden", "false")`);
  assert.equal(await status(), "consent");
  await evaluate(`bar.classList.remove("_show")`);
  assert.equal(await status(), "open");
  await evaluate(`collapseSign.setAttribute("aria-hidden", "true")`);
  assert.equal(await status(), "ready");
  await evaluate(`document.getElementById("sign-in").style.display = "none"`);
  assert.equal(await status(), "unavailable", "a hidden Sign In button does not count");
});

test("openSignIn opens the site's dialog and focuses the email field once it appears", async () => {
  await open("/sign-in");
  assert.equal(await status(), "ready");
  assert.equal(await evaluate("window.__coursesTvSite.openSignIn()"), "opening");
  assert.equal(await status(), "opening");
  await delay(400);
  assert.equal(await status(), "open");
  assert.equal(await activeId(), "email");
});

test("sign-in: Up/Down walk Close, email, password and Submit; other controls keep normal keys", async () => {
  await open("/sign-in");
  await evaluate("window.__coursesTvSite.openSignIn()");
  await delay(400);
  const order = [];
  for (const key of ["ArrowDown", "ArrowDown", "ArrowUp", "ArrowUp", "ArrowUp", "ArrowDown"]) {
    await press(key);
    order.push(await activeId());
  }
  assert.deepEqual(order, ["pass", "send2", "pass", "email", "close", "email"]);
  // Text fields gain focus with the TV keyboard held back, then keep their own inputmode.
  assert.deepEqual(await evaluate("inputModeOnFocus"), ["none", "none", null, "none", "none", null, "none"]);
  assert.deepEqual(await evaluate(`["email", "pass"].map((id) => document.getElementById(id).getAttribute("inputmode"))`),
    [null, "text"]);
  await evaluate(`defaultPrevented.length = 0; document.getElementById("forgot").focus()`);
  await press("ArrowDown");
  await press("ArrowLeft");
  assert.deepEqual(await evaluate("defaultPrevented"), [false, false]);
});

test("sign-in: focus that escaped the open dialog comes back to the email field", async () => {
  await open("/sign-in");
  await evaluate("window.__coursesTvSite.openSignIn()");
  await delay(400);
  await evaluate(`document.getElementById("page").focus()`);
  await press("ArrowDown");
  assert.equal(await activeId(), "email");
});

test("back closes the sign-in dialog, and reports when there is none", async () => {
  await open("/sign-in");
  await evaluate("window.__coursesTvSite.openSignIn()");
  await delay(400);
  assert.equal(await evaluate("window.__coursesTvSite.back()"), "handled");
  assert.equal(await status(), "ready");
  assert.equal(await evaluate("window.__coursesTvSite.back()"), "none");
});
