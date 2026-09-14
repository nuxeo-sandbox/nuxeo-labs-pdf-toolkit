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
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

import nuxeo.labs.pdf.toolkit.PDFToImages;

/**
 * Renders one chunk of pages, fills the thumbnails cache, and returns the URLs to fetch every page of
 * the document individually. This is the low-memory counterpart of {@code PDFLabs.GetThumbnails}, which
 * builds the whole base64 payload in memory.
 * <p>
 * Only the chunk holding {@code startPage} is rendered, so a 1000 pages PDF opens as fast as a 50 pages
 * one. The URLs of <b>all</b> the pages are returned nonetheless: the caller displays placeholders and
 * calls this operation again, with another {@code startPage}, as the user scrolls.
 * <p>
 * The input must be a document: an URL needs a document id. Use {@code PDFLabs.GetThumbnails} when all
 * you have is a blob.
 *
 * @since 2025.6
 */
@Operation(id = PDFPrepareThumbnailsOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Prepare Thumbnails", description = ""
        + "Input is a document. xpath is the field to use, file:content by default."
        + " Renders one chunk of pages (50 by default, see nuxeo.pdftoolkit.thumbnails.chunkSize) and fills"
        + " the thumbnails cache, then returns a JSON object"
        + " {\"pageCount\": n, \"chunkSize\": n, \"chunkStart\": n, \"chunkEnd\": n, \"urls\": [...]}"
        + " holding one URL per page of the document, each serving that page as JPEG."
        + " Call it again with another startPage to render the next chunk."
        + " The URLs are relative to the Nuxeo application root, prefix them with the server base URL."
        + " Prefer this operation over PDFLabs.GetThumbnails to display thumbnails: it does not build"
        + " a base64 payload in memory, and the images can then be cached by the browser."
        + " The operation accepts startPage (default 1), width (default 512, max 2000), height (default 512,"
        + " max 2000) and dpi (default 150, max 300) as optional parameters. Values above the maximum are"
        + " silently clamped.")
public class PDFPrepareThumbnailsOp {

    public static final String ID = "PDFLabs.PrepareThumbnails";

    /** Matches the WebEngine module declared in the MANIFEST, served under /nuxeo/site/. */
    public static final String THUMBNAIL_URL_PREFIX = "site/pdftoolkit/thumb/";

    @Param(name = "xpath", required = false)
    protected String xpath = "file:content";

    /**
     * Any page of the chunk to render. The chunk is the one holding it, so 1 and 50 both render the
     * first chunk when the chunk size is 50.
     *
     * @since 2025.7
     */
    @Param(name = "startPage", required = false)
    protected Integer startPage = 1;

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
        pdfToImages.setSize(width, height);

        // Opens, parses and renders the PDF once for the whole chunk, then fills the cache the endpoint
        // reads from.
        PDFToImages.ThumbnailsChunk chunk = pdfToImages.prepareChunk(startPage == null ? 1 : startPage);

        /*
         * Rendering is bounded by the chunk, so this limit is not about the server: every page becomes a
         * tile in the browser, and a few thousand tiles are enough to freeze a tab.
         */
        int maxPages = PDFToImages.getThumbnailsMaxPages();
        if (chunk.pageCount() > maxPages) {
            throw new NuxeoException("PDF of document " + doc.getId() + " has " + chunk.pageCount()
                    + " pages, above the " + maxPages + " pages limit for the thumbnails UI. Raise "
                    + PDFToImages.THUMBNAILS_MAX_PAGES_PROPERTY + " if your browser can afford it.");
        }

        /*
         * The URL carries the document id, so replacing file:content would otherwise produce the very
         * same URLs and the browser would keep serving the previous thumbnails from its HTTP cache.
         * The content token makes the URL change whenever the PDF changes.
         */
        String contentToken = pdfToImages.getContentToken();

        // Every page gets an URL, even those of the chunks that are not rendered yet: the caller needs
        // them to lay out its placeholders, and asking for one warms its chunk on the fly.
        JSONArray urls = new JSONArray();
        for (int pageNum = 1; pageNum <= chunk.pageCount(); pageNum++) {
            urls.put(buildUrl(doc.getId(), pageNum, contentToken));
        }

        JSONObject result = new JSONObject();
        result.put("pageCount", chunk.pageCount());
        result.put("chunkSize", chunk.chunkSize());
        result.put("chunkStart", chunk.chunkStart());
        result.put("chunkEnd", chunk.chunkEnd());
        /*
         * Diagnostics, so that the chunking can be observed from the browser's network tab without
         * touching log4j2.xml: "rendered": false proves the chunk came from the cache, hence that the
         * PDF was not opened at all.
         */
        result.put("rendered", chunk.rendered());
        result.put("renderTimeMs", chunk.renderTimeMs());
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
