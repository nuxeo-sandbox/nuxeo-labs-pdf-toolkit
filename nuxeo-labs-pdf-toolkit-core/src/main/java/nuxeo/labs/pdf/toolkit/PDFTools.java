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
import java.util.TreeSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;

/**
 * Centralized code originally copy/pasted in several places.
 *
 * @since 2025.2
 */
public final class PDFTools {

    /**
     * Largest PDF this plugin accepts to load. PDFBox loads the document in memory, so an unbounded
     * size means an unbounded heap usage.
     *
     * @since 2025.6
     */
    public static final long MAX_PDF_SIZE = 200L * 1024 * 1024;

    private PDFTools() {
        // Utility class, not meant to be instantiated.
    }

    /**
     * Reads the blob stored at {@code xpath} on {@code doc}, defaulting to {@code file:content} when {@code xpath} is
     * blank.
     * <p>
     * Fails with an actionable message instead of the raw {@code NullPointerException} /
     * {@code ClassCastException} a direct {@code (Blob) doc.getPropertyValue(xpath)} would throw.
     *
     * @param doc the document holding the blob
     * @param xpath the field to read, {@code file:content} when blank
     * @return the blob, never {@code null}
     * @throws NuxeoException if the document is null, if the property does not exist, holds nothing, or is not a blob
     * @since 2025.6
     */
    public static Blob getBlobFromDocument(DocumentModel doc, String xpath) {

        if (doc == null) {
            throw new NuxeoException("No document provided.");
        }

        String path = StringUtils.isBlank(xpath) ? "file:content" : xpath;

        Object value;
        try {
            value = doc.getPropertyValue(path);
        } catch (PropertyNotFoundException e) {
            throw new NuxeoException("Property \"" + path + "\" does not exist on document " + doc.getId() + ".", e);
        }

        if (value == null) {
            throw new NuxeoException("Document " + doc.getId() + " has no blob in \"" + path + "\".");
        }
        if (!(value instanceof Blob blob)) {
            throw new NuxeoException("Property \"" + path + "\" on document " + doc.getId() + " is not a blob (found "
                    + value.getClass().getSimpleName() + ").");
        }

        return blob;
    }

    /**
     * Fails fast, with an actionable message, when the blob cannot reasonably be processed as a PDF.
     * <p>
     * A blank mime type is accepted: some blobs are created without one, and PDFBox will reject the content anyway.
     *
     * @param blob the blob to check
     * @throws NuxeoException if the blob is null, is not a PDF, or is above {@link #MAX_PDF_SIZE}
     * @since 2025.6
     */
    public static void checkIsProcessablePdf(Blob blob) {

        if (blob == null) {
            throw new NuxeoException("No blob provided.");
        }

        String mimeType = blob.getMimeType();
        if (StringUtils.isNotBlank(mimeType) && !"application/pdf".equalsIgnoreCase(mimeType)) {
            throw new NuxeoException(
                    "Blob \"" + blob.getFilename() + "\" is not a PDF (mime-type: " + mimeType + ").");
        }

        long length = blob.getLength();
        if (length > MAX_PDF_SIZE) {
            throw new NuxeoException("PDF \"" + blob.getFilename() + "\" is " + length + " bytes, above the "
                    + MAX_PDF_SIZE + " bytes limit supported by this plugin.");
        }
    }

    /**
     * Return a file name to be used as base name.
     * Example:<br>
     * {@code String fileNameNoExt = PDFTools.getFileNameNoExtension(blob, "pdf-extracted", "-p3");}
     * If blob file name is "mydoc.pdf", returns "mydoc-p3".<br>
     * If blob has no filename, returns "pdf-extracted-p3"<br>
     * <br>
     * {@code String fileNameNoExt = PDFTools.getFileNameNoExtension(blob, "pdf-extracted", null);}
     * Returns "mydoc" or "pdf-extracted"<br>
     *
     * @param blob the source blob, whose file name is used when it has one
     * @param defaultName the base name to use when the blob has no file name
     * @param suffixBeforeExt an optional suffix appended to the base name
     * @return the base name, without extension
     * @since 2025.2
     */
    public static String getFileNameNoExtension(Blob blob, String defaultName, String suffixBeforeExt) {

        String fileNameNoExt;
        String fileName = blob.getFilename();
        if (StringUtils.isBlank(fileName)) {
            fileNameNoExt = defaultName;
        } else {
            // getBaseName() also strips any path component, Unix or Windows style.
            fileNameNoExt = FilenameUtils.getBaseName(fileName);
        }
        if (StringUtils.isNotBlank(suffixBeforeExt)) {
            fileNameNoExt += suffixBeforeExt;
        }

        return fileNameNoExt;
    }

    /**
     * Save the PDF to a temporary blob. Centralize the same code used in several places.
     *
     * @param source the original blob, used to derive the resulting file name
     * @param newPdf the document to save
     * @param defaultNameNoExt the base name to use when the source has no file name
     * @param suffixBeforeExt an optional suffix appended to the base name
     * @return a blob holding the saved PDF
     * @throws IOException if saving fails
     * @since 2025.2
     */
    public static Blob saveToFileBlob(Blob source, PDDocument newPdf, String defaultNameNoExt, String suffixBeforeExt)
            throws IOException {

        String fileNameNoExt = PDFTools.getFileNameNoExtension(source, defaultNameNoExt, suffixBeforeExt);

        /*
         * createBlobWithExtension() creates the file under nuxeo.tmp.dir AND registers it for deletion
         * (Framework.trackFile). Never use java.io.File.createTempFile + new FileBlob(File) here: the
         * latter does not flag the blob as temporary, so nothing would ever delete the file.
         */
        Blob finalBlob = Blobs.createBlobWithExtension(".pdf");
        newPdf.save(finalBlob.getFile());
        finalBlob.setFilename(fileNameNoExt + ".pdf");
        finalBlob.setMimeType("application/pdf");

        return finalBlob;
    }

    /**
     * Parse a print-style page range into a set of 1-based page numbers.
     * <p>
     * The returned set is sorted ascending: the order used in the range string is not preserved,
     * "8,2-4" and "2-4,8" both yield 2, 3, 4, 8.
     *
     * @param range e.g. "3", "3-6", "3-6,8, 12-14"
     * @param pageCount total pages in the document
     * @return sorted set of page numbers (1-based)
     * @throws IllegalArgumentException if the range is malformed or references a page out of the document
     * @since 2025.2
     */
    public static Set<Integer> parsePageRange(String range, int pageCount) {

        Set<Integer> pages = new TreeSet<>();
        String[] parts = range.split(",");

        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                throw new IllegalArgumentException("Empty token in range: \"" + range + "\"");
            }

            int dashIdx = token.indexOf('-');
            try {
                if (dashIdx < 0) {
                    // Single page
                    int page = Integer.parseInt(token);
                    validatePageNumber(page, pageCount, token);
                    pages.add(page);
                } else {
                    // Range "start-end"
                    String startStr = token.substring(0, dashIdx).trim();
                    String endStr = token.substring(dashIdx + 1).trim();
                    if (startStr.isEmpty() || endStr.isEmpty()) {
                        throw new IllegalArgumentException("Malformed range segment: \"" + token + "\"");
                    }

                    int start = Integer.parseInt(startStr);
                    int end = Integer.parseInt(endStr);

                    if (start > end) {
                        throw new IllegalArgumentException("Start page > end page in segment: \"" + token + "\"");
                    }
                    // Both bounds are validated, so no clamping is needed: anything out of range throws.
                    validatePageNumber(start, pageCount, token);
                    validatePageNumber(end, pageCount, token);

                    for (int p = start; p <= end; p++) {
                        pages.add(p);
                    }
                }
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("Invalid number in range segment: \"" + token + "\"", ex);
            }
        }

        return pages;
    }

    /**
     * Throws an error if the page is not a valid page number (starting at 1) in a document of {@code pageCount} pages.
     *
     * @param page the 1-based page number to check
     * @param pageCount the number of pages of the document
     * @param segment the range segment the page comes from, used in the error message
     * @throws IllegalArgumentException if the page is &lt; 1 or &gt; pageCount
     * @since 2025.2
     */
    public static void validatePageNumber(int page, int pageCount, String segment) {

        if (page < 1) {
            throw new IllegalArgumentException("Page numbers must be >= 1 in segment: \"" + segment + "\"");
        }

        if (page > pageCount) {
            throw new IllegalArgumentException(
                    "Page " + page + " exceeds document page count " + pageCount + " in segment: \"" + segment + "\"");
        }
    }

}
