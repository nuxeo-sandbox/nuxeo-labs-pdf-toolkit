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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.nuxeo.ecm.automation.core.util.DocumentHelper;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.Blobs;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.CoreSession.CopyOption;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.NuxeoException;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.model.Property;
import org.nuxeo.ecm.core.api.model.PropertyNotFoundException;
import org.nuxeo.ecm.core.api.pathsegment.PathSegmentService;
import org.nuxeo.ecm.core.api.versioning.VersioningService;
import org.nuxeo.runtime.api.Framework;

/**
 * Handles the destination of the pdf. See {@code Destination} enum for possible values.
 * When destination is not "Download", then the DocumentModel is required in the constructor.
 * Depending on the destination, additional params are expected.
 *
 * @since 2025.2
 */
public class PDFDestinationHandler {

    private static final Logger log = LogManager.getLogger(PDFDestinationHandler.class);

    public static final String DEFAULT_ATTACHMENTS_XPATH = "files:files";

    protected DocumentModel doc;

    protected Blob pdf;

    protected Destination destination;

    protected JSONObject details;

    public enum Destination {
        DOWNLOAD("download"), DERIVATIVE("derivative"), ATTACHMENTS("attachments"), NEW_FILE("newFile");

        private final String label;

        Destination(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Destination fromLabel(String label) {
            for (Destination d : values()) {
                if (d.label.equalsIgnoreCase(label)) { // case-insensitive
                    return d;
                }
            }
            throw new IllegalArgumentException("Unknown Destination label: " + label);
        }

    }

    /**
     * destinationDetailsJsonStr is a JSON object as string. If not passed, destination is "download".
     *
     * <pre>
     * <code>
     * {
     *   "destination": download|derivative|attachments|newFile,
     *   "details": Depends on the destination
     *     If "newFile" details can be (optional)
     *       createVersion: true|false, default false
     *       versionType: minor|major, default minor (case insensitive)
     *     If derivative, details can be (optional)
     *       resetLifeCycle: true|false default false
     *       derivativeTitle: the title of the copy, default is the file name of the pdf
     *     If "attachments", details can be:
     *       xpath: the blob list field to use. It must be multivalued.
     *       If not passed, "files:files" is used
     * }
     * </code>
     * </pre>
     *
     * Examples:
     *
     * <pre>
     * <code>
     * {
     *   "destination": "derivative",
     *   "details": {
     *     "resetLifeCycle": true,
     *     "derivativeTitle": "My doc extracted pages"
     *   }
     * }
     * Save to file, no version created
     * {
     *   "destination": "newFile"
     * }
     * Save to file, create minor version
     * {
     *   "destination": "newFile",
     *   "details": {
     *     "createVersion": true
     *   }
     * }
     * Save to file, create major version
     * {
     *   "destination": "newFile",
     *   "details": {
     *     "createVersion": true,
     *     "versionType": "major"
     *   }
     * }
     * </code>
     * </pre>
     *
     * @param doc the source document, required unless the destination is a plain download
     * @param pdf the resulting pdf to handle
     * @param destinationDetailsJsonStr the destination descriptor, "download" when blank
     * @since 2025.2
     */
    public PDFDestinationHandler(DocumentModel doc, Blob pdf, String destinationDetailsJsonStr) {

        this.doc = doc;
        this.pdf = pdf;

        String destinationLabel = "download";
        JSONObject destinationDetails = new JSONObject();

        if (StringUtils.isNotBlank(destinationDetailsJsonStr)) {
            JSONObject json;
            try {
                json = new JSONObject(destinationDetailsJsonStr);
            } catch (JSONException e) {
                throw new NuxeoException("destinationJsonStr is not valid JSON: " + destinationDetailsJsonStr, e);
            }
            destinationLabel = json.optString("destination", "download");
            /*
             * optJSONObject() returns null as soon as "details" is not an object, which would then be
             * dereferenced without check further down. Read it independently of "destination" too: a
             * payload carrying details but no destination used to have its details silently dropped.
             */
            JSONObject optionalDetails = json.optJSONObject("details");
            if (optionalDetails != null) {
                destinationDetails = optionalDetails;
            }
        }

        try {
            this.destination = Destination.fromLabel(destinationLabel);
        } catch (IllegalArgumentException e) {
            throw new NuxeoException("Unknown destination \"" + destinationLabel + "\". Expected one of "
                    + Arrays.toString(Destination.values()) + ".", e);
        }
        this.details = destinationDetails;

        // Sanity check
        if (this.doc == null && this.destination != Destination.DOWNLOAD) {
            throw new NuxeoException("A document is required when destination is not just a download.");
        }
    }

    /**
     * Run the action and returns a Blob:
     * - Either a file blob (to download from the browser)
     * - Or a JSON blob. The content depends on the destination (see constructor for info on destination/details)
     * <ul>
     * <li>"download", returns the pdf as is</li>
     * <li>"derivative", return a JSON:<br>
     * <code>{"status": "done", "derivativeId": the UUID of the derivative}</code></li>
     * <li>attachments, return a JSON, <code>{"status": "done"}</code></li>
     * <li>newFile, return a JSON, <code>{"status": "done"}</code></li>
     * </ul>
     *
     * @return the resulting blob, see above
     * @since 2025.2
     */
    public Blob run() {

        // doc is guaranteed non-null for every destination but DOWNLOAD (checked in the constructor).
        CoreSession session = (doc != null) ? doc.getCoreSession() : null;

        switch (destination) {
        case DOWNLOAD:
            return pdf;

        case ATTACHMENTS:
            return addToAttachments(session);

        case DERIVATIVE:
            return createDerivative(session);

        case NEW_FILE:
            return replaceMainFile(session);

        default:
            throw new NuxeoException("Invalid destination: " + destination);
        }

    }

    protected Blob addToAttachments(CoreSession session) {

        String xpath = details.optString("xpath", null);
        if (StringUtils.isBlank(xpath)) {
            xpath = DEFAULT_ATTACHMENTS_XPATH;
        }

        Property targetProperty;
        try {
            targetProperty = doc.getProperty(xpath);
        } catch (PropertyNotFoundException e) {
            throw new NuxeoException("Property \"" + xpath + "\" does not exist on document " + doc.getId() + ".", e);
        }

        /*
         * DocumentHelper.addBlob() silently falls back to setValue() when the property is not a list, which
         * would overwrite the target instead of appending to it. Refuse explicitly: the caller asked for
         * "attachments", destroying a single-valued blob is never the expected outcome.
         */
        if (!targetProperty.isList()) {
            throw new NuxeoException("Destination \"attachments\" requires a multivalued blob property, but \"" + xpath
                    + "\" is single-valued. Use destination \"newFile\" to replace a blob.");
        }

        DocumentHelper.addBlob(targetProperty, pdf);
        doc = session.saveDocument(doc);

        log.debug("Added \"{}\" to {} of document {}.", pdf.getFilename(), xpath, doc.getId());

        return Blobs.createJSONBlob("{\"status\": \"done\"}");
    }

    protected Blob createDerivative(CoreSession session) {

        // Future improvement: where to create the derivative?
        // Like details.getString("destinationPath") or details.getString("destinationID")
        DocumentRef target = doc.getParentRef();
        if (target == null) {
            throw new NuxeoException("Cannot create a derivative of document " + doc.getId()
                    + ": it has no parent (is it a version or the repository root?).");
        }

        List<CopyOption> options = new ArrayList<>();
        if (details.optBoolean("resetLifeCycle", false)) {
            options.add(CopyOption.RESET_LIFE_CYCLE);
        }

        // Try to find a title if it is not provided
        String title = details.optString("derivativeTitle", null);
        if (StringUtils.isBlank(title)) {
            title = pdf.getFilename();
        }

        /*
         * The 3rd argument of copy() is the document NAME (path segment), not the title: PathRef.checkName()
         * rejects any name holding a slash, which a free-text title easily contains. Normalize it the way the
         * platform does, and set dc:title separately.
         */
        String name = Framework.getService(PathSegmentService.class).generatePathSegment(title);

        DocumentModel copy = session.copy(doc.getRef(), target, name, options.toArray(CopyOption[]::new));

        copy.setPropertyValue("dc:title", title);
        copy.setPropertyValue("file:content", (Serializable) pdf);
        copy = session.saveDocument(copy);

        log.debug("Created derivative {} of document {}.", copy.getId(), doc.getId());

        JSONObject result = new JSONObject();
        result.put("status", "done");
        result.put("derivativeId", copy.getId());

        return Blobs.createJSONBlob(result.toString());
    }

    protected Blob replaceMainFile(CoreSession session) {

        // Create version?
        if (details.optBoolean("createVersion", false)) {
            // Realign versionType to be cool with the caller and avoid failing at version creation
            String versionType = details.optString("versionType", "Minor").toLowerCase();
            VersioningOption versioningOption = "major".equals(versionType) ? VersioningOption.MAJOR
                    : VersioningOption.MINOR;
            doc.putContextData(VersioningService.VERSIONING_OPTION, versioningOption);
            doc = session.saveDocument(doc);
            // Clear context data to avoid incrementing version in next operations if not needed.
            doc = session.getDocument(doc.getRef());
        }

        doc.setPropertyValue("file:content", (Serializable) pdf);
        doc = session.saveDocument(doc);

        log.debug("Replaced file:content of document {} with \"{}\".", doc.getId(), pdf.getFilename());

        return Blobs.createJSONBlob("{\"status\": \"done\"}");
    }

}
