(() => {
  "use strict";

  // Remote-control aid for the site's own dialogs: the cookie banner and the sign-in
  // dialog. It moves focus, and clicks only what the user selects. It never reads or writes field
  // values and never makes a consent choice on the user's behalf.
  if (location.protocol !== "https:" || location.hostname !== "plus.thegreatcourses.com") return;
  if (window.__coursesTvSite) return;

  const SIGN_IN_OPEN_TIMEOUT_MS = 2000;
  const STEPS = { ArrowUp: -1, ArrowLeft: -1, ArrowDown: 1, ArrowRight: 1 };
  const CONSENT_TOGGLE = ".amgdprcookie-toggle-cookie";
  const SIGN_IN_CLOSE = 'button[aria-label="Close sign-in dialog"]';
  const SIGN_IN_EMAIL = 'input#email[type="email"]';
  const SIGN_IN_CHAIN = [SIGN_IN_CLOSE, SIGN_IN_EMAIL, 'input#pass[type="password"]', 'button#send2[type="submit"]'];

  const visible = (element) => {
    if (!element || !element.isConnected) return false;
    const rect = element.getBoundingClientRect();
    const style = getComputedStyle(element);
    return rect.width > 0 && rect.height > 0 && style.display !== "none" && style.visibility !== "hidden";
  };
  const findVisible = (selector, root = document) => [...root.querySelectorAll(selector)].filter(visible);
  const usable = (element) => visible(element) && !element.disabled;
  // The TV keyboard should open only when the user presses Select on a text field, but WebView opens
  // it whenever focus moves into one. inputmode="none" holds it back for this one focus change.
  const focusQuietly = (element) => {
    if (!element.matches("input, textarea")) return element.focus();
    const inputMode = element.getAttribute("inputmode");
    element.setAttribute("inputmode", "none");
    element.focus();
    setTimeout(() => {
      if (inputMode === null) element.removeAttribute("inputmode");
      else element.setAttribute("inputmode", inputMode);
    }, 0);
  };

  // ---- Cookie banner (Amasty GDPR): the bar, or a settings dialog opened on top of it ----------

  const zIndex = (element) => Number(getComputedStyle(element).zIndex) || 0;
  const consentLayer = () =>
    findVisible('.modal-popup._show[class*="amgdprcookie"]').sort((a, b) => zIndex(b) - zIndex(a))[0] || null;
  // Buttons and the switchable cookie groups (their checkboxes are hidden; the essential one is disabled).
  const consentControls = (layer) => findVisible(`button, ${CONSENT_TOGGLE}`, layer).filter((element) =>
    element.matches(CONSENT_TOGGLE) ? element.querySelector("input:not(:disabled)") : !element.disabled);
  const focusConsentControl = (element) => {
    if (element.matches(CONSENT_TOGGLE)) element.tabIndex = -1;
    focusQuietly(element);
  };
  const enterConsent = () => {
    const layer = consentLayer();
    const first = layer && consentControls(layer)[0];
    if (first && !layer.contains(document.activeElement)) focusConsentControl(first);
  };

  // While the banner is shown, the D-pad stays on its controls. Returns true when the key was used.
  const consentKey = (key) => {
    const layer = consentLayer();
    const controls = layer ? consentControls(layer) : [];
    if (controls.length === 0) return false;
    const index = controls.indexOf(document.activeElement);
    if (index < 0) {
      if (!(key in STEPS) && key !== "Enter") return false;
      focusConsentControl(controls[0]);
      return true;
    }
    if (key in STEPS) {
      const next = controls[index + STEPS[key]];
      if (next) focusConsentControl(next);
      return true;
    }
    if (key !== "Enter") return false;
    if (controls[index].matches(CONSENT_TOGGLE)) {
      controls[index].querySelector("input").click();
      return true;
    }
    // A button activates normally. Cookie Settings opens its dialog without moving focus into it.
    setTimeout(enterConsent, 400);
    return false;
  };

  const focusStyle = document.createElement("style");
  focusStyle.textContent = `.amgdprcookie-bar-container button:focus, .amgdprcookie-groups-modal button:focus,
    ${CONSENT_TOGGLE}:focus { outline: 3px solid #007c99; outline-offset: 3px; }`;
  (document.head || document.documentElement).append(focusStyle);

  // ---- Sign-in dialog ----------------------------------------------------------------------------

  const signInDialog = () => {
    const dialog = document.querySelector('#collapseSign[role="dialog"]');
    return dialog?.getAttribute("aria-hidden") === "false" && visible(dialog) ? dialog : null;
  };
  const signInButton = () => findVisible('button[role="button"][aria-label="Sign In"]')[0] || null;
  const signInChain = (dialog) => SIGN_IN_CHAIN.map((selector) => dialog.querySelector(selector)).filter(usable);
  const focusSignInStart = (dialog) => {
    const target = signInChain(dialog).find((element) => element.matches(SIGN_IN_EMAIL)) || signInChain(dialog)[0];
    if (target) focusQuietly(target);
    return !!target;
  };

  // Up/Down walk Close, email, password and Submit; focus that escaped the open dialog is brought back.
  const signInKey = (key) => {
    const dialog = signInDialog();
    if (!dialog) return false;
    if (!dialog.contains(document.activeElement)) {
      return (key in STEPS || key === "Enter") && focusSignInStart(dialog);
    }
    const chain = signInChain(dialog);
    const index = chain.indexOf(document.activeElement);
    const step = key === "ArrowUp" ? -1 : key === "ArrowDown" ? 1 : 0;
    const next = step && index >= 0 ? chain[index + step] : null;
    if (next) focusQuietly(next);
    return !!next;
  };

  let signInRequestedUntil = 0;
  const openSignIn = () => {
    const button = signInButton();
    if (!button || signInDialog() || consentLayer()) return status();
    button.click();
    signInRequestedUntil = Date.now() + SIGN_IN_OPEN_TIMEOUT_MS;
    const focusWhenOpen = () => {
      const dialog = signInDialog();
      if (dialog) {
        signInRequestedUntil = 0;
        focusSignInStart(dialog);
      } else if (Date.now() < signInRequestedUntil) {
        setTimeout(focusWhenOpen, 100);
      }
    };
    focusWhenOpen();
    return "opening";
  };

  // ---- Back and status for the host ---------------------------------------------------------------

  // Closes the open sign-in dialog, like its own Close button.
  const back = () => {
    const close = signInDialog()?.querySelector(SIGN_IN_CLOSE);
    if (!usable(close)) return "none";
    close.click();
    return "handled";
  };

  const status = () => {
    if (consentLayer()) return "consent";
    if (signInDialog()) return "open";
    if (Date.now() < signInRequestedUntil) return "opening";
    return signInButton() ? "ready" : "unavailable";
  };

  // While the cookie banner shows, its controls alone get the keys, even with the sign-in dialog
  // already open behind it: Select on Cookie Settings must reach its button.
  document.addEventListener("keydown", (event) => {
    if (event.defaultPrevented) return;
    if (consentLayer() ? consentKey(event.key) : signInKey(event.key)) {
      event.preventDefault();
      event.stopPropagation();
    }
  }, true);

  window.__coursesTvSite = { status, openSignIn, back };
})();
