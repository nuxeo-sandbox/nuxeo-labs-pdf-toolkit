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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.json.JSONArray;
import org.json.JSONObject;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;

import nuxeo.labs.pdf.toolkit.PDFToImages;

/**
 * Renders every page of the PDF once, fills the thumbnails cache, and returns the URLs to fetch them
 * individually. This is the low-memory counterpart of {@code PDFLabs.GetThumbnails}, which builds the
 * whole base64 payload in memory.
 * <p>
 * The input must be a document: an URL needs a document id. Use {@code PDFLabs.GetThumbnails} when all
 * you have is a blob.
 *
 * @since 2025.6
 */
@Operation(id = PDFPrepareThumbnailsOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Prepare Thumbnails", description = ""
        + "Input is a document. xpath is the field to use, file:content by default."
        + " Renders every page once and fills the thumbnails cache, then returns a JSON object"
        + " {\"pageCount\": n, \"urls\": [...]} where each URL serves one page as JPEG."
        + " The URLs are relative to the Nuxeo application root, prefix them with the server base URL."
        + " Prefer this operation over PDFLabs.GetThumbnails to display thumbnails: it does not build"
        + " a base64 payload in memory, and the images can then be cached by the browser."
        + " The operation accepts width (default 512, max 2000), height (default 512, max 2000) and dpi"
        + " (default 150, max 300) as optional parameters. Values above the maximum are silently clamped.")
public class PDFPrepareThumbnailsOp {

    public static final String ID = "PDFLabs.PrepareThumbnails";

    /** Matches the WebEngine module declared in the MANIFEST, served under /nuxeo/site/. */
    public static final String THUMBNAIL_URL_PREFIX = "site/pdftoolkit/thumb/";

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

        PDFToImages pdfToImages = new PDFToImages(doc, xpath);
        pdfToImages.setDpi(dpi);

        // Opens, parses and renders the PDF once, then fills the cache the endpoint reads from.
        BlobList thumbnails = pdfToImages.createThumbnails(width, height);

        /*
         * The URL carries the document id, so replacing file:content would otherwise produce the very
         * same URLs and the browser would keep serving the previous thumbnails from its HTTP cache.
         * The content token makes the URL change whenever the PDF changes.
         */
        String contentToken = pdfToImages.getContentToken();

        JSONArray urls = new JSONArray();
        for (int pageNum = 1; pageNum <= thumbnails.size(); pageNum++) {
            urls.put(buildUrl(doc.getId(), pageNum, contentToken));
        }

        JSONObject result = new JSONObject();
        result.put("pageCount", thumbnails.size());
        result.put("urls", urls);

        return Blobs.createJSONBlob(result.toString());
    }

    protected String buildUrl(String docId, int pageNum, String contentToken) {

        StringBuilder url = new StringBuilder(THUMBNAIL_URL_PREFIX).append(docId)
                                                                   .append('/')
                                                                   .append(pageNum)
                                                                   .append("?w=")
                                                                   .append(width)
                                                                   .append("&h=")
                                                                   .append(height)
                                                                   .append("&dpi=")
                                                                   .append(dpi);
        if (contentToken != null) {
            url.append("&v=").append(URLEncoder.encode(contentToken, StandardCharsets.UTF_8));
        }
        if (xpath != null && !"file:content".equals(xpath)) {
            url.append("&xpath=").append(URLEncoder.encode(xpath, StandardCharsets.UTF_8));
        }

        return url.toString();
    }
}
