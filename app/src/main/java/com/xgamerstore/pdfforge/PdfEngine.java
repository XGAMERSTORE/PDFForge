package com.xgamerstore.pdfforge;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline PDF operations. All methods are invoked from a background thread. */
final class PdfEngine {
    enum Action { MERGE, SPLIT, EXTRACT, ROTATE, IMAGE_TO_PDF, PDF_TO_IMAGE, PDF_TO_TEXT }
    private PdfEngine() {}

    static void execute(Context context, Action action, List<Uri> files,
                        String parameter, Uri output) throws Exception {
        if (files == null || files.isEmpty()) throw new IOException("Choose at least one file.");
        try (OutputStream result = context.getContentResolver().openOutputStream(output, "w")) {
            if (result == null) throw new IOException("Cannot create the selected file.");
            switch (action) {
                case MERGE: merge(context, files, result); break;
                case SPLIT: split(context, files.get(0), result); break;
                case EXTRACT: extract(context, files.get(0), parameter, result); break;
                case ROTATE: rotate(context, files.get(0), parameter, result); break;
                case IMAGE_TO_PDF: imagesToPdf(context, files, result); break;
                case PDF_TO_IMAGE: pdfToImages(context, files.get(0), result); break;
                case PDF_TO_TEXT: pdfToText(context, files.get(0), result); break;
            }
            result.flush();
        }
    }

    private static PDDocument open(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Unable to read input document.");
            return PDDocument.load(in);
        }
    }

    private static void merge(Context ctx, List<Uri> files, OutputStream output) throws IOException {
        if (files.size() < 2) throw new IOException("Select at least two PDF documents.");
        PDFMergerUtility merger = new PDFMergerUtility();
        List<InputStream> opened = new ArrayList<>();
        try {
            for (Uri uri : files) {
                InputStream input = ctx.getContentResolver().openInputStream(uri);
                if (input == null) throw new IOException("Unable to read a selected document.");
                opened.add(input);
                merger.addSource(input);
            }
            merger.setDestinationStream(output);
            merger.mergeDocuments(MemoryUsageSetting.setupTempFileOnly());
        } finally {
            for (InputStream stream : opened) try { stream.close(); } catch (IOException ignored) {}
        }
    }

    private static void split(Context ctx, Uri uri, OutputStream output) throws IOException {
        try (PDDocument source = open(ctx, uri);
             ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(output))) {
            if (source.getNumberOfPages() == 0) throw new IOException("Empty PDF.");
            for (int i = 0; i < source.getNumberOfPages(); i++) {
                try (PDDocument single = new PDDocument()) {
                    single.importPage(source.getPage(i));
                    zip.putNextEntry(new ZipEntry(String.format(Locale.US, "page_%04d.pdf", i + 1)));
                    single.save(new NonClosingOutputStream(zip));
                    zip.closeEntry();
                }
            }
            zip.finish();
        }
    }

    private static void extract(Context ctx, Uri uri, String pages, OutputStream output) throws IOException {
        try (PDDocument source = open(ctx, uri);
             PDDocument destination = new PDDocument()) {
            for (int index : parsePages(pages, source.getNumberOfPages(), false)) {
                destination.importPage(source.getPage(index));
            }
            if (destination.getNumberOfPages() == 0) throw new IOException("No pages selected.");
            destination.save(output);
        }
    }

    private static void rotate(Context ctx, Uri uri, String pages, OutputStream output) throws IOException {
        try (PDDocument document = open(ctx, uri)) {
            for (int index : parsePages(pages, document.getNumberOfPages(), true)) {
                PDPage page = document.getPage(index);
                page.setRotation((page.getRotation() + 90) % 360);
            }
            document.save(output);
        }
    }

    /** Accept 1,3-5,7 or "all", respecting the order in the document. */
    static List<Integer> parsePages(String spec, int total, boolean allowAll) throws IOException {
        Set<Integer> chosen = new LinkedHashSet<>();
        if (total <= 0) throw new IOException("PDF has no pages.");
        if (allowAll && (spec == null || spec.trim().isEmpty()
                || "all".equalsIgnoreCase(spec.trim()))) {
            for (int i = 0; i < total; i++) chosen.add(i);
        } else {
            if (spec == null || !spec.matches("[0-9,\\-\\s]+")) {
                throw new IOException("Invalid page range. Example: 1-3,5");
            }
            for (String group : spec.replaceAll("\\s+", "").split(",")) {
                if (group.isEmpty()) throw new IOException("Invalid page range.");
                String[] bounds = group.split("-", -1);
                if (bounds.length > 2 || bounds[0].isEmpty()) throw new IOException("Invalid page range.");
                int begin, end;
                try {
                    begin = Integer.parseInt(bounds[0]);
                    end = bounds.length == 2 ? Integer.parseInt(bounds[1]) : begin;
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid page range.", e);
                }
                if (begin < 1 || end > total || begin > end) {
                    throw new IOException("Pages must be between 1 and " + total + ".");
                }
                for (int i = begin; i <= end; i++) chosen.add(i - 1);
            }
        }
        if (chosen.isEmpty()) throw new IOException("No pages selected.");
        return new ArrayList<>(chosen);
    }

    private static void imagesToPdf(Context ctx, List<Uri> files, OutputStream output) throws IOException {
        PdfDocument pdf = new PdfDocument();
        try {
            int pageNo = 0;
            for (Uri uri : files) {
                Bitmap image = decodeSampled(ctx, uri);
                if (image == null) throw new IOException("Could not read image.");
                try {
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(595, 842, ++pageNo).create();
                    PdfDocument.Page page = pdf.startPage(info);
                    Canvas canvas = page.getCanvas();
                    canvas.drawColor(Color.WHITE);
                    float maxWidth = 555f, maxHeight = 802f;
                    float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
                    float width = image.getWidth() * scale;
                    float height = image.getHeight() * scale;
                    float x = (595 - width) / 2f, y = (842 - height) / 2f;
                    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    canvas.drawBitmap(image, null, new RectF(x, y, x + width, y + height), paint);
                    pdf.finishPage(page);
                } finally {
                    image.recycle();
                }
            }
            pdf.writeTo(output);
        } finally {
            pdf.close();
        }
    }

    private static Bitmap decodeSampled(Context ctx, Uri uri) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Could not open image");
            BitmapFactory.decodeStream(in, null, opts);
        }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null;
        opts.inJustDecodeBounds = false;
        int sample = 1;
        while (opts.outWidth / sample > 2000 || opts.outHeight / sample > 2600) sample *= 2;
        opts.inSampleSize = sample;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, opts);
        }
    }

    private static void pdfToImages(Context ctx, Uri uri, OutputStream output) throws IOException {
        File cache = File.createTempFile("render_", ".pdf", ctx.getCacheDir());
        try {
            try (InputStream in = ctx.getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(cache)) {
                if (in == null) throw new IOException("Cannot open PDF.");
                copy(in, out);
            }
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(cache, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(fd);
                 ZipOutputStream zip = new ZipOutputStream(new NonClosingOutputStream(output))) {
                for (int i = 0; i < renderer.getPageCount(); i++) {
                    try (PdfRenderer.Page page = renderer.openPage(i)) {
                        int width = Math.max(1, Math.min(2400, page.getWidth() * 2));
                        int height = Math.max(1, Math.min(3200, page.getHeight() * 2));
                        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        try {
                            bitmap.eraseColor(Color.WHITE);
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            zip.putNextEntry(new ZipEntry(String.format(Locale.US, "page_%04d.png", i + 1)));
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, zip);
                            zip.closeEntry();
                        } finally {
                            bitmap.recycle();
                        }
                    }
                }
                zip.finish();
            }
        } finally {
            if (!cache.delete()) cache.deleteOnExit();
        }
    }

    private static void pdfToText(Context ctx, Uri uri, OutputStream output) throws IOException {
        try (PDDocument pdf = open(ctx, uri)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(pdf);
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] block = new byte[16 * 1024];
        int read;
        while ((read = in.read(block)) != -1) out.write(block, 0, read);
    }

    /** Prevent nested writer or ZIP close from closing the system document stream early. */
    private static final class NonClosingOutputStream extends OutputStream {
        private final OutputStream delegate;
        NonClosingOutputStream(OutputStream output) { delegate = output; }
        @Override public void write(int b) throws IOException { delegate.write(b); }
        @Override public void write(byte[] bytes, int off, int length) throws IOException { delegate.write(bytes, off, length); }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.flush(); }
    }
}
