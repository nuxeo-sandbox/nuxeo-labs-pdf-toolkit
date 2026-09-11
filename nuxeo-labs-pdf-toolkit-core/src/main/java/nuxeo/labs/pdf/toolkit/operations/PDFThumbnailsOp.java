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
package nuxeo.labs.pdf.toolkit.operations;

import org.json.JSONArray;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.PDFTools;

/**
 * An operation that returns a list of jpeg images as base64.
 * <br>
 * There already is a PDF.ConvertToPictures operation, but it returns images with a 300 DPI and they are at the
 * dimension of each page which can sometime be big.
 *
 * @since 2025.2
 */
@Operation(id = PDFThumbnailsOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Get Thumbnails", description = ""
        + "Input is either a Blob or a document. If a document, xpath is the field to use, file:content by default."
        + " Calculate thumbnails of each page of the input PDF."
        + " Returns a JSON Array (as string) of the ordered thumbnails, jpeg, as base64."
        + " The operation accepts width (default 512, max 2000), height (default 512, max 2000) and dpi"
        + " (default 150, max 300) as optional parameters. Values above the maximum are silently clamped."
        + " Warning: as all is in memory as base64, the number of pages is limited (150 by default, see the"
        + " nuxeo.pdftoolkit.maxPages configuration property).")
public class PDFThumbnailsOp {

    public static final String ID = "PDFLabs.GetThumbnails";

    /** Safety net on top of the page count limit: the whole payload is built in memory. */
    public static final long MAX_BASE64_PAYLOAD = 20L * 1024 * 1024;

    @Param(name = "xpath", required = false)
    protected String xpath = "file:content";

    @Param(name = "width", required = false)
    protected Integer width = PDFToImages.DEFAULT_THUMBNAIL_SIZE;

    @Param(name = "height", required = false)
    protected Integer height = PDFToImages.DEFAULT_THUMBNAIL_SIZE;

    @Param(name = "dpi", required = false)
    protected Integer dpi = PDFToImages.DEFAULT_DPI;

    @OperationMethod
    public Blob run(DocumentModel doc) {

        return run(PDFTools.getBlobFromDocument(doc, xpath));
    }

    @OperationMethod
    public Blob run(Blob blob) {

        PDFToImages pdfThumbnails = new PDFToImages(blob);
        pdfThumbnails.setDpi(dpi);

        BlobList thumbnails = pdfThumbnails.createThumbnails(width, height);

        long totalBytes = thumbnails.stream().mapToLong(Blob::getLength).sum();
        if (totalBytes > MAX_BASE64_PAYLOAD) {
            throw new NuxeoException("Thumbnails payload would be " + totalBytes + " bytes, above the "
                    + MAX_BASE64_PAYLOAD + " bytes limit. Lower the dpi and/or the thumbnail size.");
        }

        JSONArray array = PDFToImages.toBase64JSONArray(thumbnails);

        return Blobs.createJSONBlob(array.toString());

    }
}
