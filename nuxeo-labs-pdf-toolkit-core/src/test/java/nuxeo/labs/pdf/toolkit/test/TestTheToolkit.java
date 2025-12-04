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
package nuxeo.labs.pdf.toolkit.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.common.utils.FileUtils;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.util.BlobList;
import org.nuxeo.ecm.automation.test.AutomationFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.test.DefaultRepositoryInit;
import org.nuxeo.ecm.core.test.annotations.Granularity;
import org.nuxeo.ecm.core.test.annotations.RepositoryConfig;
import org.nuxeo.ecm.core.transientstore.AbstractTransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStore;
import org.nuxeo.ecm.core.transientstore.api.TransientStoreService;
import org.nuxeo.ecm.platform.picture.api.ImageInfo;
import org.nuxeo.ecm.platform.picture.api.ImagingService;
import org.nuxeo.runtime.api.Framework;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;

import jakarta.inject.Inject;
import nuxeo.labs.pdf.toolkit.PDFToImages;
import nuxeo.labs.pdf.toolkit.operations.PDFJpegimagePreviewOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageExtractorOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageOrderingOp;
import nuxeo.labs.pdf.toolkit.operations.PDFPageRemoverOp;
import nuxeo.labs.pdf.toolkit.operations.PDFThumbnailsOp;

/**
 * Test some features. About everything is tested in TestOperations
 * 
 */
@RunWith(FeaturesRunner.class)
@Features({AutomationFeature.class})
@RepositoryConfig(init = DefaultRepositoryInit.class, cleanup = Granularity.METHOD)
@Deploy("org.nuxeo.ecm.platform.picture.core")
@Deploy("org.nuxeo.ecm.core.convert")
@Deploy("nuxeo.labs.pdf.toolkit.nuxeo-labs-pdf-toolkit-core")
public class TestTheToolkit {

    public static final String TEST_PDF_PAH = "lorem_ipsum_10_pages.pdf";

    public static final int TEST_PDF_PAGE_COUNT = 10;

    public static final String TEXT_PAGE_3 = "HERE SOME TEXT FOR THE UNIT TEST";

    @Inject
    protected CoreSession session;

    @Test
    public void shouldUseTransientStore() throws Exception {
        
        TransientStoreService transientStoreService = Framework.getService(TransientStoreService.class);
        TransientStore store = transientStoreService.getStore(PDFToImages.TRANSIENT_STORE_NAME);
        assertNotNull(store);
        // We use the default TransientStore
        AbstractTransientStore storeAbstract = (AbstractTransientStore) store;
        Set<String> keys = storeAbstract.keySet();
        assertTrue(keys == null || keys.size() == 0);

        File f = FileUtils.getResourceFileFromContext(TEST_PDF_PAH);
        Blob b = new FileBlob(f);
        
        PDFToImages pdfThumbnails = new PDFToImages(b);
        BlobList thumbnails = pdfThumbnails.createThumbnails();
        
        keys = storeAbstract.keySet();
        assertTrue(keys.size() == 1);
        
        pdfThumbnails = new PDFToImages(b);
        thumbnails = pdfThumbnails.createThumbnails();
        
        keys = storeAbstract.keySet();
        assertTrue(keys.size() == 1);
        
    }
}
