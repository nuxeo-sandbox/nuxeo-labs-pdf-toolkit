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
 * Remove pages in a PDF.
 */
public class PDFPageRemover {

    protected Blob pdfBlob;

    // ========================================
    // Constructors
    // ========================================
    public PDFPageRemover(DocumentModel doc) {

        this(doc, null);

    }

    public PDFPageRemover(DocumentModel doc, String xpath) {

        if (StringUtils.isBlank(xpath)) {
            xpath = "file:content";
        }

        pdfBlob = (Blob) doc.getPropertyValue(xpath);

    }

    public PDFPageRemover(Blob b) {

        pdfBlob = b;

    }

    // ========================================
    // Remove pages
    // ========================================
    /**
     * Remove pages from the given PDF according to a "print dialog" style range string.
     * Examples for a 15-page PDF:
     * "3" -> removes page 3
     * "3-6" -> removes pages 3,4,5,6
     * "3-6,8" -> removes 3,4,5,6,8
     * "3-6,8, 12-14" -> removes 3,4,5,6,8,12,13,14
     *
     * @param pdf the input PDF file
     * @param range a string describing pages to remove (1-based).
     * @return a Blob containing the resulting PDF (original is untouched)
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the range is malformed
     */
    public Blob removePages(String range) {

        if (StringUtils.isBlank(range)) {
            throw new IllegalArgumentException("Range must not be null or blank");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = PDDocument.load(source.getFile());
                PDDocument reordered = PDFTools.cloneDocument(document)) {
            
            int pageCount = reordered.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            Set<Integer> pagesToRemove = PDFTools.parsePageRange(range, pageCount);
            if (pagesToRemove.isEmpty()) {
                throw new IllegalArgumentException("Range does not select any pages: \"" + range + "\"");
            }

            // Remove from highest to lowest so that indices don't shift as we remove pages.
            pagesToRemove.stream()
                         .sorted((a, b) -> Integer.compare(b, a)) // descending
                         .forEach(pageNumber -> {
                             int zeroBased = pageNumber - 1;// Parameter starts at 1, PDFBox at 0.
                             if (zeroBased >= 0 && zeroBased < reordered.getNumberOfPages()) {
                                 reordered.removePage(zeroBased);
                             }
                         });

            Blob finalBlob = PDFTools.saveToFileBlob(pdfBlob, reordered, "pdf-after-removed-pages", "-pages-removed");

            return finalBlob;
        } catch (IOException e) {
            throw new NuxeoException("Failed to remove pages from the PDF", e);
        }
    }

}
