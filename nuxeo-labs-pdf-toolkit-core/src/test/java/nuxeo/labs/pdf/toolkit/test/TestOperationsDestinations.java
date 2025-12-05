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

import javax.inject.Inject;
import nuxeo.labs.pdf.toolkit.operations.PDFPageExtractorOp;

/**
 * This class tests the destination of the operations. We don't check all operations (extract, remove, ...) not the the
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
            while (dis.read(buffer) != -1) {}
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

        PDDocument pdfDoc = PDDocument.load(pdf.getFile());
        assertEquals(4, pdfDoc.getNumberOfPages());
    }

    protected void checkOriginalNotModified(DocumentModel doc) throws Exception {

        doc = session.getDocument(doc.getRef());
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        assertNotNull(blob);
        
        String currentMd5 = getMd5(blob.getFile());
        assertEquals(TEST_PDF_MD5, currentMd5);

    }

    @Test
    public void shouldCreateDerivativeWithDefault() throws Exception {

        DocumentModel doc = createTestDoc();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "derivative");
        params.put("destinationJsonStr", destinationObj.toString());

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));

        assertTrue(resultJson.has("derivativeId"));
        String derivativeId = resultJson.getString("derivativeId");
        assertTrue(StringUtils.isNotBlank(derivativeId));

        DocumentModel copy = session.getDocument(new IdRef(derivativeId));
        Blob pdf = (Blob) copy.getPropertyValue("file:content");
        checkNumberOfPages(pdf, 4);
        
        checkOriginalNotModified(doc);
    }

    @Test
    public void shouldCreateDerivativeWithParams() throws Exception {

        DocumentModel doc = createTestDoc();
        
        String originalState = doc.getCurrentLifeCycleState();
        
        session.followTransition(doc, "approve");
        doc = session.getDocument(doc.getRef());
        assertEquals("approved", doc.getCurrentLifeCycleState());

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "derivative");
        JSONObject details = new JSONObject();
        details.put("resetLifeCycle", true);
        details.put("derivativeTitle", "THE COPY");
        destinationObj.put("details",  details);
        params.put("destinationJsonStr", destinationObj.toString());

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);
        
        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));

        assertTrue(resultJson.has("derivativeId"));
        String derivativeId = resultJson.getString("derivativeId");
        assertTrue(StringUtils.isNotBlank(derivativeId));

        DocumentModel copy = session.getDocument(new IdRef(derivativeId));
        Blob pdf = (Blob) copy.getPropertyValue("file:content");
        checkNumberOfPages(pdf, 4);
        
        // Lifecycle reset
        assertEquals(originalState, copy.getCurrentLifeCycleState());
        // Correct title
        assertEquals("THE COPY", copy.getTitle());
        
        checkOriginalNotModified(doc);
    }

    @SuppressWarnings("unchecked")
    @Test
    public void shouldAddToFiles() throws Exception {

        DocumentModel doc = createTestDoc();

        List<Map<String, Serializable>> fileList = (List<Map<String, Serializable>>) doc.getPropertyValue(
                "files:files");
        assertTrue(fileList.size() == 0);

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "attachments");
        params.put("destinationJsonStr", destinationObj.toString());

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);
        
        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));

        // Reload
        doc = session.getDocument(doc.getRef());
        fileList = (List<Map<String, Serializable>>) doc.getPropertyValue("files:files");
        assertEquals(1, fileList.size());

        // Just check it has 4 pages
        Map<String, Serializable> firstFiles = fileList.get(0);
        Blob pdf = (Blob) firstFiles.get("file");
        checkNumberOfPages(pdf, 4);
        
        checkOriginalNotModified(doc);

    }
    
    @Test
    public void shouldSaveNewBlobWithDefault() throws Exception {

        DocumentModel doc = createTestDoc();
        String originalVersion = doc.getVersionLabel();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "newFile");
        params.put("destinationJsonStr", destinationObj.toString());

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);
        
        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));
        
        doc = session.getDocument(doc.getRef());
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        checkNumberOfPages(blob, 4);
        
        String newVersion = doc.getVersionLabel();
        assertEquals(originalVersion, newVersion);
       
        // original did change, that's the goal here
        //checkOriginalNotModified(doc);
    }
    
    @Test
    public void shouldSaveNewBlobWithParams() throws Exception {

        DocumentModel doc = createTestDoc();
        String originalVersion = doc.getVersionLabel();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(doc);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages
        JSONObject destinationObj = new JSONObject();
        destinationObj.put("destination", "newFile");
        JSONObject details = new JSONObject();
        details.put("createVersion", true);
        details.put("versionType", "major");
        destinationObj.put("details",  details);
        params.put("destinationJsonStr", destinationObj.toString());

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);
        
        txFeature.nextTransaction();

        assertEquals("application/json", result.getMimeType());
        JSONObject resultJson = new JSONObject(result.getString());
        assertTrue(resultJson.has("status"));
        assertEquals("done", resultJson.getString("status"));
        
        doc = session.getDocument(doc.getRef());
        Blob blob = (Blob) doc.getPropertyValue("file:content");
        checkNumberOfPages(blob, 4);
        
        String newVersion = doc.getVersionLabel();
        assertNotEquals(originalVersion, newVersion);
        assertEquals("1.0+", newVersion);
       
        // original did change, that's the goal here
        //checkOriginalNotModified(doc);
        String originalDocId = doc.getId();
        DocumentModel version = session.getLastDocumentVersion(doc.getRef());
        assertNotEquals(originalDocId, version.getId());
        checkOriginalNotModified(version);
        
    }
}
