# PDFForge — Offline PDF Studio

Native Android app built in Java. Modern dark/light dashboard, English-first localization, offline document processing.

## Features
- Merge PDFs while retaining original text and vector content where possible
- Split a PDF into separate pages, bundled into ZIP
- Extract selected page ranges and rotate any pages
- Import photos and create a PDF
- Render PDF pages to PNG images inside ZIP
- Extract searchable text into TXT
- Recent documents, Storage Access Framework, dark mode, device-language support

Works completely offline. No account, no advertising SDK, no analytics, no INTERNET permission. Uses [PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Apache 2.0).

## Supported locales
English default plus Czech, Slovak, German, French, Spanish, Italian, Portuguese, Polish, Dutch, Swedish, Romanian, Hungarian, Turkish, Ukrainian, Russian, Arabic, Hindi, Indonesian, Vietnamese, Japanese, Korean and Chinese. Untranslated messages fall back to English.

## Build
Use Android Studio (AGP 8.8.2, JDK 17), or GitHub Actions `Android APK + AAB`, which produces a **debug APK** and an **unsigned release AAB**.

⚠️ The AAB must be signed with your own **persistent private Play upload key** before submission to Google Play. Do not publish a debug-signed package and never commit your keystore to GitHub.

## Notes
The app uses Storage Access Framework for user-controlled saving and opening; it does not request broad storage permissions. Password-protected and malformed PDFs may fail. Text extraction is not OCR. Rendering and image-to-PDF conversions produce raster images and may increase file sizes.
