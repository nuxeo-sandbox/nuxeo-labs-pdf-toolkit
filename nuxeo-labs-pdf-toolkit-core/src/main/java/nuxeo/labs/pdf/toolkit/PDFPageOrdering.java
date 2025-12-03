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

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

/**
 * A class to reorder pages in a pdf.
 * 
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

        if (StringUtils.isBlank(xpath)) {
            xpath = "file:content";
        }

        pdfBlob = (Blob) doc.getPropertyValue(xpath);

    }

    public PDFPageOrdering(Blob b) {

        pdfBlob = b;

    }

    // ========================================
    // Order pages
    // ========================================
    /**
     * Reorganize the pages of the given PDF according to the pagesOrder array.
     * Example: for a 10-page PDF and pagesOrder = [3,6,1,7,9,8,2,4,5,10]
     * New page 1 => old page 3
     * New page 2 => old page 6
     * New page 3 => old page 1
     * ...
     * 
     * TRhe result can have a smaller size (less pages).
     *
     * @param pdf the input PDF file
     * @param pagesOrder 1-based page numbers in the new order; must be a permutation of all pages
     * @return a new Blob containing the reorganized PDF. Blob file name is {originalName}-reordered.pdf
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the range is malformed
     */
    public Blob reorganizePdf(int[] newPageOrder) {

        if (newPageOrder == null || newPageOrder.length == 0) {
            throw new IllegalArgumentException("pagesOrder must not be null or empty");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument sourcePdf = PDDocument.load(source.getFile());
                PDDocument reordered = new PDDocument()) {

            int pageCount = sourcePdf.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            validatePagesOrder(newPageOrder, pageCount);

            for (int pageNum : newPageOrder) {
                int zeroBased = pageNum - 1;
                reordered.importPage(sourcePdf.getPage(zeroBased));
            }

            Blob finalBlob = PDFTools.saveToFileBlob(pdfBlob, reordered, "pdf", "-reordered");

            return finalBlob;

        } catch (IOException e) {
            throw new NuxeoException("Failed to remove pages from the PDF", e);
        }
    }

    protected void validatePagesOrder(int[] pagesOrder, int pageCount) {
        // We allow a different number of pages
        /*
        if (pagesOrder.length != pageCount) {
            throw new IllegalArgumentException("pagesOrder length (" + pagesOrder.length
                    + ") does not match document page count (" + pageCount + ")");
        }
        */

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
