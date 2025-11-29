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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Set;
import java.util.TreeSet;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;

/**
 * Centralized code originally copy/pasted in several places.
 */
public class PDFTools {

    /**
     * Deep copy of the PDF
     * 
     * @param original
     * @return
     * @throws IOException
     * @since TODO
     */
    public static PDDocument cloneDocument(PDDocument original) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        original.save(baos);
        return Loader.loadPDF(baos.toByteArray());
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
     * @param blob
     * @param defaultName
     * @param suffixBeforeExt
     * @return
     * @since TODO
     */
    public static String getFileNameNoExtension(Blob blob, String defaultName, String suffixBeforeExt) {

        String fileNameNoExt;
        String fileName = blob.getFilename();
        if (StringUtils.isBlank(fileName)) {
            fileNameNoExt = defaultName;
        } else {
            fileNameNoExt = FilenameUtils.getBaseName(fileName);
        }
        if (StringUtils.isNotBlank(suffixBeforeExt)) {
            fileNameNoExt += suffixBeforeExt;
        }

        return fileNameNoExt;
    }

    /**
     * Centralize the same code used in several places.
     * 
     * @param source
     * @param newPdf
     * @param defaultNameNoExt
     * @param suffixBeforeExt
     * @return
     * @throws IOException
     */
    public static Blob saveToFileBlob(Blob source, PDDocument newPdf, String defaultNameNoExt, String suffixBeforeExt)
            throws IOException {

        String fileNameNoExt = PDFTools.getFileNameNoExtension(source, defaultNameNoExt, suffixBeforeExt);

        File tempFile = File.createTempFile(fileNameNoExt, ".pdf");
        newPdf.save(tempFile);
        Blob finalBlob = new FileBlob(tempFile);
        finalBlob.setFilename(fileNameNoExt + ".pdf");
        finalBlob.setMimeType("application/pdf");

        return finalBlob;
    }

    /**
     * Parse a print-style page range into a set of 1-based page numbers.
     * 
     * @param range e.g. "3", "3-6", "3-6,8, 12-14"
     * @param pageCount total pages in the document (used to clamp upper bounds)
     * @return sorted set of page numbers to remove (1-based)
     * @throws IllegalArgumentException if the range is malformed
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
                    if (page >= 1 && page <= pageCount) {
                        pages.add(page);
                    }
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
                    validatePageNumber(start, pageCount, token);
                    validatePageNumber(end, pageCount, token);

                    // Clamp to [1, pageCount]
                    int clampedStart = Math.max(1, start);
                    int clampedEnd = Math.min(pageCount, end);

                    for (int p = clampedStart; p <= clampedEnd; p++) {
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
     * Throws error if the page is not a valid number (starting at 1)
     * 
     * @param page
     * @param pageCount
     * @param segment
     * @since TODO
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
