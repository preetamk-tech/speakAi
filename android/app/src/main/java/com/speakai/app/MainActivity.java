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
import com.speakai.app.models.Chapter;
import com.speakai.app.models.QuickReadHolder;
import com.speakai.app.network.ApiClient;
import com.speakai.app.network.ChapterSyncManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements BookAdapter.OnBookClickListener {

    private RecyclerView rvBooks;
    private BookAdapter adapter;
    private final List<Book> bookList = new ArrayList<>();
    private AppDatabaseHelper dbHelper;

    private LinearLayout layoutEmptyState;
    private TextView tvBookCount;
    private MaterialButton btnQuickRead, btnSaveBook;

    private ActivityResultLauncher<String[]> filePickerLauncher;
    private String pendingMode = "book";
    private boolean isCancelled = false;

    private AlertDialog progressDialog;
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
        loadBooks();
    }

    @Override
    protected void onDestroy() {
        isCancelled = true;
        pollHandler.removeCallbacksAndMessages(null);
        dismissProgressDialog();
        super.onDestroy();
    }

    private void initViews() {
        rvBooks = findViewById(R.id.rvBooks);
        layoutEmptyState = findViewById(R.id.layoutEmptyState);
        tvBookCount = findViewById(R.id.tvBookCount);
        btnQuickRead = findViewById(R.id.btnQuickRead);
        btnSaveBook = findViewById(R.id.btnSaveBook);

        rvBooks.setLayoutManager(new LinearLayoutManager(this));
        adapter = new BookAdapter(bookList, this);
        rvBooks.setAdapter(adapter);

        btnQuickRead.setOnClickListener(v -> {
            pendingMode = "quick";
            showSourcePickerDialog(getString(R.string.quick_read_title));
        });

        btnSaveBook.setOnClickListener(v -> {
            pendingMode = "book";
            showSourcePickerDialog(getString(R.string.save_book_title));
        });
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

    private void showSourcePickerDialog(String dialogTitle) {
        String[] options = {
                getString(R.string.pick_document),
                getString(R.string.pick_image)
        };

        new AlertDialog.Builder(this)
                .setTitle(dialogTitle)
                .setItems(options, (dialog, which) -> {
                    if (which == 0) {
                        filePickerLauncher.launch(new String[]{
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/msword",
                                "text/plain"
                        });
                    } else {
                        filePickerLauncher.launch(new String[]{"image/*"});
                    }
                })
                .show();
    }

    private void showProgressDialog(String mode) {
        dismissProgressDialog();
        isCancelled = false;

        View view = LayoutInflater.from(this).inflate(R.layout.dialog_upload_progress, null);
        TextView tvTitle = view.findViewById(R.id.tvProgressTitle);
        pbJobProgress = view.findViewById(R.id.pbJobProgress);
        tvJobPercent = view.findViewById(R.id.tvJobPercent);
        tvJobStage = view.findViewById(R.id.tvJobStage);
        tvJobEta = view.findViewById(R.id.tvJobEta);

        tvTitle.setText("quick".equals(mode) ? R.string.quick_read_title : R.string.save_book_title);
        tvJobStage.setText("Uploading document to server...");
        tvJobPercent.setText("5%");
        pbJobProgress.setProgress(5);
        tvJobEta.setText("Starting...");

        progressDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .setNegativeButton(R.string.cancel, (d, w) -> {
                    isCancelled = true;
                    pollHandler.removeCallbacksAndMessages(null);
                })
                .create();
        progressDialog.show();
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) {
            progressDialog.dismiss();
        }
        progressDialog = null;
    }

    private String formatEta(int seconds) {
        if (seconds <= 0) return "Almost done";
        if (seconds < 60) return "~" + seconds + " sec left";
        int mins = seconds / 60;
        int secs = seconds % 60;
        return "~" + mins + "m " + secs + "s left";
    }

    private void processSelectedFile(Uri uri, final String mode) {
        showProgressDialog(mode);

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

                ApiClient.getInstance(this).startParseJob(tempFile, mimeType, mode, new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        tempFile.delete();
                        runOnUiThread(() -> {
                            dismissProgressDialog();
                            Toast.makeText(MainActivity.this, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        tempFile.delete();
                        if (!response.isSuccessful()) {
                            runOnUiThread(() -> {
                                dismissProgressDialog();
                                Toast.makeText(MainActivity.this, "Server error: " + response.code(), Toast.LENGTH_LONG).show();
                            });
                            return;
                        }

                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            String jobId = json.getString("job_id");
                            runOnUiThread(() -> pollParseStatus(jobId, mode, finalFileName));
                        } catch (Exception ex) {
                            runOnUiThread(() -> {
                                dismissProgressDialog();
                                Toast.makeText(MainActivity.this, "Upload error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                            });
                        }
                    }
                });

            } catch (Exception e) {
                runOnUiThread(() -> {
                    dismissProgressDialog();
                    Toast.makeText(MainActivity.this, "Failed to read file: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    private void pollParseStatus(final String jobId, final String mode, final String fileName) {
        if (isCancelled) return;

        ApiClient.getInstance(this).getParseStatus(jobId, new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (isCancelled) return;
                pollHandler.postDelayed(() -> pollParseStatus(jobId, mode, fileName), 1200);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (isCancelled) return;

                if (!response.isSuccessful()) {
                    runOnUiThread(() -> {
                        dismissProgressDialog();
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
                        handleCompletedResult(result, mode, fileName);
                    } else if ("error".equals(status)) {
                        String err = statusObj.optString("error", "Parsing failed");
                        runOnUiThread(() -> {
                            dismissProgressDialog();
                            Toast.makeText(MainActivity.this, err, Toast.LENGTH_LONG).show();
                        });
                    } else {
                        pollHandler.postDelayed(() -> pollParseStatus(jobId, mode, fileName), 800);
                    }

                } catch (Exception ex) {
                    runOnUiThread(() -> {
                        dismissProgressDialog();
                        Toast.makeText(MainActivity.this, "Parsing error: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                    });
                }
            }
        });
    }

    private void handleCompletedResult(JSONObject json, String mode, String fileName) {
        try {
            String title = json.optString("title", json.optString("book_title", "Untitled Document"));
            JSONArray chArray = json.optJSONArray("chapters");
            if (chArray == null) {
                chArray = new JSONArray();
            }

            if ("quick".equals(mode)) {
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
                    dismissProgressDialog();
                    Intent intent = new Intent(MainActivity.this, AudioPlayerActivity.class);
                    intent.putExtra("is_quick_read", true);
                    startActivity(intent);
                });
                return;
            }

            final String polishJobId = json.optString("polish_job_id", "");

            List<Chapter> chapters = new ArrayList<>();
            for (int i = 0; i < chArray.length(); i++) {
                JSONObject chObj = chArray.getJSONObject(i);
                Chapter chapter = new Chapter();
                int chIndex = chObj.optInt("index", chObj.optInt("chapter_index", i + 1));
                chapter.setChapterIndex(chIndex);
                chapter.setTitle(chObj.optString("title", "Chapter " + chIndex));
                String content = chObj.optString("content", chObj.optString("text", ""));
                chapter.setContentText(content);
                chapter.setCleanStatus(chObj.optString("clean_status", "ready"));
                chapters.add(chapter);
            }

            Book book = new Book();
            book.setTitle(title);
            book.setFilename(fileName);
            int playableCount = 0;
            for (Chapter ch : chapters) {
                if (ch.getChapterIndex() > 0) playableCount++;
            }
            book.setTotalChapters(playableCount > 0 ? playableCount : chapters.size());
            book.setPolishJobId(polishJobId);

            long bookId = dbHelper.insertBook(book, chapters);

            runOnUiThread(() -> {
                if (!polishJobId.isEmpty()) {
                    ChapterSyncManager.getInstance(MainActivity.this).startSync(bookId, polishJobId);
                }
                dismissProgressDialog();
                loadBooks();
                Intent intent = new Intent(MainActivity.this, BookDetailActivity.class);
                intent.putExtra("book_id", bookId);
                startActivity(intent);
            });
        } catch (Exception ex) {
            runOnUiThread(() -> {
                dismissProgressDialog();
                Toast.makeText(MainActivity.this, "Error saving document: " + ex.getMessage(), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void loadBooks() {
        bookList.clear();
        bookList.addAll(dbHelper.getAllBooks());
        adapter.notifyDataSetChanged();

        tvBookCount.setText(bookList.size() + " Books");
        if (bookList.isEmpty()) {
            layoutEmptyState.setVisibility(View.VISIBLE);
            rvBooks.setVisibility(View.GONE);
        } else {
            layoutEmptyState.setVisibility(View.GONE);
            rvBooks.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onBookClick(Book book) {
        Intent intent = new Intent(this, BookDetailActivity.class);
        intent.putExtra("book_id", book.getId());
        startActivity(intent);
    }

    @Override
    public void onDeleteClick(Book book) {
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
