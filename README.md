# PDFForge — Offline PDF Studio

Native Android app built in Java. Modern dark/light dashboard, English-first localization, offline document processing.

## Features (PDFForge 1.3.0)
- Merge PDFs while retaining original text and vector content where possible
- Split a PDF into separate pages, bundled into ZIP
- Extract selected page ranges and rotate any pages
- Import photos and create a PDF
- Render PDF pages to PNG images inside ZIP
- Extract searchable text into TXT
- Draw a visible handwritten signature on the last PDF page (not a cryptographic digital signature)
- Word (.docx) → PDF, PDF → editable Word (.docx), and DOCX → plain text
- Plain text (.txt) → PDF
- Excel (.xlsx) → PDF and CSV; CSV → Excel (.xlsx) or PDF
- PowerPoint (.pptx) → PDF

### Fidelity and supported formats
Office files are handled **entirely offline**. Version 1.3 offers practical *content conversions*, not pixel-perfect Microsoft Office reproduction:
- DOCX → PDF converts paragraphs, headings and simple tables. Exact fonts, images, complex tables, headers, footers and layout are not guaranteed.
- PDF → DOCX makes an editable Word document from **extractable text**. It does not reproduce the original layout and does not OCR scanned pages.
- XLSX conversions use **the first worksheet** and the stored cell values; formulas are not recalculated.
- PPTX → PDF exports **slide text**, not slide artwork, charts, backgrounds or animations.
- New tools accept DOCX, XLSX, PPTX, TXT, CSV and PDF. Legacy binary DOC, XLS, PPT formats are not supported.
- For precise layout preservation, use Microsoft Office or LibreOffice to export as PDF before using PDFForge.
- Integrated PDF reader: open and zoom PDFs without installing a second reader (FREE)
- PDF editor (Premium): freehand drawings, highlights, text overlays, 90° page rotation, page removal, undo, and save as a NEW PDF
- Premium unlock through the Google Play Billing API using one non-consumable in-app product: `pdfforge_premium_lifetime`
- Recent documents, Storage Access Framework, dark mode, and in-app language selection with 24 supported locale options


Document viewing and conversion work entirely offline. Premium purchases and restoration communicate with Google Play; the app does not upload document contents. There is no advertising SDK and no analytics SDK. Uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0).

## Supported locales
English default plus Czech, Slovak, German, French, Spanish, Italian, Portuguese, Polish, Dutch, Swedish, Romanian, Hungarian, Turkish, Ukrainian, Russian, Arabic, Hindi, Indonesian, Vietnamese, Japanese, Korean and Chinese. Untranslated messages fall back to English.

## Google Play Premium setup

PDFForge Premium is implemented using a Google Play **one-time, non-consumable** purchase (not a subscription).

1. Set up this app on Play Console with application ID `com.xgamerstore.pdfforge`.
2. Create and activate an in-app product whose ID is exactly `pdfforge_premium_lifetime`, with a regional price set in Play Console.
3. Upload a **release AAB signed with your own persistent Play upload key**.
4. Test through a Google Play internal test track and licensed tester account. A sideloaded debug APK cannot normally display working Google Play purchase offers.
5. For production anti-fraud, add backend Google Play purchase token verification. The current on-device entitlement check and offline cache are a starter implementation, not a hardened backend.

PDF editing is intentionally a **non-destructive overlay** editor: you can add annotations and text, rotate or delete pages, then export a new file. It does **not** change or remove existing text objects or securely redact document content. Signed PDFs may lose or invalidate their original signatures when edited.

## Build
Use Android Studio (AGP 8.8.2, JDK 17), or GitHub Actions `Android APK + AAB`, which produces a **debug APK** and an **unsigned release AAB**.

⚠️ The AAB must be signed with your own **persistent private Play upload key** before submission to Google Play. Do not publish a debug-signed package and never commit your keystore to GitHub.

## Notes
The app uses Storage Access Framework for user-controlled saving and opening; it does not request broad storage permissions. Password-protected and malformed PDFs may fail. Text extraction is not OCR. Rendering and image-to-PDF conversions produce raster images and may increase file sizes.
