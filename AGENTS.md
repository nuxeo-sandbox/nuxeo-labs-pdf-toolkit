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
  `PDFPageOrdering`, `PDFToImages`) plus `PDFTools` (page-range parsing, blob saving) and
  `PDFDestinationHandler`.
- Every worker has the same 3 constructors: `(Blob)`, `(DocumentModel)`,
  `(DocumentModel, String xpath)` defaulting to `file:content`. Keep that pattern.
- `PDFDestinationHandler` owns the shared `destinationJsonStr` contract
  (`download` / `derivative` / `attachments` / `newFile`). Any new mutating operation should
  delegate to it rather than re-implementing a destination.
- PDFBox 3 API: use `Loader.loadPDF(...)`, **not** `PDDocument.load(...)`. PDFBox and
  `org.json` versions are inherited from the platform BOM — do not add versions to the pom.
- Operations live in `nuxeo.labs.pdf.toolkit.operations`, IDs prefixed `PDFLabs.`, category
  `CAT_CONVERSION`, each with two `@OperationMethod` overloads (`DocumentModel` and `Blob`).
  A new operation must be added to `OSGI-INF/operations-contrib.xml`; the MANIFEST already
  lists that file.
- Thumbnails and previews are cached in a TransientStore named `PDFToolkitCache`, contributed
  by `OSGI-INF/cache-contrib.xml`. Changing cache keys silently breaks `TestTheToolkit`.

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

## Web UI (`-webui`)

Polymer 2 / Web UI legacy elements under
`src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/`:

- `nuxeo-pdf-toolkit-bundle.html` is the **only** file registered in
  `OSGI-INF/webresources-contrib.xml`. It imports `nuxeo-pdf-toolkit.html` and contributes the
  `DOCUMENT_ACTIONS` slot content. A new element must be imported from `nuxeo-pdf-toolkit.html`,
  not registered separately.
- `nuxeo-pdf-toolkit.html` is the orchestrator: it owns the four `<nuxeo-operation>` elements
  and all operation calls. Children are dumb and event-based:
  - `-thumbnails.html` → selection/drag-drop, exposes `getSelectedPageRanges()` /
    `getNewPageOrder()`, notifies `hasSelection` / `hasReordered`, fires `page-preview`.
  - `-actions.html` → buttons + destination dialog, calls **no** operation, fires
    `action-complete`.
  - `-preview.html` → owns its own `PDFLabs.JpegImagePreview` operation.
- i18n: keys are namespaced `pdftoolkit.*` and live in `ui/i18n/messages.json` and
  `messages-fr.json`. `OSGI-INF/deployment-fragment.xml` **appends** these to the server files
  and maps `messages-fr.json` to both `fr` and `fr-FR`. Add every new key to both files.
- There is no test harness for the UI — changes here are verified by `mvn clean install`
  compiling/packaging only, and manually in a running server.

## Conventions

- MANIFEST `Nuxeo-Component`: one entry per line, single leading space on continuation lines,
  trailing newline at EOF (last header is dropped otherwise).
- Java: 4 spaces, K&R, ~120 cols, no wildcard imports, `jakarta.*` not `javax.*`, Log4j2
  `LogManager.getLogger()`, `Framework.getService()` for lookups, checked exceptions wrapped in
  `NuxeoException`, `@since 2025.XX` on new public API.
- Comments: `//` per line for 1–3 lines, block comment for 4+.
- Known typos kept for compatibility — do not "fix" without checking callers:
  `PDFJpegimagePreviewOp` (lowercase `image`), `TEST_PDF_PAH` in tests.
