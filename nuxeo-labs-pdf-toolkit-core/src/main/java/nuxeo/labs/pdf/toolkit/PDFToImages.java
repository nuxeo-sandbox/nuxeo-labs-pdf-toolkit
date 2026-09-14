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
import org.apache.pdfbox.pdmodel.common.PDRectangle;
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
 * Rendering is bounded on purpose: see {@link #MAX_DPI}, {@link #MAX_THUMBNAIL_SIZE},
 * {@link #DEFAULT_MAX_PAGES} and {@link #MAX_RENDERED_PIXELS}. All the operations are exposed to any
 * authenticated user, so unbounded rendering parameters would be a trivial denial of service.
 * <p>
 * The dpi and the requested size are not the only attacker-controlled inputs: the <b>page geometry
 * is one too</b>, and it is the one that actually drives the allocation. Every rendering therefore
 * goes through {@link #renderPage}, never through {@code PDFRenderer.renderImageWithDPI} — read its
 * Javadoc before touching any rendering code.
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
     * How many pixels are rendered per pixel actually kept, on the longest side.
     * <p>
     * Rendering straight at the target size makes text thin and aliased, which is why the original
     * code rendered at a fixed dpi and downsampled. Rendering at twice the target and letting
     * {@link #scaleToFit} downsample keeps that quality while bounding the raster: the cost of a page
     * becomes a function of the requested size, not of the page geometry.
     *
     * @since 2025.8
     */
    public static final int RENDER_SUPERSAMPLE = 2;

    /**
     * Absolute ceiling on the pixels of a single rendered page, whatever the page geometry, the dpi
     * and the requested size.
     * <p>
     * 40 million pixels is about 160 MB as {@code TYPE_INT_RGB}. This is the last line of defence
     * behind {@link #renderPage}: no caller may allocate more than this for one page.
     *
     * @since 2025.8
     */
    public static final long MAX_RENDERED_PIXELS = 40_000_000L;

    /**
     * Default maximum number of pages {@link #createThumbnails()} accepts to render.
     * <p>
     * The binding constraint is not the rendering itself but the base64 payload the thumbnails operation
     * builds in memory: roughly 230 KB of heap per page, counting the JSON array and its serialization.
     * 150 pages is about 34 MB per request, which stays reasonable under concurrency.
     * <p>
     * This applies to {@code PDFLabs.GetThumbnails} only. {@code PDFLabs.PrepareThumbnails} renders by
     * chunks and is bounded by {@link #DEFAULT_THUMBNAILS_MAX_PAGES} instead.
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

    /**
     * Number of pages rendered in one go by {@link #prepareChunk(int)}.
     * <p>
     * This is the unit of work of the whole thumbnails pipeline: the PDF is opened, parsed and rendered
     * once per chunk, never once per page. A 1000 pages PDF browsed end to end costs 20 openings, not
     * 1000. Making this smaller improves the latency of a single scroll step but multiplies the number
     * of times the blob is fetched from the storage, which is what hurts on a remote blob store.
     * <p>
     * Override with the {@code nuxeo.pdftoolkit.thumbnails.chunkSize} configuration property.
     *
     * @since 2025.7
     */
    public static final int DEFAULT_CHUNK_SIZE = 50;

    /**
     * Configuration property overriding {@link #DEFAULT_CHUNK_SIZE}.
     *
     * @since 2025.7
     */
    public static final String CHUNK_SIZE_PROPERTY = "nuxeo.pdftoolkit.thumbnails.chunkSize";

    /**
     * Default maximum number of pages {@code PDFLabs.PrepareThumbnails} accepts to expose.
     * <p>
     * Rendering is bounded by chunks, so this is not about the server: it is about the browser. Every
     * page becomes a tile in the dialog, and a few thousand tiles are enough to freeze a tab.
     * <p>
     * Override with the {@code nuxeo.pdftoolkit.thumbnails.maxPages} configuration property.
     *
     * @since 2025.7
     */
    public static final int DEFAULT_THUMBNAILS_MAX_PAGES = 2000;

    /**
     * Configuration property overriding {@link #DEFAULT_THUMBNAILS_MAX_PAGES}.
     *
     * @since 2025.7
     */
    public static final String THUMBNAILS_MAX_PAGES_PROPERTY = "nuxeo.pdftoolkit.thumbnails.maxPages";

    /**
     * Set to {@code true} to log every chunk rendering at {@code warn} instead of {@code info}.
     * <p>
     * This exists because of a log4j2 trap: this plugin lives in the {@code nuxeo.labs.pdf.toolkit}
     * package, which no {@code <Logger>} of the stock Nuxeo {@code log4j2.xml} covers, so it inherits
     * the root logger — at {@code warn}. Its {@code info} messages are therefore silently dropped on a
     * default server, and counting the PDF openings is impossible without editing {@code log4j2.xml}.
     * <p>
     * Turning this on is the quick way to audit an instance. The clean way is to add
     * {@code <Logger name="nuxeo.labs.pdf.toolkit" level="info" />} to {@code log4j2.xml}.
     *
     * @since 2025.7
     */
    public static final String VERBOSE_RENDERING_PROPERTY = "nuxeo.pdftoolkit.verboseRendering";

    /**
     * Largest side of a single page preview, in pixels.
     * <p>
     * The preview is displayed nearly full screen (a 90vh dialog holding an image at 95% of its
     * height), so a high DPI screen asks for roughly 1850 device pixels of height. At 1024 the image
     * was upscaled almost twofold and looked soft.
     *
     * @since 2025.2
     */
    public static final int PREVIEW_PAGE_MAX_SIZE = 2048;

    /**
     * Rendering resolution used for a single page preview, before it is resized to
     * {@link #PREVIEW_PAGE_MAX_SIZE}.
     * <p>
     * Do not raise it to sharpen the preview: a Letter page already renders to 2550x3300 here, which
     * is more detail than {@link #PREVIEW_PAGE_MAX_SIZE} keeps. Raising the cap costs nothing since
     * that detail is computed either way, raising the DPI grows the rendering cost quadratically for
     * pixels that are then thrown away.
     * <p>
     * Since 2025.8 this is an <b>upper bound</b>, not a target: {@link #renderPage} lowers it on a
     * page large enough that 300 dpi would overflow {@link #PREVIEW_PAGE_MAX_SIZE} anyway. Normal
     * page sizes are unaffected.
     *
     * @since 2025.6
     */
    public static final int PREVIEW_DPI = 300;

    public static final String TRANSIENT_STORE_NAME = "PDFToolkitCache";

    /**
     * Name of the TransientStore parameter holding the total page count of the document, stored
     * alongside every chunk.
     * <p>
     * Without it, answering "how many pages does this PDF have?" on a cache hit would mean reopening
     * and reparsing the PDF — on a remote blob store, downloading it again — just to call
     * {@code getNumberOfPages()}.
     *
     * @since 2025.7
     */
    protected static final String PAGE_COUNT_PARAM = "pageCount";

    /** Rendering triggered by {@code PDFLabs.PrepareThumbnails}: the nominal path. @since 2025.7 */
    protected static final String RENDER_REASON_PREPARE = "prepare";

    /**
     * Rendering triggered by the REST endpoint because the chunk was not in the cache. Logged as a
     * warning on purpose: on the nominal path the operation prepares the chunk before the browser asks
     * for its images, so this only happens when the cache expired, was purged, or when a thumbnail URL
     * is opened outside of the dialog.
     *
     * @since 2025.7
     */
    protected static final String RENDER_REASON_ENDPOINT = "endpoint-fallback";

    /** Whole document rendering, by {@code PDFLabs.GetThumbnails}. @since 2025.7 */
    protected static final String RENDER_REASON_FULL = "full-document";

    /**
     * Locks guarding the rendering of a chunk, striped over the cache key.
     * <p>
     * A browser opens up to six connections to the same host, so six thumbnails of the same cold chunk
     * can land on six threads at once. Without this, each of them would open, parse and render the very
     * same 50 pages. The winner renders, the others wait then read the cache.
     * <p>
     * Striping rather than a map of per-key locks: no entry to ever remove, hence no leak and no race
     * on the removal. A hash collision only means two unrelated chunks serialize, which is harmless.
     *
     * @since 2025.7
     */
    protected static final int RENDER_LOCK_COUNT = 64;

    protected static final Object[] RENDER_LOCKS = new Object[RENDER_LOCK_COUNT];

    static {
        for (int i = 0; i < RENDER_LOCK_COUNT; i++) {
            RENDER_LOCKS[i] = new Object();
        }
    }

    /**
     * One chunk of thumbnails, plus the page count of the whole document.
     *
     * @param pageCount total number of pages of the PDF
     * @param chunkStart first page of the chunk, starting at 1
     * @param chunkEnd last page of the chunk, inclusive
     * @param chunkSize the chunk size that was applied
     * @param thumbnails the thumbnails of the chunk, in page order
     * @param rendered {@code false} when the chunk came straight from the cache, which means the PDF
     *            was not opened at all
     * @param renderTimeMs time spent rendering, 0 on a cache hit
     * @since 2025.7
     */
    public record ThumbnailsChunk(int pageCount, int chunkStart, int chunkEnd, int chunkSize,
            List<Blob> thumbnails, boolean rendered, long renderTimeMs) {
    }

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

        return getPositiveIntProperty(MAX_PAGES_PROPERTY, DEFAULT_MAX_PAGES);
    }

    /**
     * Number of pages rendered in one go, see {@link #DEFAULT_CHUNK_SIZE}.
     *
     * @since 2025.7
     */
    public static int getChunkSize() {

        return getPositiveIntProperty(CHUNK_SIZE_PROPERTY, DEFAULT_CHUNK_SIZE);
    }

    /**
     * Maximum number of pages {@code PDFLabs.PrepareThumbnails} exposes, see
     * {@link #DEFAULT_THUMBNAILS_MAX_PAGES}.
     *
     * @since 2025.7
     */
    public static int getThumbnailsMaxPages() {

        return getPositiveIntProperty(THUMBNAILS_MAX_PAGES_PROPERTY, DEFAULT_THUMBNAILS_MAX_PAGES);
    }

    /**
     * Whether chunk renderings are logged at {@code warn}, see {@link #VERBOSE_RENDERING_PROPERTY}.
     *
     * @since 2025.7
     */
    public static boolean isVerboseRendering() {

        return Framework.isBooleanPropertyTrue(VERBOSE_RENDERING_PROPERTY);
    }

    /**
     * Log a rendering, that is, one opening of the PDF.
     * <p>
     * <b>Every code path that opens the PDF must go through this method</b>, never through a direct
     * {@code log.info}. The level is a single decision made in a single place: an endpoint fallback is
     * abnormal, so it is always a warning, and {@link #VERBOSE_RENDERING_PROPERTY} promotes the normal
     * ones. This matters because the plugin lives in the {@code nuxeo.labs.pdf.toolkit} package, which
     * no {@code <Logger>} of the stock Nuxeo {@code log4j2.xml} covers: it inherits the root logger, at
     * {@code warn}, so an {@code info} is silently dropped on a default server. A path logging on its
     * own would therefore be invisible exactly when someone is trying to count the PDF openings.
     *
     * @param reason one of the {@code RENDER_REASON_*} constants
     * @since 2025.7
     */
    protected void logRendering(String reason, String message, Object... args) {

        if (RENDER_REASON_ENDPOINT.equals(reason) || isVerboseRendering()) {
            log.warn(message, args);
        } else {
            log.info(message, args);
        }
    }

    /**
     * Read a strictly positive integer configuration property, falling back on {@code defaultValue}
     * when it is missing, not a number, or not positive.
     *
     * @since 2025.7
     */
    protected static int getPositiveIntProperty(String property, int defaultValue) {

        String value = Framework.getProperty(property);
        if (StringUtils.isNotBlank(value)) {
            try {
                int parsed = Integer.parseInt(value.trim());
                if (parsed > 0) {
                    return parsed;
                }
                log.warn("{} must be > 0, found \"{}\". Falling back to {}.", property, value, defaultValue);
            } catch (NumberFormatException e) {
                log.warn("{} is not a number (\"{}\"). Falling back to {}.", property, value, defaultValue);
            }
        }

        return defaultValue;
    }

    /**
     * First page of the chunk holding {@code pageNum}, both starting at 1.
     * <p>
     * With a chunk size of 50, pages 1 to 50 belong to the chunk starting at 1, pages 51 to 100 to the
     * chunk starting at 51, and so on.
     *
     * @since 2025.7
     */
    public static int chunkStartFor(int pageNum, int chunkSize) {

        if (pageNum < 1) {
            return 1;
        }

        return ((pageNum - 1) / chunkSize) * chunkSize + 1;
    }

    /**
     * The lock guarding the rendering of {@code cacheKey}, see {@link #RENDER_LOCK_COUNT}.
     *
     * @since 2025.7
     */
    protected static Object renderLockFor(String cacheKey) {

        return RENDER_LOCKS[Math.floorMod(cacheKey.hashCode(), RENDER_LOCK_COUNT)];
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
     * Cache key of one chunk of thumbnails. The rendering parameters are part of the key: the very same
     * PDF rendered at another size or another resolution is a different cache entry.
     * <p>
     * The chunk start is part of the key too, so every chunk lives and expires on its own. Browsing the
     * beginning of a 1000 pages PDF only ever stores the chunks that were actually looked at.
     * <p>
     * Returns {@code null} when the blob cannot be identified by content, in which case nothing is
     * cached. Also used as the base of the HTTP ETag served by the REST endpoint.
     *
     * @param chunkStart first page of the chunk, starting at 1
     * @since 2025.7
     */
    public String getChunkCacheKey(int chunkStart) {
        return buildCacheKey("-thumbs-" + width + "x" + height + "-" + dpi + "-c" + chunkStart);
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
     * Store one chunk of thumbnails, together with the page count of the whole document.
     * <p>
     * The page count is written before the entry is flagged completed, so a reader that sees a completed
     * entry always sees the page count too.
     *
     * @return true if the chunk was effectively cached
     * @since 2025.7
     */
    protected boolean putChunkInCache(TransientStore store, String cacheKey, List<Blob> blobs, int pageCount) {

        if (cacheKey == null) {
            return false;
        }

        try {
            store.putBlobs(cacheKey, blobs);
            store.putParameter(cacheKey, PAGE_COUNT_PARAM, String.valueOf(pageCount));
            store.setCompleted(cacheKey, true);
            return true;
        } catch (MaximumTransientSpaceExceeded e) {
            log.warn("{} is full, nothing cached for key {}.", TRANSIENT_STORE_NAME, cacheKey, e);
            return false;
        }
    }

    /**
     * Page count stored alongside a chunk, or -1 when it is missing or unreadable.
     *
     * @since 2025.7
     */
    protected int readCachedPageCount(TransientStore store, String cacheKey) {

        Serializable value = store.getParameter(cacheKey, PAGE_COUNT_PARAM);
        if (value == null) {
            return -1;
        }

        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Unreadable {} parameter \"{}\" on cache key {}.", PAGE_COUNT_PARAM, value, cacheKey);
            return -1;
        }
    }

    /**
     * Read a whole chunk from the cache, or {@code null} when there is no usable entry.
     * <p>
     * Both the blobs and the page count are required: an entry written by an older version of this
     * plugin, or one truncated somehow, must be recomputed rather than served half empty.
     *
     * @since 2025.7
     */
    protected ThumbnailsChunk readChunkFromCache(TransientStore store, String cacheKey, int chunkStart,
            int chunkSize) {

        List<Blob> blobs = getFromCache(store, cacheKey);
        if (blobs == null) {
            return null;
        }

        int pageCount = readCachedPageCount(store, cacheKey);
        if (pageCount < 1) {
            return null;
        }

        return new ThumbnailsChunk(pageCount, chunkStart, chunkStart + blobs.size() - 1, chunkSize, blobs, false, 0L);
    }

    /**
     * Render, cache and return the chunk of thumbnails holding {@code startPage}.
     * <p>
     * This is <b>the</b> unit of work of the thumbnails pipeline. The PDF is opened, parsed and rendered
     * once for the whole chunk — never once per page, which on a remote blob store would mean one full
     * download per page. A cache hit costs nothing at all: the page count travels with the chunk, so
     * even answering "how many pages?" does not reopen the document.
     *
     * @param startPage any page of the wanted chunk, starting at 1
     * @return the chunk holding that page
     * @throws IllegalArgumentException if the page is beyond the end of the document
     * @since 2025.7
     */
    public ThumbnailsChunk prepareChunk(int startPage) {

        return prepareChunk(startPage, RENDER_REASON_PREPARE);
    }

    /**
     * @param reason what triggered the rendering, for the logs. Only used on a cache miss.
     * @since 2025.7
     */
    protected ThumbnailsChunk prepareChunk(int startPage, String reason) {

        int chunkSize = getChunkSize();
        int chunkStart = chunkStartFor(startPage, chunkSize);
        TransientStore store = getTransientStore();
        String cacheKey = getChunkCacheKey(chunkStart);

        ThumbnailsChunk cached = readChunkFromCache(store, cacheKey, chunkStart, chunkSize);
        if (cached != null) {
            log.debug("Thumbnails chunk cache hit for key {}.", cacheKey);
            return cached;
        }

        // Not cacheable (no digest, no storage key): nothing to lock on, and nothing to share either.
        if (cacheKey == null) {
            return renderChunk(store, null, chunkStart, chunkSize, reason);
        }

        /*
         * Six thumbnails of the same cold chunk can reach six threads at once, because that is how many
         * connections a browser opens. Only one of them may render.
         */
        synchronized (renderLockFor(cacheKey)) {
            // The thread that held the lock before us may just have filled the entry.
            cached = readChunkFromCache(store, cacheKey, chunkStart, chunkSize);
            if (cached != null) {
                log.debug("Thumbnails chunk cache hit for key {} after waiting for the render lock.", cacheKey);
                return cached;
            }

            return renderChunk(store, cacheKey, chunkStart, chunkSize, reason);
        }
    }

    /**
     * Open the PDF once and render the pages of a single chunk.
     *
     * @since 2025.7
     */
    protected ThumbnailsChunk renderChunk(TransientStore store, String cacheKey, int chunkStart, int chunkSize,
            String reason) {

        BlobList results = new BlobList();
        boolean stored = false;
        long start = System.currentTimeMillis();

        // pdfBlob.getFile() could be null, like when the
        // related file is on S3 for example, we must download it.
        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = Loader.loadPDF(source.getFile())) {

            int pageCount = document.getNumberOfPages();
            if (chunkStart > pageCount) {
                throw new IllegalArgumentException("Page " + chunkStart + " exceeds document page count " + pageCount
                        + " of \"" + pdfBlob.getFilename() + "\"");
            }

            int chunkEnd = Math.min(chunkStart + chunkSize - 1, pageCount);
            PDFRenderer renderer = new PDFRenderer(document);

            for (int pageNum = chunkStart; pageNum <= chunkEnd; pageNum++) {

                // Bounded by the thumbnail size, never by the page geometry. See renderPage().
                BufferedImage pageImage = renderPage(renderer, document, pageNum - 1, dpi,
                        Math.max(width, height));

                // Create scaled thumbnail
                BufferedImage thumb = scaleToFit(pageImage, width, height);

                results.add(imageToBlob(thumb, "jpg", ".jpg", "image/jpeg", pageNum));
            }

            stored = putChunkInCache(store, cacheKey, results, pageCount);

            long elapsed = System.currentTimeMillis() - start;

            /*
             * One line per PDF opening, on purpose: following server.log while browsing must show
             * exactly ceil(pageCount / chunkSize) of them, and never one per page. See logRendering()
             * for why the level is decided there and not here.
             */
            logRendering(reason, "Rendered thumbnails {}-{} of {} ({}x{} @ {} dpi) in {} ms for blob \"{}\","
                    + " cached: {} [{}].", chunkStart, chunkEnd, pageCount, width, height, dpi, elapsed,
                    pdfBlob.getFilename(), stored, reason);

            return new ThumbnailsChunk(pageCount, chunkStart, chunkEnd, chunkSize, results, true, elapsed);

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to extract pages " + chunkStart + " and following of \""
                    + pdfBlob.getFilename() + "\".", e);
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
     * Create thumbnails (JPEG) for all pages of the PDF.
     * Uses the width/height/dpi defined in previous calls, or default values.
     * <p>
     * Unlike {@link #prepareChunk(int)} this renders the whole document, so it is bounded by
     * {@link #getMaxPages()}. The PDF is still opened only once: the result is merely <b>stored</b> as
     * chunks, so that the REST endpoint and this method share the very same cache entries.
     *
     * @return the ordered list of thumbnails, one per page
     * @since 2025.2
     */
    public BlobList createThumbnails() {

        int chunkSize = getChunkSize();
        TransientStore store = getTransientStore();

        BlobList cached = readAllChunksFromCache(store, chunkSize);
        if (cached != null) {
            log.debug("Thumbnails cache hit for every chunk of \"{}\".", pdfBlob.getFilename());
            return cached;
        }

        BlobList results = new BlobList();
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
            BlobList currentChunk = new BlobList();
            int chunkStart = 1;

            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {

                // Bounded by the thumbnail size, never by the page geometry. See renderPage().
                BufferedImage pageImage = renderPage(renderer, document, pageIndex, dpi, Math.max(width, height));

                // Create scaled thumbnail
                BufferedImage thumb = scaleToFit(pageImage, width, height);

                Blob thumbBlob = imageToBlob(thumb, "jpg", ".jpg", "image/jpeg", pageIndex + 1);
                results.add(thumbBlob);
                currentChunk.add(thumbBlob);

                /*
                 * Flush chunk by chunk rather than at the very end: a chunk that is written is complete
                 * and self-describing, so a failure halfway through leaves usable entries behind instead
                 * of nothing.
                 */
                if (currentChunk.size() == chunkSize || pageIndex == pageCount - 1) {
                    putChunkInCache(store, getChunkCacheKey(chunkStart), currentChunk, pageCount);
                    chunkStart = pageIndex + 2;
                    currentChunk = new BlobList();
                }
            }

            logRendering(RENDER_REASON_FULL, "Rendered {} thumbnails ({}x{} @ {} dpi) in {} ms for blob \"{}\" [{}].",
                    results.size(), width, height, dpi, System.currentTimeMillis() - start, pdfBlob.getFilename(),
                    RENDER_REASON_FULL);

            return results;

        } catch (InvalidPasswordException e) {
            throw new NuxeoException(
                    "PDF \"" + pdfBlob.getFilename() + "\" is password-protected and cannot be processed.", e);
        } catch (IOException e) {
            throw new NuxeoException("Failed to extract the pages of \"" + pdfBlob.getFilename() + "\".", e);
        }
    }

    /**
     * Rebuild the whole thumbnails list from the per-chunk cache entries, or {@code null} as soon as one
     * chunk is missing.
     *
     * @since 2025.7
     */
    protected BlobList readAllChunksFromCache(TransientStore store, int chunkSize) {

        String firstChunkKey = getChunkCacheKey(1);
        if (firstChunkKey == null) {
            return null;
        }

        // The page count travels with every chunk, so the first one tells us how many we are looking for.
        List<Blob> firstChunk = getFromCache(store, firstChunkKey);
        if (firstChunk == null) {
            return null;
        }
        int pageCount = readCachedPageCount(store, firstChunkKey);
        if (pageCount < 1) {
            return null;
        }

        BlobList all = new BlobList();
        all.addAll(firstChunk);

        for (int chunkStart = 1 + chunkSize; chunkStart <= pageCount; chunkStart += chunkSize) {
            List<Blob> chunk = getFromCache(store, getChunkCacheKey(chunkStart));
            if (chunk == null) {
                return null;
            }
            all.addAll(chunk);
        }

        // A partially expired set of chunks must not be served as a complete document.
        return all.size() == pageCount ? all : null;
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

        if (pageNum < 1) {
            throw new IllegalArgumentException("Page numbers must be >= 1, got " + pageNum);
        }

        /*
         * On a cache miss this renders the chunk holding the page, never the single page and never the
         * whole document. One page at a time would mean one getCloseableFile() per page — one full
         * download of the PDF per page on a remote blob store.
         */
        ThumbnailsChunk chunk = prepareChunk(pageNum, RENDER_REASON_ENDPOINT);

        PDFTools.validatePageNumber(pageNum, chunk.pageCount(), String.valueOf(pageNum));

        int indexInChunk = pageNum - chunk.chunkStart();
        if (indexInChunk < 0 || indexInChunk >= chunk.thumbnails().size()) {
            throw new IllegalArgumentException("Page " + pageNum + " is not in the chunk starting at "
                    + chunk.chunkStart() + " of \"" + pdfBlob.getFilename() + "\"");
        }

        return chunk.thumbnails().get(indexInChunk);
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
            // Bounded by PREVIEW_PAGE_MAX_SIZE, never by the page geometry. See renderPage().
            BufferedImage pageImage = renderPage(renderer, document, pageNum - 1, PREVIEW_DPI,
                    PREVIEW_PAGE_MAX_SIZE);

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

    /**
     * Render one page, never allocating more pixels than the caller is going to keep.
     * <p>
     * <b>Do not call {@code renderImageWithDPI} directly.</b> It derives the scale from the dpi alone,
     * so the raster grows with the page geometry — and the page geometry comes from the file, which
     * means it is attacker-controlled. The PDF specification allows a 14400x14400 pt page (200x200
     * inches) in a few hundred bytes, and {@link PDFTools#MAX_PDF_SIZE} cannot catch that: the file is
     * tiny, only its declared page box is huge. Measured with PDFBox 3.0.7 on such a page:
     * <ul>
     * <li>at 72 dpi (what the Web UI dialog asks for): 14400x14400 px, <b>791 MB</b>, and it
     * <i>succeeds</i> — no error, just a server that dies under a handful of concurrent calls;</li>
     * <li>at 150 dpi ({@link #DEFAULT_DPI}): 29999x29999 px, {@code OutOfMemoryError} on a 1 GB heap;</li>
     * <li>PDFBox's own guard only fires above {@code Integer.MAX_VALUE} pixels, around 8 GB, so it
     * protects nothing on a server.</li>
     * </ul>
     * Here the dpi is only an upper bound: the effective scale is whichever is smaller between the
     * requested resolution and the one that fills {@code maxSide} times {@link #RENDER_SUPERSAMPLE}.
     * A page bigger than the target is therefore rendered <i>smaller</i> than the dpi asks for, which
     * is also a straight performance win on large-format documents (plans, posters, maps).
     *
     * @param renderer the renderer of {@code document}
     * @param document the open PDF
     * @param pageIndex the page to render, starting at 0
     * @param renderDpi the wanted resolution, used as an upper bound only
     * @param maxSide the longest side, in pixels, the caller will keep
     * @return the rendered page, at most {@link #MAX_RENDERED_PIXELS} pixels
     * @throws IOException if the rendering fails
     * @since 2025.8
     */
    protected BufferedImage renderPage(PDFRenderer renderer, PDDocument document, int pageIndex, int renderDpi,
            int maxSide) throws IOException {

        PDRectangle box = document.getPage(pageIndex).getCropBox();
        // A page rotation swaps the rendered sides, but not the longest one.
        float longestPt = Math.max(box.getWidth(), box.getHeight());

        float scale = renderDpi / 72f;

        // The dpi is a ceiling, not a target: never render more than we are going to keep.
        if (longestPt > 0f && maxSide > 0) {
            scale = Math.min(scale, (maxSide * (float) RENDER_SUPERSAMPLE) / longestPt);
        }

        /*
         * Last line of defence, for a degenerate box or a caller asking for MAX_THUMBNAIL_SIZE on a
         * page that is already enormous. Scale down by the square root since the pixel count grows
         * with the square of the scale.
         */
        long pixels = renderedPixels(box, scale);
        if (pixels > MAX_RENDERED_PIXELS) {
            scale *= (float) Math.sqrt((double) MAX_RENDERED_PIXELS / (double) pixels);
            log.debug("Page {} of \"{}\" is {}x{} pt: rendering scale capped to {} to stay under {} pixels.",
                    pageIndex + 1, pdfBlob.getFilename(), box.getWidth(), box.getHeight(), scale,
                    MAX_RENDERED_PIXELS);
        }

        // A NaN or infinite box must not turn into a NaN scale, which PDFBox would carry into the raster.
        if (!(scale > 0f) || Float.isInfinite(scale)) {
            scale = 1f;
        }

        return renderer.renderImage(pageIndex, scale, ImageType.RGB);
    }

    /**
     * Number of pixels {@code box} would occupy once rendered at {@code scale}.
     *
     * @since 2025.8
     */
    protected static long renderedPixels(PDRectangle box, float scale) {

        long width = (long) Math.ceil(Math.abs(box.getWidth()) * scale);
        long height = (long) Math.ceil(Math.abs(box.getHeight()) * scale);

        return Math.max(1L, width) * Math.max(1L, height);
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
