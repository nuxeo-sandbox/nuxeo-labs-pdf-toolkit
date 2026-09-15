# AGENTS.md — nuxeo-labs-pdf-toolkit

Nuxeo plugin (LTS 2025). Adds a "PDF Toolkit" Web UI dialog (thumbnails, page selection, reorder)
backed by 6 Automation operations and one REST endpoint.

`README.md` is accurate and is the functional spec — read it before touching an operation signature.
The Java is heavily commented: this file holds what the comments cannot say, i.e. cross-file rules,
traps that span modules, and things you would only learn by breaking them.

`AGENTS.md` is deliberately **not** in `.gitignore` here: it ships with the repo. Never put a local
path, a credential or any PII in it.

## Freshness

Every factual claim in this file was checked against the tree at commit `c7dc2f7`
(2026-09-15, "Post 2025.8.0 release" — the audit was done at `f6ccf9b`, and no source file changed
between the two).

**Before reviewing this file again, get the delta instead of re-reading the codebase:**

```bash
git diff c7dc2f7..HEAD --stat
```

Empty output means this file is current: say so and stop. Otherwise audit only the files it lists.
`AGENTS.md` appearing in the delta means nothing — the anchor tracks the code that was verified,
not this file's own edits.

**A release commit does not invalidate this file.** `/nuxeo-release-plugin` produces one commit,
`Post <version> release`, touching exactly 6 files (4 `pom.xml`, 2 `MANIFEST.MF`), one line each,
every one a version string. Confirm that and discount it:

```bash
git diff c7dc2f7..HEAD -- '*pom.xml' '*MANIFEST.MF' | rg '^[+-]' | rg -v '^(\+\+\+|---)'
```

Every line must be a `<version>` or a `Bundle-Version:`. Anything else — a new `Nuxeo-Component`
entry, a new dependency, a changed `Bundle-SymbolicName` — is a real change and must be reviewed.
Leave the SHA where it is: the rule keeps working across any number of releases.

Bump the SHA **only** after an actual re-verification, never as a courtesy — a marker claiming more
than was checked is worse than no marker. If git does not know the SHA (rebase, amend, shallow
clone), the shortcut is void: review in full.

## Branches

- `master` = LTS 2025. **The only branch to work on**, and the only one maintained.
- `lts2023` is **frozen**: no back-port, no fix, no support. Do not modify it, do not cherry-pick to
  it, do not mention it in commits unless asked. It predates `PDFToImages.renderPage`, so a crafted
  PDF can still exhaust its heap — say that first if someone asks for "just a small back-port".
  `README.md` states the policy for users; keep the two consistent.

## Build & test

```bash
mvn clean install                                   # default gate after any code change
mvn -pl nuxeo-labs-pdf-toolkit-core test            # faster loop, core only
mvn -pl nuxeo-labs-pdf-toolkit-core test -Dtest=TestOperationsDestinations#shouldAddToFiles
node nuxeo-labs-pdf-toolkit-webui/src/test/js/scroll-harness.js      # UI, see Web UI section
node nuxeo-labs-pdf-toolkit-webui/src/test/js/selection-harness.js
```

- Java 21 (`<release>21</release>` from `nuxeo-parent:2025.0`). No CI, no formatter config, no lint
  step: `mvn clean install` plus the two Node harnesses are the whole gate.
- Needs network access to `packages.nuxeo.com` (maven-public **and** maven-private) and
  `connect.nuxeo.com`. A cold `mvn clean install` also pulls the large `nuxeo-nxr-server` zip for the
  `-package` module — prefer the `-pl ...-core` loop while iterating.
- 116 tests across 4 classes (58 / 21 / 19 / 18), all green, ~75 s. A failure is a real regression,
  not flakiness. Tests run on `-Xmx1g` (`it.memory.argLine` from the platform parent), which is the
  heap the rendering-bounds tests are calibrated against.
- `target/` may hold stale artifacts from an old `lts2023` build — never trust it without `clean`.
  `nuxeo-labs-pdf-toolkit-core/bin/` is stale Eclipse output from before the
  `nuxeo.labs.pdf.tools` → `nuxeo.labs.pdf.toolkit` rename; it is gitignored, ignore it.

## Modules

| Module | What it is |
|---|---|
| `-core` | All Java: PDF logic + 6 operations + the REST endpoint. The only module with JUnit tests. |
| `-webui` | Resources only (Polymer 2 HTML + i18n) + two Node harnesses. It calls `PDFLabs.*` but declares **no** Maven dependency on `-core`: the two always ship together in the marketplace package, so this only matters when hot-reloading a single bundle. |
| `-package` | Marketplace zip, assembled by `src/main/assemble/assembly.xml`. Rarely needs edits. |

## Core: how it is wired

- `nuxeo.labs.pdf.toolkit` holds the workers (`PDFPageExtractor`, `PDFPageRemover`,
  `PDFPageOrdering`, `PDFToImages`) plus `PDFTools` (blob resolution/validation, page-range parsing,
  blob saving) and `PDFDestinationHandler`.
- Every worker has the same 3 constructors: `(Blob)`, `(DocumentModel)`,
  `(DocumentModel, String xpath)` defaulting to `file:content`. Keep that pattern. The
  `DocumentModel` ones delegate through `PDFTools.getBlobFromDocument(doc, xpath)`; the `(Blob)` one
  calls `PDFTools.checkIsProcessablePdf(b)`. Never go back to a bare
  `(Blob) doc.getPropertyValue(xpath)`: it NPEs on a document with no file and
  ClassCastExceptions on a non-blob xpath.
- Validation rules that each fixed a silent bug, all in `PDFTools`:
  - `checkIsProcessablePdf` caps at `MAX_PDF_SIZE` (200 MB) and handles `getLength() == -1`
    (unknown length) **explicitly** — `length > MAX` is false for -1, so the cap silently did not
    apply. A blank mime type is accepted on purpose; a non-`application/pdf` one is refused.
  - `parsePageRange` splits with `split(",", -1)`. The default limit drops trailing empty tokens,
    which made `"2-4,"` silently valid while `",2-4"` was rejected — the same typo accepted or
    refused depending on which end it was on.
- `PDFDestinationHandler` owns the shared `destinationJsonStr` contract
  (`download` / `derivative` / `attachments` / `newFile`), one protected method per destination.
  Any new mutating operation should delegate to it rather than re-implement a destination. Four
  rules enforced there, keep them:
  - `attachments` refuses a single-valued property, because `DocumentHelper.addBlob()` silently
    falls back to `setValue()` and would **overwrite** the target blob. It also refuses a multivalued
    property that does not hold blobs (`dc:subjects`): `isList()` is true there too, and `addBlob()`
    then fails deep in the property model, naming neither the destination nor the xpath.
  - `derivative` runs the title through `PathSegmentService.generatePathSegment()` before passing it
    to `session.copy()`: the 3rd argument is the document **name**, and `PathRef.checkName()` rejects
    any name holding a `/`.
  - `parseVersioningOption()` refuses anything but minor/major (any case, trimmed). It runs on the
    path that replaces `file:content`, and the version is the user's only safety net, so `"Majr"`
    must not quietly produce a minor version.
  - `details` is read with `optJSONObject` **independently of** `destination`: a payload carrying
    details but no destination used to have its details silently dropped.
- PDFBox 3 API: `Loader.loadPDF(...)`, **not** `PDDocument.load(...)`. PDFBox and `org.json` versions
  are inherited from the platform BOM — do not add versions to the pom.
- Removing or reordering pages never touches the source file: PDFBox only mutates the in-memory
  model and we always save to a fresh temp blob. A defensive full-document copy was removed on
  purpose (it tripled the heap) — **do not reintroduce a `cloneDocument()`**.
- Operations live in `nuxeo.labs.pdf.toolkit.operations`, IDs prefixed `PDFLabs.`, category
  `CAT_CONVERSION`, each with two `@OperationMethod` overloads (`DocumentModel` and `Blob`) — except
  `PDFLabs.PrepareThumbnails`, which needs a document id to build URLs and only accepts a
  `DocumentModel`. A new operation must be added to `OSGI-INF/operations-contrib.xml`; the MANIFEST
  already lists that file.

### Thumbnails are served by a REST endpoint, not as base64

`PDFLabs.PrepareThumbnails` + `nuxeo.labs.pdf.toolkit.rest.PDFToolkitEndpoint` replace the base64
transport for the UI. `PDFLabs.GetThumbnails` is kept for blob inputs and scripting.

- The endpoint is a **WebEngine module**, declared by `Nuxeo-WebModule` in the core MANIFEST. No OSGi
  fragment, no extra module. Served at `/nuxeo/site/pdftoolkit/`.
- **In tests the WebEngine servlet is mapped on `/*`**, so the same route is at
  `<httpUrl>/pdftoolkit`, without `site/`. Do not "fix" one to match the other.
- **Never return a `Blob` as the JAX-RS entity if you set headers**: the platform `BlobWriter` starts
  with `httpHeaders.clear()` and delegates to the `DownloadService`, wiping the `ETag`, the
  `Cache-Control` and the content type. Stream `blob.getStream()` instead. Symptom: a 200 with none
  of the headers you set.
- **Never throw a bare `NuxeoException` subclass from the endpoint expecting the platform to map
  it.** The method is `@Produces("image/jpeg")`, so JAX-RS looks for a `MessageBodyWriter` able to
  serialize the *exception* as `image/jpeg`, finds none, and the failure cascades: the real status is
  lost and the client gets `404 jakarta.ws.rs.NotFoundException` with a 500 in the logs. Same family
  as the `BlobWriter` trap: on a binary endpoint the error path needs its own content type. Use
  `error(status, message)`, which builds a `WebApplicationException` with a `text/plain` entity.
- **The thumbnail URL must carry the content token** (`&v=<digest>`, from
  `PDFToImages.getContentToken()`). The path only holds the document id, so without the token
  replacing `file:content` yields the very same URLs; combined with a long `max-age` the browser
  keeps serving the previous thumbnails (reordering then reopening the dialog showed the old order).
  A non-zero `max-age` is only legitimate on a content-addressed URL — **an `ETag` alone protects
  from nothing**, since `max-age` tells the browser not to revalidate, so the `ETag` is never
  compared. `cacheControl(versionedUrl)` enforces it: `max-age` with a token, `no-cache` without.
- The token is deliberately **not** used to select what is served: the endpoint always returns the
  current content of the document. It only drives the cache policy.
- The endpoint resolves the document through `getContext().getCoreSession()`: the read permission is
  enforced by the repository, not by us. Keep it that way — it is the **only** thing protecting this
  endpoint, there is no `guard` on the `@WebObject`. `shouldRefuseAThumbnailToAUserWithoutRead` and
  `shouldRefuseAThumbnailFromTheCacheToAUserWithoutRead` guard it; both accept 403 **or** 404,
  because the repository answers 404 for a document the user cannot browse.
- Rendering parameters arrive in the query string and go through the same setters as the operation,
  so they are clamped **and snapped** identically. A URL is no more trustable than an operation param.
- **`w`, `h` and `dpi` are snapped to a ladder** (`THUMBNAIL_SIZE_LADDER`, `DPI_LADDER`), down, first
  step as floor and last as cap. They are part of the cache key, so arbitrary values would let anyone
  force an unbounded number of chunk renderings by walking a query string one pixel at a time.
  Snapping bounds the distinct renderings of a document to 5x5x3.
  **`PDFPrepareThumbnailsOp` must emit the snapped values in the URL** (`getWidth()` / `getHeight()`
  / `getDpi()`, never the raw `@Param`), otherwise operation and endpoint compute different cache
  keys and every image becomes an `endpoint-fallback` render.
  `shouldPutTheSnappedParametersInTheUrls` and `shouldNotMultiplyCacheEntriesForNearbySizes` guard it.

### The chunk is the unit of work — never render a single page

Thumbnails are rendered by chunks of `nuxeo.pdftoolkit.thumbnails.chunkSize` pages (50 by default).
This is what makes a 1000 pages PDF work at all; before it, the operation simply threw above 150.

- **Serving N pages must cost `ceil(N / chunkSize)` PDF openings, never N.** One opening per page
  means one `getCloseableFile()` per page, that is one full download of the PDF per page on a remote
  blob store. `shouldOpenThePdfOncePerChunkNotOncePerPage` guards it.
- `prepareChunk(startPage)` is the only entry point that renders. `getThumbnail(pageNum)` goes
  through it, so the endpoint fallback renders the chunk, not the page and not the document.
- **The render lock is not optional.** A browser opens up to six connections, so six thumbnails of
  the same cold chunk land on six threads at once. `renderLockFor(cacheKey)` stripes 64 locks over
  the key; the winner renders, the others re-check the cache after waiting. Striping rather than a
  map of per-key locks: nothing to remove, hence no leak and no race on the removal.
  `shouldRenderAChunkOnlyOnceUnderConcurrency` guards it.
- **Page limits are enforced before rendering, and on the cache-hit path too.** `setMaxPageCount()`
  is called by `PrepareThumbnails` and checked inside `renderChunk` right after
  `getNumberOfPages()`, before the loop — checking on the way out still paid for a full chunk on
  every refused call. Symmetrically, `readAllChunksFromCache` calls
  `checkMaxPagesForFullRendering`: `GetThumbnails` and `PrepareThumbnails` share the chunk cache, so
  a document browsed in the dialog used to leave `GetThumbnails` able to serve it whole, well above
  its own documented limit. `shouldRefusePdfAboveTheUiPageLimit` asserts the cache stays **empty**,
  which is what proves nothing was rendered.
- **The page count is cached with every chunk** (`putParameter(PAGE_COUNT_PARAM)`). Without it,
  answering "how many pages?" on a cache hit would reopen and reparse the PDF — on S3, download it
  again. Any new cache write must keep writing it; `readChunkFromCache` rejects an entry lacking it.
- `createThumbnails()` (whole document, for `GetThumbnails`) still opens the PDF **once**, but stores
  its result chunk by chunk so both paths share the same cache entries. Do not make it loop over
  `prepareChunk`: that would reopen the PDF once per chunk.
- Rendering cost grows with the **square of the dpi** and barely at all with the target size
  (measured: 10 pages at 512px/150dpi ≈ 230 ms, 120px/150dpi ≈ 200 ms, 2000px/300dpi ≈ 850 ms). If
  something is slow, look at the dpi, not the size.
- **The plugin's `info` logs are invisible on a stock server.** The package is
  `nuxeo.labs.pdf.toolkit`, which no `<Logger>` of Nuxeo's `log4j2.xml` covers, so it inherits the
  root logger — at `warn`. Never rely on a `log.info` to prove anything to a user: the test
  `log4j2-test.xml` sets the root to `info`, so it shows in surefire and nowhere else.
- **Every path that opens the PDF logs through `logRendering(reason, ...)`**, never a direct
  `log.info`. It is the single place deciding the level: `warn` for an endpoint fallback (abnormal)
  or when `nuxeo.pdftoolkit.verboseRendering` is on, `info` otherwise. `createThumbnails()` used to
  log on its own and stayed invisible when verbose rendering was turned on.
- `RENDER_LOCKS` is JVM-local: on a cluster a cold chunk is rendered once per node. Throughput, not
  correctness — but it changes the numbers when auditing with `verboseRendering`.
- **Rendering is single-threaded end to end, and that is not an oversight.** `renderChunk` walks its
  pages in a plain `for` loop, and the dialog sends one chunk request at a time (`_chunkLoading`
  gates `_drainChunkQueue`). One user scrolling one document therefore occupies **exactly one core**,
  however many the machine has — so adding vCPUs buys concurrent users, never a faster scroll. Only
  single-thread speed helps the person waiting. `README.md` states this for administrators sizing a
  server; the reason it must stay that way is here: **`PDDocument` and `PDFRenderer` are not
  thread-safe**, so rendering pages in parallel means one parsed document per thread — N times the
  parse cost and N times the heap against `MAX_RENDERED_PIXELS`, on a box that is usually CPU-bound
  already. Do not "optimise" this into a `parallelStream()`.

### TransientStore cache — read this before touching `PDFToImages`

Thumbnails and previews are cached in a TransientStore named `PDFToolkitCache`, contributed by
`OSGI-INF/cache-contrib.xml`. The API contract is counter-intuitive and caused a class of bugs:

- `exists(key)` only checks that the `.completed` key is **present**, whatever its value. So
  `setCompleted(key, false)` makes `exists()` return `true` immediately.
- `getBlobs(key)` returns an **empty list** for an entry that exists but holds no blob, and `null`
  for an entry that vanished (TTL).

Consequences, all enforced by tests — keep them:

- Read through `getFromCache()`, which requires `exists` **and** `isCompleted` **and** a non-empty
  list. Never call `store.getBlobs(...)` directly.
- Write through `putInCache()` / `putChunkInCache()`, which set `completed` to `true` only after a
  successful `putBlobs`, and swallow `MaximumTransientSpaceExceeded`: a full cache must never fail a
  request whose result is already computed.
- The `finally` block calls `store.remove(key)` when nothing was stored **and the entry is not
  completed**, so a failed run never leaves a poisoned entry behind — and never deletes one a
  concurrent `createThumbnails()` just filled, since that path writes chunks without taking the
  render lock. Do not drop the `isCompleted` check.
- A blob read from the store can lose its file between the lookup and the read (GC, size eviction).
  The endpoint catches that, calls `evictChunkOf()` and renders once more before giving up with a
  **503**; do not turn it back into a 500.
- Cache keys are built by `buildCacheKey()` and **include the rendering parameters and the chunk
  start** (`width`, `height`, `dpi`, `-c<chunkStart>` for thumbnails; page number and preview
  constants for previews). Dropping them serves wrongly-sized images, or the wrong pages.
- A blob with neither digest nor `ManagedBlob` key is **not cached** (`buildCacheKey` returns
  `null`). Do not add a `filename + length` fallback: it can collide across documents. `prepareChunk`
  then also skips the render lock — there is nothing to share anyway.
- **The cache is content-addressed, not an authorization boundary.** Two different documents holding
  byte-identical PDFs deliberately share their cached images. That is safe only because every read
  path resolves the document through the **user's session before touching the cache**. Any new path
  that serves from the cache without doing so turns this into a cross-document read.

### Rendering bounds — do not remove

All operations are reachable by any authenticated user, so `PDFToImages` clamps and snaps everything
**inside the setters** (`setWidth` / `setHeight` / `setDpi`). Never assign `width`, `height` or `dpi`
directly, and never trust an operation `@Param` or a query param.

**The page geometry is an attacker-controlled input too, and it is the one that drives the
allocation.** Never call `PDFRenderer.renderImageWithDPI` — it derives the scale from the dpi alone,
so the raster grows with the page size, and `MAX_PDF_SIZE` cannot catch a 14400x14400 pt page in a
~600 bytes file. Measured with PDFBox 3.0.7 on such a page: 791 MB at 72 dpi (and it *succeeds*,
silently), `OutOfMemoryError` at 150 dpi on a 1 GB heap; PDFBox's own guard only fires around 8 GB.
Every rendering goes through **`renderPage(renderer, doc, pageIndex, dpi, maxSide)`**, which takes
`min(dpi/72, maxSide * RENDER_SUPERSAMPLE / longestSidePt)` then applies `MAX_RENDERED_PIXELS`. See
its Javadoc for the full numbers. `shouldNotBlowUpOnAHugePageGeometry{,ForThePreview}` guard it.

Supersampling is not decoration: rendering straight at the target size makes text thin and aliased,
which is why the original code rendered at a fixed dpi and let `scaleToFit` downsample. Keep a
factor ≥ 2, or the thumbnails get visibly worse.

| Constant | Value | Note |
|---|---|---|
| `DEFAULT_DPI` / `MAX_DPI` | 150 / 300 | Above 300 a single page can exhaust the heap. |
| `DEFAULT_THUMBNAIL_SIZE` / `MAX_THUMBNAIL_SIZE` | 512 / 2000 | |
| `RENDER_SUPERSAMPLE` | 2 | Pixels rendered per pixel kept, on the longest side. |
| `MAX_RENDERED_PIXELS` | 40 M (~160 MB) | Absolute ceiling for one page, whatever geometry/dpi/size. |
| `THUMBNAIL_SIZE_LADDER` / `DPI_LADDER` | 120/256/512/1024/2000 and 72/150/300 | The only values rendered. Snapped **down**; first step is the floor, last is the cap, so there is no separate clamping. Bounds the distinct cache keys. |
| `DEFAULT_MAX_PAGES` | 150 | `GetThumbnails` **only** (base64 payload, ~230 KB heap/page). Not `PrepareThumbnails`, not extract/remove/reorder. |
| `PDFThumbnailsOp.MAX_BASE64_PAYLOAD` | 20 MB | Of *jpeg* bytes, so ~80 MB heap (base64 1.33x, `JSONArray`, `toString()`, `createJSONBlob` each copy). Checked **while** encoding, not after. A backstop, deliberately above the working set (150 pages at 512/150 ≈ 8 MB) so the *page* limit is what users meet — lowering it to 5 MB made it the binding constraint instead. |
| `DEFAULT_THUMBNAILS_MAX_PAGES` | 2000 | Ceiling of `PrepareThumbnails`. Not about the server (chunked) but about the browser: one tile per page. |
| `PDFTools.MAX_PDF_SIZE` | 200 MB | PDFBox loads the document in memory. |
| `PREVIEW_DPI` / `PREVIEW_PAGE_MAX_SIZE` | 300 / 2048 | Rendered then resized by the `pictureResize` converter. Cache and return the **resized** blob. Raise the cap, never the DPI: the 300 dpi render already holds more detail than the cap keeps. |

### Temporary blobs — the only correct way

Always `Blobs.createBlobWithExtension(ext)`, then write into `blob.getFile()`. It is the only API
that both lands under `nuxeo.tmp.dir` **and** calls `Framework.trackFile`, so the file is eventually
deleted. `File.createTempFile(...)` + `new FileBlob(File)` leaks the file forever —
`FileBlob(File)` does not set `isTemporary` and registers nothing. Applies to
`PDFTools.saveToFileBlob` and `PDFToImages.imageToBlob`.

### Odds and ends that bit once

- `ImageIO.scanForPlugins()` is in a static initializer, so it runs on whichever request thread
  touches the class first and mutates the **global** `IIORegistry` with that thread's classloader.
  Ugly, and kept on purpose: the platform does not scan for us (the only call in the distribution is
  in `nuxeo-platform-pdf-utils`, which this plugin does not depend on). Do not delete it.
  `ImageIO.setUseCache(false)` is *not* the fix either — a global JVM setting, and
  `ImageIO.write(img, fmt, File)` goes through a `FileImageOutputStream` that never spills anyway.
- `toBase64JSONArray`, `setSize(String)`, `createThumbnails(String)` and `setheight` have no caller
  left. The first is `@Deprecated(forRemoval = true)` because it is unbounded: it was the vehicle of
  the `GetThumbnails` heap problem, and a new caller would bring it straight back.

## Configuration properties

| Property | Default | Scope |
|---|---|---|
| `nuxeo.pdftoolkit.thumbnails.chunkSize` | 50 | Pages rendered per PDF opening |
| `nuxeo.pdftoolkit.thumbnails.maxPages` | 2000 | `PrepareThumbnails` page cap |
| `nuxeo.pdftoolkit.maxPages` | 150 | `GetThumbnails` only (base64 payload) |
| `nuxeo.pdftoolkit.cache.targetMaxSizeMB` | 500 | `cache-contrib.xml` only |
| `nuxeo.pdftoolkit.cache.absoluteMaxSizeMB` | 600 | `cache-contrib.xml` only |
| `nuxeo.pdftoolkit.verboseRendering` | false | Chunk renderings logged at `warn` |

The first three go through `getPositiveIntProperty()`, which falls back on the constant when the
property is missing, non-numeric or ≤ 0, and **clamps above a ceiling**
(`MAX_CONFIGURABLE_CHUNK_SIZE` = 500, `MAX_CONFIGURABLE_PAGES` = 10000). The ceiling is a typo
guard, not a tuning limit: `chunkSize=5000` instead of `500` makes one request rasterize five
thousand pages. **Everything works with no `nuxeo.conf` entry** — that matters, the plugin ships on
presales demo instances.

## Anything user-facing must reach `README.md`

`README.md` is the contract with the user: configuration properties, operation parameters and
response fields, and the public attributes of `<nuxeo-pdf-toolkit>`. Three sets that silently drift.

**After adding a configuration property, an operation parameter, a response field or an element
attribute, run these checks** — they caught `debug` and `thumbnailWidth/Height/Dpi` being shipped
undocumented. All three are green today:

```bash
# 1. Config properties: the union of the first two lists must equal the third
rg -o '"nuxeo\.pdftoolkit\.[a-zA-Z.]+"' --type java nuxeo-labs-pdf-toolkit-core/src/main | sed 's/.*"\(.*\)"/\1/' | sort -u
rg -o 'nuxeo\.pdftoolkit\.[a-zA-Z.]+' nuxeo-labs-pdf-toolkit-core/src/main/resources/OSGI-INF/cache-contrib.xml | sort -u
rg -o 'nuxeo\.pdftoolkit\.[a-zA-Z.]+' README.md | sort -u

# 2. Public attributes of the element: every one must appear in README (8 today)
rg -n '^        [a-z]\w*: \{' nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/nuxeo-pdf-toolkit.html

# 3. i18n keys: both files must hold the same set
python3 -c "import json;a=set(json.load(open('nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/i18n/messages.json')));b=set(json.load(open('nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/i18n/messages-fr.json')));print('EN-only',a-b,'FR-only',b-a)"
```

A property documented in `AGENTS.md` but not in `README.md` is a bug: agents read the former, users
read the latter.

## Tests

- JUnit 4 + `FeaturesRunner`, `jakarta.inject.Inject`. **No external service** — no Docker, no S3, no
  Testcontainers; embedded H2 repository.
- The 3 `@Deploy` are identical on all 4 classes (`org.nuxeo.ecm.platform.picture.core`,
  `org.nuxeo.ecm.core.convert`, `nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core`). The rest is
  **not**: `TestPDFToolkitEndpoint` uses `@Features(WebEngineFeature.class)` and has **no**
  `@RepositoryConfig`; the other three use `AutomationFeature` + `DefaultRepositoryInit`.
- Fixture `src/test/resources/lorem_ipsum_10_pages.pdf`: exactly 10 pages, Letter portrait, page 3
  holds the sentinel `HERE SOME TEXT FOR THE UNIT TEST` (once in the whole file). Page counts, the
  sentinel and the page geometry are hardcoded in assertions — **do not replace or re-generate it**.
  `FileUtils.getResourceFileFromContext()` hands back the real file in `target/test-classes`, not a
  copy: anything mutating its input in place would corrupt the fixture for the rest of the fork.
- Surefire picks up `Test*` class names — which is why the `TestUtils` helper is a **nested** class
  in `TestPDFToolkitEndpoint`; a top-level `Test…` helper would be collected and fail.
- Who tests what:
  - `TestOperationsWithDownload` (18) — every operation but `PrepareThumbnails`, default `download`
    destination, plus the negative tests on page ranges and orders. Calls the workers directly, so it
    uses `@Test(expected = ...)`.
  - `TestOperationsDestinations` (21) — the 4 destinations, the negative tests on
    `destinationJsonStr`, and the write-permission tests (a read-only user must be refused `newFile`
    and `attachments`, but still allowed to download). Assert `checkOriginalNotModified()` whenever
    the operation is not supposed to touch the source.
  - `TestTheToolkit` (58) — caching, chunking, the render lock, rendering bounds, blob validation,
    single-page thumbnail, `PDFLabs.PrepareThumbnails`, the content token in the URLs, and the log
    levels.
  - `TestPDFToolkitEndpoint` (19) — the endpoint over HTTP with `HttpClientTestRule`, plus the read
    permission tests and the 4xx/503 mapping.
- Traps, each of which has already cost a debugging session:
  - Automation wraps runtime exceptions, so operation-level negative tests walk the cause chain.
    `assertFailureMentions` and `assertBadRequest` live in `TestOperationsDestinations` **only**;
    `TestTheToolkit` re-implements the walk inline and `TestPDFToolkitEndpoint` has `assertRefused`.
    Worker-level tests can use `@Test(expected = ...)` directly.
  - **A second `HttpClientTestRule` cannot be a `@Rule`**: `FeaturesRunner` binds rules into Guice by
    type and two fields of the same type fail the injector with "bound multiple times". Build it by
    hand and call `starting()`/`finished()`, see `asUserWithoutAccess()`.
  - **HTTP tests need `session.save()` *and* `txFeature.nextTransaction()`** before the request: the
    servlet runs in another thread and transaction. Skipping either gives a 404 that looks like an
    endpoint bug.
  - **Negative ACLs are constrained by the repository** and the two classes use different, non
    interchangeable workarounds: `TestOperationsDestinations` grants READ then denies WRITE (a "deny
    READ" ACE is refused); `TestPDFToolkitEndpoint` blocks inheritance with an `EVERYONE`/`EVERYTHING`
    deny. Administrators bypass ACLs, so the admin client keeps working. `assertRefused` accepts 403
    **or** 404 on purpose — do not tighten it.
  - **A permission test must use `CoreInstance.getCoreSession(repo, user)` and re-read the document
    through that session.** Reusing the injected admin `session` makes the test vacuous.
  - **Anything testing the cache needs a blob stored in the repository** (it then carries a digest).
    `TestTheToolkit.createTestDoc()` creates, commits with `nextTransaction()` and **re-reads**;
    `TestOperationsDestinations.createTestDoc()` does neither — copying that one into a cache test
    silently produces an uncacheable blob. A bare `new FileBlob(f)` is deliberately not cacheable.
  - `TestTheToolkit` wipes the store in `@Before` via `((TransientStoreProvider) store).removeAll()`
    — use `TransientStoreProvider`, **not** `AbstractTransientStore` (the default implementation is
    `KeyValueBlobTransientStore`, which does not extend it). **`TestPDFToolkitEndpoint` does not wipe
    it**, and its `@Before` recreates the document from the same file, hence the same digest and the
    same cache keys: any new endpoint test asserting a cold render is order-dependent unless it calls
    `TestUtils.wipeThumbnailsCache()` explicitly.
  - `RENDER_COUNT` in `TestTheToolkit` is **static**: every test using `CountingPDFToImages` must
    `RENDER_COUNT.set(0)` first, or it passes or fails depending on JUnit's method order.
  - The 5 log-level tests capture events with an in-memory Log4j2 appender filtered on
    `getFormattedMessage().startsWith("Rendered")`. **Rewording either production message makes them
    collect 0 events and fail with no hint.** Go through `captureRenderingLogs()`, which also forces
    the plugin logger to INFO and restores it.
  - A raw `Thread` has no transaction: wrap the work in `TransactionHelper.runInTransaction(...)`,
    and assert on a collected `failures` list — an `AssertionError` in a worker thread is swallowed.
  - The three `PDFToImages` subclasses live in the test package and reach `renderPage` / `renderChunk`
    purely by `protected` inheritance. Narrowing either breaks all three at once.
  - Chunk tests lower the chunk size to 3 with
    `@WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")` rather than adding a
    bigger PDF fixture. Used by `TestTheToolkit` and `TestPDFToolkitEndpoint`.
  - Always close `PDDocument` in tests (`try-with-resources`), like production code does.

## Web UI (`-webui`)

Polymer 2 / Web UI legacy elements under `src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/`.

### Structure

- `nuxeo-pdf-toolkit-bundle.html` is the **only** file registered in
  `OSGI-INF/webresources-contrib.xml`. It imports `nuxeo-pdf-toolkit.html` and contributes the
  `DOCUMENT_ACTIONS` slot content. A new element must be imported from `nuxeo-pdf-toolkit.html`, not
  registered separately. Its `nuxeo-filter` expression has **four** terms, and the one people forget
  is the mime type: `file:content` must exist **and** be `application/pdf`, and the document must be
  neither a version nor a proxy (they cannot be written to, and a version has no parent so no
  derivative). If the button does not show up on a `File`, check the mime type first.
- `nuxeo-pdf-toolkit.html` is the orchestrator. It owns **four** `<nuxeo-operation>` elements —
  `ExtractPagesByRange`, `RemovePages`, `ReorderPages`, `PrepareThumbnails` — and the calls that go
  with them. The preview owns a fifth. Children are dumb and event-based:
  - `-thumbnails.html` → selection/drag-drop, exposes `getSelectedPageRanges()` /
    `getNewPageOrder()`, notifies `hasSelection` / `hasReordered`, fires `page-preview`.
  - `-actions.html` → buttons + destination dialog, calls **no** operation, fires `action-complete`.
    The three write destinations are hidden through `_canWrite`, which mirrors
    `FiltersBehavior.hasPermission` and **fails open** when the `permissions` enricher is missing
    (the server stays the authority).
  - `-preview.html` → owns its own `PDFLabs.JpegImagePreview` operation, and revokes its blob URL on
    `iron-overlay-closed` so ESC and backdrop clicks do not leak. It also **duplicates** the
    orchestrator's `_serverMessage` parsing inline — two places to fix.
- Behaviors differ per element: the orchestrator uses `Nuxeo.LayoutBehavior`, the two children only
  `Nuxeo.I18nBehavior`.
- What this Web UI version does **not** provide:
  - no `nuxeo-confirm-dialog` — the destructive-action confirmation is a plain `paper-dialog` in
    `nuxeo-pdf-toolkit.html`, checked in `_onActionComplete`. It guards **every** action whose
    destination is `newFile`, not just Remove: extracting 3 pages out of a 100 pages contract and
    replacing the main file destroys exactly as much. Skipped when `createVersion` is on, since the
    user already asked for a safety net. Key `pdftoolkit.confirm.replaceFile`;
    `pdftoolkit.confirm.removePages` is an unreferenced duplicate kept for Studio overrides.
  - `Nuxeo.LayoutBehavior` is `[RoutingBehavior, FiltersBehavior, FormatBehavior]`, so there is **no**
    `this.notify()`. Use `this.fire('notify', { message: ... })`.
- `_thumbnailSources`, `_loading`, `_chunkGeneration`… are internal state, underscore-prefixed on
  purpose: the public attributes of `<nuxeo-pdf-toolkit>` are the 8 in the README table and nothing
  else. Keep new internals underscored or they become API by accident.
- **`_getScrollContainer()` in `-thumbnails.html` walks up looking for `.dialog-content`, a class
  owned by the orchestrator.** Renaming or restructuring that class in the parent silently kills
  scroll binding, chunk loading and drag auto-scroll in the child, with no error. Change both files
  together.

### Chunked loading, and the traps it brings

Nothing is transported as base64 any more: `_loadThumbnails()` calls `PDFLabs.PrepareThumbnails` and
the `<img>` tags fetch the URLs it returns. Those URLs are **relative to the Nuxeo application root**,
so `_getBaseUrl()` prefixes them with `this.$.nx.url` (with `/nuxeo/` only as a last-resort fallback).

The orchestrator asks for one chunk, gets the URLs of *every* page, and builds a tile per page
immediately — including pages with no image yet. That is what keeps shift-click over a wide range and
dragging a page to the far end working on a 1000 pages document.

- **The first chunk does not go through `applyChunk()`.** `_onChunkReady` takes its "first chunk"
  branch as soon as `_pageCount` is 0: it builds the sources array, fills it and marks the chunk
  prepared **unconditionally**. Only chunks 2..n are subject to the `applied > 0` rule below.
- `applyChunk()` matches tiles on **`originalPageNumber`, never on position** (after a reorder the
  two diverge) and **returns how many tiles it filled**. The orchestrator marks such a chunk prepared
  only when that count is > 0 — marking unconditionally made a chunk that landed on nothing
  permanently unrequestable, so those pages stayed empty forever.
- `_markChunkPrepared(requestedStart, serverStart)` records **both** keys, because the server snaps
  the requested page down to its chunk start. Dropping the second write makes a chunk re-requested
  forever.
- `_visiblePageNumbers()` finds the visible tiles by **binary search on their position**, never by
  computing them from a measured grid (columns x row height). The computed version silently yielded
  an *empty* range at some scroll positions — tiles that never loaded, no error anywhere. Measure, do
  not deduce. The search is only valid while `querySelectorAll` returns the tiles in **vertical**
  order, which is a CSS assumption (`grid-auto-flow`, a sticky child, RTL would break it); it is
  checked on the two ends with a linear scan as fallback, because the failure mode is again a silent
  empty range. `selection-harness.js` covers both paths.
- Tiles are read through **`data-index`**, never through their rank in the `querySelectorAll` result.
- Chunk requests are **suspended while `_draggedIndex >= 0`**: applying images mutates a bound
  property, which re-renders the `dom-repeat` and aborts the gesture. `_onDragEnd` re-scans, and so
  does `applyChunk` (the viewport may span several chunks) and a window resize.
- `<nuxeo-operation>` is a single shared element, so chunk calls are **serialized**: `_chunkQueue`
  holds the pending starts and `_chunkLoading` gates the drain. Two overlapping calls would fight
  over `op.params`. Nothing removes a queued chunk that scrolled out of view — see "Known gaps".
- Every chunk call captures `_chunkGeneration` and drops its response if it changed. `_resetChunkState()`
  bumps it and `_closeDialog()` calls it: without that, closing the dialog after a fast scroll kept
  rendering chunks nobody would look at, and a response for the previous document could rebuild the
  grid from its URLs.
- The `dom-repeat` uses `initial-count` + `on-dom-change`: the grid renders in batches, so the first
  scan sees only part of the tiles and the re-scan on `dom-change` is what completes the initial fill.
- A tile with no image yet shows a transparent-pixel data URL, **never `src=""`**: an empty src makes
  some browsers refetch the current page. Same rule in `-preview.html`.
- `.page-thumbnail` has a **fixed 120x170 box with `object-fit: contain`**. Without a reserved size,
  each incoming image reflows the grid and the browser — seeing a compact grid — schedules far more
  fetches than the viewport needs.
- The dialog asks for **256px @ 72 dpi** (`thumbnailWidth/Height/Dpi`), not the operation defaults of
  512 @ 150: ~4x faster to render, 4x smaller in cache, and the CSS displays at 120px anyway. Do not
  change the *operation* defaults to match — Studio and scripts depend on them.

### Selection, drag and drop

- **`getSelectedPageRanges()` and `getNewPageOrder()` speak in `originalPageNumber`, never in grid
  positions.** Extract and Remove run on the source PDF, which a drag and drop does *not* reorder:
  returning positions made the first tile after a reorder resolve to original page 1 instead of page
  5, so Extract produced the wrong pages and Remove + `newFile` silently deleted them.
  `_getSelectedPageRanges()` collects `originalPageNumber` **and sorts ascending** before folding the
  ranges — the folding loop assumes an ascending list, which positions gave for free and original
  numbers do not. `selection-harness.js` guards this.
- Drag and drop moves **every selected page**, not just the grabbed tile, and regroups them
  contiguously at the drop point keeping their relative order. Grabbing a tile outside the selection
  makes it the selection first.
  - The drag handlers manipulate classes through `classList`, **never through a bound property**:
    changing one would re-render the `dom-repeat` and abort the drag. That is also why the count badge
    is a node already in the template whose text is filled imperatively.
  - The auto-scroll pointer (`_dragMouseY`) is fed by a `dragover` listener on the **container**,
    bound in `_bindScroll`, not only by the per-tile handler: `dragover` does not fire over the grid
    gap, the padding or the empty area under the last row — exactly where the user goes to reach the
    edge and trigger the scroll.
  - `_onDrop` replaces `pages`, so the `dom-repeat` re-renders and nodes get recycled. Always clear
    the drag classes on **every** tile in `_onDragEnd`, never on `e.currentTarget` alone.
- **Never loop a `set()` over every page.** Selection helpers go through `_selectOnly()`, which
  notifies only the pages whose state actually changes. A `set()` per page is 1000 Polymer
  notifications on a long document, and a double click fires two `click` before the `dblclick`, so it
  was 2000 in a row: the `dom-repeat` re-rendered wholesale, the container lost its height and
  `scrollTop` fell back to 0 — opening a page preview sent the user back to page 1.
- `_sourcesChanged` calls `reset()` first, wiping selection, reorder state and `_originalOrder`. Any
  future path re-assigning `sources` mid-session destroys the user's work silently.

### Scroll survival — the hardest thing here

- **Closing an overlay stacked on top of the dialog makes the platform re-open the dialog.** Measured
  on a 1000 pages PDF: `iron-overlay-closed` fires on the preview with `scrollTop` still intact, then
  `iron-overlay-opened` fires **twice** on `#dialog` and the content container goes through
  `clientHeight = 0` / `scrollHeight = 0`. That wipes `scrollTop` with **no assignment at all** —
  nothing to intercept, only something to put back. Restoring on `iron-overlay-closed` is therefore
  always too early: `_onDialogOverlayOpened` + `_restoreScrollTop()` retry until the height is back
  and the value sticks.
- **Do not "restore" when nothing was lost.** `_onPreviewDialogClosed` only *arms* `_restorePending`.
  An early restore finds the value already correct, declares success and consumes
  `_scrollTopBeforePreview`, so the real wipe has nothing left to put back — that defect silently
  neutralised a whole fix.
- **The live `scrollTop` wins over `_lastScrollTop`.** The memorised value is only a fallback for a
  position already wiped before we read it. Trusting it first froze the saved position at whatever the
  first restoration had written, and every later preview came back to that same page.
- **Listeners inside this dialog must be self-healing.** `_bindScroll()` (grid) and
  `_bindDialogScroll()` (orchestrator) both re-attach when the container changed or left the document,
  because a stack of Polymer overlays detaches and re-attaches elements and the content container is
  replaced when the dialog re-opens. Binding once was not enough — the grid silently stopped loading
  anything on scroll. `refreshScrollBinding()` is the public entry point the orchestrator calls.
- Two traps when debugging this by hand: `scroll` events are **not `composed`**, so a listener on
  `document` never sees a scroll inside a shadow root; and `document.contains(node)` **does not cross
  shadow boundaries** and answers `false` for a live node — use `node.isConnected`.
- **A branch that logs only when it acts is a blind spot.** `_onDialogOverlayOpened` logs its decision
  every time, including "nothing to do". The previous version put its only `console.log` inside the
  `if`, which hid the fact that the restoration was being skipped.

### Diagnostics and UI tests

- `debug` on `<nuxeo-pdf-toolkit>` traces the whole chain in the console (visible pages, chunk
  queued/skipped/applied, tiles filled). `window.NUXEO_PDF_TOOLKIT_DEBUG = true` sets it without a
  Studio change; the property's `value:` reads that global at creation time **and** `_openDialog()`
  re-reads it, because the element is created with the document page long before anyone can set it
  from the console. `localStorage.NUXEO_PDF_TOOLKIT_DEBUG` is honoured in `_openDialog()` only.
- **`src/test/js/scroll-harness.js` and `selection-harness.js` are the only automated UI checks.**
  They load the real element definition out of the HTML and mock everything around it — no PDF, no
  server, no browser, ~1.5 s each. Run `scroll-harness.js` after touching the scroll, preview or
  dialog logic; `selection-harness.js` after touching selection, drag and drop or range logic. They
  are deliberately outside `mvn clean install` (the plugin has no JS build). They call private methods
  and depend on the four operation element ids, so renaming those breaks them — fix the harness, do
  not delete it. Everything else in the UI is verified by packaging plus a manual run on a server.
- The spinner overlay has a hard 2 s floor and the dialog closes from that timer, so every action
  keeps the dialog open at least 2 s however fast the server answered. That is intentional, not a bug.
- In `-actions.html`, selection highlighting relies on
  `this.root.querySelectorAll('.destination-option')`. Do not wrap those options in a
  `<nuxeo-filter>`: the templatizer moves them out of that query's scope. Use `hidden$=` instead.
- i18n: keys namespaced `pdftoolkit.*` in `ui/i18n/messages.json` and `messages-fr.json`.
  `OSGI-INF/deployment-fragment.xml` **appends** them to the server files and maps `messages-fr.json`
  to both `fr` and `fr-FR`. Add every new key to both files; never hardcode a user-visible string.

## Known gaps, deliberately not fixed

Both were specced against a real deployment (S3 blobs, a burstable EC2 instance) and left undone
because the common case — ordinary PDFs of a few dozen pages — is already fast. Neither is a bug,
and `README.md` carries the operational side of the story under "About Big PDFs and S3 Storage".

- **The chunk queue never drops stale entries.** `_chunkQueue` is FIFO and nothing re-checks whether
  a queued chunk is still on screen, so on a slow backend a fast scroll renders every chunk you
  passed *before* the one you are looking at. Invisible locally, where chunks drain faster than a
  user scrolls; at seconds per chunk it multiplies the wait several-fold. If it is ever fixed:
  record the set from the latest `_onChunkNeeded`, drop queued heads no longer in it, and
  `delete this._pendingChunks[start]` for every one dropped — otherwise that chunk becomes
  permanently unrequestable, the same failure `applyChunk` already guards against. Worth a case in
  `scroll-harness.js`.
- **`PDFRenderer.setSubsamplingAllowed` is left at its default `false`.** Enabling it at the two
  thumbnail sites — `renderChunk` and `createThumbnails`, *not* `getJpegPreviewImage`, where the
  quality trade stops being free — lets PDFBox read fewer pixels from large embedded images instead
  of decoding them in full and throwing the detail away. Costs nothing on text PDFs, a real win on
  scanned ones. Available in the PDFBox the platform pins (3.0.4).

## Conventions

- MANIFEST `Nuxeo-Component`: one entry per line, single leading space on continuation lines,
  trailing newline at EOF (the last header is silently dropped otherwise).
- `Bundle-Version` is hardcoded in both MANIFESTs and **must be bumped by hand at each release**, to
  the release version without the `-SNAPSHOT` suffix. `mvn versions:set` does not touch manifests and
  `/nuxeo-release-plugin` has no step for them, so this bullet is the only thing that makes it
  happen. It cannot be filtered from `${project.version}` either: a `-SNAPSHOT` version is not a
  valid OSGi version (the dash is illegal).
- `Bundle-SymbolicName` follows `groupId.artifactId` in both modules. The core one is referenced by
  `@Deploy(...)` in the 4 test classes — renaming it breaks them.
- Java: 4 spaces, K&R, ~120 cols, no wildcard imports, `jakarta.*` not `javax.*` (`javax.imageio` is
  the legitimate exception), Log4j2 `LogManager.getLogger()`, `Framework.getService()` for lookups,
  checked exceptions wrapped in `NuxeoException`, `@since 2025.XX` on new public API.
- Exception messages name the offending blob/document/property. They are the only diagnostic a
  support engineer gets — **which is why every caller-fixable failure must be a 4xx**. Nuxeo replaces
  the message of a 5xx with a bare "Internal Server Error" before it leaves the server
  (`JsonWebengineWriter.getExceptionMessage`), so an exception left at the default status arrives
  stripped of the wording you just wrote. Raise them through `PDFTools.badRequest(...)`, which carries
  `SC_BAD_REQUEST`. Keep a plain `NuxeoException` (500) only for genuine server faults — an
  `IOException` while reading or writing the PDF.
- The Web UI reads that message back: `_serverMessage(error)` parses `error.response`, because the JS
  client only puts the HTTP status text in `error.message` and leaves the body untouched on
  `error.response`. `_notifyFailure` shows it and falls back on a generic i18n key. This is also what
  makes `_notifyChunkError`'s page-limit detection work — it used to match on `JSON.stringify(error)`,
  which serializes an `Error` to `{}`, so the branch was dead code.
- Comments: `//` per line for 1–3 lines, block comment for 4+.
- Known typos kept for compatibility — do not "fix" without checking callers:
  `PDFJpegimagePreviewOp` (lowercase `image`), `TEST_PDF_PAH` in the 4 test classes,
  `PDFToImages.setheight` (deprecated, delegates to `setHeight`).
