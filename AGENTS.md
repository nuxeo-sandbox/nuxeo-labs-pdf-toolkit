# AGENTS.md — nuxeo-labs-pdf-toolkit

Nuxeo plugin (LTS 2025). Adds a "PDF Toolkit" Web UI dialog (thumbnails, page selection,
reorder) backed by 5 Automation operations. See `README.md` for the functional spec and the
full operation/parameter reference — it is accurate and worth reading before touching an
operation signature.

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
  `connect.nuxeo.com`.
- No CI, no formatter config, no lint step. `mvn clean install` is the whole gate.
- 56 tests across 4 classes, all green, ~45 s. A failure is a real regression, not flakiness.
- `target/` may hold stale artifacts from an old `lts2023` build — never trust it without a
  `clean`.

## Modules

| Module | What it is |
|---|---|
| `-core` | All Java: PDF logic + 5 operations. The only module with tests. |
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
- The two-phase split is the whole point and must be preserved: the operation opens, parses and
  renders the PDF **once**, the endpoint only reads the cache. Making the endpoint render the
  single page it was asked for would mean one `Loader.loadPDF()` **and one
  `getCloseableFile()` per page** — that is one full download of the PDF per page on a remote
  blob store. `PDFToImages.getThumbnail()` therefore renders the whole document on a cache miss.
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
- Cache keys are built by `buildCacheKey()` and **include the rendering parameters**
  (`width`, `height`, `dpi` for thumbnails; page number and preview constants for previews).
  Dropping them serves wrongly-sized images.
- A blob with neither digest nor `ManagedBlob` key is **not cached** (`buildCacheKey` returns
  `null`). Do not add a `filename + length` fallback: it can collide across documents.

### Rendering bounds — do not remove

All operations are reachable by any authenticated user, so `PDFToImages` clamps everything
**inside the setters** (`setWidth` / `setHeight` / `setDpi`). Never assign `width`, `height` or
`dpi` directly, and never trust an operation `@Param`.

| Constant | Value | Why |
|---|---|---|
| `DEFAULT_DPI` | 150 | Thumbnails are downscaled anyway; 512 rendered ~76 MB per A4 page. |
| `MAX_DPI` | 300 | Above this a single page can exhaust the heap. |
| `MAX_THUMBNAIL_SIZE` | 2000 | Same reason. |
| `DEFAULT_MAX_PAGES` | 150 | The thumbnails operation builds the whole base64 payload in memory, ~230 KB of heap per page. Applies to thumbnails only, not to extract/remove/reorder. |
| `PREVIEW_DPI` / `PREVIEW_PAGE_MAX_SIZE` | 300 / 1024 | Preview is rendered then resized by the `pictureResize` converter. Cache and return the **resized** blob, not the full-size one. |

## Configuration properties

| Property | Default |
|---|---|
| `nuxeo.pdftoolkit.maxPages` | 150 |
| `nuxeo.pdftoolkit.cache.targetMaxSizeMB` | 500 |
| `nuxeo.pdftoolkit.cache.absoluteMaxSizeMB` | 600 |

Documented in `README.md` too — update both.

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
  - `TestOperationsWithDownload` — the 5 operations with the default `download` destination,
    plus the negative tests on page ranges and page orders.
  - `TestOperationsDestinations` — the 4 destinations, plus the negative tests on
    `destinationJsonStr`. Always assert `checkOriginalNotModified()` when the operation is not
    supposed to touch the source.
  - `TestTheToolkit` — caching, rendering bounds, blob validation, single page thumbnail and
    `PDFLabs.PrepareThumbnails`, and the content token in the generated URLs.
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
- `GetThumbnails` returns base64 **JPEG**: the data URL prefix is `data:image/jpeg;base64,`.
- In `-actions.html`, selection highlighting relies on `this.root.querySelectorAll('.destination-option')`.
  Do not wrap those options in a `<nuxeo-filter>`: the templatizer moves them out of that query's
  scope. Use `hidden$=` instead.
- i18n: keys are namespaced `pdftoolkit.*` and live in `ui/i18n/messages.json` and
  `messages-fr.json`. `OSGI-INF/deployment-fragment.xml` **appends** these to the server files
  and maps `messages-fr.json` to both `fr` and `fr-FR`. Add every new key to both files, and
  never hardcode a user-visible string in an element.
- There is no test harness for the UI — changes here are verified by `mvn clean install`
  compiling/packaging only, and manually in a running server.

## Conventions

- MANIFEST `Nuxeo-Component`: one entry per line, single leading space on continuation lines,
  trailing newline at EOF (last header is dropped otherwise).
- `Bundle-Version` is hardcoded in both MANIFESTs and **must be bumped by hand at each release**,
  to the release version without the `-SNAPSHOT` suffix (`mvn versions:set` does not touch
  manifests). It cannot be filtered from `${project.version}`: `2025.6.0-SNAPSHOT` is not a valid
  OSGi version (the dash is illegal).
- `Bundle-SymbolicName` follows `groupId.artifactId` in both modules. The core one is
  referenced by `@Deploy(...)` in the 3 test classes — renaming it breaks them.
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
