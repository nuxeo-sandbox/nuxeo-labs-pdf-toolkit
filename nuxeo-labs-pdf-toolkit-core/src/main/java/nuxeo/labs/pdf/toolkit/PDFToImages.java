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
import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.JSONArray;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CloseableFile;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.blobholder.BlobHolder;
import org.nuxeo.ecm.core.api.blobholder.SimpleBlobHolder;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.blob.ManagedBlob;
import org.nuxeo.ecm.core.convert.api.ConversionService;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreService;
import org.nuxeo.ecm.platform.picture.api.ImagingConvertConstants;
import org.nuxeo.runtime.api.Framework;

/**
 * Extract thumbnails or previews
 * 
 * @since LTS 2025
 */
public class PDFToImages {

    public static final int DEFAULT_THUMBNAIL_SIZE = 512;

    public static final int DEFAULT_DPI = 512;
    
    public static final int PREVIEW_PAGE_MAX_SIZE = 1024;
    
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

        if (StringUtils.isBlank(xpath)) {
            xpath = "file:content";
        }

        pdfBlob = (Blob) doc.getPropertyValue(xpath);

    }

    public PDFToImages(Blob b) {

        pdfBlob = b;

    }

    // ========================================
    // Misc. ways to set the dimension
    // ========================================
    public void setWidth(int value) {
        width = value > 0 ? value : DEFAULT_THUMBNAIL_SIZE;
    }

    public void setheight(int value) {
        height = value > 0 ? value : DEFAULT_THUMBNAIL_SIZE;
    }

    public void setSize(int size) {

        setWidth(size);
        setheight(size);
    }

    public void setSize(int width, int height) {

        setWidth(width);
        setheight(height);
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
            width = Integer.parseInt(wStr);
            height = Integer.parseInt(hStr);

            setSize(width, height);

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed dimension string: " + size, e);
        }

    }

    public void setDpi(int value) {
        dpi = value > 0 ? value : DEFAULT_DPI;
    }

    // ========================================
    // Extraction
    // ========================================
    /**
     * Extract the thumbnails, with max width/height of maxDimension.
     * If maxDimension is <= 0, the DEFAULT_DIMENSION applies.
     * 
     * @param maxDimension
     * @return
     * @throws IOException
     * @since TODO
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
     * If not passed, default dimension applies (same if a value is <= 0)
     * 
     * @param maxDimension
     * @return
     * @throws IOException
     * @since TODO
     */
    public BlobList createThumbnails(String size) {

        setSize(size);

        if (StringUtils.isBlank(size)) {
            return createThumbnails(0);
        }

        int idx = size.indexOf('x');
        if (idx <= 0 || idx == size.length() - 1) {
            throw new IllegalArgumentException("Malformed dimension string: " + size);
        }

        String wStr = size.substring(0, idx).trim();
        String hStr = size.substring(idx + 1).trim();

        try {
            width = Integer.parseInt(wStr);
            height = Integer.parseInt(hStr);

            if (width <= 0) {
                width = DEFAULT_THUMBNAIL_SIZE;
            }
            if (height == 0) {
                height = DEFAULT_THUMBNAIL_SIZE;
            }

            return createThumbnails();

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed dimension string: " + size, e);
        }
    }
    
    protected static TransientStore getTransientStore() {
        TransientStoreService transientStoreService = Framework.getService(TransientStoreService.class);
        return transientStoreService.getStore(TRANSIENT_STORE_NAME);
    }
    
    // Returns a key that can be used either by the list of thumbnails (pageNum null) or by a preview.
    protected String getCacheKey(Integer pageNum) {
        
        String pageNumSuffix = "-" + ((pageNum != null) ? pageNum : 0);
        
        String key = pdfBlob.getDigest();
        if(StringUtils.isNotBlank(key)) {
            return key + pageNumSuffix;
        }
        if(pdfBlob instanceof ManagedBlob) {
            key = ((ManagedBlob) pdfBlob).getKey();
        }
        if(StringUtils.isNotBlank(key)) {
            return key + pageNumSuffix;
        }
        
        String fileName = pdfBlob.getFilename();
        long length = pdfBlob.getLength();
        if(StringUtils.isNotBlank(fileName)) {
            return fileName + "-" + length + pageNumSuffix;
        }
        
        // No digest, no key, no filename : what the hell is this blob? :-)
        // (likely something from a unit test)
        return null;
        
    }

    /**
     * Create thumbnails (PNG) for all pages of the given PDF.
     * Uses the width/height defined in previous calls, or default values.
     */
    public BlobList createThumbnails() {
        
        String cacheKey = getCacheKey(null);
        TransientStore store = getTransientStore();
        if(cacheKey != null) {
            if(store.exists(cacheKey)) {
                return new BlobList(store.getBlobs(cacheKey));
            }
            store.setCompleted(cacheKey, false);
        }

        ImageIO.scanForPlugins();
        BlobList results = new BlobList();

        // pdfBlob.getFile() could be null, like when the
        // related file is on S3 for example, we must download it.
        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = PDDocument.load(source.getFile())) {

            PDFRenderer renderer = new PDFRenderer(document);

            int pageCount = document.getNumberOfPages();
            for (int pageIndex = 0; pageIndex < pageCount; pageIndex++) {

                BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, dpi, ImageType.RGB);

                // Create scaled thumbnail
                BufferedImage thumb = scaleToFit(pageImage, width, height);

                Blob resultBlob = imageToBlob(thumb, "jpg", ".jpg", "image/jpeg", pageIndex + 1);

                results.add(resultBlob);
            }
            if(cacheKey != null) {
                store.putBlobs(cacheKey, results);
            }

            return results;

        } catch (IOException e) {
            throw new NuxeoException("Failed to extract the pages", e);
        } finally {
            if(cacheKey != null) {
                store.setCompleted(cacheKey, true);
            }
        }
    }

    /**
     * Return the image preview, with no resizing.
     * Only dpi can be tuned (previous call to setDpi()) if nneded.
     * It resizes it at max PREVIEW_PAGE_SIZE/PREVIEW_PAGE_SIZE
     * 
     * @param pageNum
     * @return
     */
    public Blob getJpegPreviewImage(int pageNum) {
        
        String cacheKey = getCacheKey(pageNum);
        TransientStore store = getTransientStore();
        if(cacheKey != null) {
            if(store.exists(cacheKey)) {
                return store.getBlobs(cacheKey).get(0);
            }
            store.setCompleted(cacheKey, false);
        }

        ImageIO.scanForPlugins();

        // pdfBlob.getFile() could be null, like when the
        // related file is on S3 for example, we must download it.
        try (CloseableFile source = pdfBlob.getCloseableFile();
                PDDocument document = PDDocument.load(source.getFile())) {

            int pageCount = document.getNumberOfPages();
            PDFTools.validatePageNumber(pageNum, pageCount, "" + pageNum);

            PDFRenderer renderer = new PDFRenderer(document);
            int pageIndex = pageNum - 1;
            BufferedImage pageImage = renderer.renderImageWithDPI(pageIndex, 300, ImageType.RGB);

            // pageImage = scaleToFit(pageImage, PREVIEW_PAGE_SIZE, PREVIEW_PAGE_SIZE);

            Blob resultBlob = imageToBlob(pageImage, "jpg", ".jpg", "image/jpeg", pageNum);

            SimpleBlobHolder bh = new SimpleBlobHolder(resultBlob);
            Map<String, Serializable> parameters = new HashMap<>();

            parameters.put(ImagingConvertConstants.OPTION_RESIZE_WIDTH, PREVIEW_PAGE_MAX_SIZE);
            parameters.put(ImagingConvertConstants.OPTION_RESIZE_HEIGHT, PREVIEW_PAGE_MAX_SIZE);
            parameters.put(ImagingConvertConstants.CONVERSION_FORMAT, ImagingConvertConstants.JPEG_CONVERSATION_FORMAT);

            BlobHolder holder = Framework.getService(ConversionService.class).convert("pictureResize", bh, parameters);
            Blob resizedBlob = holder.getBlob();
            // Make sure to realign values
            resizedBlob.setMimeType("image/jpeg");
            resultBlob.setFilename(resultBlob.getFilename());
            
            if(cacheKey != null) {
                store.putBlobs(cacheKey, Collections.singletonList(resultBlob));
            }

            return resizedBlob;

        } catch (IOException e) {
            throw new NuxeoException("Failed to extract the page and make it a PNG.", e);
        } finally {
            if(cacheKey != null) {
                store.setCompleted(cacheKey, true);
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

        File resultFile = Framework.createTempFile(fileNameNoExt, fileExtension);
        ImageIO.write(img, formatName, resultFile);

        FileBlob result = new FileBlob(resultFile);
        result.setFilename(fileNameNoExt + fileExtension);
        result.setMimeType(mimeType);

        return result;

    }

    /**
     * Return an array of Base64 encoding of the input blobs (in same order)
     * 
     * @param blobs
     * @return
     * @throws IOException
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
            throw new NuxeoException(e);
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

        int newW = (int) Math.round(w * scale);
        int newH = (int) Math.round(h * scale);

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
