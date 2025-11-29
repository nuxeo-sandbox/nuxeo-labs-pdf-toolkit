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

import java.io.IOException;
import java.util.Base64;

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.NuxeoException;

import nuxeo.labs.pdf.toolkit.PDFToImages;

/**
 * @since TODO
 */
@Operation(id = PDFJpegimagePreviewOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Jpeg Image Preview", description = ""
        + "Input is either a Blob or a document. If a document, xpath is the field to use, file:content by default."
        + " pageNumber is an integer, starting at 1. Result jpeg is max 1024x1024, dpi 300")
public class PDFJpegimagePreviewOp {

    public static final String ID = "PDFLabs.JpegImagePreview";

    @Context
    protected CoreSession session;

    @Param(name = "xpath", required = false)
    protected String xpath = "file:content";

    @Param(name = "pageNumber", required = true)
    protected Integer pageNumber;
    
    @Param(name = "asBase64", required = false)
    protected Boolean asBase64 = false;

    @OperationMethod
    public Blob run(DocumentModel doc) {

        Blob b = (Blob) doc.getPropertyValue(xpath);

        return run(b);
    }

    @OperationMethod
    public Blob run(Blob blob) {

        PDFToImages pageExtractor = new PDFToImages(blob);

        Blob jpeg = pageExtractor.getJpegPreviewImage(pageNumber);
        
        if(asBase64) {
            byte[] bytes;
            try {
                bytes = jpeg.getByteArray();
            } catch (IOException e) {
                throw new NuxeoException(e);
            }
            String base64 = Base64.getEncoder().encodeToString(bytes);
            jpeg = Blobs.createBlob(base64);
        }

        return jpeg;

    }
}
