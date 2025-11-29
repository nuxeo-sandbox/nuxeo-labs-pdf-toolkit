# nuxeo-labs-pdf-toolkit

> [!WARNING]
> This is **work in progress**. Using Github as a backup for now.
> Do not use it now, please.


## Manipulate PDFs from the UI

The plugin displays a dialog with the thumbnails of the pages of a PDF. User can:

* Select 1-N pages (with command/ctrl-clic and shift-click)
* Reorganize pages by drag-drop

User can then:

* Extract selected page(s)
* Remove selected page(s)
* Generate pdf with the new page order

For now, **each of these actions downloads the resulting PDF**. Original Document is never modified.

Also, double-clic on a thumbnail displays a bigger preview of the page.

<img src="README-Medias/01-Dialog.png" alt="nuxeo-labs-pdf-toolkit" width="800">


<br />

## Operations

Every action of the dialog is backed by operation that can be used, of course, outside the context of this UI:

* PDFLabs.GetThumbnails
* PDFLabs.JpegImagePreview
* PDFLabs.ExtractPagesByRange
* PDFLabs.RemovePages
* PDFLabs.ReorderPages



## To Do - Possibly

* Actions with the resulting pdf:
  * Store in file content (replkace current pdf), with opetionaly creating a version before
  * Add to files:files,
  * Create a new document (a derivative)
    * With keeping the relation in a field?
* Generating the thumbnails is not cached. So, displaying 3 times the same PDF makes Nuxeo calculate thumbnails 3 times. This could be optimized using one among different Nuxeo caches.


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

## Nuxeo Marketplace
NOT THERE YET
[here](https://connect.nuxeo.com/nuxeo/site/marketplace/package/nuxeo-labs-pdf-toolkit)

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
