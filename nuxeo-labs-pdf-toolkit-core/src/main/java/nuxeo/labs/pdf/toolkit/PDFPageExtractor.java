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
package nuxeo.labs.pdf.toolkit;

import java.io.IOException;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Extract pages from a PDF.
 * 
 * We cannot use the platform org.nuxeo.ecm.platform.pdf.PDFPageExtractor, since it extracts only a page
 * range with start-to. We want something more complex
 */
public class PDFPageExtractor {

    protected Blob pdfBlob;

    // ========================================
    // Constructors
    // ========================================
    public PDFPageExtractor(DocumentModel doc) {

        this(doc, null);

    }

    public PDFPageExtractor(DocumentModel doc, String xpath) {

        if (StringUtils.isBlank(xpath)) {
            xpath = "file:content";
        }

        pdfBlob = (Blob) doc.getPropertyValue(xpath);

    }

    public PDFPageExtractor(Blob b) {

        pdfBlob = b;

    }

    // ========================================
    // Extract pages
    // ========================================
    /**
     * Extract pages from the given PDF according to a "print dialog" style range string.
     * Examples for a 15-page PDF:
     * "3" -> extracts page 3
     * "3-6" -> extracts pages 3,4,5,6
     * "3-6,8" -> extracts 3,4,5,6,8
     * "3-6,8, 12-14" -> extracts 3,4,5,6,8,12,13,14
     *
     * @param pdf the input Blob file
     * @param range a string describing pages to remove (1-based).
     * @return a Blob containing the resulting PDF (original is untouched)
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the range is malformed
     */
    public Blob extractPages(String range) {

        if (StringUtils.isBlank(range)) {
            throw new IllegalArgumentException("Range must not be null or blank");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument sourcePdf = PDDocument.load(source.getFile());
                PDDocument extracted = new PDDocument()) {

            int pageCount = sourcePdf.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            Set<Integer> pagesToExtract = PDFTools.parsePageRange(range, pageCount);
            if (pagesToExtract.isEmpty()) {
                throw new IllegalArgumentException("Range does not select any pages: \"" + range + "\"");
            }

            for (int pageNumber : pagesToExtract) {
                int zeroBased = pageNumber - 1;
                PDFTools.validatePageNumber(pageNumber, pageCount, range);
                extracted.importPage(sourcePdf.getPage(zeroBased));
            }
            
            Blob finalBlob = PDFTools.saveToFileBlob(pdfBlob, extracted, "pdf-extracted-pages", "-extracted");
            
            return finalBlob;
            
        } catch (IOException e) {
            throw new NuxeoException("Failed to extract pages from the PDF", e);
        }
    }

}
