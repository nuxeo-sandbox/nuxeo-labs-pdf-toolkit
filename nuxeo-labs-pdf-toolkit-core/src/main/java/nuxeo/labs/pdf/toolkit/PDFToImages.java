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

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Serializable;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.JSONArray;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.ecm.core.transientstore.api.MaximumTransientSpaceExceeded;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreService;
import org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants;
import org.nuxeo.runtime.api.Framework;

/**
 * Extract thumbnails or previews.
 * <p>
 * Rendering is bounded on purpose: see {@link #MAX_DPI}, {@link #MAX_THUMBNAIL_SIZE} and
 * {@link #DEFAULT_MAX_PAGES}. All the operations are exposed to any authenticated user, so unbounded
 * rendering parameters would be a trivial denial of service.
 *
 * @since 2025.2
 */
public class PDFToImages {

    private static final Logger log = LogManager.getLogger(PDFToImages.class);

    static {
        /*
         * Image plugin discovery walks the whole classpath and mutates the global IIORegistry.
         * It must happen once per class loading, definitely not on every request.
         */
        ImageIO.scanForPlugins();
    }

    public static final int DEFAULT_THUMBNAIL_SIZE = 512;

    /**
     * Rendering resolution used when none is provided. Kept low on purpose: thumbnails are downscaled
     * to {@link #DEFAULT_THUMBNAIL_SIZE} anyway, rendering higher is pure waste.
     */
    public static final int DEFAULT_DPI = 150;

    /**
     * Hard upper bound for the rendering resolution. A single A4 page at 300 dpi is already ~26 MB of heap.
     *
     * @since 2025.6
     */
    public static final int MAX_DPI = 300;

    /**
     * Hard upper bound for a thumbnail side, in pixels.
     *
     * @since 2025.6
     */
    public static final int MAX_THUMBNAIL_SIZE = 2000;

    /**
     * Default maximum number of pages {@link #createThumbnails()} accepts to render.
     * <p>
     * The binding constraint is not the rendering itself but the base64 payload the thumbnails operation
     * builds in memory: roughly 230 KB of heap per page, counting the JSON array and its serialization.
     * 150 pages is about 34 MB per request, which stays reasonable under concurrency.
     * <p>
     * Override with the {@code nuxeo.pdftoolkit.maxPages} configuration property.
     *
     * @since 2025.6
     */
    public static final int DEFAULT_MAX_PAGES = 150;

    /**
     * Configuration property overriding {@link #DEFAULT_MAX_PAGES}.
     *
     * @since 2025.6
     */
    public static final String MAX_PAGES_PROPERTY = "nuxeo.pdftoolkit.maxPages";

    public static final int PREVIEW_PAGE_MAX_SIZE = 1024;

    /**
     * Rendering resolution used for a single page preview, before it is resized to
     * {@link #PREVIEW_PAGE_MAX_SIZE}.
     *
     * @since 2025.6
     */
    public static final int PREVIEW_DPI = 300;

    public static final String TRANSIENT_STORE_NAME = "PDFToolkitCache";

    protected int width = DEFAULT_THUMBNAIL_SIZE;

    protected int height = DEFAULT_THUMBNAIL_SIZE;

    protected int dpi = DEFAULT_DPI;

    protected Blob pdfBlob;

    // ========================================
    // Constructors
    // ========================================
    public PDFToImages(DocumentModel doc) {

        this(doc, null);

    }

    public PDFToImages(DocumentModel doc, String xpath) {

        this(PDFTools.getBlobFromDocument(doc, xpath));

    }

    public PDFToImages(Blob b) {

        PDFTools.checkIsProcessablePdf(b);
        pdfBlob = b;

    }

    // ========================================
    // Misc. ways to set the dimension
    // ========================================
    public void setWidth(int value) {
        width = value > 0 ? Math.min(value, MAX_THUMBNAIL_SIZE) : DEFAULT_THUMBNAIL_SIZE;
    }

    /**
     * @since 2025.6
     */
    public void setHeight(int value) {
        height = value > 0 ? Math.min(value, MAX_THUMBNAIL_SIZE) : DEFAULT_THUMBNAIL_SIZE;
    }

    /**
     * @deprecated since 2025.6, use {@link #setHeight(int)} instead. Kept for compatibility (naming typo).
     */
    @Deprecated
    public void setheight(int value) {
        setHeight(value);
    }

    public void setSize(int size) {

        setWidth(size);
        setHeight(size);
    }

    public void setSize(int width, int height) {

        setWidth(width);
        setHeight(height);
    }

    public void setSize(String size) {

        if (StringUtils.isBlank(size)) {
            setSize(DEFAULT_THUMBNAIL_SIZE);
            return;
        }

        int idx = size.indexOf('x');
        if (idx <= 0 || idx == size.length() - 1) {
            throw new IllegalArgumentException("Malformed dimension string: " + size);
        }

        String wStr = size.substring(0, idx).trim();
        String hStr = size.substring(idx + 1).trim();

        try {
            // Always go through the setters: they normalize non-positive values and apply the upper bounds.
            setSize(Integer.parseInt(wStr), Integer.parseInt(hStr));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed dimension string: " + size, e);
        }

    }

    public void setDpi(int value) {
        dpi = value > 0 ? Math.min(value, MAX_DPI) : DEFAULT_DPI;
    }

    // ========================================
    // Extraction
    // ========================================
    /**
     * Extract the thumbnails, with max width/height of {@code size}.
     * If {@code size} is &lt;= 0, {@link #DEFAULT_THUMBNAIL_SIZE} applies.
     *
     * @param size the max width and height of each thumbnail
     * @return the ordered list of thumbnails, one per page
     * @since 2025.2
     */
    public BlobList createThumbnails(int size) {

        setSize(size);

        return createThumbnails();

    }

    public BlobList createThumbnails(int width, int height) {

        setSize(width, height);

        return createThumbnails();

    }

    /**
     * Extract the thumbnails, given a dimension passed as string, "{width}x{height}".
     * If not passed, default dimension applies (same if a value is &lt;= 0).
     *
     * @param size the dimension, "{width}x{height}"
     * @return the ordered list of thumbnails, one per page
     * @since 2025.2
     */
    public BlobList createThumbnails(String size) {

        setSize(size);

        return createThumbnails();

    }

    protected static TransientStore getTransientStore() {
        TransientStoreService transientStoreService = Framework.getService(TransientStoreService.class);
        return transientStoreService.getStore(TRANSIENT_STORE_NAME);
    }

    /**
     * Maximum number of pages the thumbnails rendering accepts, see {@link #DEFAULT_MAX_PAGES}.
     *
     * @since 2025.6
     */
    protected static int getMaxPages() {

        String value = Framework.getProperty(MAX_PAGES_PROPERTY);
        if (StringUtils.isNotBlank(value)) {
            try {
                int max = Integer.parseInt(value.trim());
                if (max > 0) {
                    return max;
                }
                log.warn("{} must be > 0, found \"{}\". Falling back to {}.", MAX_PAGES_PROPERTY, value,
                        DEFAULT_MAX_PAGES);
            } catch (NumberFormatException e) {
                log.warn("{} is not a number (\"{}\"). Falling back to {}.", MAX_PAGES_PROPERTY, value,
                        DEFAULT_MAX_PAGES);
            }
        }

        return DEFAULT_MAX_PAGES;
    }

    /**
     * A token identifying the <b>content</b> being rendered, not the document holding it.
     * <p>
     * Two uses, and they must stay consistent: it is the base of the cache key, and it is put in the
     * thumbnail URLs so that a new content yields a new URL. Without it, replacing {@code file:content}
     * would leave the browser serving the previous thumbnails from its own HTTP cache, since the URL
     * only carries the document id.
     *
     * @return the content token, or {@code null} when the blob cannot be identified by content
     * @since 2025.6
     */
    public String getContentToken() {

        String token = pdfBlob.getDigest();
        if (StringUtils.isBlank(token) && pdfBlob instanceof ManagedBlob managed) {
            token = managed.getKey();
        }

        if (StringUtils.isBlank(token)) {
            log.debug("Blob \"{}\" has no digest and no storage key, caching disabled.", pdfBlob.getFilename());
            return null;
        }

        return token;
    }

    /**
     * Build a cache key from the content token plus the given rendering discriminator.
     * <p>
     * Returns {@code null} when the blob cannot be identified by content: caching on a weaker key
     * (file name and length, say) could serve another document's images.
     *
     * @since 2025.6
     */
    protected String buildCacheKey(String renderingSuffix) {

        String token = getContentToken();

        return token == null ? null : token + renderingSuffix;
    }

    /**
     * Cache key of the whole thumbnails list. The rendering parameters are part of the key: the very same
     * PDF rendered at another size or another resolution is a different cache entry.
     * <p>
     * Returns {@code null} when the blob cannot be identified by content, in which case nothing is cached.
     * Also used as the base of the HTTP ETag served by the REST endpoint.
     *
     * @since 2025.6
     */
    public String getThumbnailsCacheKey() {
        return buildCacheKey("-thumbs-" + width + "x" + height + "-" + dpi);
    }

    /**
     * Cache key of a single page preview.
     *
     * @since 2025.6
     */
    protected String getPreviewCacheKey(int pageNum) {
        return buildCacheKey("-preview-" + pageNum + "-" + PREVIEW_PAGE_MAX_SIZE + "-" + PREVIEW_DPI);
    }

    /**
     * Return the cached blobs for this key, or {@code null} when there is no usable cache entry.
     * <p>
     * An entry is usable only if it is flagged completed AND actually holds blobs. Beware of the
     * TransientStore contract: {@code exists()} only checks that the ".completed" key is present, whatever
     * its value, and {@code getBlobs()} returns an empty list for an entry that exists but holds nothing,
     * and null for an entry that vanished. A reserved-but-unfinished entry, or one left over by a failed
     * run, must never be served.
     *
     * @since 2025.6
     */
    protected List<Blob> getFromCache(TransientStore store, String cacheKey) {

        if (cacheKey == null || !store.exists(cacheKey) || !store.isCompleted(cacheKey)) {
            return null;
        }

        List<Blob> blobs = store.getBlobs(cacheKey);
        if (blobs == null || blobs.isEmpty()) {
            return null;
        }

        return blobs;
    }

    /**
     * Store the blobs in the cache. A full cache must never fail a request whose result is already computed.
     *
     * @return true if the blobs were effectively cached
     * @since 2025.6
     */
    protected boolean putInCache(TransientStore store, String cacheKey, List<Blob> blobs) {

        if (cacheKey == null) {
            return false;
        }

        try {
            store.putBlobs(cacheKey, blobs);
            store.setCompleted(cacheKey, true);
            return true;
        } catch (MaximumTransientSpaceExceeded e) {
            log.warn("{} is full, nothing cached for key {}.", TRANSIENT_STORE_NAME, cacheKey, e);
            return false;
        }
    }

    /**
     * Create thumbnails (JPEG) for all pages of the PDF.
     * Uses the width/height/dpi defined in previous calls, or default values.
     *
     * @return the ordered list of thumbnails, one per page
     * @since 2025.2
     */
    public BlobList createThumbnails() {

        TransientStore store = getTransientStore();
        String cacheKey = getThumbnailsCacheKey();

        List<Blob> cached = getFromCache(store, cacheKey);
        if (cached != null) {
            log.debug("Thumbnails cache hit for key {}.", cacheKey);
            return new BlobList(cached);
        }

        BlobList results = new BlobList();
        boolean stored = false;
        long start = System.currentTimeMillis();

        // pdfBlob.getFile() could be null, like when the
        // related file is on S3 for example, we must download it.
        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = Loader.loadPDF(source.getFile())) {

            int pageCount = document.getNumberOfPages();
            int maxPages = getMaxPages();
            if (pageCount > maxPages) {
                throw new NuxeoException("PDF \"" + pdfBlob.getFilename() + "\" has " + pageCount
                        + " pages, above the " + maxPages + " pages limit for thumbnails rendering. Raise "
                        + MAX_PAGES_PROPERTY + " if your server can afford it.");
            }

            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {

                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

                // Create scaled thumbnail
                BufferedImage thumb = scaleToFit(pageImage, width, height);

                results.add(imageToBlob(thumb, "jpg", ".jpg", "image/jpeg", pageIndex + 1));
            }

            stored = putInCache(store, cacheKey, results);

            log.info("Rendered {} thumbnails ({}x{} @ {} dpi) in {} ms for blob \"{}\", cached: {}.", results.size(),
                    width, height, dpi, System.currentTimeMillis() - start, pdfBlob.getFilename(), stored);

            return results;

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to extract the pages of \"" + pdfBlob.getFilename() + "\".", e);
        } finally {
            /*
             * Never leave a half-baked entry behind. An entry that exists without blobs is served as an
             * empty result forever (until the TTL expires), which is worse than no cache at all.
             */
            if (cacheKey != null && !stored) {
                store.remove(cacheKey);
            }
        }
    }

    /**
     * Return the thumbnail of a single page, served from the cache whenever possible.
     *
     * @param pageNum the page, starting at 1
     * @return the JPEG thumbnail of that page
     * @throws IllegalArgumentException if the page is out of the document
     * @since 2025.6
     */
    public Blob getThumbnail(int pageNum) {

        List<Blob> thumbnails = getFromCache(getTransientStore(), getThumbnailsCacheKey());

        if (thumbnails == null) {
            /*
             * Cache miss: render the whole document at once and repopulate. Rendering only the requested
             * page would mean one Loader.loadPDF() per page, and above all one getCloseableFile() per
             * page, which re-downloads the entire PDF from the blob store every time. On a 20-page PDF
             * served page by page, that would be 20 downloads and 20 full parses instead of one.
             */
            log.debug("Thumbnails cache miss on page {}, rendering the whole document.", pageNum);
            thumbnails = createThumbnails();
        }

        PDFTools.validatePageNumber(pageNum, thumbnails.size(), String.valueOf(pageNum));

        return thumbnails.get(pageNum - 1);
    }

    /**
     * Return the JPEG preview of a page, resized to at most {@link #PREVIEW_PAGE_MAX_SIZE} on each side.
     *
     * @param pageNum the page to render, starting at 1
     * @return the JPEG preview of the page
     * @since 2025.2
     */
    public Blob getJpegPreviewImage(int pageNum) {

        TransientStore store = getTransientStore();
        String cacheKey = getPreviewCacheKey(pageNum);

        List<Blob> cached = getFromCache(store, cacheKey);
        if (cached != null) {
            log.debug("Preview cache hit for key {}.", cacheKey);
            return cached.get(0);
        }

        boolean stored = false;

        // pdfBlob.getFile() could be null, like when the
        // related file is on S3 for example, we must download it.
        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = Loader.loadPDF(source.getFile())) {

            int pageCount = document.getNumberOfPages();
            PDFTools.validatePageNumber(pageNum, pageCount, String.valueOf(pageNum));

            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage pageImage = renderer.renderImageWithDPI(pageNum - 1, PREVIEW_DPI, ImageType.RGB);

            Blob fullSizeBlob = imageToBlob(pageImage, "jpg", ".jpg", "image/jpeg", pageNum);

            SimpleBlobHolder bh = new SimpleBlobHolder(fullSizeBlob);
            Map<String, Serializable> parameters = new HashMap<>();

            parameters.put(ImagingConvertConstants.OPTION_RESIZE_WIDTH, PREVIEW_PAGE_MAX_SIZE);
            parameters.put(ImagingConvertConstants.OPTION_RESIZE_HEIGHT, PREVIEW_PAGE_MAX_SIZE);
            parameters.put(ImagingConvertConstants.CONVERSION_FORMAT, ImagingConvertConstants.JPEG_CONVERSATION_FORMAT);

            BlobHolder holder = Framework.getService(ConversionService.class).convert("pictureResize", bh, parameters);
            Blob resizedBlob = holder.getBlob();
            // Realign the metadata lost by the converter, then cache and return the very same blob.
            resizedBlob.setMimeType("image/jpeg");
            resizedBlob.setFilename(fullSizeBlob.getFilename());

            stored = putInCache(store, cacheKey, Collections.singletonList(resizedBlob));

            return resizedBlob;

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException(
                    "Failed to extract page " + pageNum + " of \"" + pdfBlob.getFilename() + "\" as a JPEG.", e);
        } finally {
            if (cacheKey != null && !stored) {
                store.remove(cacheKey);
            }
        }

    }

    // ========================================
    // Utilities
    // ========================================
    // pageNum starts at 1
    protected Blob imageToBlob(BufferedImage img, String formatName, String fileExtension, String mimeType, int pageNum)
            throws IOException {

        String fileNameNoExt = PDFTools.getFileNameNoExtension(pdfBlob, "pdf-img", "-p" + pageNum);

        // See PDFTools.saveToFileBlob: this is the only way to get a temporary file that will be deleted.
        Blob result = Blobs.createBlobWithExtension(fileExtension);
        ImageIO.write(img, formatName, result.getFile());
        result.setFilename(fileNameNoExt + fileExtension);
        result.setMimeType(mimeType);

        return result;

    }

    /**
     * Return an array of Base64 encoding of the input blobs (in same order).
     *
     * @param blobs the blobs to encode
     * @return the JSON array of base64 strings
     * @since 2025.2
     */
    public static JSONArray toBase64JSONArray(BlobList blobs) {

        JSONArray array = new JSONArray();

        try {
            for (Blob blob : blobs) {
                byte[] bytes = blob.getByteArray();
                String base64 = Base64.getEncoder().encodeToString(bytes);

                array.put(base64);
            }

            return array;

        } catch (IOException e) {
            throw new NuxeoException("Failed to base64-encode the images.", e);
        }
    }

    public static BufferedImage scaleToFit(BufferedImage src, int maxWidth, int maxHeight) {

        int w = src.getWidth();
        int h = src.getHeight();

        // Compute scale factors for each dimension
        double scaleW = (double) maxWidth / (double) w;
        double scaleH = (double) maxHeight / (double) h;

        // Choose the smaller scale so both constraints are satisfied
        double scale = Math.min(scaleW, scaleH);

        // Do not upscale if smaller
        if (scale > 1.0) {
            scale = 1.0;
        }

        // Never let a rounding to zero produce an invalid image
        int newW = Math.max(1, (int) Math.round(w * scale));
        int newH = Math.max(1, (int) Math.round(h * scale));

        BufferedImage dst = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = dst.createGraphics();
        try {
            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.drawImage(src, 0, 0, newW, newH, null);
        } finally {
            g2d.dispose();
        }
        return dst;
    }

}
