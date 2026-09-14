# AGENTS.md — nuxeo-labs-pdf-toolkit

Nuxeo plugin (LTS 2025). Adds a "PDF Toolkit" Web UI dialog (thumbnails, page selection,
reorder) backed by 6 Automation operations and one REST endpoint. See `README.md` for the
functional spec and the full operation/parameter reference — it is accurate and worth reading
before touching an operation signature.

`AGENTS.md` is deliberately **not** in `.gitignore` here: it ships with the repo. Never put a
local path, a credential or any PII in it.

## Branches

- `master` = LTS 2025. **This is the only branch to work on.**
- `lts2023` exists but is **not** back-ported to. Do not modify it, do not cherry-pick to it,
  do not mention it in commits unless explicitly asked.

## Build & test

```bash
mvn clean install                                   # default verification after any code change
mvn -pl nuxeo-labs-pdf-toolkit-core test            # faster loop, core only
mvn -pl nuxeo-labs-pdf-toolkit-core test -Dtest=TestOperationsDestinations#shouldAddToFiles
```

- Java 21 (`<release>21</release>` from `nuxeo-parent:2025.0`).
- Requires network access to `packages.nuxeo.com` (maven-public **and** maven-private) and
  `connect.nuxeo.com`. A cold `mvn clean install` also pulls the `nuxeo-nxr-server` zip for the
  `-package` module, which is large — prefer the `-pl ...-core` loop while iterating.
- No CI, no formatter config, no lint step. `mvn clean install` is the whole gate.
- 85 tests across 4 classes, all green, ~65 s. A failure is a real regression, not flakiness.
- `target/` may hold stale artifacts from an old `lts2023` build — never trust it without a
  `clean`. `nuxeo-labs-pdf-toolkit-core/bin/` is stale Eclipse output from before the
  `nuxeo.labs.pdf.tools` → `nuxeo.labs.pdf.toolkit` rename; it is gitignored, ignore it.

## Modules

| Module | What it is |
|---|---|
| `-core` | All Java: PDF logic + 6 operations + the REST endpoint. The only module with tests. |
| `-webui` | Resources only (Polymer 2 HTML + i18n). No Java, no dependencies, no JS build/lint. |
| `-package` | Marketplace zip, assembled by `src/main/assemble/assembly.xml` (ant-assembly-maven-plugin). Rarely needs edits. |

## Core: how it is wired

- `nuxeo.labs.pdf.toolkit` holds the workers (`PDFPageExtractor`, `PDFPageRemover`,
  `PDFPageOrdering`, `PDFToImages`) plus `PDFTools` (blob resolution/validation, page-range
  parsing, blob saving) and `PDFDestinationHandler`.
- Every worker has the same 3 constructors: `(Blob)`, `(DocumentModel)`,
  `(DocumentModel, String xpath)` defaulting to `file:content`. Keep that pattern. The
  `DocumentModel` ones delegate to the `(Blob)` one through
  `PDFTools.getBlobFromDocument(doc, xpath)`; the `(Blob)` one calls
  `PDFTools.checkIsProcessablePdf(b)`. Never go back to a bare
  `(Blob) doc.getPropertyValue(xpath)`: it NPEs on a document with no file and
  ClassCastExceptions on a non-blob xpath.
- `checkIsProcessablePdf` also caps the input at `PDFTools.MAX_PDF_SIZE` (200 MB): PDFBox loads
  the document in memory. A blank mime type is accepted on purpose (some blobs have none, and
  PDFBox rejects the content anyway); a non-`application/pdf` one is refused.
- `PDFDestinationHandler` owns the shared `destinationJsonStr` contract
  (`download` / `derivative` / `attachments` / `newFile`), one protected method per
  destination. Any new mutating operation should delegate to it rather than re-implementing a
  destination. Two non-obvious rules are enforced there and must stay:
  - `attachments` refuses a single-valued property, because `DocumentHelper.addBlob()`
    silently falls back to `setValue()` and would **overwrite** the target blob.
  - `derivative` runs the title through `PathSegmentService.generatePathSegment()` before
    passing it to `session.copy()`: the 3rd argument is the document **name**, and
    `PathRef.checkName()` rejects any name holding a `/`.
- PDFBox 3 API: use `Loader.loadPDF(...)`, **not** `PDDocument.load(...)`. PDFBox and
  `org.json` versions are inherited from the platform BOM — do not add versions to the pom.
- Removing or reordering pages never touches the source file: PDFBox only mutates the
  in-memory model and we always save to a fresh temp blob. A defensive full-document copy was
  removed on purpose (it tripled the heap usage) — **do not reintroduce a `cloneDocument()`**.
- Operations live in `nuxeo.labs.pdf.toolkit.operations`, IDs prefixed `PDFLabs.`, category
  `CAT_CONVERSION`, each with two `@OperationMethod` overloads (`DocumentModel` and `Blob`) —
  except `PDFLabs.PrepareThumbnails`, which needs a document id to build URLs and therefore only
  accepts a `DocumentModel`. A new operation must be added to `OSGI-INF/operations-contrib.xml`;
  the MANIFEST already lists that file.

### Thumbnails are served by a REST endpoint, not as base64

`PDFLabs.PrepareThumbnails` + `nuxeo.labs.pdf.toolkit.rest.PDFToolkitEndpoint` replace the base64
transport for the UI. `PDFLabs.GetThumbnails` is kept for blob inputs and scripting.

- The endpoint is a **WebEngine module**, declared by the `Nuxeo-WebModule` header in the core
  MANIFEST. No OSGi fragment, no extra module. Served at `/nuxeo/site/pdftoolkit/`.
- **In tests the WebEngine servlet is mapped on `/*`**, so the very same route is at
  `<httpUrl>/pdftoolkit`, without `site/`. Do not "fix" one to match the other.
- **Never return a `Blob` as the JAX-RS entity if you set headers**: the platform `BlobWriter`
  starts with `httpHeaders.clear()` and delegates to the `DownloadService`, wiping the `ETag`,
  the `Cache-Control` and the content type. Stream `blob.getStream()` instead. This cost an hour
  of debugging, the symptom is a 200 with none of the headers you set.
- **The thumbnail URL must carry the content token** (`&v=<digest>`, produced by
  `PDFToImages.getContentToken()`). The path only holds the document id, so without the token
  replacing `file:content` yields the very same URLs. Combined with a long `max-age` the browser
  then keeps serving the previous thumbnails: reordering a PDF and reopening the dialog showed
  the pages in their old order. A non-zero `max-age` is only legitimate on a content-addressed
  URL — **an `ETag` alone protects from nothing**, since `max-age` tells the browser not to
  revalidate at all, so the `ETag` is never compared. `cacheControl(versionedUrl)` enforces this:
  `max-age` with a token, `no-cache` without.
- The token is deliberately **not** used to select what is served: the endpoint always returns the
  current content of the document. It only drives the cache policy.
- The endpoint resolves the document through `getContext().getCoreSession()`: the read permission
  is enforced by the repository, not by us. Keep it that way.
- Rendering parameters arrive in the query string, so they go through the same setters as the
  operation and are clamped identically. An URL is no more trustable than an operation param.

### The chunk is the unit of work — never render a single page

Everything about thumbnails is rendered by chunks of `nuxeo.pdftoolkit.thumbnails.chunkSize`
pages (50 by default). This is what makes a 1000 pages PDF work at all; before it, the operation
simply threw above 150 pages.

- **Serving N pages must cost `ceil(N / chunkSize)` PDF openings, never N.** One opening per page
  means one `getCloseableFile()` per page, that is one full download of the PDF per page on a
  remote blob store. `shouldOpenThePdfOncePerChunkNotOncePerPage` guards this.
- `prepareChunk(startPage)` is the only entry point that renders. `getThumbnail(pageNum)` goes
  through it, so the endpoint's fallback renders the chunk, not the page and not the document.
- **The render lock is not optional.** A browser opens up to six connections, so six thumbnails of
  the same cold chunk land on six threads at once. `renderLockFor(cacheKey)` stripes 64 locks over
  the key; the winner renders, the others re-check the cache after waiting. Striping rather than a
  map of per-key locks: nothing to remove, hence no leak and no race on the removal.
  `shouldRenderAChunkOnlyOnceUnderConcurrency` guards this.
- **The page count is cached with every chunk** (`putParameter(PAGE_COUNT_PARAM)`). Without it,
  answering "how many pages?" on a cache hit would reopen and reparse the PDF — on S3, download it
  again. Any new cache write must keep writing it, and `readChunkFromCache` rejects an entry that
  lacks it.
- `createThumbnails()` (whole document, for `GetThumbnails`) still opens the PDF **once**, but
  stores its result chunk by chunk so both paths share the same cache entries. Do not make it loop
  over `prepareChunk`: that would reopen the PDF once per chunk.
- Rendering cost grows with the **square of the dpi** and barely at all with the target size
  (measured: 10 pages at 512px/150dpi ≈ 230 ms, at 120px/150dpi ≈ 200 ms, at 2000px/300dpi
  ≈ 850 ms). If something is slow, look at the dpi, not the size.
- **The plugin's `info` logs are invisible on a stock server.** The package is
  `nuxeo.labs.pdf.toolkit`, which no `<Logger>` of Nuxeo's `log4j2.xml` covers, so it inherits the
  root logger — at `warn`. Never rely on a `log.info` to prove anything to a user: the test
  `log4j2-test.xml` sets the root to `info`, so it shows in surefire and nowhere else.
- **Every path that opens the PDF logs through `logRendering(reason, ...)`**, never a direct
  `log.info`. It is the single place deciding the level: `warn` for an endpoint fallback (abnormal)
  or when `nuxeo.pdftoolkit.verboseRendering` is on, `info` otherwise. `createThumbnails()` used to
  log on its own and stayed invisible when verbose rendering was turned on — the four
  `shouldLog*Rendering*` tests capture the level with an in-memory appender and guard this.

### Temporary blobs — the only correct way

Always `Blobs.createBlobWithExtension(ext)`, then write into `blob.getFile()`.

It is the only API that both lands under `nuxeo.tmp.dir` **and** calls `Framework.trackFile`,
so the file is eventually deleted. `File.createTempFile(...)` + `new FileBlob(File)` leaks the
file forever — `FileBlob(File)` does not set `isTemporary` and registers nothing. This applies
to `PDFTools.saveToFileBlob` and `PDFToImages.imageToBlob`.

### TransientStore cache — read this before touching `PDFToImages`

Thumbnails and previews are cached in a TransientStore named `PDFToolkitCache`, contributed by
`OSGI-INF/cache-contrib.xml`.

The API contract is counter-intuitive and caused a whole class of bugs:

- `exists(key)` only checks that the `.completed` key is **present**, whatever its value. So
  `setCompleted(key, false)` makes `exists()` return `true` immediately.
- `getBlobs(key)` returns an **empty list** for an entry that exists but holds no blob, and
  `null` for an entry that vanished (TTL).

Consequences, all enforced by tests — keep them:

- Read through `getFromCache()`, which requires `exists` **and** `isCompleted` **and** a
  non-empty list. Never call `store.getBlobs(...)` directly.
- Write through `putInCache()`, which sets `completed` to `true` only after a successful
  `putBlobs`, and swallows `MaximumTransientSpaceExceeded`: a full cache must never fail a
  request whose result is already computed.
- The `finally` block calls `store.remove(key)` when nothing was stored, so a failed run never
  leaves a poisoned entry behind.
- Cache keys are built by `buildCacheKey()` and **include the rendering parameters and the chunk
  start** (`width`, `height`, `dpi`, `-c<chunkStart>` for thumbnails; page number and preview
  constants for previews). Dropping them serves wrongly-sized images, or the wrong pages.
- A blob with neither digest nor `ManagedBlob` key is **not cached** (`buildCacheKey` returns
  `null`). Do not add a `filename + length` fallback: it can collide across documents. Note that
  `prepareChunk` then also skips the render lock — there is nothing to share anyway.

### Rendering bounds — do not remove

All operations are reachable by any authenticated user, so `PDFToImages` clamps everything
**inside the setters** (`setWidth` / `setHeight` / `setDpi`). Never assign `width`, `height` or
`dpi` directly, and never trust an operation `@Param`.

| Constant | Value | Why |
|---|---|---|
| `DEFAULT_DPI` | 150 | Thumbnails are downscaled anyway; 512 rendered ~76 MB per A4 page. |
| `MAX_DPI` | 300 | Above this a single page can exhaust the heap. |
| `MAX_THUMBNAIL_SIZE` | 2000 | Same reason. |
| `DEFAULT_MAX_PAGES` | 150 | The thumbnails operation builds the whole base64 payload in memory, ~230 KB of heap per page. Applies to `GetThumbnails` only — **not** to `PrepareThumbnails`, which is bounded by the chunk, nor to extract/remove/reorder. |
| `DEFAULT_THUMBNAILS_MAX_PAGES` | 2000 | Plafond of `PrepareThumbnails`. Not about the server (rendering is chunked) but about the browser: one tile per page, and a few thousand tiles freeze a tab. |
| `PDFTools.MAX_PDF_SIZE` | 200 MB | PDFBox loads the document in memory. Checked in `checkIsProcessablePdf`. |
| `PREVIEW_DPI` / `PREVIEW_PAGE_MAX_SIZE` | 300 / 2048 | Preview is rendered then resized by the `pictureResize` converter. Cache and return the **resized** blob, not the full-size one. Raise the cap, never the DPI: the 300 dpi render already holds more detail than the cap keeps, so the cap is free while the DPI costs quadratically. |

## Configuration properties

| Property | Default | Scope |
|---|---|---|
| `nuxeo.pdftoolkit.thumbnails.chunkSize` | 50 | Pages rendered per PDF opening |
| `nuxeo.pdftoolkit.thumbnails.maxPages` | 2000 | `PrepareThumbnails` page cap |
| `nuxeo.pdftoolkit.maxPages` | 150 | `GetThumbnails` only (base64 payload) |
| `nuxeo.pdftoolkit.cache.targetMaxSizeMB` | 500 | |
| `nuxeo.pdftoolkit.cache.absoluteMaxSizeMB` | 600 | |
| `nuxeo.pdftoolkit.verboseRendering` | false | Chunk renderings logged at `warn` |

All read through `getPositiveIntProperty()`, which falls back on the constant when the property is
missing, non-numeric or ≤ 0. **Everything works with no `nuxeo.conf` entry** — that matters, the
plugin ships on presales demo instances.

## Anything user-facing must reach `README.md`

`README.md` is the contract with the user: configuration properties, operation parameters and
response fields, and the public attributes of `<nuxeo-pdf-toolkit>`. Three sets that silently drift.

**After adding a configuration property, an operation parameter, a response field or an element
attribute, run these checks** — they caught `debug` and `thumbnailWidth/Height/Dpi` being shipped
undocumented:

```bash
# 1. Config properties: the two lists must match
rg -o '"nuxeo\.pdftoolkit\.[a-zA-Z.]+"' --type java nuxeo-labs-pdf-toolkit-core/src/main | sed 's/.*"\(.*\)"/\1/' | sort -u
rg -o 'nuxeo\.pdftoolkit\.[a-zA-Z.]+' nuxeo-labs-pdf-toolkit-core/src/main/resources/OSGI-INF/cache-contrib.xml | sort -u
rg -o 'nuxeo\.pdftoolkit\.[a-zA-Z.]+' README.md | sort -u

# 2. Public attributes of the element: every one must appear in README
rg -n '^        [a-z]\w*: \{' nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/nuxeo-pdf-toolkit.html

# 3. i18n keys: both files must hold the same set
python3 -c "import json;a=set(json.load(open('nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/i18n/messages.json')));b=set(json.load(open('nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/i18n/messages-fr.json')));print('EN-only',a-b,'FR-only',b-a)"
```

A property documented in `AGENTS.md` but not in `README.md` is a bug: agents read the former, users
read the latter.

## Tests

- JUnit 4 + `FeaturesRunner`, `AutomationFeature`, `jakarta.inject.Inject`.
- Standard header on every test class:
  `@Deploy("org.nuxeo.ecm.platform.picture.core")`, `@Deploy("org.nuxeo.ecm.core.convert")`,
  `@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")`.
- **No external service needed** (no Docker, no S3, no Testcontainers). In-memory repo.
- Fixture `src/test/resources/lorem_ipsum_10_pages.pdf`: exactly 10 pages, page 3 contains the
  sentinel `HERE SOME TEXT FOR THE UNIT TEST`. Several assertions hardcode page counts and that
  string — **do not replace or re-generate this file.**
- Surefire picks up `Test*` class names; keep the prefix.
- Who tests what:
  - `TestOperationsWithDownload` — every operation but `PrepareThumbnails`, with the default
    `download` destination, plus the negative tests on page ranges and page orders.
  - `TestOperationsDestinations` — the 4 destinations, plus the negative tests on
    `destinationJsonStr`. Always assert `checkOriginalNotModified()` when the operation is not
    supposed to touch the source.
  - `TestTheToolkit` — caching, chunking, the render lock, rendering bounds, blob validation,
    single page thumbnail and `PDFLabs.PrepareThumbnails`, and the content token in the generated
    URLs. Chunk tests lower the chunk size to 3 with
    `@WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")` rather than
    introducing a second, bigger PDF fixture.
  - `TestPDFToolkitEndpoint` — the REST endpoint over HTTP, under `WebEngineFeature` (not
    `AutomationFeature`: the two do not mix well in one class) with `HttpClientTestRule`.
- `TestTheToolkit` wipes the store in `@Before` via `((TransientStoreProvider) store).removeAll()`.
  Use `TransientStoreProvider`, **not** `AbstractTransientStore`: the default implementation is
  `KeyValueBlobTransientStore`, which does not extend it.
- Anything testing the cache must use a blob **stored in the repository** (it then carries a
  digest). A bare `new FileBlob(f)` is deliberately not cacheable.
- Automation wraps runtime exceptions, so operation-level negative tests walk the cause chain
  (`assertFailureMentions`) instead of `@Test(expected = ...)`. Worker-level tests can use
  `expected` directly.
- Always close `PDDocument` in tests (`try-with-resources`), like production code does.

## Web UI (`-webui`)

Polymer 2 / Web UI legacy elements under
`src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/`:

- `nuxeo-pdf-toolkit-bundle.html` is the **only** file registered in
  `OSGI-INF/webresources-contrib.xml`. It imports `nuxeo-pdf-toolkit.html` and contributes the
  `DOCUMENT_ACTIONS` slot content. A new element must be imported from `nuxeo-pdf-toolkit.html`,
  not registered separately. Its `nuxeo-filter` expression excludes versions and proxies
  (`!document.isVersion && !document.isProxy`): they cannot be written to, and a version has no
  parent so a derivative cannot be created from it.
- `nuxeo-pdf-toolkit.html` is the orchestrator: it owns the four `<nuxeo-operation>` elements
  and all operation calls. Children are dumb and event-based:
  - `-thumbnails.html` → selection/drag-drop, exposes `getSelectedPageRanges()` /
    `getNewPageOrder()`, notifies `hasSelection` / `hasReordered`, fires `page-preview`.
    Drag and drop moves **every selected page**, not just the grabbed tile, and regroups them
    contiguously at the drop point keeping their relative order. Grabbing a tile outside the
    selection makes it the selection first. Two rules to keep in mind when touching it:
    - the drag handlers manipulate classes through `classList`, **never through a bound
      property**: changing one would re-render the `dom-repeat` and abort the drag. That is also
      why the count badge is a node already in the template whose text is filled imperatively.
    - `_onDrop` replaces `pages`, so the `dom-repeat` re-renders and nodes get recycled. Always
      clear the drag classes on **every** tile in `_onDragEnd`, never on `e.currentTarget` alone.
  - `-actions.html` → buttons + destination dialog, calls **no** operation, fires
    `action-complete`. The three write destinations are hidden through `_canWrite`, which
    mirrors `FiltersBehavior.hasPermission` and **fails open** when the `permissions` enricher
    is missing (the server stays the authority).
  - `-preview.html` → owns its own `PDFLabs.JpegImagePreview` operation, and revokes its blob
    URL on `iron-overlay-closed` so ESC and backdrop clicks do not leak.
- What this Web UI version does **not** provide, do not try to use it:
  - no `nuxeo-confirm-dialog` element — the destructive-action confirmation is a plain
    `paper-dialog` in `nuxeo-pdf-toolkit.html`;
  - `Nuxeo.LayoutBehavior` is `[RoutingBehavior, FiltersBehavior, FormatBehavior]`, so there is
    no `this.notify()`. Use `this.fire('notify', { message: ... })`.
- Nothing is transported as base64 any more: `_loadThumbnails()` calls `PDFLabs.PrepareThumbnails`
  and the `<img>` tags fetch the URLs it returns. Those URLs are **relative to the Nuxeo
  application root**, so `_getBaseUrl()` prefixes them with `this.$.nx.url` — do not drop that,
  and do not assume `/nuxeo/` (the context path is configurable).
- **Chunked loading, and the traps it brings.** The orchestrator asks for one chunk, gets the URLs
  of *every* page, and builds a tile per page immediately — including pages with no image yet.
  That is what keeps shift-click over a wide range and dragging a page to the far end working on a
  1000 pages document. Five rules, each of which cost a bug:
  - `_visiblePageNumbers()` finds the visible tiles by **binary search on their position**, never by
    computing them from a measured grid (columns x row height). The computed version silently
    yielded an *empty* range at some scroll positions — tiles that never loaded, no error anywhere.
    Measure, do not deduce. ~10 probes locate the first visible tile among a thousand.
  - Tiles are read through **`data-index`**, never through their rank in the `querySelectorAll`
    result: the two are not guaranteed to match, which is why the drag handlers already did so.
  - `applyChunk()` matches tiles on **`originalPageNumber`, never on position** (after a reorder the
    two diverge) and **returns how many tiles it filled**. The orchestrator marks a chunk prepared
    only when that count is > 0 — marking unconditionally made a chunk that landed on nothing
    permanently unrequestable, so those pages stayed empty forever.
  - Chunk requests are **suspended while `_draggedIndex >= 0`**: applying images mutates a bound
    property, which re-renders the `dom-repeat` and aborts the gesture. `_onDragEnd` re-scans, and
    so does `applyChunk` (the viewport may span several chunks) and a window resize.
  - `<nuxeo-operation>` is a single shared element, so chunk calls are **serialized through
    `_chunkQueue`**; two overlapping calls would fight over `op.params`.
  - A tile with no image yet shows a transparent-pixel data URL, **never `src=""`**: an empty src
    makes some browsers refetch the current page.
- **Never loop a `set()` over every page.** Selection helpers go through `_selectOnly()`, which
  notifies only the pages whose state actually changes. A `set()` per page is 1000 Polymer
  notifications on a long document, and a double click fires two `click` before the `dblclick`, so
  it was 2000 in a row: the `dom-repeat` re-rendered wholesale, the container lost its height and
  `scrollTop` fell back to 0 — opening a page preview sent the user back to page 1.
- **The scroll listener must survive a detach.** It is bound from `attached()` **and** from
  `_sourcesChanged`, and `_bindScroll()` is idempotent and self-healing: it re-attaches when the
  scrolling ancestor changed or left the document. Binding once from `_sourcesChanged` was not
  enough — a stack of Polymer overlays (opening the page preview) detaches and re-attaches the
  element, and the grid then stopped loading anything on scroll, silently. `refreshScrollBinding()`
  is the public entry point, called by the orchestrator when the preview closes.
- **Closing an overlay stacked on top of the dialog makes the platform re-open the dialog.**
  Measured on a 1000 pages PDF: `iron-overlay-closed` fires on the preview with `scrollTop` still
  intact, then `iron-overlay-opened` fires **twice** on `#dialog` and the content container goes
  through `clientHeight = 0` / `scrollHeight = 0`. That wipes `scrollTop` with **no assignment at
  all** — nothing to intercept, only something to put back. Restoring on `iron-overlay-closed` is
  therefore always too early: `_onDialogOverlayOpened` + `_restoreScrollTop()` retry until the
  height is back and the value sticks.
- Two traps when debugging this by hand, both of which cost a wasted round trip:
  - `scroll` events are **not `composed`**: a listener on `document` never sees a scroll happening
    inside a shadow root. Attach it to the container itself.
  - `document.contains(node)` **does not cross shadow boundaries** and answers `false` for a
    perfectly live node. Use `node.isConnected`.
- `window.NUXEO_PDF_TOOLKIT_DEBUG = true` (or the same key in `localStorage`) turns the traces on
  without a rebuild, which the `debug` attribute alone cannot do (it needs a Studio change). The flag
  is **re-read in `_openDialog()`**, not in the property's `value:` — the element is created with the
  document page, long before anyone can set it from the console, so reading it once at creation time
  is always too early.
- **A branch that logs only when it acts is a blind spot.** `_onDialogOverlayOpened` logs its
  decision every time, including "nothing to do". The previous version put its only `console.log`
  inside the `if`, which hid the fact that the restoration was being skipped.
- **Do not "restore" the scroll when nothing was lost.** `_onPreviewDialogClosed` only *arms*
  `_restorePending`; the wipe happens later. An early restore finds the value already correct,
  declares success and consumes `_scrollTopBeforePreview`, so the real wipe has nothing left to put
  back — that defect silently neutralised a whole fix.
- **The live `scrollTop` wins over `_lastScrollTop`.** The memorised value is only a fallback for a
  position already wiped before we read it. Trusting it first froze the saved position at whatever
  the first restoration had written, and every later preview came back to that same page.
- **`_bindDialogScroll()` is self-healing too**, for the same reason as the grid's: the content
  container is replaced when the dialog re-opens, and a listener left on the old node stops updating
  `_lastScrollTop` for good. Any listener kept on a node inside this dialog needs that treatment.
- The orchestrator keeps `_lastScrollTop` up to date on every scroll rather than reading the
  position when the preview opens: once the scroll is wiped there is nothing left to read.
- `debug` attribute on `<nuxeo-pdf-toolkit>` traces the whole chain in the console (visible pages,
  chunk queued/skipped/applied, tiles filled). There is no UI test harness, so this is the only
  diagnostic available — keep it working.
- **One UI check is automated**, and it is the only one:

  ```bash
  node nuxeo-labs-pdf-toolkit-webui/src/test/js/scroll-harness.js
  ```

  It loads the real element definition out of `nuxeo-pdf-toolkit.html`, mocks everything around the
  scroll and replays the preview scenario. No PDF, no server, no browser, ~1.5 s. **Run it after
  touching the scroll, preview or dialog logic.** It is deliberately outside `mvn clean install`:
  the plugin has no JS build, and adding Node to the build for one file would cost more than it is
  worth. It calls private methods, so renaming them breaks it — fix the harness, do not delete it.
- `.page-thumbnail` has a **fixed 120x170 box with `object-fit: contain`**. Without a reserved
  size, each incoming image reflows the grid and the browser — seeing a compact grid — schedules
  far more fetches than the viewport needs.
- `_visibleIndices()` measures the grid on the **first few tiles** then computes the visible range
  from the scroll offset. Calling `getBoundingClientRect()` on every tile would be a thousand
  forced reflows per scroll event.
- The dialog asks for **256px @ 72 dpi** (`thumbnailWidth/Height/Dpi`), not the operation defaults
  of 512 @ 150: ~4x faster to render and 4x smaller in cache, and the CSS displays at 120px
  anyway. Do not change the *operation* defaults to match — Studio and scripts depend on them.
- In `-actions.html`, selection highlighting relies on `this.root.querySelectorAll('.destination-option')`.
  Do not wrap those options in a `<nuxeo-filter>`: the templatizer moves them out of that query's
  scope. Use `hidden$=` instead.
- i18n: keys are namespaced `pdftoolkit.*` and live in `ui/i18n/messages.json` and
  `messages-fr.json`. `OSGI-INF/deployment-fragment.xml` **appends** these to the server files
  and maps `messages-fr.json` to both `fr` and `fr-FR`. Add every new key to both files, and
  never hardcode a user-visible string in an element.
- There is no test harness for the UI — changes here are verified by `mvn clean install`
  compiling/packaging only, and manually in a running server. The single exception is
  `src/test/js/scroll-harness.js`, see above.

## Conventions

- MANIFEST `Nuxeo-Component`: one entry per line, single leading space on continuation lines,
  trailing newline at EOF (last header is dropped otherwise).
- `Bundle-Version` is hardcoded in both MANIFESTs and **must be bumped by hand at each release**,
  to the release version without the `-SNAPSHOT` suffix (`mvn versions:set` does not touch
  manifests). It cannot be filtered from `${project.version}`: `2025.7.0-SNAPSHOT` is not a valid
  OSGi version (the dash is illegal).
- `Bundle-SymbolicName` follows `groupId.artifactId` in both modules. The core one is
  referenced by `@Deploy(...)` in the 4 test classes — renaming it breaks them.
- Java: 4 spaces, K&R, ~120 cols, no wildcard imports, `jakarta.*` not `javax.*`
  (`javax.imageio` is the legitimate exception), Log4j2 `LogManager.getLogger()`,
  `Framework.getService()` for lookups, checked exceptions wrapped in `NuxeoException`,
  `@since 2025.XX` on new public API.
- Exception messages name the offending blob/document/property. They are the only diagnostic a
  support engineer gets.
- Comments: `//` per line for 1–3 lines, block comment for 4+.
- Known typos kept for compatibility — do not "fix" without checking callers:
  `PDFJpegimagePreviewOp` (lowercase `image`), `TEST_PDF_PAH` in tests,
  `PDFToImages.setheight` (deprecated, delegates to `setHeight`).
