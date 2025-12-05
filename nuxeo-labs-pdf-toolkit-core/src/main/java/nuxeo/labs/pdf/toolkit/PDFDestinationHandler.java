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
import java.util.List;

import org.apache.commons.lang3.StringUtils;
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
import org.nuxeo.ecm.core.api.versioning.VersioningService;

/**
 * Handles the destination of the pdf. See {@code Destination} enum for possible values.
 * When destination is not "Download", then the DocumentModel is required in the constructor.
 * Depending on the destintion, additional params are expected.
 */
public class PDFDestinationHandler {

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
     *       derivativeTitle: the title of the copy, default is the title of the pdf
     *     If "attachments", details can be:
     *       xpath: the blob list field to use
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
     * @param doc
     * @param pdf
     * @param destinationDetailsJsonStr
     */
    public PDFDestinationHandler(DocumentModel doc, Blob pdf, String destinationDetailsJsonStr) {
        this.doc = doc;
        this.pdf = pdf;

        String destination = "download";
        JSONObject details = new JSONObject();

        if (StringUtils.isNotBlank(destinationDetailsJsonStr)) {
            JSONObject destinationDetails = new JSONObject(destinationDetailsJsonStr);
            if (destinationDetails.has("destination")) {
                destination = destinationDetails.optString("destination", "download");
                if (destinationDetails.has("details")) {
                    details = destinationDetails.optJSONObject("details");
                }
            }
        }

        this.destination = Destination.fromLabel(destination);
        this.details = details;

        // Sanity check
        if (this.doc == null && this.destination != Destination.DOWNLOAD) {
            throw new NuxeoException("A document is required when desitnation is not just a download.");
        }
    }

    /**
     * Run the action and returns a Blob:
     * - Either a file blob (to download from the browser)
     * - Or a JSON blob. The content depends on the destination (see contructor for info on destination/details)
     * <ul>
     * <li>"download", returns the pdf as is</li>
     * <li>"derivative", return a JSON:<\br>
     * <code>{"status": "done", "derivativeId": the UUID of the derivative}</code></li>
     * <li>attachments, return a JSON, <code>{"status": "done"}</code></li>
     * <li>newFile, return a JSON, <code>{"status": "done"}</code></li>
     * </ul>
     * 
     * @return
     */
    public Blob run() {

        CoreSession session = null;
        ;
        if (doc != null) {
            session = doc.getCoreSession();
        }

        switch (destination) {
        case DOWNLOAD:
            return pdf;

        case ATTACHMENTS:
            String xpath = null;

            if (details != null && details.has("xpath")) {
                xpath = details.getString("xpath");
            }

            if (StringUtils.isBlank(xpath)) {
                xpath = "files:files";
            }
            DocumentHelper.addBlob(doc.getProperty(xpath), pdf);
            doc = session.saveDocument(doc);
            
            return Blobs.createJSONBlob("{\"status\": \"done\"}");

        case DERIVATIVE:
            // Future improvement: where to create the derivative?
            // Like details.getString("destinationPath") or details.getString("destinationID")
            DocumentRef target = doc.getParentRef();

            List<CopyOption> options = new ArrayList<>();

            if (details.optBoolean("resetLifeCycle", false)) {
                options.add(CopyOption.RESET_LIFE_CYCLE);
            }
            // Try to find a name if it is not provided
            String title = details.optString("derivativeTitle", null);
            if (StringUtils.isBlank(title)) {
                title = pdf.getFilename();
            }

            DocumentModel copy = session.copy(doc.getRef(), target, title, options.toArray(CopyOption[]::new));
            
            copy.setPropertyValue("dc:title", title);
            copy.setPropertyValue("file:content", (Serializable) pdf);
            copy = session.saveDocument(copy);

            // doc uid etc
            JSONObject result = new JSONObject();
            result.put("status", "done");
            result.put("derivativeId", copy.getId());
            return Blobs.createJSONBlob(result.toString());

        case NEW_FILE:
            // Create version?
            if (details.optBoolean("createVersion", false)) {
                // Realign versionType to be cool with the caller and avoir failing at version creation
                String versionType = details.optString("versionType", "Minor").toLowerCase();
                VersioningOption vo;
                if ("major".equals(versionType)) {
                    vo = VersioningOption.MAJOR;
                } else {
                    vo = VersioningOption.MINOR;
                }
                doc.putContextData(VersioningService.VERSIONING_OPTION, vo);
                doc = session.saveDocument(doc);
                // Clear context data to avoid incrementing version in next operations if not needed.
                doc = session.getDocument(doc.getRef());
            }

            doc.setPropertyValue("file:content", (Serializable) pdf);
            doc = session.saveDocument(doc);

            return Blobs.createJSONBlob("{\"status\": \"done\"}");

        default:
            throw new IllegalArgumentException("Invalid destination");
        }

    }

}
