/*
 * (C) Copyright 2025 Hyland (http://hyland.com/)  and others.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Contributors:
 *     Thibaud Arguillere
 */
package nuxeo.labs.pdf.toolkit.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreProvider;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreService;
import org.nuxeo.ecm.platform.picture.api.ImageInfo;
import org.nuxeo.ecm.platform.picture.api.ImagingService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;
import org.nuxeo.runtime.transaction.TransactionHelper;

import jakarta.inject.Inject;
import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.operations.PDFPageRemoverOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPrepareThumbnailsOp;

/**
 * Test the caching and the rendering bounds. About everything else is tested in TestOperations*.
 */
@RunWith(FeaturesRunner.class)
@Features({ AutomationFeature.class })
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")
public class TestTheToolkit {

    public static final String TEST_PDF_PAH = "lorem_ipsum_10_pages.pdf";

    public static final int TEST_PDF_PAGE_COUNT = 10;

    public static final String TEXT_PAGE_3 = "HERE SOME TEXT FOR THE UNIT TEST";

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature txFeature;

    @Inject
    protected AutomationService automationService;

    protected TransientStore store;

    @Before
    public void cleanCache() {
        store = Framework.getService(TransientStoreService.class).getStore(PDFToImages.TRANSIENT_STORE_NAME);
        // Make every test independent from the execution order.
        ((TransientStoreProvider) store).removeAll();
    }

    protected Set<String> cacheKeys() {
        return ((TransientStoreProvider) store).keySet();
    }

    /**
     * Caching requires a blob that can be identified by content, so we need a blob stored in the repository
     * (it then carries a digest), not a bare FileBlob.
     */
    protected DocumentModel createTestDoc() {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);

        DocumentModel doc = session.createDocumentModel("/", "testFile", "File");
        doc.setPropertyValue("file:content", new FileBlob(f));
        doc = session.createDocument(doc);
        txFeature.nextTransaction();

        return session.getDocument(doc.getRef());
    }

    protected Blob createTestDocBlob() {

        Blob blob = (Blob) createTestDoc().getPropertyValue("file:content");
        assertNotNull(blob);

        return blob;
    }

    @Test
    public void shouldUseTransientStore() throws Exception {

        assertTrue(cacheKeys().isEmpty());

        Blob b = createTestDocBlob();

        BlobList thumbnails = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());
        assertEquals(1, cacheKeys().size());

        // Same blob, same parameters: served from the cache, no new entry.
        thumbnails = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());
        assertEquals(1, cacheKeys().size());
    }

    @Test
    public void shouldNotCacheBlobWithoutDigest() throws Exception {

        // A bare FileBlob has no digest and no storage key: caching must be disabled rather than
        // fall back on a weak key that could collide with another document.
        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        BlobList thumbnails = new PDFToImages(new FileBlob(f)).createThumbnails();

        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());
        assertTrue(cacheKeys().isEmpty());
    }

    @Test
    public void shouldNotServeCachedThumbnailsOfAnotherSize() throws Exception {

        Blob b = createTestDocBlob();
        ImagingService imaging = Framework.getService(ImagingService.class);

        BlobList small = new PDFToImages(b).createThumbnails(120, 120);
        ImageInfo smallInfo = imaging.getImageInfo(small.get(0));
        assertTrue(smallInfo.getWidth() <= 120);
        assertTrue(smallInfo.getHeight() <= 120);

        BlobList large = new PDFToImages(b).createThumbnails(700, 700);
        ImageInfo largeInfo = imaging.getImageInfo(large.get(0));
        assertTrue("Cache key must include the requested size", largeInfo.getHeight() > 120);
        assertTrue(largeInfo.getWidth() <= 700);
        assertTrue(largeInfo.getHeight() <= 700);

        // Two different renderings, so two different cache entries.
        assertEquals(2, cacheKeys().size());
    }

    @Test
    public void shouldIgnorePoisonedCacheEntry() throws Exception {

        Blob b = createTestDocBlob();

        BlobList thumbnails = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());

        Set<String> keys = cacheKeys();
        assertEquals(1, keys.size());
        String key = keys.iterator().next();

        /*
         * Simulate what a failed run used to leave behind: an entry that exists and is flagged completed,
         * but holds no blob at all. It must be ignored and recomputed, not served as an empty result.
         */
        store.remove(key);
        store.setCompleted(key, false);
        store.setCompleted(key, true);
        assertTrue(store.exists(key));
        assertTrue(store.getBlobs(key).isEmpty());

        thumbnails = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());
    }

    @Test
    public void shouldIgnoreUnfinishedCacheEntry() throws Exception {

        Blob b = createTestDocBlob();

        BlobList thumbnails = new PDFToImages(b).createThumbnails();
        String key = cacheKeys().iterator().next();

        // A concurrent request that only reserved the key must not be seen as a usable result.
        store.remove(key);
        store.setCompleted(key, false);
        assertTrue(store.exists(key));
        assertFalse(store.isCompleted(key));

        thumbnails = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());
    }

    @Test
    public void shouldReturnSamePreviewWhetherCachedOrNot() throws Exception {

        Blob b = createTestDocBlob();
        ImagingService imaging = Framework.getService(ImagingService.class);

        Blob first = new PDFToImages(b).getJpegPreviewImage(3);
        ImageInfo firstInfo = imaging.getImageInfo(first);
        assertTrue(firstInfo.getWidth() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);
        assertTrue(firstInfo.getHeight() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);

        // Second call is a cache hit: it must return the very same image, not the full size one.
        Blob second = new PDFToImages(b).getJpegPreviewImage(3);
        ImageInfo secondInfo = imaging.getImageInfo(second);
        assertEquals(firstInfo.getWidth(), secondInfo.getWidth());
        assertEquals(firstInfo.getHeight(), secondInfo.getHeight());
    }

    @Test
    public void shouldClampRenderingParameters() throws Exception {

        PDFToImages tool = new PDFToImages(createTestDocBlob());

        tool.setDpi(99999);
        tool.setSize(99999, 99999);

        BlobList thumbnails = tool.createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, thumbnails.size());

        ImageInfo info = Framework.getService(ImagingService.class).getImageInfo(thumbnails.get(0));
        assertTrue("Thumbnail size must be clamped to MAX_THUMBNAIL_SIZE",
                info.getWidth() <= PDFToImages.MAX_THUMBNAIL_SIZE);
        assertTrue(info.getHeight() <= PDFToImages.MAX_THUMBNAIL_SIZE);
    }

    @Test
    public void shouldRejectNonPdfBlob() throws Exception {

        Blob notAPdf = org.nuxeo.ecm.core.api.Blobs.createBlob("I am not a PDF", "text/plain");
        try {
            new PDFToImages(notAPdf);
            fail("Should have rejected a non-PDF blob");
        } catch (NuxeoException e) {
            assertTrue(e.getMessage().contains("is not a PDF"));
        }
    }

    @Test
    public void shouldRejectDocumentWithoutBlob() throws Exception {

        DocumentModel empty = session.createDocument(session.createDocumentModel("/", "empty", "File"));
        try {
            new PDFToImages(empty);
            fail("Should have rejected a document with no blob");
        } catch (NuxeoException e) {
            assertTrue(e.getMessage().contains("has no blob"));
        }
    }

    // ========================================
    // Single page thumbnail, used by the REST endpoint
    // ========================================
    @Test
    public void shouldGetSingleThumbnailFromCache() throws Exception {

        Blob b = createTestDocBlob();

        // Fill the cache the way PDFLabs.PrepareThumbnails does
        assertEquals(TEST_PDF_PAGE_COUNT, new PDFToImages(b).createThumbnails().size());
        assertEquals(1, cacheKeys().size());

        Blob thumbnail = new PDFToImages(b).getThumbnail(3);
        assertNotNull(thumbnail);
        assertEquals("image/jpeg", thumbnail.getMimeType());

        // Serving a page must not create another entry: it only reads the cache
        assertEquals(1, cacheKeys().size());
    }

    @Test
    public void shouldRenderTheChunkWhenSingleThumbnailMissesTheCache() throws Exception {

        Blob b = createTestDocBlob();
        assertTrue(cacheKeys().isEmpty());

        /*
         * No cache at all: getThumbnail must render the whole chunk holding the page, not just that
         * page, otherwise serving N pages would reopen and reparse the PDF N times. The test PDF is
         * shorter than the default chunk size, so one chunk covers it entirely.
         */
        Blob thumbnail = new PDFToImages(b).getThumbnail(7);
        assertNotNull(thumbnail);

        assertEquals(1, cacheKeys().size());
        assertEquals(TEST_PDF_PAGE_COUNT, new PDFToImages(b).createThumbnails().size());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectThumbnailPageAboveDocumentPageCount() throws Exception {
        new PDFToImages(createTestDocBlob()).getThumbnail(TEST_PDF_PAGE_COUNT + 1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectThumbnailPageZero() throws Exception {
        new PDFToImages(createTestDocBlob()).getThumbnail(0);
    }

    // ========================================
    // PDFLabs.PrepareThumbnails
    // ========================================
    @Test
    public void shouldPrepareThumbnailsAndReturnUrls() throws Exception {

        DocumentModel doc = createTestDoc();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Blob result = (Blob) automationService.run(ctx, PDFPrepareThumbnailsOp.ID, new HashMap<>());
        assertNotNull(result);
        assertEquals("application/json", result.getMimeType());

        JSONObject json = new JSONObject(result.getString());
        assertEquals(TEST_PDF_PAGE_COUNT, json.getInt("pageCount"));

        JSONArray urls = json.getJSONArray("urls");
        assertEquals(TEST_PDF_PAGE_COUNT, urls.length());

        // One URL per page, pointing at the WebEngine module, carrying the rendering parameters
        String first = urls.getString(0);
        assertTrue("Unexpected URL: " + first, first.startsWith("site/pdftoolkit/thumb/" + doc.getId() + "/1?"));
        assertTrue(first.contains("w=512"));
        assertTrue(first.contains("h=512"));
        assertTrue(first.contains("dpi=150"));
        assertTrue(urls.getString(TEST_PDF_PAGE_COUNT - 1).contains("/" + TEST_PDF_PAGE_COUNT + "?"));

        // The whole point: the cache is filled, so the endpoint never has to open the PDF
        assertEquals(1, cacheKeys().size());
    }

    @Test
    public void shouldPrepareThumbnailsWithCustomSize() throws Exception {

        DocumentModel doc = createTestDoc();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("width", 120);
        params.put("height", 120);
        Blob result = (Blob) automationService.run(ctx, PDFPrepareThumbnailsOp.ID, params);

        JSONObject json = new JSONObject(result.getString());
        String first = json.getJSONArray("urls").getString(0);
        assertTrue("Rendering parameters must travel in the URL: " + first, first.contains("w=120"));
        assertTrue(first.contains("h=120"));
    }

    /** Extract the value of the content token from a thumbnail URL. */
    protected String contentTokenOf(String url) {
        Matcher m = Pattern.compile("[?&]v=([^&]+)").matcher(url);
        assertTrue("No content token in URL: " + url, m.find());
        return m.group(1);
    }

    protected JSONObject prepareThumbnails(DocumentModel doc) throws Exception {
        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Blob result = (Blob) automationService.run(ctx, PDFPrepareThumbnailsOp.ID, new HashMap<>());
        return new JSONObject(result.getString());
    }

    @Test
    public void shouldPutTheContentTokenInTheUrls() throws Exception {

        DocumentModel doc = createTestDoc();
        Blob pdf = (Blob) doc.getPropertyValue("file:content");

        JSONArray urls = prepareThumbnails(doc).getJSONArray("urls");
        assertEquals(pdf.getDigest(), contentTokenOf(urls.getString(0)));
    }

    /**
     * The bug this guards against: the URL carries the document id, so replacing file:content used to
     * produce the very same URLs. Combined with a long max-age, the browser kept serving the previous
     * thumbnails, and reopening the dialog showed the pages in their old order.
     */
    @Test
    public void shouldChangeTheUrlsWhenTheBlobIsReplaced() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject before = prepareThumbnails(doc);
        assertEquals(TEST_PDF_PAGE_COUNT, before.getInt("pageCount"));
        String tokenBefore = contentTokenOf(before.getJSONArray("urls").getString(0));

        // Replace file:content the way the dialog does, here by removing pages
        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "1-4");
        params.put("destinationJsonStr", "{\"destination\":\"newFile\"}");
        automationService.run(ctx, PDFPageRemoverOp.ID, params);
        txFeature.nextTransaction();

        doc = session.getDocument(doc.getRef());

        JSONObject after = prepareThumbnails(doc);
        assertEquals(TEST_PDF_PAGE_COUNT - 4, after.getInt("pageCount"));
        String tokenAfter = contentTokenOf(after.getJSONArray("urls").getString(0));

        assertNotEquals("A new PDF must yield new URLs, otherwise the browser serves stale thumbnails",
                tokenBefore, tokenAfter);

        // And the server did render the new content, not reuse the old cache entry
        assertEquals(2, cacheKeys().size());
    }

    // ========================================
    // Chunked rendering
    //
    // The test PDF has 10 pages, well below the default chunk size of 50, so the chunk size is lowered
    // per test rather than introducing a second, bigger fixture.
    // ========================================

    /** Counts how many times the PDF is actually opened and rendered. */
    protected static final AtomicInteger RENDER_COUNT = new AtomicInteger();

    static class CountingPDFToImages extends PDFToImages {

        CountingPDFToImages(Blob b) {
            super(b);
        }

        @Override
        protected ThumbnailsChunk renderChunk(TransientStore store, String cacheKey, int chunkStart, int chunkSize,
                String reason) {
            RENDER_COUNT.incrementAndGet();
            return super.renderChunk(store, cacheKey, chunkStart, chunkSize, reason);
        }
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRenderOnlyTheRequestedChunk() throws Exception {

        Blob b = createTestDocBlob();

        PDFToImages.ThumbnailsChunk chunk = new PDFToImages(b).prepareChunk(1);

        // The chunk is bounded, but the page count of the whole document is known
        assertEquals(3, chunk.thumbnails().size());
        assertEquals(1, chunk.chunkStart());
        assertEquals(3, chunk.chunkEnd());
        assertEquals(3, chunk.chunkSize());
        assertEquals(TEST_PDF_PAGE_COUNT, chunk.pageCount());

        // Only one chunk was rendered, so only one cache entry exists
        assertEquals(1, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldSnapAnyPageToItsChunk() throws Exception {

        Blob b = createTestDocBlob();

        // Pages 4, 5 and 6 all belong to the chunk starting at 4
        for (int pageNum : new int[] { 4, 5, 6 }) {
            PDFToImages.ThumbnailsChunk chunk = new PDFToImages(b).prepareChunk(pageNum);
            assertEquals("Page " + pageNum, 4, chunk.chunkStart());
            assertEquals("Page " + pageNum, 6, chunk.chunkEnd());
        }

        assertEquals("The three pages share one chunk, hence one cache entry", 1, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRenderALastPartialChunk() throws Exception {

        Blob b = createTestDocBlob();

        // 10 pages with a chunk size of 3: the last chunk starts at 10 and holds a single page
        PDFToImages.ThumbnailsChunk chunk = new PDFToImages(b).prepareChunk(10);
        assertEquals(10, chunk.chunkStart());
        assertEquals(10, chunk.chunkEnd());
        assertEquals(1, chunk.thumbnails().size());
        assertEquals(TEST_PDF_PAGE_COUNT, chunk.pageCount());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRejectAChunkBeyondTheDocument() throws Exception {

        try {
            new PDFToImages(createTestDocBlob()).prepareChunk(100);
            fail("Should have rejected a chunk starting past the end of the document");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("exceeds document page count"));
        }
    }

    /**
     * The point of the whole design: serving every page of a document costs one PDF opening per chunk,
     * never one per page. On a remote blob store, one opening per page means one full download per page.
     */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldOpenThePdfOncePerChunkNotOncePerPage() throws Exception {

        Blob b = createTestDocBlob();
        RENDER_COUNT.set(0);

        for (int pageNum = 1; pageNum <= TEST_PDF_PAGE_COUNT; pageNum++) {
            assertNotNull(new CountingPDFToImages(b).getThumbnail(pageNum));
        }

        // ceil(10 / 3) == 4
        assertEquals("One rendering per chunk", 4, RENDER_COUNT.get());
        assertEquals(4, cacheKeys().size());
    }

    /**
     * A browser opens up to six connections to the same host, so six thumbnails of the same cold chunk
     * can reach six threads at once. Without the render lock, each of them would render the very same
     * pages.
     */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRenderAChunkOnlyOnceUnderConcurrency() throws Exception {

        Blob b = createTestDocBlob();
        RENDER_COUNT.set(0);

        int threads = 6;
        CyclicBarrier startTogether = new CyclicBarrier(threads);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            // Pages 1, 2 and 3 are all in the very same chunk
            int pageNum = (i % 3) + 1;
            new Thread(() -> {
                try {
                    startTogether.await(30, TimeUnit.SECONDS);
                    TransactionHelper.runInTransaction(() -> {
                        assertNotNull(new CountingPDFToImages(b).getThumbnail(pageNum));
                    });
                } catch (Exception | AssertionError e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            }).start();
        }

        assertTrue("Threads did not finish in time", done.await(60, TimeUnit.SECONDS));
        assertTrue("Concurrent access failed: " + failures, failures.isEmpty());

        assertEquals("The render lock must let a single thread render the chunk", 1, RENDER_COUNT.get());
        assertEquals(1, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldStoreTheWholeDocumentAsChunks() throws Exception {

        Blob b = createTestDocBlob();
        RENDER_COUNT.set(0);

        // GetThumbnails still renders everything, but in a single PDF opening
        BlobList all = new CountingPDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, all.size());
        assertEquals("The full rendering must not go through renderChunk", 0, RENDER_COUNT.get());

        // Stored as chunks, so the endpoint reads the very same entries
        assertEquals(4, cacheKeys().size());

        // And a single page is then served without any rendering at all
        assertNotNull(new CountingPDFToImages(b).getThumbnail(8));
        assertEquals(0, RENDER_COUNT.get());
        assertEquals(4, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRebuildTheFullListFromTheChunkCache() throws Exception {

        Blob b = createTestDocBlob();

        assertEquals(TEST_PDF_PAGE_COUNT, new PDFToImages(b).createThumbnails().size());
        assertEquals(4, cacheKeys().size());

        // Second call is served from the per-chunk entries, in the right order and without re-rendering
        BlobList again = new PDFToImages(b).createThumbnails();
        assertEquals(TEST_PDF_PAGE_COUNT, again.size());
        assertEquals(4, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldRerenderWhenAChunkExpired() throws Exception {

        Blob b = createTestDocBlob();
        assertEquals(TEST_PDF_PAGE_COUNT, new PDFToImages(b).createThumbnails().size());

        // Drop the chunk holding pages 4 to 6, the way a TTL would
        store.remove(new PDFToImages(b).getChunkCacheKey(4));
        assertEquals(3, cacheKeys().size());

        // A partial set of chunks must not be served as a complete document
        assertEquals(TEST_PDF_PAGE_COUNT, new PDFToImages(b).createThumbnails().size());
        assertEquals(4, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldKeepChunksOfDifferentSizesApart() throws Exception {

        Blob b = createTestDocBlob();

        new PDFToImages(b).prepareChunk(1);
        new PDFToImages(b).prepareChunk(4);
        assertEquals(2, cacheKeys().size());

        // Same chunks, other rendering parameters: different entries
        PDFToImages small = new PDFToImages(b);
        small.setSize(120, 120);
        small.prepareChunk(1);
        assertEquals(3, cacheKeys().size());
    }

    // ========================================
    // PDFLabs.PrepareThumbnails, chunked
    // ========================================
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldReturnEveryUrlButRenderOneChunkOnly() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject json = prepareThumbnails(doc);

        // Every page gets an URL, so the caller can lay out its placeholders...
        assertEquals(TEST_PDF_PAGE_COUNT, json.getInt("pageCount"));
        assertEquals(TEST_PDF_PAGE_COUNT, json.getJSONArray("urls").length());

        // ...but only the first chunk was rendered
        assertEquals(3, json.getInt("chunkSize"));
        assertEquals(1, json.getInt("chunkStart"));
        assertEquals(3, json.getInt("chunkEnd"));
        assertEquals(1, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldPrepareAnotherChunkOnDemand() throws Exception {

        DocumentModel doc = createTestDoc();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("startPage", 8);
        Blob result = (Blob) automationService.run(ctx, PDFPrepareThumbnailsOp.ID, params);

        JSONObject json = new JSONObject(result.getString());
        assertEquals(7, json.getInt("chunkStart"));
        assertEquals(9, json.getInt("chunkEnd"));
        assertEquals(TEST_PDF_PAGE_COUNT, json.getInt("pageCount"));

        // Scrolling straight to the end must not have rendered the chunks in between
        assertEquals(1, cacheKeys().size());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.THUMBNAILS_MAX_PAGES_PROPERTY, value = "5")
    public void shouldRefusePdfAboveTheUiPageLimit() throws Exception {

        DocumentModel doc = createTestDoc();

        try {
            prepareThumbnails(doc);
            fail("Should have refused a PDF above the UI page limit");
        } catch (Exception e) {
            // Automation wraps runtime exceptions, walk the cause chain
            String message = "";
            for (Throwable t = e; t != null; t = t.getCause()) {
                message += t.getMessage() + " ";
            }
            assertTrue(message, message.contains("pages limit for the thumbnails UI"));
            assertTrue("The message must name the property to raise", message.contains(
                    PDFToImages.THUMBNAILS_MAX_PAGES_PROPERTY));
        }
    }

    @Test
    public void shouldNotLimitPrepareThumbnailsTo150PagesAnymore() throws Exception {

        // The default UI limit is well above the legacy 150 pages one, which used to fail the operation
        assertTrue(PDFToImages.getThumbnailsMaxPages() > PDFToImages.DEFAULT_MAX_PAGES);
        assertEquals(PDFToImages.DEFAULT_THUMBNAILS_MAX_PAGES, PDFToImages.getThumbnailsMaxPages());
    }

    @Test
    public void shouldFallBackOnDefaultsForBogusProperties() throws Exception {
        // No property set at all: the plugin must work out of the box, demo servers have no nuxeo.conf entry
        assertEquals(PDFToImages.DEFAULT_CHUNK_SIZE, PDFToImages.getChunkSize());
        assertEquals(PDFToImages.DEFAULT_THUMBNAILS_MAX_PAGES, PDFToImages.getThumbnailsMaxPages());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "not-a-number")
    public void shouldIgnoreANonNumericChunkSize() throws Exception {
        assertEquals(PDFToImages.DEFAULT_CHUNK_SIZE, PDFToImages.getChunkSize());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldReportWhetherTheChunkWasRendered() throws Exception {

        Blob b = createTestDocBlob();

        // Cold: the PDF is opened and the chunk rendered
        PDFToImages.ThumbnailsChunk cold = new PDFToImages(b).prepareChunk(1);
        assertTrue("A cold chunk must be reported as rendered", cold.rendered());
        assertTrue("A rendering takes a measurable time", cold.renderTimeMs() >= 0);

        // Warm: served from the cache, so the PDF is not opened at all
        PDFToImages.ThumbnailsChunk warm = new PDFToImages(b).prepareChunk(1);
        assertFalse("A cached chunk must not be reported as rendered", warm.rendered());
        assertEquals(0L, warm.renderTimeMs());
        assertEquals(cold.pageCount(), warm.pageCount());
    }

    /**
     * The diagnostics travel to the browser, which is how the chunking can be checked from the
     * network tab without touching log4j2.xml.
     */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldExposeRenderingDiagnosticsInTheResponse() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject first = prepareThumbnails(doc);
        assertTrue("First call renders", first.getBoolean("rendered"));
        assertTrue(first.has("renderTimeMs"));

        JSONObject second = prepareThumbnails(doc);
        assertFalse("Second call is a cache hit, no PDF opening", second.getBoolean("rendered"));
        assertEquals(0, second.getInt("renderTimeMs"));
    }

    @Test
    public void shouldNotBeVerboseByDefault() throws Exception {
        // Renderings are logged at info, which a default Nuxeo log4j2.xml hides. Opt-in only.
        assertFalse(PDFToImages.isVerboseRendering());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.VERBOSE_RENDERING_PROPERTY, value = "true")
    public void shouldTurnVerboseRenderingOn() throws Exception {
        assertTrue(PDFToImages.isVerboseRendering());
    }

    /**
     * Capture the levels the plugin logs at, so that a rendering path logging on its own instead of
     * going through logRendering() is caught.
     */
    protected List<LogEvent> captureRenderingLogs(Runnable action) {

        List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
        Logger pluginLogger = (Logger) LogManager.getLogger(PDFToImages.class);

        AbstractAppender collector = new AbstractAppender("TestCollector", null, null, true, null) {
            @Override
            public void append(LogEvent event) {
                if (event.getMessage().getFormattedMessage().startsWith("Rendered")) {
                    events.add(event.toImmutable());
                }
            }
        };
        collector.start();

        Level previous = pluginLogger.getLevel();
        pluginLogger.addAppender(collector);
        Configurator.setLevel(pluginLogger.getName(), Level.INFO);
        try {
            action.run();
        } finally {
            pluginLogger.removeAppender(collector);
            collector.stop();
            Configurator.setLevel(pluginLogger.getName(), previous);
        }

        return events;
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldLogChunkRenderingAtInfoByDefault() throws Exception {

        Blob b = createTestDocBlob();
        List<LogEvent> events = captureRenderingLogs(() -> new PDFToImages(b).prepareChunk(1));

        assertEquals(1, events.size());
        assertEquals(Level.INFO, events.get(0).getLevel());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    @WithFrameworkProperty(name = PDFToImages.VERBOSE_RENDERING_PROPERTY, value = "true")
    public void shouldLogChunkRenderingAtWarnWhenVerbose() throws Exception {

        Blob b = createTestDocBlob();
        List<LogEvent> events = captureRenderingLogs(() -> new PDFToImages(b).prepareChunk(1));

        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
    }

    /**
     * createThumbnails() is the other path that opens the PDF. It used to log on its own, so turning
     * verbose rendering on left it invisible — exactly when someone is counting PDF openings.
     */
    @Test
    public void shouldLogFullDocumentRenderingAtInfoByDefault() throws Exception {

        Blob b = createTestDocBlob();
        List<LogEvent> events = captureRenderingLogs(() -> new PDFToImages(b).createThumbnails());

        assertEquals(1, events.size());
        assertEquals(Level.INFO, events.get(0).getLevel());
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.VERBOSE_RENDERING_PROPERTY, value = "true")
    public void shouldLogFullDocumentRenderingAtWarnWhenVerbose() throws Exception {

        Blob b = createTestDocBlob();
        List<LogEvent> events = captureRenderingLogs(() -> new PDFToImages(b).createThumbnails());

        assertEquals("Every path that opens the PDF must honour verboseRendering", 1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
    }

    /** An endpoint fallback is abnormal, so it is a warning whatever the verbosity setting. */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldAlwaysLogEndpointFallbackAtWarn() throws Exception {

        Blob b = createTestDocBlob();
        List<LogEvent> events = captureRenderingLogs(() -> new PDFToImages(b).getThumbnail(5));

        assertEquals(1, events.size());
        assertEquals(Level.WARN, events.get(0).getLevel());
        assertTrue(events.get(0).getMessage().getFormattedMessage().contains("endpoint-fallback"));
    }

    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "0")
    public void shouldIgnoreAZeroChunkSize() throws Exception {
        assertEquals(PDFToImages.DEFAULT_CHUNK_SIZE, PDFToImages.getChunkSize());
    }
}
