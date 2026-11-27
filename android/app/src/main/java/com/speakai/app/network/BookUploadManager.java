package com.speakai.app.network;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import com.speakai.app.database.AppDatabaseHelper;
import com.speakai.app.models.Book;
import com.speakai.app.models.Chapter;
import com.speakai.app.utils.DocumentPreProcessor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

/**
 * Manages non-blocking background book upload & parsing so users can leave the app
 * or browse their library while a PDF processes, resuming automatically if needed.
 */
public class BookUploadManager {

    public interface UploadListener {
        void onUploadUpdated();
        void onUploadCompleted(long bookId, String bookTitle);
        void onUploadError(String message);
    }

    private static final String PREF_NAME = "speakai_upload_prefs";
    private static final String KEY_ACTIVE = "upload_active";
    private static final String KEY_JOB_ID = "upload_job_id";
    private static final String KEY_TITLE = "upload_title";
    private static final String KEY_FILENAME = "upload_filename";
    private static final String KEY_PROGRESS = "upload_progress";
    private static final String KEY_STAGE = "upload_stage";
    private static final String KEY_ETA = "upload_eta";

    private static BookUploadManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final AppDatabaseHelper dbHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<UploadListener> listeners = new HashSet<>();
    private boolean isPollingLoopRunning = false;
    private boolean isCancelled = false;

    private BookUploadManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.dbHelper = new AppDatabaseHelper(appContext);
    }

    public static synchronized BookUploadManager getInstance(Context context) {
        if (instance == null) {
            instance = new BookUploadManager(context);
        }
        return instance;
    }

    public synchronized void addListener(UploadListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public synchronized void removeListener(UploadListener listener) {
        listeners.remove(listener);
    }

    public boolean isUploading() {
        return prefs.getBoolean(KEY_ACTIVE, false);
    }

    public Book getProcessingBookCard() {
        if (!isUploading()) return null;

        Book placeholder = new Book();
        placeholder.setId(-999);
        placeholder.setProcessing(true);
        placeholder.setTitle(prefs.getString(KEY_TITLE, "Processing Book..."));
        placeholder.setFilename(prefs.getString(KEY_FILENAME, ""));
        placeholder.setProcessingProgress(prefs.getInt(KEY_PROGRESS, 5));
        placeholder.setProcessingStage(prefs.getString(KEY_STAGE, "Uploading to server..."));
        placeholder.setProcessingEta(prefs.getString(KEY_ETA, ""));
        return placeholder;
    }

    public void startBookUpload(final File tempFile, final String displayFileName, final String mimeType) {
        isCancelled = false;
        final String cleanTitle = stripExtension(displayFileName);
        saveState(true, "", cleanTitle, displayFileName, 5, "Preparing document...", "");
        notifyUpdated();

        new Thread(() -> {
            File uploadFile = tempFile;
            List<String> preExtractedPages = null;
            String lowerName = displayFileName != null ? displayFileName.toLowerCase() : "";

            if (lowerName.endsWith(".pdf") || (mimeType != null && mimeType.contains("pdf"))) {
                preExtractedPages = DocumentPreProcessor.tryExtractScannedPdfPages(
                        appContext,
                        uploadFile,
                        new DocumentPreProcessor.ScanProgressCallback() {
                            @Override
                            public boolean isCancelled() {
                                return BookUploadManager.this.isCancelled;
                            }

                            @Override
                            public void onScanProgress(int done, int total, int etaSeconds) {
                                int pct = 8 + Math.round((done / (float) Math.max(1, total)) * 67f);
                                String stage = "Scanning page " + done + " of " + total + " on device...";
                                String etaStr = formatEta(etaSeconds);
                                prefs.edit()
                                        .putInt(KEY_PROGRESS, pct)
                                        .putString(KEY_STAGE, stage)
                                        .putString(KEY_ETA, etaStr)
                                        .apply();
                                notifyUpdated();
                            }
                        }
                );
            } else if (lowerName.endsWith(".docx")) {
                preExtractedPages = DocumentPreProcessor.tryExtractDocxPages(uploadFile);
            } else if (mimeType != null && mimeType.startsWith("image/")) {
                uploadFile = DocumentPreProcessor.optimizeImageIfLarge(uploadFile);
            }

            if (isCancelled) {
                safeDelete(uploadFile);
                return;
            }

            final File fileToCleanup = uploadFile;
            Callback uploadCallback = new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    safeDelete(fileToCleanup);
                    if (isCancelled) return;
                    clearState();
                    notifyError("Upload connection failed: " + e.getMessage());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    safeDelete(fileToCleanup);
                    if (isCancelled) return;

                    if (!response.isSuccessful()) {
                        clearState();
                        notifyError("Server error: " + response.code());
                        return;
                    }

                    try {
                        String body = response.body().string();
                        JSONObject json = new JSONObject(body);
                        String jobId = json.getString("job_id");
                        saveState(true, jobId, cleanTitle, displayFileName, 78, "Organizing chapters & index...", "");
                        notifyUpdated();
                        startPollingIfNeeded();
                    } catch (Exception ex) {
                        clearState();
                        notifyError("Upload error: " + ex.getMessage());
                    }
                }
            };

            if (preExtractedPages != null && !preExtractedPages.isEmpty()) {
                safeDelete(fileToCleanup);
                prefs.edit()
                        .putInt(KEY_PROGRESS, 76)
                        .putString(KEY_STAGE, "Sending scanned pages to server...")
                        .putString(KEY_ETA, "")
                        .apply();
                notifyUpdated();
                ApiClient.getInstance(appContext).startParseJobWithPages(
                        preExtractedPages, displayFileName, "book", uploadCallback
                );
            } else {
                prefs.edit()
                        .putString(KEY_STAGE, "Uploading to server...")
                        .apply();
                notifyUpdated();
                ApiClient.getInstance(appContext).startParseJob(
                        uploadFile, mimeType, "book", uploadCallback
                );
            }
        }).start();
    }

    public void resumePendingIfNeeded() {
        if (isUploading()) {
            String jobId = prefs.getString(KEY_JOB_ID, "");
            if (!jobId.isEmpty() && !isPollingLoopRunning) {
                startPollingIfNeeded();
            }
        }
    }

    public void cancelUpload() {
        isCancelled = true;
        isPollingLoopRunning = false;
        handler.removeCallbacksAndMessages(null);
        clearState();
        notifyUpdated();
    }

    private void startPollingIfNeeded() {
        if (isPollingLoopRunning) return;
        String jobId = prefs.getString(KEY_JOB_ID, "");
        if (jobId.isEmpty()) return;

        isPollingLoopRunning = true;
        scheduleNextPoll(jobId, 600);
    }

    private void scheduleNextPoll(final String jobId, long delayMs) {
        handler.postDelayed(() -> pollOnce(jobId), delayMs);
    }

    private void pollOnce(final String jobId) {
        if (isCancelled || !isUploading() || !jobId.equals(prefs.getString(KEY_JOB_ID, ""))) {
            isPollingLoopRunning = false;
            return;
        }

        ApiClient.getInstance(appContext).getParseStatus(jobId, new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (isCancelled || !isUploading()) {
                    isPollingLoopRunning = false;
                    return;
                }
                scheduleNextPoll(jobId, 2000);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (isCancelled || !isUploading()) {
                    isPollingLoopRunning = false;
                    return;
                }

                if (response.code() == 404) {
                    isPollingLoopRunning = false;
                    clearState();
                    notifyError("Processing session expired on server. Please upload again.");
                    return;
                }

                if (!response.isSuccessful()) {
                    scheduleNextPoll(jobId, 2500);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject statusObj = new JSONObject(body);
                    String status = statusObj.optString("status", "processing");

                    if ("completed".equals(status)) {
                        JSONObject result = statusObj.getJSONObject("result");
                        String displayFile = prefs.getString(KEY_FILENAME, "Book.pdf");
                        long bookId = persistParsedBook(result, displayFile);
                        String title = result.optString("title", prefs.getString(KEY_TITLE, "Book"));
                        isPollingLoopRunning = false;
                        clearState();
                        notifyCompleted(bookId, title);
                    } else if ("error".equals(status)) {
                        String err = statusObj.optString("error", "Parsing failed");
                        isPollingLoopRunning = false;
                        clearState();
                        notifyError(err);
                    } else {
                        int progress = statusObj.optInt("progress", 0);
                        String stage = statusObj.optString("stage", "Processing...");
                        int etaSec = statusObj.optInt("eta_seconds", 0);
                        String etaStr = formatEta(etaSec);
                        prefs.edit()
                                .putInt(KEY_PROGRESS, progress)
                                .putString(KEY_STAGE, stage)
                                .putString(KEY_ETA, etaStr)
                                .apply();
                        notifyUpdated();
                        scheduleNextPoll(jobId, 1000);
                    }
                } catch (Exception ex) {
                    scheduleNextPoll(jobId, 2000);
                }
            }
        });
    }

    private long persistParsedBook(JSONObject json, String fileName) throws Exception {
        String title = json.optString("title", json.optString("book_title", "Untitled Document"));
        JSONArray chArray = json.optJSONArray("chapters");
        if (chArray == null) chArray = new JSONArray();

        String polishJobId = json.optString("polish_job_id", "");
        List<Chapter> chapters = new ArrayList<>();
        int playableCount = 0;

        for (int i = 0; i < chArray.length(); i++) {
            JSONObject chObj = chArray.getJSONObject(i);
            Chapter chapter = new Chapter();
            int chIndex = chObj.optInt("index", chObj.optInt("chapter_index", i + 1));
            chapter.setChapterIndex(chIndex);
            chapter.setTitle(chObj.optString("title", "Chapter " + chIndex));
            chapter.setContentText(chObj.optString("content", chObj.optString("text", "")));
            chapter.setCleanStatus(chObj.optString("clean_status", "ready"));
            chapters.add(chapter);
            if (chIndex > 0) playableCount++;
        }

        Book book = new Book();
        book.setTitle(title);
        book.setFilename(fileName);
        book.setTotalChapters(playableCount > 0 ? playableCount : chapters.size());
        book.setPolishJobId(polishJobId);

        long bookId = dbHelper.insertBook(book, chapters);
        if (!polishJobId.isEmpty()) {
            ChapterSyncManager.getInstance(appContext).startSync(bookId, polishJobId);
        }
        return bookId;
    }

    private void saveState(boolean active, String jobId, String title, String fileName, int progress, String stage, String eta) {
        prefs.edit()
                .putBoolean(KEY_ACTIVE, active)
                .putString(KEY_JOB_ID, jobId)
                .putString(KEY_TITLE, title)
                .putString(KEY_FILENAME, fileName)
                .putInt(KEY_PROGRESS, progress)
                .putString(KEY_STAGE, stage)
                .putString(KEY_ETA, eta)
                .apply();
    }

    private void clearState() {
        prefs.edit().clear().apply();
    }

    private void notifyUpdated() {
        handler.post(() -> {
            Set<UploadListener> snapshot;
            synchronized (BookUploadManager.this) {
                snapshot = new HashSet<>(listeners);
            }
            for (UploadListener l : snapshot) l.onUploadUpdated();
        });
    }

    private void notifyCompleted(long bookId, String title) {
        handler.post(() -> {
            Set<UploadListener> snapshot;
            synchronized (BookUploadManager.this) {
                snapshot = new HashSet<>(listeners);
            }
            for (UploadListener l : snapshot) l.onUploadCompleted(bookId, title);
        });
    }

    private void notifyError(String message) {
        handler.post(() -> {
            Set<UploadListener> snapshot;
            synchronized (BookUploadManager.this) {
                snapshot = new HashSet<>(listeners);
            }
            for (UploadListener l : snapshot) l.onUploadError(message);
        });
    }

    private String formatEta(int seconds) {
        if (seconds <= 0) return "";
        if (seconds < 60) return "~" + seconds + "s left";
        int mins = seconds / 60;
        int rem = seconds % 60;
        return "~" + mins + "m " + rem + "s left";
    }

    private String stripExtension(String name) {
        if (name == null || name.isEmpty()) return "Processing Book";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void safeDelete(File f) {
        if (f != null && f.exists()) {
            f.delete();
        }
    }
}
