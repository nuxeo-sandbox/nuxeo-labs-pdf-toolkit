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

import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;

import nuxeo.labs.pdf.toolkit.PDFPageExtractor;

/**
 * 
 * 
 * @since TODO
 */
@Operation(id = PDFPageExtractorOp.ID, category = Constants.CAT_CONVERSION, label = "PDF Extract Pages by Range", description = ""
        + "Input is either a Blob or a document. If a document, xpath is the field to use, file:content by default."
        + " pageRange is a string, required, formated as when you display a print dialog, with pages starting at 1."
        + " For example, '2-5' removes page 2 to 5 (inclusive). '2-5,8, 10-14' removes pages 2 to 5, 8 and 10 to 14."
        + " Notice there also is a PDF.ExtractPages operaiton, with accepts only a start-end pages.")
public class PDFPageExtractorOp {

    public static final String ID = "PDFLabs.ExtractPagesByRange";

    @Context
    protected CoreSession session;

    @Param(name = "xpath", required = false)
    protected String xpath = "file:content";

    @Param(name = "pageRange", required = true)
    protected String pageRange;

    @OperationMethod
    public Blob run(DocumentModel doc) {

        Blob b = (Blob) doc.getPropertyValue(xpath);

        return run(b);
    }

    @OperationMethod
    public Blob run(Blob blob) {

        PDFPageExtractor pageExtractor = new PDFPageExtractor(blob);

        Blob resultPdf = pageExtractor.extractPages(pageRange);

        return resultPdf;

    }
}
