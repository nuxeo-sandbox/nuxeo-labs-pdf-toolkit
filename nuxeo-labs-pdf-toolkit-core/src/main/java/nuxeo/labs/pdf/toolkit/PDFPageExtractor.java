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
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * Extract pages from a PDF.
 * <p>
 * We cannot use the platform org.nuxeo.ecm.platform.pdf.PDFPageExtractor, since it extracts only a page
 * range with start-to. We want something more complex.
 *
 * @since 2025.2
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

        this(PDFTools.getBlobFromDocument(doc, xpath));

    }

    public PDFPageExtractor(Blob b) {

        PDFTools.checkIsProcessablePdf(b);
        pdfBlob = b;

    }

    // ========================================
    // Extract pages
    // ========================================
    /**
     * Extract pages from the PDF according to a "print dialog" style range string.
     * Examples for a 15-page PDF:
     * "3" -> extracts page 3
     * "3-6" -> extracts pages 3,4,5,6
     * "3-6,8" -> extracts 3,4,5,6,8
     * "3-6,8, 12-14" -> extracts 3,4,5,6,8,12,13,14
     * <p>
     * The extracted pages always keep their original document order, whatever the order used in the range
     * string: "8,2-4" and "2-4,8" both produce pages 2, 3, 4, 8. Use {@link PDFPageOrdering} to obtain an
     * arbitrary page order.
     *
     * @param range a string describing the pages to extract (1-based)
     * @return a Blob containing the resulting PDF (original is untouched)
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the range is malformed
     * @since 2025.2
     */
    public Blob extractPages(String range) {

        if (StringUtils.isBlank(range)) {
            throw new IllegalArgumentException("Range must not be null or blank");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument sourcePdf = Loader.loadPDF(source.getFile());
                PDDocument extracted = new PDDocument()) {

            int pageCount = sourcePdf.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            Set<Integer> pagesToExtract = PDFTools.parsePageRange(range, pageCount);
            if (pagesToExtract.isEmpty()) {
                throw new IllegalArgumentException("Range does not select any pages: \"" + range + "\"");
            }

            // parsePageRange() returns a sorted set of already validated pages.
            for (int pageNumber : pagesToExtract) {
                extracted.importPage(sourcePdf.getPage(pageNumber - 1));
            }

            // Saving while sourcePdf is still open: importPage() does not deep-copy the page resources.
            return PDFTools.saveToFileBlob(pdfBlob, extracted, "pdf-extracted-pages", "-extracted");

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to extract pages from the PDF \"" + pdfBlob.getFilename() + "\".", e);
        }
    }

}
