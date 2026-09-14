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
import java.util.Comparator;
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
 * Remove pages in a PDF.
 *
 * @since 2025.2
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

        this(PDFTools.getBlobFromDocument(doc, xpath));

    }

    public PDFPageRemover(Blob b) {

        PDFTools.checkIsProcessablePdf(b);
        pdfBlob = b;

    }

    // ========================================
    // Remove pages
    // ========================================
    /**
     * Remove pages from the PDF according to a "print dialog" style range string.
     * Examples for a 15-page PDF:
     * "3" -> removes page 3
     * "3-6" -> removes pages 3,4,5,6
     * "3-6,8" -> removes 3,4,5,6,8
     * "3-6,8, 12-14" -> removes 3,4,5,6,8,12,13,14
     *
     * @param range a string describing the pages to remove (1-based)
     * @return a Blob containing the resulting PDF (original is untouched)
     * @throws NuxeoException if reading or writing the PDF fails
     * @throws IllegalArgumentException if the range is malformed
     * @since 2025.2
     */
    public Blob removePages(String range) {

        if (StringUtils.isBlank(range)) {
            throw new IllegalArgumentException("Range must not be null or blank");
        }

        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = Loader.loadPDF(source.getFile())) {

            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                throw new IllegalArgumentException("Source PDF has no pages");
            }

            Set<Integer> pagesToRemove = PDFTools.parsePageRange(range, pageCount);
            if (pagesToRemove.isEmpty()) {
                throw new IllegalArgumentException("Range does not select any pages: \"" + range + "\"");
            }

            /*
             * Removing pages only mutates the in-memory model: the source file is left untouched because we
             * always save to a brand new temporary file. No defensive copy of the whole document needed.
             * Remove from highest to lowest so that indices don't shift as we remove pages.
             */
            pagesToRemove.stream()
                         .sorted(Comparator.reverseOrder())
                         .forEach(pageNumber -> document.removePage(pageNumber - 1)); // Parameter starts at 1, PDFBox at 0.

            return PDFTools.saveToFileBlob(pdfBlob, document, "pdf-after-removed-pages", "-pages-removed");

        } catch (InvalidPasswordException e) {
            throw PDFTools.badRequest(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to remove pages from the PDF \"" + pdfBlob.getFilename() + "\".", e);
        }
    }

}
