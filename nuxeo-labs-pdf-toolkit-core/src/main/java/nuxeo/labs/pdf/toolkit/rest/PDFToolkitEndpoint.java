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
package nuxeo.labs.pdf.toolkit.rest;

import java.io.IOException;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.ResponseBuilder;

import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.webengine.model.WebObject;
import org.nuxeo.ecm.webengine.model.impl.ModuleRoot;

import nuxeo.labs.pdf.toolkit.PDFToImages;

/**
 * Serves the page thumbnails prepared by the {@code PDFLabs.PrepareThumbnails} operation.
 * <p>
 * Deployed as a WebEngine module (see the {@code Nuxeo-WebModule} header in the MANIFEST), so the
 * endpoint lives under {@code /nuxeo/site/pdftoolkit/}.
 * <p>
 * This endpoint only ever <b>reads</b> the thumbnails cache on the nominal path: the PDF is opened,
 * parsed and rendered once per chunk by the operation, not once per page. See
 * {@link PDFToImages#getThumbnail(int)} for what happens when the cache entry vanished in between — it
 * renders the whole chunk holding the page, never that single page.
 *
 * @since 2025.6
 */
@WebObject(type = "pdftoolkit")
@Path("/pdftoolkit")
public class PDFToolkitEndpoint extends ModuleRoot {

    /** Thumbnails are immutable for a given digest and rendering parameters, so they can be cached. */
    public static final int CACHE_MAX_AGE_SECONDS = 3600;

    /**
     * Smoke route: confirms the WebEngine module is registered and reachable.
     */
    @GET
    @Path("ping")
    @Produces(MediaType.TEXT_PLAIN)
    public String ping() {
        return "pong";
    }

    /**
     * Returns the JPEG thumbnail of a single page.
     *
     * @param docId the document holding the PDF
     * @param pageNum the page, starting at 1
     * @param width max thumbnail width, optional
     * @param height max thumbnail height, optional
     * @param dpi rendering resolution, optional
     * @param contentToken the content token built by PDFLabs.PrepareThumbnails, optional
     * @param xpath the blob field, {@code file:content} when not provided
     */
    @GET
    @Path("thumb/{docId}/{pageNum}")
    @Produces("image/jpeg")
    public Response getThumbnail(@PathParam("docId") String docId, @PathParam("pageNum") int pageNum,
            @QueryParam("w") Integer width, @QueryParam("h") Integer height, @QueryParam("dpi") Integer dpi,
            @QueryParam("v") String contentToken, @QueryParam("xpath") String xpath, @Context Request request) {

        CoreSession session = getContext().getCoreSession();
        // Resolving through the user session is what enforces the permissions: no read, no thumbnail.
        DocumentModel doc = session.getDocument(new IdRef(docId));

        /*
         * Everything this constructor refuses is a client error: no blob at that xpath, a property that
         * does not exist or is not a blob, a blob that is not a PDF or is too big. NuxeoException
         * defaults to a 500, which would log a stack trace per tile — 50 of them for one chunk — and
         * report a server fault for a perfectly well understood bad request.
         */
        PDFToImages pdfToImages;
        try {
            pdfToImages = new PDFToImages(doc, xpath);
        } catch (NuxeoException e) {
            throw error(Response.Status.BAD_REQUEST,
                    "Cannot render a thumbnail of document " + docId + ": " + e.getMessage());
        }

        /*
         * Go through the setters so the very same bounds as the operation apply: the rendering
         * parameters are in the URL, they cannot be trusted any more than an operation parameter.
         * They are snapped to a ladder, exactly as PDFLabs.PrepareThumbnails does when it builds the
         * URL, so both sides land on the same cache key.
         */
        if (width != null || height != null) {
            pdfToImages.setSize(width == null ? PDFToImages.DEFAULT_THUMBNAIL_SIZE : width,
                    height == null ? PDFToImages.DEFAULT_THUMBNAIL_SIZE : height);
        }
        if (dpi != null) {
            pdfToImages.setDpi(dpi);
        }

        /*
         * The token is deliberately NOT used to pick what to serve: this endpoint always serves the
         * current content of the document. It only tells us the URL is content-addressed, hence that a
         * long freshness lifetime is safe. Without it we must revalidate on every request, otherwise
         * replacing file:content would leave the browser on the previous thumbnails.
         */
        boolean versionedUrl = contentToken != null && !contentToken.isBlank();

        // A blob with no digest is not cacheable, hence no ETag either.
        int chunkStart = PDFToImages.chunkStartFor(pageNum, PDFToImages.getChunkSize());
        String cacheKey = pdfToImages.getChunkCacheKey(chunkStart);
        EntityTag etag = cacheKey == null ? null : new EntityTag(cacheKey + "-" + pageNum);

        if (etag != null) {
            ResponseBuilder notModified = request.evaluatePreconditions(etag);
            if (notModified != null) {
                return notModified.cacheControl(cacheControl(versionedUrl)).build();
            }
        }

        Blob thumbnail;
        try {
            thumbnail = pdfToImages.getThumbnail(pageNum);
        } catch (IllegalArgumentException e) {
            throw error(Response.Status.NOT_FOUND, "No page " + pageNum + " in document " + docId + ": "
                    + e.getMessage());
        }

        /*
         * Stream the bytes instead of handing the Blob over to JAX-RS: the platform BlobWriter starts by
         * calling httpHeaders.clear() and then delegates everything to the DownloadService, which would
         * wipe the ETag and the Cache-Control set here. Thumbnails are small, we do not need the extra
         * services (ranges, storage redirect) the DownloadService brings.
         */
        ResponseBuilder builder;
        try {
            builder = Response.ok(thumbnail.getStream()).type("image/jpeg").cacheControl(cacheControl(versionedUrl));
        } catch (IOException e) {
            throw new NuxeoException("Failed to read the thumbnail of page " + pageNum + " of document " + docId, e);
        }

        long length = thumbnail.getLength();
        if (length > 0) {
            builder.header(HttpHeaders.CONTENT_LENGTH, length);
        }
        if (etag != null) {
            builder.tag(etag);
        }

        return builder.build();
    }

    /**
     * A long freshness lifetime is only legitimate on a content-addressed URL. On a plain URL the ETag
     * alone protects from nothing: a non-zero max-age tells the browser not to revalidate at all, so it
     * would never get to compare the ETag.
     */
    protected CacheControl cacheControl(boolean versionedUrl) {
        CacheControl cc = new CacheControl();
        cc.setPrivate(true);
        if (versionedUrl) {
            cc.setMaxAge(CACHE_MAX_AGE_SECONDS);
        } else {
            cc.setNoCache(true);
        }
        return cc;
    }

    /**
     * Build an error response with an <b>explicit</b> media type.
     * <p>
     * Never throw a bare {@code NuxeoException} subclass from this resource expecting the platform to
     * map it. This method declares {@code @Produces("image/jpeg")}, so JAX-RS looks for a
     * {@code MessageBodyWriter} able to serialize the <i>exception</i> as {@code image/jpeg}, finds
     * none, and the failure cascades: the real status is lost and the client gets a
     * {@code 404 jakarta.ws.rs.NotFoundException} with a stack trace in the logs, whatever the status
     * the exception carried. Same family of trap as returning a {@code Blob} and losing the headers —
     * on an endpoint that produces binary, the error path needs its own content type.
     *
     * @since 2025.8
     */
    protected WebApplicationException error(Response.Status status, String message) {

        return new WebApplicationException(
                Response.status(status).type(MediaType.TEXT_PLAIN).entity(message).build());
    }

}
