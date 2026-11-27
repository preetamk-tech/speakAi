package com.speakai.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.speakai.app.adapters.ChapterAdapter;
import com.speakai.app.database.AppDatabaseHelper;
import com.speakai.app.models.Book;
import com.speakai.app.models.Chapter;
import com.speakai.app.network.ChapterSyncManager;

import java.util.ArrayList;
import java.util.List;

public class BookDetailActivity extends AppCompatActivity implements ChapterAdapter.OnChapterClickListener, ChapterSyncManager.SyncListener {

    private long bookId;
    private AppDatabaseHelper dbHelper;
    private Book currentBook;
    private final List<Chapter> chapterList = new ArrayList<>();
    private ChapterAdapter adapter;

    private TextView tvHeaderTitle, tvDetailBookTitle, tvDetailTotalChapters, tvDetailProgressText;
    private ProgressBar pbDetailOverallProgress;
    private MaterialButton btnResumeBook;
    private ImageButton btnBack, btnDeleteBookDetail;
    private RecyclerView rvChapters;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_book_detail);

        bookId = getIntent().getLongExtra("book_id", -1);
        if (bookId == -1) {
            finish();
            return;
        }

        dbHelper = new AppDatabaseHelper(this);

        initViews();
        loadBookData();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ChapterSyncManager.getInstance(this).addListener(this);
        loadBookData();
    }

    @Override
    protected void onPause() {
        ChapterSyncManager.getInstance(this).removeListener(this);
        super.onPause();
    }

    @Override
    public void onSyncUpdated(long updatedBookId) {
        if (updatedBookId == bookId) {
            loadBookData();
        }
    }

    private void initViews() {
        tvHeaderTitle = findViewById(R.id.tvHeaderTitle);
        tvDetailBookTitle = findViewById(R.id.tvDetailBookTitle);
        tvDetailTotalChapters = findViewById(R.id.tvDetailTotalChapters);
        tvDetailProgressText = findViewById(R.id.tvDetailProgressText);
        pbDetailOverallProgress = findViewById(R.id.pbDetailOverallProgress);
        btnResumeBook = findViewById(R.id.btnResumeBook);
        btnBack = findViewById(R.id.btnBack);
        btnDeleteBookDetail = findViewById(R.id.btnDeleteBookDetail);
        rvChapters = findViewById(R.id.rvChapters);

        rvChapters.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChapterAdapter(chapterList, this);
        rvChapters.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnDeleteBookDetail.setOnClickListener(v -> confirmDeleteBook());

        btnResumeBook.setOnClickListener(v -> {
            if (chapterList.isEmpty()) return;

            // Find first incomplete playable chapter (skipping Index entry at chapterIndex == 0)
            Chapter firstPlayable = null;
            Chapter target = null;
            for (Chapter ch : chapterList) {
                if (ch.getChapterIndex() <= 0) continue;
                if (firstPlayable == null) firstPlayable = ch;
                if (ch.getProgressPercent() < 100) {
                    target = ch;
                    break;
                }
            }
            if (target == null) {
                target = firstPlayable != null ? firstPlayable : chapterList.get(0);
            }
            if (target.getChapterIndex() == 0) {
                showIndexDialog(target);
            } else {
                openChapterPlayer(target);
            }
        });
    }

    private void loadBookData() {
        currentBook = dbHelper.getBook(bookId);

        if (currentBook == null) {
            finish();
            return;
        }

        if (currentBook.getPolishJobId() != null && !currentBook.getPolishJobId().isEmpty()) {
            ChapterSyncManager.getInstance(this).startSync(bookId, currentBook.getPolishJobId());
        }

        tvHeaderTitle.setText(currentBook.getTitle());
        tvDetailBookTitle.setText(currentBook.getTitle());
        tvDetailTotalChapters.setText(currentBook.getTotalChapters() + " Chapters");
        tvDetailProgressText.setText(currentBook.getOverallProgress() + "% Completed");
        pbDetailOverallProgress.setProgress(currentBook.getOverallProgress());

        chapterList.clear();
        chapterList.addAll(dbHelper.getChaptersForBook(bookId));
        adapter.notifyDataSetChanged();
    }

    @Override
    public void onChapterClick(Chapter chapter) {
        if (chapter.getChapterIndex() == 0) {
            showIndexDialog(chapter);
        } else {
            openChapterPlayer(chapter);
        }
    }

    private void showIndexDialog(Chapter indexChapter) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_index_viewer, null);
        TextView tvSubtitle = dialogView.findViewById(R.id.tvIndexSubtitle);
        TextView tvContent = dialogView.findViewById(R.id.tvIndexContent);

        tvSubtitle.setText(currentBook != null ? currentBook.getTitle() : "Table of Contents");
        tvContent.setText(indexChapter.getContentText() != null ? indexChapter.getContentText() : "");

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void openChapterPlayer(Chapter chapter) {
        Intent intent = new Intent(this, AudioPlayerActivity.class);
        intent.putExtra("chapter_id", chapter.getId());
        intent.putExtra("book_id", bookId);
        intent.putExtra("book_title", currentBook != null ? currentBook.getTitle() : "");
        intent.putExtra("polish_job_id", currentBook != null ? currentBook.getPolishJobId() : "");
        startActivity(intent);
    }

    private void confirmDeleteBook() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_book_title)
                .setMessage(R.string.delete_book_confirm)
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    dbHelper.deleteBook(bookId);
                    Toast.makeText(this, "Book deleted", Toast.LENGTH_SHORT).show();
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
