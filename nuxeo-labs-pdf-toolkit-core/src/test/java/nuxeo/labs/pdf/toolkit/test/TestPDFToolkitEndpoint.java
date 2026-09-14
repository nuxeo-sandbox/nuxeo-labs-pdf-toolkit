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
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.function.Consumer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.api.security.ACE;
import org.nuxeo.ecm.core.api.security.ACL;
import org.nuxeo.ecm.core.api.security.ACP;
import org.nuxeo.ecm.core.api.security.SecurityConstants;
import org.nuxeo.ecm.platform.usermanager.UserManager;
import org.nuxeo.ecm.webengine.test.WebEngineFeature;
import org.nuxeo.http.test.HttpClientTestRule;
import org.nuxeo.http.test.handler.HttpStatusCodeHandler;
import org.nuxeo.http.test.handler.StringHandler;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.ServletContainerFeature;
import org.nuxeo.runtime.test.runner.TransactionalFeature;
import org.nuxeo.runtime.test.runner.WithFrameworkProperty;

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

    @Inject
    protected UserManager userManager;

    /** A user that exists but is explicitly denied Read on the test document. */
    public static final String NO_ACCESS_USER = "no-access-user";

    @Rule
    public final HttpClientTestRule httpClient = HttpClientTestRule.builder()
                                                                   .url(() -> servletContainerFeature.getHttpUrl())
                                                                   .adminCredentials()
                                                                   .build();

    /**
     * Run {@code action} with a client authenticated as {@link #NO_ACCESS_USER}.
     * <p>
     * Built by hand rather than declared as a second {@code @Rule}: FeaturesRunner binds every rule
     * into Guice by type, so two {@code HttpClientTestRule} fields fail the injector with
     * "bound multiple times".
     */
    protected void asUserWithoutAccess(Consumer<HttpClientTestRule> action) {

        HttpClientTestRule client = HttpClientTestRule.builder()
                                                      .url(() -> servletContainerFeature.getHttpUrl())
                                                      .credentials(NO_ACCESS_USER, NO_ACCESS_USER)
                                                      .build();
        client.starting();
        try {
            action.accept(client);
        } finally {
            client.finished();
        }
    }

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

    @Before
    public void createUserWithoutAccess() {
        if (userManager.getPrincipal(NO_ACCESS_USER) == null) {
            DocumentModel user = userManager.getBareUserModel();
            user.setPropertyValue("user:username", NO_ACCESS_USER);
            user.setPropertyValue("user:password", NO_ACCESS_USER);
            userManager.createUser(user);
        }
    }

    /**
     * Make the test document unreadable by {@link #NO_ACCESS_USER}, whatever the default ACP of the
     * test repository is.
     * <p>
     * Uses the canonical "block inheritance" ACE rather than a Read deny: the repository refuses a
     * negative ACL unless it is Everyone/Everything or Write. Administrators bypass ACLs, so the admin
     * client keeps working.
     */
    protected void blockAccessToTestDoc() {
        DocumentModel doc = session.getDocument(new IdRef(docId));
        ACP acp = doc.getACP();
        ACL acl = acp.getOrCreateACL(ACL.LOCAL_ACL);
        acl.add(new ACE(SecurityConstants.EVERYONE, SecurityConstants.EVERYTHING, false));
        session.setACP(doc.getRef(), acp, true);
        session.save();
        txFeature.nextTransaction();
    }

    /**
     * The document must not be served. Both 403 and 404 are correct: the repository answers 404 for a
     * document the user cannot even browse, which is the better of the two since it does not disclose
     * that the document exists. What matters is that it is never a 200.
     */
    protected void assertRefused(String message, Integer status) {
        assertTrue(message + " (got " + status + ")", status == 403 || status == 404);
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

    /**
     * The one security promise of this endpoint: it resolves the document through the <b>current user
     * session</b>, so the repository enforces the read permission. Nothing else protects it — there is
     * no guard on the WebObject — so an "optimisation" resolving through a system session would open
     * every PDF of the repository to everyone. Hence this test.
     */
    @Test
    public void shouldRefuseAThumbnailToAUserWithoutRead() {

        blockAccessToTestDoc();

        asUserWithoutAccess(client -> client.buildGetRequest(thumbPath(1))
                                            .executeAndConsume(new HttpStatusCodeHandler(),
                                                    status -> assertRefused(
                                                            "A user without Read must not get a thumbnail", status)));
    }

    /** Warming the cache as an administrator must not make the images readable by everyone. */
    @Test
    public void shouldRefuseAThumbnailFromTheCacheToAUserWithoutRead() {

        // Fill the cache first, as admin
        httpClient.buildGetRequest(thumbPath(1))
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));

        blockAccessToTestDoc();

        asUserWithoutAccess(client -> client.buildGetRequest(thumbPath(1))
                                            .executeAndConsume(new HttpStatusCodeHandler(),
                                                    status -> assertRefused(
                                                            "The cache is not an authorization bypass", status)));
    }

    /**
     * A blob that is not a PDF is a client error, not a server fault. It used to be a 500, so a chunk
     * of 50 tiles produced 50 stack traces in server.log.
     */
    @Test
    public void shouldReturn400OnANonPdfBlob() {

        DocumentModel doc = session.createDocumentModel("/", "notAPdf", "File");
        doc.setPropertyValue("file:content", (java.io.Serializable) Blobs.createBlob("I am not a PDF", "text/plain"));
        doc = session.createDocument(doc);
        session.save();
        txFeature.nextTransaction();

        httpClient.buildGetRequest("/pdftoolkit/thumb/" + doc.getId() + "/1")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(400, status.intValue()));
    }

    @Test
    public void shouldReturn400OnADocumentWithoutBlob() {

        DocumentModel doc = session.createDocument(session.createDocumentModel("/", "empty", "File"));
        session.save();
        txFeature.nextTransaction();

        httpClient.buildGetRequest("/pdftoolkit/thumb/" + doc.getId() + "/1")
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(400, status.intValue()));
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

    /**
     * With a chunk size below the page count, serving every page crosses several chunks. Each of them is
     * rendered on demand, so no page may fail — and above all, the endpoint never renders a single page
     * on its own.
     */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldServeEveryPageAcrossChunks() {
        for (int pageNum = 1; pageNum <= TEST_PDF_PAGE_COUNT; pageNum++) {
            int page = pageNum;
            httpClient.buildGetRequest(thumbPath(page)).executeAndConsume(response -> {
                assertEquals("Failed on page " + page, 200, response.getStatus());
                assertTrue(response.getType().startsWith("image/jpeg"));
            });
        }
    }

    /**
     * The dialog prepares the first chunk then lets the browser fetch the images. A page of another
     * chunk, asked before the UI got around to preparing it, must still be served.
     */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldServeAPageOfANotYetPreparedChunk() {
        httpClient.buildGetRequest(thumbPath(1))
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(200, status.intValue()));

        // Page 9 is in the chunk starting at 7, which nothing prepared
        httpClient.buildGetRequest(thumbPath(9)).executeAndConsume(response -> {
            assertEquals(200, response.getStatus());
            assertTrue(response.getType().startsWith("image/jpeg"));
        });
    }

    /** Two pages of two different chunks must not share an ETag. */
    @Test
    @WithFrameworkProperty(name = PDFToImages.CHUNK_SIZE_PROPERTY, value = "3")
    public void shouldSendADistinctEtagPerPage() {
        String etagPage2 = httpClient.buildGetRequest(thumbPath(2))
                                     .executeAndThen(response -> response.getFirstHeader("ETag"));
        String etagPage9 = httpClient.buildGetRequest(thumbPath(9))
                                     .executeAndThen(response -> response.getFirstHeader("ETag"));

        assertNotNull(etagPage2);
        assertNotNull(etagPage9);
        assertNotEquals(etagPage2, etagPage9);

        // And revalidation still works once the chunk is warm
        httpClient.buildGetRequest(thumbPath(9))
                  .addHeader("If-None-Match", etagPage9)
                  .executeAndConsume(new HttpStatusCodeHandler(), status -> assertEquals(304, status.intValue()));
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
