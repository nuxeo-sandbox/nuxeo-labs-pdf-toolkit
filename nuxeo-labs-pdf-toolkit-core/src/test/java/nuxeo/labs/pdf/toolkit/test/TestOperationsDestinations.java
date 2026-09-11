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
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.Serializable;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.json.JSONObject;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.TransactionalFeature;

import jakarta.inject.Inject;
import nuxeo.labs.pdf.toolkit.operations.PDFPageExtractorOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageRemoverOp;

/**
 * This class tests the destination of the operations. We don't check all operations (extract, remove, ...) nor the
 * resulting pdf itself, since this is well tested in {@code TestOperationsWithDownload}
 */
@RunWith(FeaturesRunner.class)
@Features({ AutomationFeature.class })
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")
public class TestOperationsDestinations {

    public static final String TEST_PDF_PAH = "lorem_ipsum_10_pages.pdf";

    protected static String TEST_PDF_MD5 = null;

    public static final int TEST_PDF_PAGE_COUNT = 10;

    public static final String TEXT_PAGE_3 = "HERE SOME TEXT FOR THE UNIT TEST";

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    @Inject
    protected TransactionalFeature txFeature;

    @BeforeClass
    public static void doOnce() throws Exception {
        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        TEST_PDF_MD5 = getMd5(f);
    }

    protected static String getMd5(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (InputStream fis = new FileInputStream(f);
                DigestInputStream dis = new DigestInputStream(fis, md)) {

            byte[] buffer = new byte[32768]; // 32 KB
            while (dis.read(buffer) != -1) {
            }
        }

        byte[] digest = md.digest();
        return HexFormat.of().formatHex(digest);
    }

    protected DocumentModel createTestDoc() {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);

        DocumentModel doc = session.createDocumentModel("/", "testFile", "File");
        doc.setPropertyValue("file:content", new FileBlob(f));
        doc = session.createDocument(doc);

        return doc;

    }

    protected void checkNumberOfPages(Blob pdf, int expected) throws Exception {

        assertNotNull(pdf);
        try (PDDocument pdfDoc = Loader.loadPDF(pdf.getFile())) {
            assertEquals("Unexpected page count in " + pdf.getFilename(), expected, pdfDoc.getNumberOfPages());
        }
    }

    protected void checkOriginalNotModified(DocumentModel doc) throws Exception {

        doc = session.getDocument(doc.getRef());
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        assertNotNull(blob);

        String currentMd5 = getMd5(blob.getFile());
        assertEquals(TEST_PDF_MD5, currentMd5);

    }

    /** Automation wraps runtime exceptions, so assert on the whole cause chain. */
    protected void assertFailureMentions(Exception e, String expected) {

        Throwable current = e;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(expected)) {
                return;
            }
            current = current.getCause();
        }
        fail("No exception in the chain mentions \"" + expected + "\". Root was: " + e);
    }

    protected Blob runExtract(DocumentModel doc, String destinationJson) throws Exception {

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        if (destinationJson != null) {
            params.put("destinationJsonStr", destinationJson);
        }

        return (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
    }

    @Test
    public void shouldCreateDerivativeWithDefault() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "derivative");

        Blob result = runExtract(doc, destinationObj.toString());
        assertNotNull(result);

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));

        assertTrue(resultJson.has("derivativeId"));
        String derivativeId = resultJson.getString("derivativeId");
        assertTrue(StringUtils.isNotBlank(derivativeId));

        DocumentModel copy = session.getDocument(new IdRef(derivativeId));
        checkNumberOfPages((Blob) copy.getPropertyValue("file:content"), 4);

        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldCreateDerivativeWithParams() throws Exception {

        DocumentModel doc = createTestDoc();

        String originalState = doc.getCurrentLifeCycleState();

        session.followTransition(doc, "approve");
        doc = session.getDocument(doc.getRef());
        assertEquals("approved", doc.getCurrentLifeCycleState());

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "derivative");
        JSONObject details = new JSONObject();
        details.put("resetLifeCycle", true);
        details.put("derivativeTitle", "THE COPY");
        destinationObj.put("details", details);

        Blob result = runExtract(doc, destinationObj.toString());
        assertNotNull(result);

        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertEquals("done", resultJson.getString("status"));

        String derivativeId = resultJson.getString("derivativeId");
        assertTrue(StringUtils.isNotBlank(derivativeId));

        DocumentModel copy = session.getDocument(new IdRef(derivativeId));
        checkNumberOfPages((Blob) copy.getPropertyValue("file:content"), 4);

        // Lifecycle reset
        assertEquals(originalState, copy.getCurrentLifeCycleState());
        // Correct title
        assertEquals("THE COPY", copy.getTitle());

        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldCreateDerivativeWhenTitleHoldsASlash() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "derivative");
        JSONObject details = new JSONObject();
        // A document name cannot hold a slash: the title must be normalized before being used as a name.
        details.put("derivativeTitle", "Contract 2026/2027");
        destinationObj.put("details", details);

        Blob result = runExtract(doc, destinationObj.toString());
        txFeature.nextTransaction();

        JSONObject resultJson = new JSONObject(result.getString());
        DocumentModel copy = session.getDocument(new IdRef(resultJson.getString("derivativeId")));

        // The title is kept as typed...
        assertEquals("Contract 2026/2027", copy.getTitle());
        // ... while the name, hence the path, is sanitized.
        assertTrue("Document name must not hold a slash: " + copy.getName(), !copy.getName().contains("/"));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddToFiles() throws Exception {

        DocumentModel doc = createTestDoc();

        List<Map<String, Serializable>> fileList = (List<Map<String, Serializable>>) doc.getPropertyValue(
                "files:files");
        assertTrue(fileList.isEmpty());

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "attachments");

        Blob result = runExtract(doc, destinationObj.toString());
        assertNotNull(result);

        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertEquals("done", resultJson.getString("status"));

        // Reload
        doc = session.getDocument(doc.getRef());
        fileList = (List<Map<String, Serializable>>) doc.getPropertyValue("files:files");
        assertEquals(1, fileList.size());

        // Just check it has 4 pages
        Map<String, Serializable> firstFiles = fileList.get(0);
        checkNumberOfPages((Blob) firstFiles.get("file"), 4);

        checkOriginalNotModified(doc);

    }

    @Test
    public void shouldRefuseAttachmentsOnSingleValuedProperty() throws Exception {

        DocumentModel doc = createTestDoc();

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "attachments");
        JSONObject details = new JSONObject();
        // file:content is not a list: appending would silently overwrite the main file.
        details.put("xpath", "file:content");
        destinationObj.put("details", details);

        try {
            runExtract(doc, destinationObj.toString());
            fail("Should have refused a single-valued xpath for the attachments destination");
        } catch (Exception e) {
            assertFailureMentions(e, "requires a multivalued blob property");
        }

        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldSaveNewBlobWithDefault() throws Exception {

        DocumentModel doc = createTestDoc();
        String originalVersion = doc.getVersionLabel();

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "newFile");

        Blob result = runExtract(doc, destinationObj.toString());
        assertNotNull(result);

        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertEquals("done", resultJson.getString("status"));

        doc = session.getDocument(doc.getRef());
        checkNumberOfPages((Blob) doc.getPropertyValue("file:content"), 4);

        assertEquals(originalVersion, doc.getVersionLabel());

        // original did change, that's the goal here
    }

    @Test
    public void shouldSaveNewBlobWithParams() throws Exception {

        DocumentModel doc = createTestDoc();
        String originalVersion = doc.getVersionLabel();

        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "newFile");
        JSONObject details = new JSONObject();
        details.put("createVersion", true);
        details.put("versionType", "major");
        destinationObj.put("details", details);

        Blob result = runExtract(doc, destinationObj.toString());
        assertNotNull(result);

        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertEquals("done", resultJson.getString("status"));

        doc = session.getDocument(doc.getRef());
        checkNumberOfPages((Blob) doc.getPropertyValue("file:content"), 4);

        String newVersion = doc.getVersionLabel();
        assertNotEquals(originalVersion, newVersion);
        assertEquals("1.0+", newVersion);

        String originalDocId = doc.getId();
        DocumentModel version = session.getLastDocumentVersion(doc.getRef());
        assertNotEquals(originalDocId, version.getId());
        checkOriginalNotModified(version);

    }

    /**
     * The most destructive combination of the plugin: a mutating operation that replaces the main file.
     * It must keep the untouched original in the version.
     */
    @Test
    public void shouldRemovePagesAndKeepOriginalInVersion() throws Exception {

        DocumentModel doc = createTestDoc();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Remove 4 pages out of 10
        params.put("destinationJsonStr",
                "{\"destination\":\"newFile\",\"details\":{\"createVersion\":true,\"versionType\":\"major\"}}");

        Blob result = (Blob) automationService.run(ctx, PDFPageRemoverOp.ID, params);
        assertNotNull(result);

        txFeature.nextTransaction();

        doc = session.getDocument(doc.getRef());
        checkNumberOfPages((Blob) doc.getPropertyValue("file:content"), 6);

        DocumentModel version = session.getLastDocumentVersion(doc.getRef());
        assertNotNull(version);
        checkOriginalNotModified(version);
    }

    @Test
    public void shouldRejectUnknownDestination() throws Exception {

        DocumentModel doc = createTestDoc();

        try {
            runExtract(doc, "{\"destination\": \"nowhere\"}");
            fail("Should have rejected an unknown destination");
        } catch (Exception e) {
            assertFailureMentions(e, "Unknown destination");
        }

        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldRejectMalformedDestinationJson() throws Exception {

        DocumentModel doc = createTestDoc();

        try {
            runExtract(doc, "{not json at all");
            fail("Should have rejected a malformed destinationJsonStr");
        } catch (Exception e) {
            assertFailureMentions(e, "not valid JSON");
        }

        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldIgnoreDetailsThatAreNotAnObject() throws Exception {

        DocumentModel doc = createTestDoc();

        // "details" as a string used to set the internal JSONObject to null, then NPE.
        Blob result = runExtract(doc, "{\"destination\": \"newFile\", \"details\": \"createVersion\"}");
        assertNotNull(result);

        txFeature.nextTransaction();

        doc = session.getDocument(doc.getRef());
        checkNumberOfPages((Blob) doc.getPropertyValue("file:content"), 4);
    }
}
