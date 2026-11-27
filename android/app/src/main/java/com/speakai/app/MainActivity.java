package com.speakai.app;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.speakai.app.adapters.BookAdapter;
import com.speakai.app.database.AppDatabaseHelper;
import com.speakai.app.models.Book;
import com.speakai.app.models.QuickReadHolder;
import com.speakai.app.network.ApiClient;
import com.speakai.app.network.BookUploadManager;
import com.speakai.app.ui.MiniPlayerController;
import com.speakai.app.ui.SearchBarHelper;
import com.speakai.app.utils.DocumentPreProcessor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements BookAdapter.OnBookClickListener, BookUploadManager.UploadListener {

    private RecyclerView rvBooks;
    private BookAdapter adapter;
    private final List<Book> allSavedBooks = new ArrayList<>();
    private final List<Book> bookList = new ArrayList<>();
    private String currentBookQuery = "";
    private View bookSearchBar;
    private AppDatabaseHelper dbHelper;
    private MiniPlayerController miniPlayerController;

    private LinearLayout layoutEmptyState;
    private TextView tvBookCount;
    private MaterialButton btnQuickRead, btnSaveBook, btnEnglishTutor;

    private ActivityResultLauncher<String[]> filePickerLauncher;
    private String pendingMode = "book";
    private boolean isQuickCancelled = false;

    private AlertDialog quickProgressDialog;
    private ProgressBar pbJobProgress;
    private TextView tvJobPercent, tvJobStage, tvJobEta;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dbHelper = new AppDatabaseHelper(this);

        initViews();
        setupPickers();
        loadBooks();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BookUploadManager uploadManager = BookUploadManager.getInstance(this);
        uploadManager.addListener(this);
        uploadManager.resumePendingIfNeeded();
        if (miniPlayerController != null) miniPlayerController.onResume();
        loadBooks();
    }

    @Override
    protected void onPause() {
        BookUploadManager.getInstance(this).removeListener(this);
        if (miniPlayerController != null) miniPlayerController.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        isQuickCancelled = true;
        pollHandler.removeCallbacksAndMessages(null);
        dismissQuickProgressDialog();
        super.onDestroy();
    }

    private void initViews() {
        rvBooks = findViewById(R.id.rvBooks);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvBookCount = findViewById(R.id.tvBookCount);
        btnQuickRead = findViewById(R.id.btnQuickRead);
        btnSaveBook = findViewById(R.id.btnSaveBook);
        btnEnglishTutor = findViewById(R.id.btnEnglishTutor);
        bookSearchBar = findViewById(R.id.bookSearchBar);
        miniPlayerController = new MiniPlayerController(this, findViewById(R.id.miniPlayerContainer));
        SearchBarHelper.attach(
                bookSearchBar,
                getString(R.string.search_books_hint),
                query -> {
                    currentBookQuery = query;
                    applyBookFilter();
                }
        );

        rvBooks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BookAdapter(bookList, this);
        rvBooks.setAdapter(adapter);

        btnQuickRead.setOnClickListener(v -> {
            pendingMode = "quick";
            showSourcePickerDialog(getString(R.string.quick_read_title));
        });

        btnSaveBook.setOnClickListener(v -> {
            if (BookUploadManager.getInstance(this).isUploading()) {
                Toast.makeText(this, "A book is already processing below in your library.", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingMode = "book";
            showSourcePickerDialog(getString(R.string.save_book_title));
        });

        btnEnglishTutor.setOnClickListener(v ->
                startActivity(new Intent(MainActivity.this, EnglishTutorActivity.class))
        );
    }

    private void setupPickers() {
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) {
                        processSelectedFile(uri, pendingMode);
                    }
                }
        );
    }

    private void showSourcePickerDialog(String title) {
        String[] options = {"PDF Document", "Photo / Screenshot", "Word / Text File (.docx, .txt)"};
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        filePickerLauncher.launch(new String[]{"application/pdf"});
                    } else if (which == 1) {
                        filePickerLauncher.launch(new String[]{"image/*"});
                    } else {
                        filePickerLauncher.launch(new String[]{
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/msword",
                                "text/plain"
                        });
                    }
                })
                .show();
    }

    private void showQuickProgressDialog() {
        isQuickCancelled = false;
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null);
        TextView tvTitle = dialogView.findViewById(R.id.tvProgressTitle);
        tvJobStage = dialogView.findViewById(R.id.tvJobStage);
        pbJobProgress = dialogView.findViewById(R.id.pbJobProgress);
        tvJobPercent = dialogView.findViewById(R.id.tvJobPercent);
        tvJobEta = dialogView.findViewById(R.id.tvJobEta);

        tvTitle.setText(R.string.quick_read_title);
        tvJobStage.setText("Uploading to server...");
        pbJobProgress.setProgress(5);
        tvJobPercent.setText("5%");
        tvJobEta.setText("");

        quickProgressDialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    isQuickCancelled = true;
                    pollHandler.removeCallbacksAndMessages(null);
                })
                .create();
        quickProgressDialog.show();
    }

    private void dismissQuickProgressDialog() {
        if (quickProgressDialog != null && quickProgressDialog.isShowing()) {
            quickProgressDialog.dismiss();
        }
        quickProgressDialog = null;
    }

    private String formatEta(int seconds) {
        if (seconds <= 0) return "Almost done";
        if (seconds < 60) return "~" + seconds + " sec left";
        int mins = seconds / 60;
        int secs = seconds % 60;
        return "~" + mins + "m " + secs + "s left";
    }

    private void processSelectedFile(Uri uri, final String mode) {
        if ("quick".equals(mode)) {
            showQuickProgressDialog();
        }

        new Thread(() -> {
            try {
                String mimeType = getContentResolver().getType(uri);
                String resolvedName = "Document";
                try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameIdx != -1) {
                            String dn = cursor.getString(nameIdx);
                            if (dn != null && !dn.trim().isEmpty()) {
                                resolvedName = dn;
                            }
                        }
                    }
                } catch (Exception ignored) {}

                if (!resolvedName.contains(".")) {
                    if (mimeType != null && mimeType.contains("pdf")) resolvedName += ".pdf";
                    else if (mimeType != null && mimeType.contains("image")) resolvedName += ".png";
                    else resolvedName += ".txt";
                }

                final String finalFileName = resolvedName;
                final File tempFile = new File(getCacheDir(), finalFileName);
                InputStream is = getContentResolver().openInputStream(uri);
                FileOutputStream fos = new FileOutputStream(tempFile);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = is.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }
                fos.close();
                is.close();

                if ("book".equals(mode)) {
                    runOnUiThread(() -> {
                        BookUploadManager.getInstance(MainActivity.this)
                                .startBookUpload(tempFile, finalFileName, mimeType);
                        loadBooks();
                    });
                    return;
                }

                File uploadQuickFile = tempFile;
                if (mimeType != null && mimeType.startsWith("image/")) {
                    uploadQuickFile = DocumentPreProcessor.optimizeImageIfLarge(tempFile);
                }

                final File quickCleanupFile = uploadQuickFile;
                ApiClient.getInstance(this).startParseJob(uploadQuickFile, mimeType, "quick", new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        quickCleanupFile.delete();
                        runOnUiThread(() -> {
                            dismissQuickProgressDialog();
                            Toast.makeText(MainActivity.this, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        quickCleanupFile.delete();
                        if (!response.isSuccessful()) {
                            runOnUiThread(() -> {
                                dismissQuickProgressDialog();
                                Toast.makeText(MainActivity.this, "Server error: " + response.code(), Toast.LENGTH_LONG).show();
                            });
                            return;
                        }

                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            String jobId = json.getString("job_id");
                            runOnUiThread(() -> pollQuickParseStatus(jobId));
                        } catch (Exception ex) {
                            runOnUiThread(() -> {
                                dismissQuickProgressDialog();
                                Toast.makeText(MainActivity.this, "Upload error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissQuickProgressDialog();
                    Toast.makeText(MainActivity.this, "Failed to read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void pollQuickParseStatus(final String jobId) {
        if (isQuickCancelled) return;

        ApiClient.getInstance(this).getParseStatus(jobId, new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (isQuickCancelled) return;
                pollHandler.postDelayed(() -> pollQuickParseStatus(jobId), 1200);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (isQuickCancelled) return;

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        dismissQuickProgressDialog();
                        Toast.makeText(MainActivity.this, "Processing error: " + response.code(), Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject statusObj = new JSONObject(body);
                    String status = statusObj.optString("status", "processing");
                    int progress = statusObj.optInt("progress", 0);
                    String stage = statusObj.optString("stage", "Processing...");
                    int etaSeconds = statusObj.optInt("eta_seconds", 0);

                    runOnUiThread(() -> {
                        if (pbJobProgress != null) pbJobProgress.setProgress(progress);
                        if (tvJobPercent != null) tvJobPercent.setText(progress + "%");
                        if (tvJobStage != null) tvJobStage.setText(stage);
                        if (tvJobEta != null) tvJobEta.setText(formatEta(etaSeconds));
                    });

                    if ("completed".equals(status)) {
                        JSONObject result = statusObj.getJSONObject("result");
                        handleQuickResult(result);
                    } else if ("error".equals(status)) {
                        String err = statusObj.optString("error", "Parsing failed");
                        runOnUiThread(() -> {
                            dismissQuickProgressDialog();
                            Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                        });
                    } else {
                        pollHandler.postDelayed(() -> pollQuickParseStatus(jobId), 800);
                    }
                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        dismissQuickProgressDialog();
                        Toast.makeText(MainActivity.this, "Parsing error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private void handleQuickResult(JSONObject json) {
        try {
            String title = json.optString("title", json.optString("book_title", "Quick Read"));
            JSONArray chArray = json.optJSONArray("chapters");
            if (chArray == null) chArray = new JSONArray();

            StringBuilder fullText = new StringBuilder();
            for (int i = 0; i < chArray.length(); i++) {
                JSONObject chObj = chArray.getJSONObject(i);
                String c = chObj.optString("content", chObj.optString("text", ""));
                if (!c.isEmpty()) {
                    if (fullText.length() > 0) fullText.append("\n\n");
                    fullText.append(c);
                }
            }
            QuickReadHolder.set(title, fullText.toString());

            runOnUiThread(() -> {
                dismissQuickProgressDialog();
                Intent intent = new Intent(MainActivity.this, AudioPlayerActivity.class);
                intent.putExtra("is_quick_read", true);
                startActivity(intent);
            });
        } catch (Exception ex) {
            runOnUiThread(() -> {
                dismissQuickProgressDialog();
                Toast.makeText(MainActivity.this, "Quick Read error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void loadBooks() {
        allSavedBooks.clear();
        allSavedBooks.addAll(dbHelper.getAllBooks());
        tvBookCount.setText(allSavedBooks.size() + " Books");
        if (bookSearchBar != null) {
            bookSearchBar.setVisibility(allSavedBooks.isEmpty() && !BookUploadManager.getInstance(this).isUploading()
                    ? View.GONE : View.VISIBLE);
        }
        applyBookFilter();
    }

    private void applyBookFilter() {
        bookList.clear();
        String q = currentBookQuery != null ? currentBookQuery.toLowerCase(Locale.ROOT) : "";
        for (Book b : allSavedBooks) {
            String title = b.getTitle() != null ? b.getTitle().toLowerCase(Locale.ROOT) : "";
            if (q.isEmpty() || title.contains(q)) {
                bookList.add(b);
            }
        }

        Book processingCard = BookUploadManager.getInstance(this).getProcessingBookCard();
        if (processingCard != null) {
            String pTitle = processingCard.getTitle() != null ? processingCard.getTitle().toLowerCase(Locale.ROOT) : "";
            if (q.isEmpty() || pTitle.contains(q)) {
                // Place processing book card below existing saved books
                bookList.add(processingCard);
            }
        }

        adapter.notifyDataSetChanged();

        if (bookList.isEmpty() && q.isEmpty()) {
            layoutEmptyState.setVisibility(View.VISIBLE);
            rvBooks.setVisibility(View.GONE);
        } else {
            layoutEmptyState.setVisibility(View.GONE);
            rvBooks.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onUploadUpdated() {
        loadBooks();
    }

    @Override
    public void onUploadCompleted(long bookId, String bookTitle) {
        loadBooks();
        Toast.makeText(this, bookTitle + " saved to library!", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onUploadError(String message) {
        loadBooks();
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    public void onBookClick(Book book) {
        if (book.isProcessing()) {
            Toast.makeText(this, "Book is processing in background. Tap once complete.", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent intent = new Intent(this, BookDetailActivity.class);
        intent.putExtra("book_id", book.getId());
        startActivity(intent);
    }

    @Override
    public void onDeleteClick(Book book) {
        if (book.isProcessing()) {
            new AlertDialog.Builder(this)
                    .setTitle("Cancel Upload")
                    .setMessage("Stop monitoring this book upload?")
                    .setPositiveButton("Stop", (dialog, which) -> {
                        BookUploadManager.getInstance(this).cancelUpload();
                        loadBooks();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_book_title)
                .setMessage(R.string.delete_book_confirm)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    dbHelper.deleteBook(book.getId());
                    loadBooks();
                    Toast.makeText(this, "Book deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
