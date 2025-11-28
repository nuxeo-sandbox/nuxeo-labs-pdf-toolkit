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
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;

import nuxeo.labs.pdf.toolkit.PDFPageOrdering;

/**
 * @since TODO
 */
@Operation(id = PDFPageOrderingOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Reorder Pages", description = ""
        + "Input is either a Blob or a document. If a document, xpath is the field to use, file:content by default."
        + " pageOrderJsonStr is a JSON array as string, required, with the number of current pages, reorganized in the array."
        + " For example, passing [3,1,4,2] => move page 3 to first, page 1 to second etc."
        + " The number of pages can be less or equal to the original number of pages.")
public class PDFPageOrderingOp {

    public static final String ID = "PDFLabs.ReorderPages";

    @Context
    protected CoreSession session;

    @Param(name = "xpath", required = false)
    protected String xpath = "file:content";

    @Param(name = "pageOrderJsonStr", required = true)
    protected String pageOrderJsonStr;

    @OperationMethod
    public Blob run(DocumentModel doc) {

        Blob b = (Blob) doc.getPropertyValue(xpath);

        return run(b);
    }

    @OperationMethod
    public Blob run(Blob blob) {

        PDFPageOrdering pageOrdering = new PDFPageOrdering(blob);

        JSONArray arr = new JSONArray(pageOrderJsonStr);
        int[] newPageOrder = new int[arr.length()];

        for (int i = 0; i < arr.length(); i++) {
            newPageOrder[i] = arr.getInt(i);
        }

        Blob resultPdf = pageOrdering.reorganizePdf(newPageOrder);

        return resultPdf;

    }
}
