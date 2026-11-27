package com.speakai.app.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import android.util.Xml;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Optimizes uploads on-device before hitting the cloud backend:
 * - Compresses oversized photos/screenshots cleanly
 * - Extracts .docx XML text locally
 * - Runs fast on-device ML Kit OCR for scanned-image PDFs via PdfRenderer
 */
public class DocumentPreProcessor {

    public interface ScanProgressCallback {
        boolean isCancelled();
        void onScanProgress(int done, int total, int etaSeconds);
    }

    public static File optimizeImageIfLarge(File imageFile) {
        if (imageFile == null || !imageFile.exists()) return imageFile;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imageFile.getAbsolutePath(), bounds);

            int w = bounds.outWidth;
            int h = bounds.outHeight;
            if (w <= 0 || h <= 0) return imageFile;
            if (Math.max(w, h) <= 1600 && imageFile.length() <= 450_000) {
                return imageFile;
            }

            int sampleSize = 1;
            while (Math.max(w / sampleSize, h / sampleSize) > 2200) {
                sampleSize *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize;
            Bitmap decoded = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), opts);
            if (decoded == null) return imageFile;

            int dw = decoded.getWidth();
            int dh = decoded.getHeight();
            Bitmap scaled = decoded;
            if (Math.max(dw, dh) > 1600) {
                float ratio = 1600f / Math.max(dw, dh);
                scaled = Bitmap.createScaledBitmap(decoded, Math.round(dw * ratio), Math.round(dh * ratio), true);
                if (scaled != decoded) decoded.recycle();
            }

            File out = new File(imageFile.getParentFile(), "opt_" + imageFile.getName() + ".jpg");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                scaled.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            scaled.recycle();
            imageFile.delete();
            return out;
        } catch (Exception ignored) {
            return imageFile;
        }
    }

    public static List<String> tryExtractDocxPages(File docxFile) {
        if (docxFile == null || !docxFile.exists()) return null;
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(docxFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    XmlPullParser parser = Xml.newPullParser();
                    parser.setInput(zis, "UTF-8");
                    int event = parser.getEventType();
                    StringBuilder para = new StringBuilder();
                    while (event != XmlPullParser.END_DOCUMENT) {
                        String tag = parser.getName();
                        if (event == XmlPullParser.START_TAG && "t".equals(tag)) {
                            para.append(parser.nextText());
                        } else if (event == XmlPullParser.END_TAG && "p".equals(tag)) {
                            String line = para.toString().trim();
                            if (!line.isEmpty()) {
                                sb.append(line).append("\n");
                            }
                            para.setLength(0);
                        }
                        event = parser.next();
                    }
                    break;
                }
            }
        } catch (Exception e) {
            return null;
        }
        String text = sb.toString().trim();
        if (text.isEmpty()) return null;
        List<String> pages = new ArrayList<>();
        pages.add(text);
        return pages;
    }

    public static List<String> tryExtractScannedPdfPages(
            Context context,
            File pdfFile,
            ScanProgressCallback callback
    ) {
        if (pdfFile == null || !pdfFile.exists()) return null;

        ParcelFileDescriptor pfd = null;
        PdfRenderer renderer = null;
        TextRecognizer recognizer = null;
        try {
            pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int totalPages = renderer.getPageCount();
            if (totalPages <= 0) return null;

            if (!isLikelyScannedPdf(pdfFile, totalPages)) {
                // Text PDF (like embedded Hindi/English PDFs): let backend pypdf extract in <1s
                return null;
            }

            recognizer = TextRecognition.getClient(new DevanagariTextRecognizerOptions.Builder().build());
            List<String> pages = new ArrayList<>(totalPages);
            int nonEmptyCount = 0;
            long startMs = System.currentTimeMillis();

            for (int i = 0; i < totalPages; i++) {
                if (callback != null && callback.isCancelled()) {
                    return null;
                }

                PdfRenderer.Page page = renderer.openPage(i);
                Bitmap bitmap = null;
                try {
                    int targetW = Math.max(900, Math.min(1300, page.getWidth() * 2));
                    float scale = targetW / (float) Math.max(1, page.getWidth());
                    int targetH = Math.max(1, Math.round(page.getHeight() * scale));

                    bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888);
                    Canvas canvas = new Canvas(bitmap);
                    canvas.drawColor(Color.WHITE);
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                } finally {
                    page.close();
                }

                String pageText = "";
                try {
                    InputImage img = InputImage.fromBitmap(bitmap, 0);
                    Text result = Tasks.await(recognizer.process(img), 20, TimeUnit.SECONDS);
                    if (result != null && result.getText() != null) {
                        pageText = result.getText().trim();
                    }
                } finally {
                    if (bitmap != null && !bitmap.isRecycled()) {
                        bitmap.recycle();
                    }
                }

                if (!pageText.isEmpty()) nonEmptyCount++;
                pages.add(pageText);

                if (callback != null) {
                    int done = i + 1;
                    long elapsedMs = Math.max(100, System.currentTimeMillis() - startMs);
                    int etaSec = done < totalPages
                            ? (int) Math.round(((elapsedMs / 1000.0) / done) * (totalPages - done)) + 1
                            : 0;
                    callback.onScanProgress(done, totalPages, etaSec);
                }
            }

            if (nonEmptyCount < Math.max(1, totalPages / 3)) {
                return null;
            }
            return pages;
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (recognizer != null) {
                try { recognizer.close(); } catch (Exception ignored) {}
            }
            if (renderer != null) {
                try { renderer.close(); } catch (Exception ignored) {}
            }
            if (pfd != null) {
                try { pfd.close(); } catch (Exception ignored) {}
            }
        }
    }

    private static boolean isLikelyScannedPdf(File pdfFile, int totalPages) {
        try (InputStream is = new FileInputStream(pdfFile)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16384];
            int n;
            int maxScanBytes = 1_500_000;
            while ((n = is.read(chunk)) != -1 && buffer.size() < maxScanBytes) {
                buffer.write(chunk, 0, n);
            }
            String ascii = new String(buffer.toByteArray(), "ISO-8859-1");
            int imageTags = countOccurrences(ascii, "/Subtype /Image") + countOccurrences(ascii, "/Subtype/Image");
            int toUnicodeTags = countOccurrences(ascii, "/ToUnicode");
            return imageTags >= Math.max(3, (int) (totalPages * 0.5))
                    && toUnicodeTags < Math.max(3, (int) (totalPages * 0.25));
        } catch (Exception e) {
            return false;
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
