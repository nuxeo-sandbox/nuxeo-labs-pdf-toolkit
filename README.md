# nuxeo-labs-pdf-toolkit

The plugin (for Nuxeo LTS 2025  nand 2023) displays a "PDF Toolkit" button for documents which have a `file:content` blob whose mime type is "application/pdf" (see below how to override this button). Clicking this button displays a dialog with the thumbnails of the pages of the PDF.

<img src="README-Medias/01-Dialog.png" alt="nuxeo-labs-pdf-toolkit" width="800">

Users can then...

* Select 1-N pages (with command/ctrl-clic and shift-click)
* Reorganize pages by drag-drop

...and:

* Extract selected page(s)
* Remove selected page(s)
* Generate pdf with the new page order

When they click one of these buttons, a "Destination" dialog iallows for choosing what to do with the resulting PDF:

<img src="README-Medias/02-Destination.png" alt="nuxeo-labs-pdf-toolkit" width="600">

* **Download** the resulting PDF on their computer.
* **Derivative** creates a derivative of the original document.
  * This creates a copy in the same parent as the current document.
  * Users have (optional) settings available:
    * Reset the lifecycle to its inital state (so, if current document was "Approved", the derivative will be back to "project", for example)
    * Give a title to the copy. If no name is provided, the file name of the resulting blob is used
* **Attachments** save the resulting PDF `files:files`.
* **Replace file** save the resulting PDF in `file:content`.
  * Users have the option to first create a Minor/Major version

Also, double-click on a thumbnail displays a bigger preview of the page, with a better rendition.

> [!NOTE]
> Thumbnails and previews are cached in a TransientStore. So, opening the same PDF shortly after the first opening displays the thumbnails very quickly. Displying the sazme preview is also faster.

<br />

## Tuning the UI

### The "PDF Toolkit" Button

The plugin deploys a contribution to the DOCUMENTS_ACTION slot (see [nuxeo-pdf-toolkit-bundle.html](nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit/nuxeo-pdf-toolkit-bundle.html)).

To override it, copy the contribution and tune it, typically in your Studio project custom bundle. Here are some examples:

* To disable it:

```html
<nuxeo-slot-content name="pdfToolkit" slot="DOCUMENT_ACTIONS" order="1" disabled>
</nuxeo-slot-content>
```

* To change the icon (default is `icons:build`), add the `icon` attribute to the call to `nuxeo-pdf-toolkit`:

```html
<nuxeo-slot-content name="pdfToolkit" slot="DOCUMENT_ACTIONS" order="1">
  . . .
        <nuxeo-pdf-toolkit document="[[document]]" icon="nuxeo:search"></nuxeo-pdf-toolkit>
  . . .
</nuxeo-slot-content>
```

* To change the filter, display the button only for  `Contract`(and keep the text on "application/pdf"):

```html
<nuxeo-slot-content name="pdfToolkit" slot="DOCUMENT_ACTIONS" order="1">
  <template>
    <nuxeo-filter document="[[document]]" type="Contract" expression="document.properties[&quot;file:content&quot;] !&#x3D;&#x3D; null &amp;&amp; document.properties[&quot;file:content&quot;][&quot;mime-type&quot;] &#x3D;&#x3D;&#x3D; &quot;application/pdf&quot;" user="[[user]]">
    . . .
</nuxeo-slot-content>
```

<br />

### The Whole Dialog Itself

If you want to tune the dialog, you must import it in your Studio project, and it must be created at the correct place, so it overrides the file deployed by the plugin. Notice the whole plugin actually uses several elements, you can tune each of them of course (see [here](/nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/nuxeo-pdf-toolkit)). To override only nuxeo-pdf-toolkit, for example, you would do the following:

* Go to Designer > Resources
* Select "UI"
* Create a folder, named "nuxeo-pdf-toolkit"
* Select this "nuxeo-pdf-toolkit" folder, create a new element, named "nuxeo-pdf-toolkit.html"
* Paste the whole content of the original file
* Tune it as needed.

<br />

### Translation Keys

The plugin provides UI in EN and FR thanks to translation keys. If you want the UI in a different language, you can add your own messages-{language}.json file (to you Studio Project > Designer > Translations, typically). The keys to use are [here](/nuxeo-labs-pdf-toolkit-webui/src/main/resources/web/nuxeo.war/ui/i18n).

## Operations

Every action of the dialog is backed by an operation that can be used, of course, outside the context of this UI:

* PDFLabs.GetThumbnails
* PDFLabs.JpegImagePreview
* PDFLabs.ExtractPagesByRange
* PDFLabs.RemovePages
* PDFLabs.ReorderPages

### `PDFLabs.GetThumbnails`

Returns a JSON array of Base64 encoded jpeg thumbnails (so the result can be used directly in an `<img src`).

* Input: Either a `blob` or a `document`. If a `document`, `xpath` is the field to use, `file:content` by default.
* Output: JSON Array `blob` of the ordered thumbnails, jpeg, as base64.
* Parameters:
  * `xpath`: String, optional, used if input is `document`. `file:content` by default.
  * `width`: Integer, optional. The max. width of each thumbnail. Default value is 512.
  * `height`: Integer, optional. The max. height of each thumbnail. Default value is 512.
  * `dpi`: Integer, optional. The dpi to use when creating the images. Default value is 150.

> [!WARNING]
> As all is in memory as base64, don't use big images and high dpi

<br />

### `PDFLabs.JpegImagePreview`

Returns a `blob`, the jpeg of the preview, size max 1024x1024, and dpi 300.

* Input: Either a `blob` or a `document`. If a `document`, `xpath` is the field to use, `file:content` by default.
* Output: `blob`, the jpeg preview of the page
* Parameters:
  * `xpath`: String, optional, used if input is `document`. `file:content` by default.
  * `pageNumber`: Integer, required. The page to preview, starting at 1. If it is an invalid page number, a Java `IllegalArgumentException` is thrown.
  * `asBase64`: Boolean, optional (default `false`). If `ture`, returns instead a text blob of the base64 encoding of the image.

<br />

### `PDFLabs.ExtractPagesByRange`

Returns a `blob`, a pdf containing the extracted page(s).

* Input: Either a `blob` or a `document`. If a `document`, `xpath` is the field to use, `file:content` by default.
* Output: `blob`, the pdf with the extracted pages
* Parameters:
  * `xpath`: String, optional, used if input is `document`. `file:content` by default.
  * `pageRange`: String, required. Formated as in a print dialog, with pages starting at 1. For example:
    * '2-5' extracts page 2 to 5 (inclusive)
    * '2-5,8, 10-14' extracts pages 2 to 5, 8 and 10 to 14.
  * `destinationJsonStr`, string, optional (default to "download"). See below "The `destinationJsonStr` parameter".

If `pageRange` is malformed, a Java `IllegalArgumentException` is thrown. Maformed means a page number is < 1, or > number of pages, or a start page is > endPage ("10-2"), etc.

> [!NOTE]
> There also is a `PDF.ExtractPages` operation provided by the platform, which accepts only a start-end pages.

<br />

### `PDFLabs.RemovePages`

Returns a `blob`, a pdf containing the pdf without the page(s) removed.

* Input: Either a `blob` or a `document`. If a `document`, `xpath` is the field to use, `file:content` by default.
* Output: `blob`, the pdf with the page(s) removed
* Parameters:
  * `xpath`: String, optional, used if input is `document`. `file:content` by default.
  * `pageRange`: String, required. Formated as in a print dialog, with pages starting at 1. For example:
    * '2-5' removes page 2 to 5 (inclusive)
    * '2-5,8, 10-14' removes pages 2 to 5, 8 and 10 to 14.
  * `destinationJsonStr`, string, optional (default to "download"). See below "The `destinationJsonStr` parameter".

If `pageRange` is malformed, a Java `IllegalArgumentException` is thrown. Maformed means a page number is < 1, or > number of pages, or a start page is > endPage ("10-2"), etc.

<br />

### `PDFLabs.ReorderPages`

Returns a `blob`, a pdf containing the pages having the new page order.

* Input: Either a `blob` or a `document`. If a `document`, `xpath` is the field to use, `file:content` by default.
* Output: `blob`, the pdf with the page(s) removed
* Parameters:
  * `xpath`: String, optional, used if input is `document`. `file:content` by default.
  * `pageOrderJsonStr`: String, required. A JSON Array as string, with the number of the current pages, reorganized in the array. For example, `"[3,1,4,2]"` => moves page 3 to first, page 1 to second, etc.
    * It is possible to generate a new page order with less pages. For example, if the PDF has 10 pages, it is OK to pass "[3,1,4,2]", it will create a 4 pages PDF.
  * `destinationJsonStr`, string, optional (default to "download"). See below "The `destinationJsonStr` parameter".

<br />

### The `destinationJsonStr` parameter

* When not passed, the default is "download", and the operaiton returns the Blob of the resulting PDF.
* When passed it must have at least one property, `destination` whose (case insensitive) value can be: `"download"`, `"derivative"`, `"attachments"` or `"newFile"`.

For some destination, an extra `details`field, object, can be passed (optional):

* When `"derivative"`, `details` can have:
  * `"resetLifeCycle"`, a boolean, `false` by default.
  * `"derivativeTitle"`, string, the title to use for the copy. Default is the resulting PDF fil name.
* When `"attachements"`, `details` can have an `xpath` value, the field of type multivalued Blob where to append the resulting PDF. Default is `files:files`.
* When `"newFile"`, `defails` can have:
  *`"createVersion"`, boolean, default `false`.
  * If `createVersion`is `true`, another property `createVersion`, string must be either "Minor" or "Major" (default to `Minor`))


Here are some examples (don't forget to `JSON.stringify` before calling the operation):

* Derivative with lifecyle reste and custom title:

```json
{
  "destination": "derivative",
  "details": {
    "resetLifeCycle": true,
    "derivativeTitle": "My doc extracted pages"
  }
}
```

* Save to file, no version created:

```json
{
  "destination": "newFile"
}
```

Save to file, create minor version:

```json
{
  "destination": "newFile",
  "details: {"createVersion": true}
}
```

* Save to file, create major version:

```json
{
  "destination": "newFile",
  "details": {
    "createVersion": true,
    "versionType": "major"
  }
}
```

<br />

## Installation

The plugin is available on [Nuxeo MarketPlace](https://connect.nuxeo.com/nuxeo/site/marketplace/package/nuxeo-labs-pdf-toolkit), for LTS 2025 and LTS 2023. So you can

* Add it as a dependency of your Nuxeo Studio project (Modeler > Settings > Application Definition)
* Add it to `NUXEO_PACKAGES` in your Docker toolling
* Or use `nuxeoctl mp-install nuxeo-labs-pdf-toolkit`
* Or download the package and install it manually: `nuxeoctl mp-install nuxeo-labs-pdf-toolkit-{plugin version}`

<br />

## How to build
```bash
git clone https://github.com/nuxeo-sandbox/nuxeo-labs-pdf-toolkit
cd nuxeo-labs-pdf-toolkit
mvn clean install
```

<br />

## Support
**These features are not part of the Nuxeo Production platform.**

These solutions are provided for inspiration and we encourage customers to use them as code samples and learning
resources.

This is a moving project (no API maintenance, no deprecation process, etc.) If any of these solutions are found to be
useful for the Nuxeo Platform in general, they will be integrated directly into platform, not maintained here.

<br />

## License
[Apache License, Version 2.0](http://www.apache.org/licenses/LICENSE-2.0.html)

<br />

## About Nuxeo
Nuxeo Platform is an open source Content Services platform, written in Java. Data can be stored in both SQL & NoSQL
databases.

The development of the Nuxeo Platform is mostly done by Nuxeo employees with an open development model.

The source code, documentation, roadmap, issue tracker, testing, benchmarks are all public.

Typically, Nuxeo users build different types of information management solutions
for [document management](https://www.nuxeo.com/solutions/document-management/), [case management](https://www.nuxeo.com/solutions/case-management/),
and [digital asset management](https://www.nuxeo.com/solutions/dam-digital-asset-management/), use cases. It uses
schema-flexible metadata & content models that allows content to be repurposed to fulfill future use cases.

More information is available at [www.nuxeo.com](https://www.nuxeo.com).
