/*
 * Checks the page numbers the thumbnails grid produces — both the ranges it sends to the operations
 * and the pages it reports as visible.
 *
 *   node nuxeo-labs-pdf-toolkit-webui/src/test/js/selection-harness.js
 *
 * Exit code 0 when every case passes. Like scroll-harness.js it needs nothing but node: the element
 * definition is loaded from nuxeo-pdf-toolkit-thumbnails.html itself, and the DOM is mocked away.
 *
 * Why it exists, twice over:
 *
 * 1. Extract and Remove run on the SOURCE pdf, which a drag and drop does not reorder.
 *    _getSelectedPageRanges() used to return the position in the grid, so after moving page 5 to the
 *    front, selecting the first tile and clicking Extract produced original page 1 — and with the
 *    "Replace file" destination, Remove deleted the wrong pages, silently. Nothing in the UI hinted
 *    at it: the tiles even display "position (original)".
 *
 * 2. _visiblePageNumbers() finds the tiles on screen by binary search, which is only valid while
 *    querySelectorAll returns them in vertical order — a CSS assumption. When it does not hold the
 *    search yields an EMPTY range, so tiles simply never load and nothing is logged.
 *
 * Run it after touching the selection, drag and drop, range or visibility logic. It is deliberately
 * outside `mvn clean install`, for the same reason as scroll-harness.js.
 *
 * Beware: it mocks Polymer internals and calls private methods, so renaming them breaks it.
 */
const fs = require('fs');
const nodePath = require('path');

const DEFAULT_ELEMENT = nodePath.resolve(__dirname,
  '../../main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/nuxeo-pdf-toolkit-thumbnails.html');

const elementPath = process.argv[2] || DEFAULT_ELEMENT;
const js = fs.readFileSync(elementPath, 'utf8').match(/<script>([\s\S]*)<\/script>/)[1];

let captured = null;
new Function('Polymer', 'Nuxeo', 'window', 'console', js)(
  (def) => { captured = def; },
  {},
  { addEventListener: () => {}, removeEventListener: () => {} },
  { log: () => {}, warn: () => {}, error: () => {} });

function makeGrid(pageCount) {
  const grid = Object.create(captured);
  Object.keys(captured.properties).forEach((k) => {
    const p = captured.properties[k];
    grid[k] = p && typeof p.value === 'function' ? p.value() : (p ? p.value : undefined);
  });

  grid.set = function (path, value) {
    // Only "pages.<i>.<field>" and plain property names are used by the code under test
    const parts = String(path).split('.');
    if (parts.length === 1) {
      this[parts[0]] = value;
      return;
    }
    this[parts[0]][parts[1]][parts[2]] = value;
  };
  grid.fire = function () {};
  grid.async = function (fn) { return setTimeout(fn, 0); };
  grid.debounce = function () {};
  grid.root = { querySelector: () => null, querySelectorAll: () => [] };

  grid.pages = [];
  for (let i = 0; i < pageCount; i++) {
    grid.pages.push({ image: '', selected: false, originalPageNumber: i + 1 });
  }
  grid._originalOrder = grid.pages.map((p) => p.originalPageNumber);

  return grid;
}

/** Move the page currently at `from` to position `to`, the way _onDrop does. */
function move(grid, from, to) {
  const moved = grid.pages.splice(from, 1)[0];
  grid.pages.splice(to, 0, moved);
}

function select(grid, positions) {
  grid.pages.forEach((p, i) => { p.selected = positions.indexOf(i) !== -1; });
}

let failures = 0;
function check(label, actual, expected) {
  const ok = String(actual) === String(expected);
  if (!ok) {
    failures++;
  }
  console.log((ok ? '  PASS  ' : '  FAIL  ') + label + ' -> "' + actual + '"'
    + (ok ? '' : ' (expected "' + expected + '")'));
}

console.log('Element: ' + elementPath);

// --- Nothing reordered: position and original page number coincide ---
let grid = makeGrid(10);
select(grid, [0]);
check('single page, untouched grid', grid.getSelectedPageRanges(), '1');

select(grid, [1, 2, 3]);
check('contiguous run folds into a range', grid.getSelectedPageRanges(), '2-4');

select(grid, [0, 2, 3, 4, 7]);
check('mixed singles and ranges', grid.getSelectedPageRanges(), '1,3-5,8');

select(grid, []);
check('no selection', grid.getSelectedPageRanges(), '');

// --- After a reorder: the ORIGINAL page numbers are what the operations need ---
grid = makeGrid(10);
move(grid, 4, 0); // original page 5 becomes the first tile
check('reorder is reflected in the page order', grid.getNewPageOrder().join(','), '5,1,2,3,4,6,7,8,9,10');

select(grid, [0]);
check('first tile after a reorder is original page 5', grid.getSelectedPageRanges(), '5');

select(grid, [0, 1]);
check('selection spanning the moved page stays on originals', grid.getSelectedPageRanges(), '1,5');

// A selection that is contiguous on screen but scattered in the source must not fold
grid = makeGrid(10);
move(grid, 7, 0); // original 8 first
move(grid, 5, 1); // original 5 second  -> [8,5,1,2,3,4,6,7,9,10]
check('two moves', grid.getNewPageOrder().join(','), '8,5,1,2,3,4,6,7,9,10');
select(grid, [0, 1]);
check('adjacent on screen, scattered in the source', grid.getSelectedPageRanges(), '5,8');

// Reversing the whole document must still yield a single full range
grid = makeGrid(5);
grid.pages.reverse();
select(grid, [0, 1, 2, 3, 4]);
check('everything selected, order reversed', grid.getSelectedPageRanges(), '1-5');

/* ==================== Visible page detection ==================== */

/*
 * _visiblePageNumbers() locates the tiles on screen by binary search, which is only valid while
 * querySelectorAll returns them in vertical order. That is a CSS assumption, not a JS one, so the
 * code checks it and falls back to a linear scan. Both paths are exercised here: the previous
 * implementation's failure mode was an empty range nobody noticed.
 */
function fakeTile(index, top, height) {
  return {
    dataset: { index: String(index) },
    getBoundingClientRect: function() { return { top: top, bottom: top + height }; }
  };
}

function withTiles(pageCount, tiles) {
  var g = makeGrid(pageCount);
  g._getScrollContainer = function() {
    return { getBoundingClientRect: function() { return { top: 0, bottom: 400, height: 400 }; } };
  };
  g.root = { querySelectorAll: function() { return tiles; }, querySelector: function() { return null; } };
  return g;
}

// 10 tiles, 200px apart, 180px tall. The view is 0..400 with one screen of margin -> -400..800,
// so tiles 0..4 intersect it.
var ordered = [];
for (var i = 0; i < 10; i++) {
  ordered.push(fakeTile(i, i * 200, 180));
}
check('visible pages, tiles in vertical order', withTiles(10, ordered)._visiblePageNumbers().join(','), '1,2,3,4,5');

// Same geometry, but the NodeList is not in vertical order: the binary search would be meaningless
var shuffled = ordered.slice().reverse();
check('visible pages, tiles out of order',
  withTiles(10, shuffled)._visiblePageNumbers().sort(function(a, b) { return a - b; }).join(','),
  '1,2,3,4,5');

// A page that already has its image must never be requested again
var withImages = withTiles(10, ordered);
withImages.pages[0].image = 'http://example/1.jpg';
withImages.pages[2].image = 'http://example/3.jpg';
check('pages that already have an image are skipped', withImages._visiblePageNumbers().join(','), '2,4,5');

console.log(failures === 0 ? '\nAll cases pass' : '\n' + failures + ' case(s) failed');
process.exit(failures === 0 ? 0 : 1);
