/*
 * Checks that the page grid comes back to the right position after a page preview is closed.
 *
 *   node nuxeo-labs-pdf-toolkit-webui/src/test/js/scroll-harness.js
 *
 * Exit code 0 when every case passes. It needs nothing but node: no PDF, no server, no browser.
 * The element definition is loaded from nuxeo-pdf-toolkit.html itself, so this exercises the real
 * code, and everything around the scroll — operations, thumbnails, DOM — is mocked away.
 *
 * Why it exists: this Web UI has no test harness, and the position was silently lost or restored to
 * a stale page three times in a row. Closing an overlay stacked on top of the dialog makes the
 * platform re-open it with a brand new content container, which is what reopenDialog() below
 * reproduces, and what any naive fix gets wrong.
 *
 * Run it after touching the scroll, preview or dialog logic. It is not part of `mvn clean install`:
 * the plugin has no JS build, and adding one for a single file would cost more than it is worth.
 *
 * Beware: it mocks Polymer internals and calls private methods, so renaming them breaks it.
 */
const fs = require('fs');
const nodePath = require('path');

const DEFAULT_ELEMENT = nodePath.resolve(__dirname,
  '../../main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/nuxeo-pdf-toolkit.html');

const elementPath = process.argv[2] || DEFAULT_ELEMENT;
const js = fs.readFileSync(elementPath, 'utf8').match(/<script>([\s\S]*)<\/script>/)[1];

let captured = null;
// Set `console` below to the real one to see the [pdf-toolkit] traces
new Function('Polymer', 'Nuxeo', 'window', 'console', js)(
  (def) => { captured = def; },
  { LayoutBehavior: {} },
  { localStorage: { getItem: () => null } },
  { log: () => {}, warn: () => {} });

function makeContainer(scrollTop) {
  return {
    scrollTop: scrollTop || 0, clientHeight: 417, scrollHeight: 35311, isConnected: true,
    _l: [],
    addEventListener(t, h) { this._l.push(h); },
    removeEventListener(t, h) { this._l = this._l.filter(x => x !== h); },
    scrollTo(v) { this.scrollTop = v; this._l.forEach(h => h()); }
  };
}

function makeToolkit() {
  const container = makeContainer(0);
  const tk = Object.create(captured);
  Object.keys(captured.properties).forEach(k => {
    const p = captured.properties[k];
    tk[k] = typeof p.value === 'function' ? p.value() : p.value;
  });

  // A promise that never settles: only the scroll is under test, not the thumbnails loading
  const op = { input: null, params: null, execute: () => new Promise(() => {}) };
  tk.$ = {
    dialogContent: container,
    dialog: { open() {} },
    thumbnails: { refreshScrollBinding() {}, reset() {}, applyChunk() { return 0; } },
    preview: { loadPreviewPage() {} },
    nx: { url: '/nuxeo/' },
    prepareThumbnailsOp: op, extractOp: op, removePagesOp: op, reorderOp: op
  };
  tk.set = function (k, v) { this[k] = v; };
  tk.async = function (fn, ms) { return setTimeout(fn, ms || 0); };
  tk.fire = function () {};
  tk.i18n = function (k) { return k; };
  tk.document = { uid: 'x' };
  tk.container = container;
  return tk;
}

/*
 * The platform re-opens the dialog when the preview closes: the content container is replaced by a
 * fresh node, with no listener on it and its scroll back to 0. THEN iron-overlay-opened fires.
 * Without this replacement the harness passes on broken code — it did, the first time round.
 */
function reopenDialog(tk) {
  tk.$.dialogContent = makeContainer(0);
  tk.container = tk.$.dialogContent;
  tk._onDialogOverlayOpened();
}

const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let failures = 0;
function check(label, actual, expected) {
  const ok = Math.abs(actual - expected) < 2;
  if (!ok) {
    failures++;
  }
  console.log((ok ? '  PASS  ' : '  FAIL  ') + label + ' -> ' + actual
    + (ok ? '' : ' (expected ' + expected + ')'));
}

async function previewAndClose(tk, page, wipe) {
  tk._onPagePreview({ detail: { pageNum: page } });
  tk._onPreviewDialogClosed();
  if (wipe) {
    reopenDialog(tk);
  } else {
    tk._onDialogOverlayOpened();
  }
  await sleep(wipe ? 200 : 900);
}

(async function () {
  console.log('Element: ' + elementPath);

  const tk = makeToolkit();
  tk._openDialog();

  // Somewhere in the middle of a long document
  tk.container.scrollTo(14473);
  check('last known position follows the scroll', tk._lastScrollTop, 14473);
  tk._onPagePreview({ detail: { pageNum: 438 } });
  check('position saved on first preview', tk._scrollTopBeforePreview, 14473);
  tk._onPreviewDialogClosed();
  reopenDialog(tk);
  await sleep(200);
  check('restored after first preview', tk.container.scrollTop, 14473);

  // Move elsewhere and preview again: the saved position must follow, not stay on the first one
  tk.container.scrollTo(24453);
  tk._onPagePreview({ detail: { pageNum: 739 } });
  check('position saved on second preview', tk._scrollTopBeforePreview, 24453);
  tk._onPreviewDialogClosed();
  reopenDialog(tk);
  await sleep(200);
  check('restored after second preview', tk.container.scrollTop, 24453);

  // And a third time, to catch anything that freezes after two rounds
  tk.container.scrollTo(30713);
  await previewAndClose(tk, 929, true);
  check('restored after third preview', tk.container.scrollTop, 30713);

  // When nothing wipes the scroll, nothing must move either
  tk.container.scrollTo(9000);
  await previewAndClose(tk, 250, false);
  check('untouched position left alone', tk.container.scrollTop, 9000);

  console.log(failures === 0 ? '\nAll cases pass' : '\n' + failures + ' case(s) failed');
  process.exit(failures === 0 ? 0 : 1);
})();
