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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public void shouldRenderWholeDocumentWhenSingleThumbnailMissesTheCache() throws Exception {

        Blob b = createTestDocBlob();
        assertTrue(cacheKeys().isEmpty());

        // No cache at all: getThumbnail must render everything once, not just the asked page,
        // otherwise serving N pages would reopen and reparse the PDF N times.
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
}
