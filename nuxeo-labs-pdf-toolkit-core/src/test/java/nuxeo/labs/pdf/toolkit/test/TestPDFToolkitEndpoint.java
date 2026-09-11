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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.webengine.test.WebEngineFeature;
import org.nuxeo.http.test.HttpClientTestRule;
import org.nuxeo.http.test.handler.HttpStatusCodeHandler;
import org.nuxeo.http.test.handler.StringHandler;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.ServletContainerFeature;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import jakarta.inject.Inject;
import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.rest.PDFToolkitEndpoint;

/**
 * HTTP tests of the thumbnails endpoint.
 * <p>
 * Beware: in this test harness the WebEngine servlet is mapped on "/*", so the module is served at
 * {@code <httpUrl>/pdftoolkit}. In a real server it is mapped on "/site/*", hence
 * {@code /nuxeo/site/pdftoolkit} — which is what PDFLabs.PrepareThumbnails returns.
 */
@RunWith(FeaturesRunner.class)
@Features({ WebEngineFeature.class })
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")
public class TestPDFToolkitEndpoint {

    public static final String TEST_PDF_PAH = "lorem_ipsum_10_pages.pdf";

    public static final int TEST_PDF_PAGE_COUNT = 10;

    @Inject
    protected CoreSession session;

    @Inject
    protected TransactionalFeature txFeature;

    @Inject
    protected ServletContainerFeature servletContainerFeature;

    @Rule
    public final HttpClientTestRule httpClient = HttpClientTestRule.builder()
                                                                   .url(() -> servletContainerFeature.getHttpUrl())
                                                                   .adminCredentials()
                                                                   .build();

    protected String docId;

    protected String contentToken;

    @Before
    public void createTestDoc() {
        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        DocumentModel doc = session.createDocumentModel("/", "testFile", "File");
        doc.setPropertyValue("file:content", new FileBlob(f));
        doc = session.createDocument(doc);
        session.save();
        txFeature.nextTransaction();

        doc = session.getDocument(doc.getRef());
        docId = doc.getId();
        contentToken = ((Blob) doc.getPropertyValue("file:content")).getDigest();
    }

    protected String thumbPath(int pageNum) {
        return "/pdftoolkit/thumb/" + docId + "/" + pageNum;
    }

    @Test
    public void shouldRegisterTheWebEngineModule() {
        httpClient.buildGetRequest("/pdftoolkit/ping")
                  .executeAndConsume(new StringHandler(), body -> assertEquals("pong", body));
    }

    @Test
    public void shouldServeAThumbnailAsJpeg() {
        httpClient.buildGetRequest(thumbPath(3)).executeAndConsume(response -> {
            assertEquals(200, response.getStatus());
            assertTrue("Unexpected content type: " + response.getType(), response.getType().startsWith("image/jpeg"));
        });
    }

    @Test
    public void shouldServeEveryPage() {
        for (int pageNum = 1; pageNum <= TEST_PDF_PAGE_COUNT; pageNum++) {
            int page = pageNum;
            httpClient.buildGetRequest(thumbPath(page))
                      .executeAndConsume(new HttpStatusCodeHandler(),
                              status -> assertEquals("Failed on page " + page, 200, status.intValue()));
        }
    }

    @Test
    public void shouldSendAnEtagAndCacheControl() {
        httpClient.buildGetRequest(thumbPath(1)).executeAndConsume(response -> {
            assertEquals(200, response.getStatus());
            assertNotNull("An ETag is required for the browser to cache the thumbnails",
                    response.getFirstHeader("ETag"));
            String cacheControl = response.getFirstHeader("Cache-Control");
            assertNotNull(cacheControl);
            assertTrue("Thumbnails must not be cached by shared caches: " + cacheControl,
                    cacheControl.contains("private"));
        });
    }

    /**
     * A long freshness lifetime is only legitimate when the URL carries the content token, otherwise
     * replacing file:content leaves the browser on the previous thumbnails: same URL, still fresh.
     */
    @Test
    public void shouldOnlyAllowLongCachingOnAVersionedUrl() {

        httpClient.buildGetRequest(thumbPath(1)).addQueryParameter("v", contentToken).executeAndConsume(response -> {
            String cacheControl = response.getFirstHeader("Cache-Control");
            assertTrue("A content-addressed URL can be cached: " + cacheControl,
                    cacheControl.contains("max-age=" + PDFToolkitEndpoint.CACHE_MAX_AGE_SECONDS));
            assertTrue(cacheControl.contains("private"));
        });
    }

    @Test
    public void shouldForceRevalidationOnAPlainUrl() {

        httpClient.buildGetRequest(thumbPath(1)).executeAndConsume(response -> {
            String cacheControl = response.getFirstHeader("Cache-Control");
            assertTrue("Without a content token the browser must revalidate: " + cacheControl,
                    cacheControl.contains("no-cache"));
            // The ETag is still there, so revalidation costs a 304, not a full transfer
            assertNotNull(response.getFirstHeader("ETag"));
        });
    }

    @Test
    public void shouldAnswer304OnMatchingEtag() {
        String etag = httpClient.buildGetRequest(thumbPath(2))
                                .executeAndThen(response -> response.getFirstHeader("ETag"));
        assertNotNull(etag);

        httpClient.buildGetRequest(thumbPath(2))
                  .addHeader("If-None-Match", etag)
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(304, status.intValue()));
    }

    @Test
    public void shouldServeDifferentSizesSeparately() {
        httpClient.buildGetRequest(thumbPath(1))
                  .addQueryParameter("w", "120")
                  .addQueryParameter("h", "120")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));

        httpClient.buildGetRequest(thumbPath(1))
                  .addQueryParameter("w", "700")
                  .addQueryParameter("h", "700")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));
    }

    @Test
    public void shouldClampRenderingParametersFromTheUrl() {
        // The URL is no more trustable than an operation parameter: the bounds must apply here too.
        httpClient.buildGetRequest(thumbPath(1))
                  .addQueryParameter("w", "99999")
                  .addQueryParameter("h", "99999")
                  .addQueryParameter("dpi", "99999")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));
    }

    @Test
    public void shouldReturn404OnPageAboveDocumentPageCount() {
        httpClient.buildGetRequest(thumbPath(TEST_PDF_PAGE_COUNT + 1))
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(404, status.intValue()));
    }

    @Test
    public void shouldReturn404OnUnknownDocument() {
        httpClient.buildGetRequest("/pdftoolkit/thumb/not-a-document-id/1")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(404, status.intValue()));
    }

    @Test
    public void shouldStillServeAfterCacheWasWiped() {
        // First call fills the cache
        httpClient.buildGetRequest(thumbPath(5))
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));

        TestUtils.wipeThumbnailsCache();

        // The endpoint must fall back on a full render rather than fail
        httpClient.buildGetRequest(thumbPath(5)).executeAndConsume(response -> {
            assertEquals(200, response.getStatus());
            assertTrue(response.getType().startsWith("image/jpeg"));
        });
    }

    /** Kept close to the constants it relies on. */
    static class TestUtils {
        static void wipeThumbnailsCache() {
            var store = org.nuxeo.runtime.api.Framework.getService(
                    org.nuxeo.ecm.core.transientstore.api.TransientStoreService.class)
                                                       .getStore(PDFToImages.TRANSIENT_STORE_NAME);
            ((org.nuxeo.ecm.core.transientstore.api.TransientStoreProvider) store).removeAll();
        }
    }
}
