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
import java.util.HashSet;
import java.util.Set;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * A class to reorder pages in a pdf.
 *
 * @since 2025.2
 */
public class PDFPageOrdering {

    protected Blob pdfBlob;

    // ========================================
    // Constructors
    // ========================================
    public PDFPageOrdering(DocumentModel doc) {

        this(doc, null);

    }

    public PDFPageOrdering(DocumentModel doc, String xpath) {

        this(PDFTools.getBlobFromDocument(doc, xpath));

    }

    public PDFPageOrdering(Blob b) {

        PDFTools.checkIsProcessablePdf(b);
        pdfBlob = b;

    }

    // ========================================
    // Order pages
    // ========================================
    /**
     * Reorganize the pages of the PDF according to the newPageOrder array.
     * Example: for a 10-page PDF and newPageOrder = [3,6,1,7,9,8,2,4,5,10]
     * New page 1 =&gt; old page 3
     * New page 2 =&gt; old page 6
     * New page 3 =&gt; old page 1
     * ...
     * <p>
     * The result can have a smaller size (less pages): the array does not have to hold every page.
     *
     * @param newPageOrder 1-based page numbers in the new order, without duplicates
     * @return a new Blob containing the reorganized PDF. Blob file name is {originalName}-reordered.pdf
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the page order is malformed
     * @since 2025.2
     */
    public Blob reorganizePdf(int[] newPageOrder) {

        if (newPageOrder == null || newPageOrder.length == 0) {
            throw new IllegalArgumentException("pagesOrder must not be null or empty");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument sourcePdf = Loader.loadPDF(source.getFile());
                PDDocument reordered = new PDDocument()) {

            int pageCount = sourcePdf.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            validatePagesOrder(newPageOrder, pageCount);

            for (int pageNum : newPageOrder) {
                reordered.importPage(sourcePdf.getPage(pageNum - 1));
            }

            // Saving while sourcePdf is still open: importPage() does not deep-copy the page resources.
            return PDFTools.saveToFileBlob(pdfBlob, reordered, "pdf", "-reordered");

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to reorder pages in the PDF \"" + pdfBlob.getFilename() + "\".", e);
        }
    }

    protected void validatePagesOrder(int[] pagesOrder, int pageCount) {

        // An array shorter than the document is allowed on purpose: it produces a PDF with fewer pages.
        Set<Integer> seen = new HashSet<>();
        for (int p : pagesOrder) {
            if (p < 1 || p > pageCount) {
                throw new IllegalArgumentException(
                        "Invalid page number in pagesOrder: " + p + " (must be between 1 and " + pageCount + ")");
            }
            if (!seen.add(p)) {
                throw new IllegalArgumentException("Duplicate page number in pagesOrder: " + p);
            }
        }
    }

}
