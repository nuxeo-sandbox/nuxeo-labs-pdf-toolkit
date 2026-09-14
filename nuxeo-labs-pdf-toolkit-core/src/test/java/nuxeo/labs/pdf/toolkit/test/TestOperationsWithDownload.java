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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.platform.picture.api.ImageInfo;
import org.nuxeo.ecm.platform.picture.api.ImagingService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import jakarta.inject.Inject;
import nuxeo.labs.pdf.toolkit.PDFPageExtractor;
import nuxeo.labs.pdf.toolkit.PDFPageOrdering;
import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.operations.PDFJpegimagePreviewOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageExtractorOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageOrderingOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageRemoverOp;
import nuxeo.labs.pdf.toolkit.operations.PDFThumbnailsOp;

/**
 * This class tests operations without using the destinationJsonStr parameter, which leads to "download" by default.
 */
@RunWith(FeaturesRunner.class)
@Features({ AutomationFeature.class })
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")
public class TestOperationsWithDownload {

    public static final String TEST_PDF_PAH = "lorem_ipsum_10_pages.pdf";

    public static final int TEST_PDF_PAGE_COUNT = 10;

    public static final String TEXT_PAGE_3 = "HERE SOME TEXT FOR THE UNIT TEST";

    @Inject
    protected CoreSession session;

    @Inject
    protected AutomationService automationService;

    protected File testPdfFile() {
        return FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
    }

    protected Blob testPdfBlob() {
        return new FileBlob(testPdfFile());
    }

    protected String extractText(File f) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(f)) {
            return extractText(pdf);
        }
    }

    protected String extractText(PDDocument pdf) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        return stripper.getText(pdf);
    }

    protected int pageCount(File f) throws Exception {
        try (PDDocument pdf = Loader.loadPDF(f)) {
            return pdf.getNumberOfPages();
        }
    }

    @Test
    public void shouldGetThumbnails() throws Exception {

        File f = testPdfFile();

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(new FileBlob(f));

        Blob result = (Blob) automationService.run(ctx, PDFThumbnailsOp.ID);
        assertNotNull(result);

        JSONArray array = new JSONArray(result.getString());
        assertEquals(TEST_PDF_PAGE_COUNT, array.length());
        assertEquals(pageCount(f), array.length());

        // Check the first item
        byte[] bytes = Base64.getDecoder().decode(array.getString(0));
        Blob blob = Blobs.createBlob(bytes);
        ImageInfo info = Framework.getService(ImagingService.class).getImageInfo(blob);
        assertEquals("jpeg", info.getFormat().toLowerCase());
        assertTrue(info.getHeight() <= PDFToImages.DEFAULT_THUMBNAIL_SIZE);
        assertTrue(info.getWidth() <= PDFToImages.DEFAULT_THUMBNAIL_SIZE);

    }

    @Test
    public void shouldRemovePages() throws Exception {

        File f = testPdfFile();

        String text = extractText(f);
        int originalIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone change the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(new FileBlob(f));
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // remove 4 pages

        Blob result = (Blob) automationService.run(ctx, PDFPageRemoverOp.ID, params);
        assertNotNull(result);

        try (PDDocument resultPdf = Loader.loadPDF(result.getFile())) {
            assertEquals(6, resultPdf.getNumberOfPages());
            assertEquals(-1, extractText(resultPdf).indexOf(TEXT_PAGE_3));
        }

        // Check original not modified
        assertEquals(TEST_PDF_PAGE_COUNT, pageCount(f));
    }

    @Test
    public void shouldExtractPages() throws Exception {

        File f = testPdfFile();

        int originalPageCount = pageCount(f);
        assertEquals(TEST_PDF_PAGE_COUNT, originalPageCount);

        int originalIndex = extractText(f).indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone change the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(new FileBlob(f));
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);

        // Check original not modified
        assertEquals(originalPageCount, pageCount(f));

        // New PDF has 4 pages
        try (PDDocument extractedPdf = Loader.loadPDF(result.getFile())) {
            assertEquals(4, extractedPdf.getNumberOfPages());
            assertTrue(extractText(extractedPdf).indexOf(TEXT_PAGE_3) < originalIndex);
        }
    }

    @Test
    public void shouldExtractPagesInDocumentOrderWhateverTheRangeOrder() throws Exception {

        // "8,2-4" and "2-4,8" must both produce pages 2, 3, 4, 8.
        Blob fromUnordered = new PDFPageExtractor(testPdfBlob()).extractPages("8,2-4");
        Blob fromOrdered = new PDFPageExtractor(testPdfBlob()).extractPages("2-4,8");

        try (PDDocument a = Loader.loadPDF(fromUnordered.getFile());
                PDDocument b = Loader.loadPDF(fromOrdered.getFile())) {
            assertEquals(4, a.getNumberOfPages());
            assertEquals(4, b.getNumberOfPages());
            assertEquals(extractText(a), extractText(b));
        }
    }

    @Test
    public void shouldReorganizePages() throws Exception {

        File f = testPdfFile();

        int originalPageCount = pageCount(f);
        int originalIndex = extractText(f).indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone change the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(new FileBlob(f));
        Map<String, Object> params = new HashMap<>();
        params.put("pageOrderJsonStr", "[3, 1, 6, 2, 4, 5, 7, 8, 9, 10]");// Page 3 first

        Blob result = (Blob) automationService.run(ctx, PDFPageOrderingOp.ID, params);
        assertNotNull(result);

        // Check original not modified
        assertEquals(originalPageCount, pageCount(f));

        try (PDDocument reorganizedPdf = Loader.loadPDF(result.getFile())) {
            assertEquals(originalPageCount, reorganizedPdf.getNumberOfPages());
            assertTrue(extractText(reorganizedPdf).indexOf(TEXT_PAGE_3) < originalIndex);
        }
    }

    @Test
    public void shouldReorganizeToFewerPages() throws Exception {

        Blob result = new PDFPageOrdering(testPdfBlob()).reorganizePdf(new int[] { 3, 1, 4, 2 });

        try (PDDocument pdf = Loader.loadPDF(result.getFile())) {
            assertEquals(4, pdf.getNumberOfPages());
        }
    }

    @Test
    public void shouldGetJpegImagePreview() throws Exception {

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(testPdfBlob());
        Map<String, Object> params = new HashMap<>();
        params.put("pageNumber", 3);

        Blob result = (Blob) automationService.run(ctx, PDFJpegimagePreviewOp.ID, params);
        assertNotNull(result);

        assertEquals("image/jpeg", result.getMimeType());
        String fileName = result.getFilename();
        assertTrue(StringUtils.isNotBlank(fileName));
        fileName = fileName.toLowerCase();
        assertTrue("Unexpected preview file name: " + fileName,
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"));

        // More check it really is a JPEG.
        ImageInfo info = Framework.getService(ImagingService.class).getImageInfo(result);
        assertEquals("jpeg", info.getFormat().toLowerCase());
        assertTrue(info.getHeight() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);
        assertTrue(info.getWidth() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);
        /*
         * The upper bound alone would be satisfied by a 200px preview. The test fixture is a portrait
         * Letter page, so its height is what the resize clamps: it must actually reach the cap.
         */
        assertTrue("The preview should reach the cap, not stop short: " + info.getWidth() + "x" + info.getHeight(),
                info.getHeight() > PDFToImages.PREVIEW_PAGE_MAX_SIZE * 0.9);

    }

    // ========================================
    // Negative tests
    // ========================================
    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectPageAboveDocumentPageCount() {
        new PDFPageExtractor(testPdfBlob()).extractPages("11");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectZeroPage() {
        new PDFPageExtractor(testPdfBlob()).extractPages("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectReversedRange() {
        new PDFPageExtractor(testPdfBlob()).extractPages("8-2");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyToken() {
        new PDFPageExtractor(testPdfBlob()).extractPages("2,,4");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNonNumericRange() {
        new PDFPageExtractor(testPdfBlob()).extractPages("two");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectBlankRange() {
        new PDFPageExtractor(testPdfBlob()).extractPages("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectDuplicatePageInNewOrder() {
        new PDFPageOrdering(testPdfBlob()).reorganizePdf(new int[] { 1, 2, 2 });
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectOutOfBoundsPageInNewOrder() {
        new PDFPageOrdering(testPdfBlob()).reorganizePdf(new int[] { 1, 2, 42 });
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectEmptyNewOrder() {
        new PDFPageOrdering(testPdfBlob()).reorganizePdf(new int[0]);
    }
}
