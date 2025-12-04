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

import javax.inject.Inject;
import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.operations.PDFJpegimagePreviewOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageExtractorOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageOrderingOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageRemoverOp;
import nuxeo.labs.pdf.toolkit.operations.PDFThumbnailsOp;

/**
 * This class test operaitions without using the destinationJsonStr parameter, which leads to "download" by default.
 * 
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

    @Test
    public void shouldGetThumbnails() throws Exception {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(b);

        Blob result = (Blob) automationService.run(ctx, PDFThumbnailsOp.ID);
        assertNotNull(result);

        String arrayStr = result.getString();
        JSONArray array = new JSONArray(arrayStr);

        PDDocument sourcePdf = PDDocument.load(f);
        int pageCount = sourcePdf.getNumberOfPages();
        assertEquals(array.length(), pageCount);

        // Check the first item
        String base64 = array.getString(0);
        byte[] bytes = Base64.getDecoder().decode(base64);
        Blob blob = Blobs.createBlob(bytes);
        ImageInfo info = Framework.getService(ImagingService.class).getImageInfo(blob);
        assertEquals("jpeg", info.getFormat().toLowerCase());
        assertTrue(info.getHeight() <= PDFToImages.DEFAULT_THUMBNAIL_SIZE);
        assertTrue(info.getWidth() <= PDFToImages.DEFAULT_THUMBNAIL_SIZE);

    }

    @Test
    public void shouldRemovePages() throws Exception {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);

        PDDocument sourcePdf = PDDocument.load(f);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String text = stripper.getText(sourcePdf);
        int originalIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone changed the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(b);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // remove 4 pages

        Blob result = (Blob) automationService.run(ctx, PDFPageRemoverOp.ID, params);
        assertNotNull(result);

        sourcePdf = PDDocument.load(result.getFile());
        int pageCount = sourcePdf.getNumberOfPages();
        assertEquals(pageCount, 6);

        stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        text = stripper.getText(sourcePdf);
        int newIndex = text.indexOf(TEXT_PAGE_3);
        assertEquals(-1, newIndex);

    }

    @Test
    public void shouldExtractPages() throws Exception {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);

        PDDocument sourcePdf = PDDocument.load(f);
        int originalPageCount = sourcePdf.getNumberOfPages();
        // assertEquals(10, originalPageCount);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String text = stripper.getText(sourcePdf);
        int originalIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone changed the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(b);
        Map<String, Object> params = new HashMap<>();
        params.put("pageRange", "2-4, 8"); // Extract 4 pages

        Blob result = (Blob) automationService.run(ctx, PDFPageExtractorOp.ID, params);
        assertNotNull(result);

        // Check original not modified
        sourcePdf = PDDocument.load(f);
        int newPageCount = sourcePdf.getNumberOfPages();
        assertEquals(originalPageCount, newPageCount);

        // New PDF has 4 pages
        PDDocument extractedPdf = PDDocument.load(result.getFile());
        int pageCount = extractedPdf.getNumberOfPages();
        assertEquals(pageCount, 4);

        stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        text = stripper.getText(extractedPdf);
        int newIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(newIndex < originalIndex);

    }

    @Test
    public void shouldReorganizePages() throws Exception {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);

        PDDocument sourcePdf = PDDocument.load(f);
        int originalPageCount = sourcePdf.getNumberOfPages();
        // assertEquals(10, originalPageCount);

        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        String text = stripper.getText(sourcePdf);
        int originalIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(originalIndex > -1); // Did someone changed the test pdf?

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(b);
        Map<String, Object> params = new HashMap<>();
        params.put("pageOrderJsonStr", "[3, 1, 6, 2, 4, 5, 7, 8, 9, 10]");// Page 3 first

        Blob result = (Blob) automationService.run(ctx, PDFPageOrderingOp.ID, params);
        assertNotNull(result);

        // Check original not modified
        sourcePdf = PDDocument.load(f);
        int newPageCount = sourcePdf.getNumberOfPages();
        assertEquals(originalPageCount, newPageCount);

        // New PDF has 10 pages
        PDDocument reorganizedPdf = PDDocument.load(result.getFile());
        int pageCount = reorganizedPdf.getNumberOfPages();
        assertEquals(pageCount, originalPageCount);

        stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        text = stripper.getText(reorganizedPdf);
        int newIndex = text.indexOf(TEXT_PAGE_3);
        assertTrue(newIndex < originalIndex);

    }

    @Test
    public void shouldGetJpegImagePreview() throws Exception {

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);

        OperationContext ctx = new OperationContext(session);
        ctx.setInput(b);
        Map<String, Object> params = new HashMap<>();
        params.put("pageNumber", 3);

        Blob result = (Blob) automationService.run(ctx, PDFJpegimagePreviewOp.ID, params);
        assertNotNull(result);

        assertEquals("image/jpeg", result.getMimeType());
        String fileName = result.getFilename();
        assertTrue(StringUtils.isNotBlank(fileName));
        fileName = fileName.toLowerCase();
        assertTrue(fileName.endsWith(".jpg") || fileName.endsWith(".jpeg"));

        // More check it really is a JPEG.
        ImageInfo info = Framework.getService(ImagingService.class).getImageInfo(result);
        assertEquals("jpeg", info.getFormat().toLowerCase());
        assertTrue(info.getHeight() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);
        assertTrue(info.getWidth() <= PDFToImages.PREVIEW_PAGE_MAX_SIZE);

    }
}
