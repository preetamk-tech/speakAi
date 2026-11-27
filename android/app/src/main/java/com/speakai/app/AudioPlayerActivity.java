package com.speakai.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.speakai.app.database.AppDatabaseHelper;
import com.speakai.app.models.Book;
import com.speakai.app.models.Chapter;
import com.speakai.app.models.QuickReadHolder;
import com.speakai.app.network.ApiClient;
import com.speakai.app.network.ChapterSyncManager;
import com.speakai.app.services.AudioPlayerService;

import org.json.JSONObject;

import java.io.File;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class AudioPlayerActivity extends AppCompatActivity implements AudioPlayerService.PlaybackListener, ChapterSyncManager.SyncListener {

    private long chapterId;
    private long bookId;
    private String bookTitle;
    private String polishJobId = "";
    private boolean waitingForChapterClean = false;

    private AppDatabaseHelper dbHelper;
    private Chapter currentChapter;

    private AudioPlayerService audioService;
    private boolean isBound = false;

    private TextView tvPlayerChapterTitle, tvPlayerBookTitle, tvCurrentTextChunk, tvChunkProgress;
    private ProgressBar pbPlaybackProgress, pbBuffering;
    private Spinner spLanguage;
    private MaterialButtonToggleGroup tgGender, tgSpeed;
    private MaterialButton btnDownloadOffline;
    private FloatingActionButton btnPlayPause;
    private ImageButton btnPrevChunk, btnNextChunk, btnBackPlayer;

    private String currentSessionId;
    private int currentChunkIndex = 0;
    private int totalChunks = 1;
    private boolean isPlaying = false;
    private boolean isOfflineMode = false;
    private boolean isQuickRead = false;

    private String selectedLanguage = "hi";
    private String selectedGender = "female";
    private float currentSpeed = 1.0f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            audioService = binder.getService();
            audioService.setPlaybackListener(AudioPlayerActivity.this);
            isBound = true;
            startPlaybackFlow();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            audioService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_audio_player);

        isQuickRead = getIntent().getBooleanExtra("is_quick_read", false);
        chapterId = getIntent().getLongExtra("chapter_id", -1);
        bookId = getIntent().getLongExtra("book_id", -1);
        bookTitle = getIntent().getStringExtra("book_title");
        polishJobId = getIntent().getStringExtra("polish_job_id");
        if (polishJobId == null) polishJobId = "";

        if (isQuickRead) {
            currentChapter = new Chapter();
            currentChapter.setTitle(QuickReadHolder.getTitle());
            currentChapter.setContentText(QuickReadHolder.getContent());
            currentChapter.setCleanStatus("ready");
            bookTitle = "Quick Read (Not Saved)";
        } else {
            if (chapterId == -1) {
                finish();
                return;
            }
            dbHelper = new AppDatabaseHelper(this);
            currentChapter = dbHelper.getChapter(chapterId);
            if (polishJobId.isEmpty() && bookId != -1) {
                Book b = dbHelper.getBook(bookId);
                if (b != null) polishJobId = b.getPolishJobId();
            }
        }

        if (currentChapter == null) {
            finish();
            return;
        }

        initViews();
        setupLanguageSpinner();
        setupToggleGroups();
        setupActionButtons();

        Intent serviceIntent = new Intent(this, AudioPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initViews() {
        tvPlayerChapterTitle = findViewById(R.id.tvPlayerChapterTitle);
        tvPlayerBookTitle = findViewById(R.id.tvPlayerBookTitle);
        tvCurrentTextChunk = findViewById(R.id.tvCurrentTextChunk);
        tvChunkProgress = findViewById(R.id.tvChunkProgress);
        pbPlaybackProgress = findViewById(R.id.pbPlaybackProgress);
        pbBuffering = findViewById(R.id.pbBuffering);
        spLanguage = findViewById(R.id.spLanguage);
        tgGender = findViewById(R.id.tgGender);
        tgSpeed = findViewById(R.id.tgSpeed);
        btnDownloadOffline = findViewById(R.id.btnDownloadOffline);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrevChunk = findViewById(R.id.btnPrevChunk);
        btnNextChunk = findViewById(R.id.btnNextChunk);
        btnBackPlayer = findViewById(R.id.btnBackPlayer);

        tvPlayerChapterTitle.setText(currentChapter.getTitle());
        tvPlayerBookTitle.setText(bookTitle != null ? bookTitle : "speakAi Library");

        if (isQuickRead) {
            btnDownloadOffline.setVisibility(View.GONE);
        } else if (currentChapter.isDownloaded()
                && currentChapter.getLocalAudioPath() != null
                && new File(currentChapter.getLocalAudioPath()).exists()) {
            btnDownloadOffline.setText(R.string.downloaded);
            btnDownloadOffline.setEnabled(false);
        }

        btnBackPlayer.setOnClickListener(v -> finish());
    }

    private void setupLanguageSpinner() {
        String[] languages = {"Hindi (हिन्दी)", "Marathi (मराठी)", "Gujarati (ગુજરાતી)", "Bengali (বাংলা)", "Tamil (தமிழ்)", "Telugu (తెలుగు)", "English (Indian)"};
        String[] langCodes = {"hi", "mr", "gu", "bn", "ta", "te", "en"};

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, languages);
        spLanguage.setAdapter(adapter);

        spLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newCode = langCodes[position];
                if (!newCode.equals(selectedLanguage)) {
                    selectedLanguage = newCode;
                    if (!isOfflineMode) {
                        restartSession();
                    }
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupToggleGroups() {
        tgGender.check(R.id.btnFemale);
        tgGender.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnMale) {
                    selectedGender = "male";
                } else {
                    selectedGender = "female";
                }
                if (!isOfflineMode) {
                    restartSession();
                }
            }
        });

        tgSpeed.check(R.id.btnSpeed10);
        tgSpeed.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                if (checkedId == R.id.btnSpeed08) currentSpeed = 0.8f;
                else if (checkedId == R.id.btnSpeed10) currentSpeed = 1.0f;
                else if (checkedId == R.id.btnSpeed12) currentSpeed = 1.2f;
                else if (checkedId == R.id.btnSpeed15) currentSpeed = 1.5f;

                if (audioService != null) {
                    audioService.setSpeed(currentSpeed);
                }
            }
        });
    }

    private void setupActionButtons() {
        btnPlayPause.setOnClickListener(v -> togglePlayPause());

        btnPrevChunk.setOnClickListener(v -> {
            if (currentChunkIndex > 0) {
                currentChunkIndex--;
                fetchAndPlayChunk(currentChunkIndex);
            }
        });

        btnNextChunk.setOnClickListener(v -> {
            if (currentChunkIndex < totalChunks - 1) {
                currentChunkIndex++;
                fetchAndPlayChunk(currentChunkIndex);
            }
        });

        btnDownloadOffline.setOnClickListener(v -> startOfflineDownload());
    }

    private void startPlaybackFlow() {
        if (!isQuickRead && currentChapter.isDownloaded()
                && currentChapter.getLocalAudioPath() != null
                && new File(currentChapter.getLocalAudioPath()).exists()) {
            isOfflineMode = true;
            playLocalAudio(currentChapter.getLocalAudioPath());
        } else if (!isQuickRead && !"ready".equals(currentChapter.getCleanStatus())) {
            isOfflineMode = false;
            waitForChapterReady();
        } else {
            isOfflineMode = false;
            startStreamingSession();
        }
    }

    private void waitForChapterReady() {
        waitingForChapterClean = true;
        pbBuffering.setVisibility(View.VISIBLE);
        tvChunkProgress.setText("Processing chapter...");
        tvCurrentTextChunk.setText("Processing chapter sentences for clear narration. Please wait...");

        ChapterSyncManager.getInstance(this).addListener(this);

        if (polishJobId != null && !polishJobId.isEmpty()) {
            ChapterSyncManager.getInstance(this).startSync(bookId, polishJobId);
            ApiClient.getInstance(this).prioritizeChapter(polishJobId, currentChapter.getChapterIndex(), new Callback() {
                @Override
                public void onFailure(Call call, java.io.IOException e) {
                    runOnUiThread(() -> polishSingleChapterOnDemand());
                }

                @Override
                public void onResponse(Call call, Response response) {
                    if (!response.isSuccessful()) {
                        runOnUiThread(() -> polishSingleChapterOnDemand());
                    }
                }
            });
        } else {
            polishSingleChapterOnDemand();
        }
    }

    private void polishSingleChapterOnDemand() {
        if (!waitingForChapterClean) return;
        ApiClient.getInstance(this).polishSingleChapter(
                currentChapter.getContentText(),
                bookTitle,
                currentChapter.getTitle(),
                new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        runOnUiThread(() -> {
                            waitingForChapterClean = false;
                            startStreamingSession();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (response.isSuccessful()) {
                            try {
                                String body = response.body().string();
                                JSONObject json = new JSONObject(body);
                                String cleaned = json.optString("content", currentChapter.getContentText());
                                dbHelper.updateChapterCleanedTextById(chapterId, cleaned, "ready");
                                currentChapter.setContentText(cleaned);
                                currentChapter.setCleanStatus("ready");
                            } catch (Exception ignored) {}
                        }
                        runOnUiThread(() -> {
                            waitingForChapterClean = false;
                            startStreamingSession();
                        });
                    }
                }
        );
    }

    @Override
    public void onSyncUpdated(long updatedBookId) {
        if (!waitingForChapterClean || updatedBookId != bookId) return;
        Chapter refreshed = dbHelper.getChapter(chapterId);
        if (refreshed != null) {
            currentChapter = refreshed;
            if ("ready".equals(refreshed.getCleanStatus())) {
                waitingForChapterClean = false;
                ChapterSyncManager.getInstance(this).removeListener(this);
                startStreamingSession();
            } else if ("processing".equals(refreshed.getCleanStatus())) {
                tvChunkProgress.setText("Processing chapter...");
            }
        }
    }

    private void playLocalAudio(String filePath) {
        tvCurrentTextChunk.setText(currentChapter.getContentText());
        tvChunkProgress.setText("Offline Playback");
        pbPlaybackProgress.setProgress(100);

        if (audioService != null) {
            audioService.playAudio(filePath, currentChapter.getTitle(), currentSpeed);
        }
    }

    private void startStreamingSession() {
        pbBuffering.setVisibility(View.VISIBLE);
        tvCurrentTextChunk.setText("Synthesizing narration voice...");

        ApiClient.getInstance(this).startSession(
                currentChapter.getContentText(),
                selectedLanguage,
                selectedGender,
                "auto",
                new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        runOnUiThread(() -> {
                            pbBuffering.setVisibility(View.GONE);
                            Toast.makeText(AudioPlayerActivity.this, "Connection failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (!response.isSuccessful()) {
                            runOnUiThread(() -> {
                                pbBuffering.setVisibility(View.GONE);
                                Toast.makeText(AudioPlayerActivity.this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }

                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            currentSessionId = json.getString("session_id");
                            totalChunks = json.getInt("total_chunks");
                            currentChunkIndex = 0;

                            JSONObject chunk0 = json.getJSONObject("chunk_0");
                            String audioUrl = chunk0.getString("audio_url");
                            String text = chunk0.optString("translated_text", chunk0.optString("original_text", ""));

                            runOnUiThread(() -> {
                                pbBuffering.setVisibility(View.GONE);
                                playChunkAudio(audioUrl, text);
                            });

                        } catch (Exception e) {
                            runOnUiThread(() -> {
                                pbBuffering.setVisibility(View.GONE);
                                Toast.makeText(AudioPlayerActivity.this, "Session error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                }
        );
    }

    private void restartSession() {
        if (audioService != null && audioService.isPlaying()) {
            audioService.pause();
        }
        startStreamingSession();
    }

    private void fetchAndPlayChunk(int index) {
        if (currentSessionId == null) return;

        pbBuffering.setVisibility(View.VISIBLE);
        ApiClient.getInstance(this).getChunk(currentSessionId, index, new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                runOnUiThread(() -> {
                    pbBuffering.setVisibility(View.GONE);
                    Toast.makeText(AudioPlayerActivity.this, "Network error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    runOnUiThread(() -> pbBuffering.setVisibility(View.GONE));
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject chunk = new JSONObject(body);
                    String status = chunk.getString("status");

                    if ("ready".equals(status)) {
                        String audioUrl = chunk.getString("audio_url");
                        String text = chunk.optString("translated_text", chunk.optString("original_text", ""));
                        runOnUiThread(() -> {
                            pbBuffering.setVisibility(View.GONE);
                            playChunkAudio(audioUrl, text);
                        });
                    } else {
                        // Chunk still processing on server, poll after 600ms
                        mainHandler.postDelayed(() -> fetchAndPlayChunk(index), 600);
                    }

                } catch (Exception e) {
                    runOnUiThread(() -> pbBuffering.setVisibility(View.GONE));
                }
            }
        });
    }

    private void playChunkAudio(String audioUrl, String text) {
        tvCurrentTextChunk.setText(text);
        int displayIndex = currentChunkIndex + 1;
        tvChunkProgress.setText("Chunk " + displayIndex + " of " + totalChunks);

        int currentProgress = (currentChunkIndex * 100) / totalChunks;
        pbPlaybackProgress.setProgress(currentProgress);

        String fullUrl = audioUrl.startsWith("http") ? audioUrl : (ApiClient.getInstance(this).getBaseUrl() + audioUrl);
        if (audioService != null) {
            audioService.playAudio(fullUrl, currentChapter.getTitle(), currentSpeed);
        }
    }

    private void togglePlayPause() {
        if (audioService == null) return;

        if (audioService.isPlaying()) {
            audioService.pause();
        } else {
            audioService.resume();
        }
    }

    private void startOfflineDownload() {
        btnDownloadOffline.setEnabled(false);
        btnDownloadOffline.setText("Downloading...");

        ApiClient.getInstance(this).synthesizeFullChapter(
                currentChapter.getContentText(),
                selectedLanguage,
                selectedGender,
                new Callback() {
                    @Override
                    public void onFailure(Call call, java.io.IOException e) {
                        runOnUiThread(() -> {
                            btnDownloadOffline.setEnabled(true);
                            btnDownloadOffline.setText(R.string.download_offline);
                            Toast.makeText(AudioPlayerActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (!response.isSuccessful()) {
                            runOnUiThread(() -> {
                                btnDownloadOffline.setEnabled(true);
                                btnDownloadOffline.setText(R.string.download_offline);
                                Toast.makeText(AudioPlayerActivity.this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }

                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            String audioUrl = json.getString("audio_url");

                            File audioDir = new File(getFilesDir(), "audio/book_" + bookId);
                            audioDir.mkdirs();
                            File destFile = new File(audioDir, "chapter_" + chapterId + ".mp3");

                            ApiClient.getInstance(AudioPlayerActivity.this).downloadFile(audioUrl, destFile, new ApiClient.DownloadListener() {
                                @Override
                                public void onProgress(int progress) {}

                                @Override
                                public void onSuccess(File downloadedFile) {
                                    runOnUiThread(() -> {
                                        dbHelper.setChapterDownloaded(chapterId, true, downloadedFile.getAbsolutePath());
                                        btnDownloadOffline.setText(R.string.downloaded);
                                        btnDownloadOffline.setEnabled(false);
                                        Toast.makeText(AudioPlayerActivity.this, "Saved for offline listening", Toast.LENGTH_SHORT).show();
                                    });
                                }

                                @Override
                                public void onError(String message) {
                                    runOnUiThread(() -> {
                                        btnDownloadOffline.setEnabled(true);
                                        btnDownloadOffline.setText(R.string.download_offline);
                                        Toast.makeText(AudioPlayerActivity.this, "Download error: " + message, Toast.LENGTH_SHORT).show();
                                    });
                                }
                            });

                        } catch (Exception ex) {
                            runOnUiThread(() -> {
                                btnDownloadOffline.setEnabled(true);
                                btnDownloadOffline.setText(R.string.download_offline);
                            });
                        }
                    }
                }
        );
    }

    @Override
    public void onPlaybackStarted() {
        runOnUiThread(() -> {
            isPlaying = true;
            btnPlayPause.setImageResource(R.drawable.ic_pause);
        });
    }

    @Override
    public void onPlaybackPaused() {
        runOnUiThread(() -> {
            isPlaying = false;
            btnPlayPause.setImageResource(R.drawable.ic_play);
        });
    }

    @Override
    public void onPlaybackCompleted() {
        runOnUiThread(() -> {
            int completedChunks = currentChunkIndex + 1;
            int progress = (completedChunks * 100) / totalChunks;
            pbPlaybackProgress.setProgress(progress);
            if (!isQuickRead && dbHelper != null) {
                dbHelper.updateChapterProgress(chapterId, progress, currentChunkIndex);
            }

            if (!isOfflineMode && currentChunkIndex + 1 < totalChunks) {
                currentChunkIndex++;
                fetchAndPlayChunk(currentChunkIndex);
            } else {
                isPlaying = false;
                btnPlayPause.setImageResource(R.drawable.ic_play);
                if (!isQuickRead && dbHelper != null) {
                    dbHelper.updateChapterProgress(chapterId, 100, currentChunkIndex);
                }
                pbPlaybackProgress.setProgress(100);
            }
        });
    }

    @Override
    public void onPlaybackError(String error) {
        runOnUiThread(() -> {
            isPlaying = false;
            btnPlayPause.setImageResource(R.drawable.ic_play);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        });
    }

    @Override
    protected void onDestroy() {
        ChapterSyncManager.getInstance(this).removeListener(this);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
