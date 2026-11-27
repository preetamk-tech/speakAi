package com.speakai.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

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

public class AudioPlayerActivity extends AppCompatActivity implements AudioPlayerService.StateListener, ChapterSyncManager.SyncListener {

    private static final String[] LANG_CODES = {"hi", "mr", "gu", "bn", "ta", "te", "en"};

    private long chapterId;
    private long bookId;
    private String bookTitle;
    private String polishJobId = "";
    private boolean attachActive = false;
    private boolean waitingForChapterClean = false;
    private boolean isSyncingControls = false;

    private AppDatabaseHelper dbHelper;
    private Chapter currentChapter;

    private AudioPlayerService audioService;
    private boolean isBound = false;

    private TextView tvPlayerChapterTitle, tvPlayerBookTitle, tvCurrentTextChunk, tvChunkProgress;
    private ProgressBar pbPlaybackProgress, pbBuffering;
    private Spinner spLanguage;
    private MaterialButtonToggleGroup tgGender, tgSpeed;
    private SwitchCompat swBackgroundPlay;
    private MaterialButton btnDownloadOffline;
    private FloatingActionButton btnPlayPause;
    private ImageButton btnPrevChunk, btnNextChunk, btnBackPlayer;

    private String selectedLanguage = "hi";
    private String selectedGender = "female";
    private float currentSpeed = 1.0f;
    private boolean isQuickRead = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            AudioPlayerService.LocalBinder binder = (AudioPlayerService.LocalBinder) service;
            audioService = binder.getService();
            isBound = true;
            onServiceReady();
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

        dbHelper = new AppDatabaseHelper(this);
        initViews();
        setupLanguageSpinner();
        setupToggleGroups();
        setupBackgroundSwitch();
        setupActionButtons();

        if (!loadChapterFromIntent(getIntent())) {
            finish();
            return;
        }

        Intent serviceIntent = new Intent(this, AudioPlayerService.class);
        startService(serviceIntent);
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (loadChapterFromIntent(intent) && audioService != null) {
            onServiceReady();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        AudioPlayerService.addStateListener(this);
        if (audioService != null) {
            syncUiFromService();
        }
    }

    @Override
    protected void onPause() {
        AudioPlayerService.removeStateListener(this);
        super.onPause();
    }

    private boolean loadChapterFromIntent(Intent intent) {
        attachActive = intent.getBooleanExtra("attach_active", false);
        isQuickRead = intent.getBooleanExtra("is_quick_read", false);
        chapterId = intent.getLongExtra("chapter_id", -1);
        bookId = intent.getLongExtra("book_id", -1);
        bookTitle = intent.getStringExtra("book_title");
        polishJobId = intent.getStringExtra("polish_job_id");
        if (polishJobId == null) polishJobId = "";

        if (isQuickRead) {
            currentChapter = new Chapter();
            currentChapter.setTitle(QuickReadHolder.getTitle());
            currentChapter.setContentText(QuickReadHolder.getContent());
            currentChapter.setCleanStatus("ready");
            bookTitle = "Quick Read (Not Saved)";
        } else {
            if (chapterId == -1) {
                AudioPlayerService active = AudioPlayerService.getActiveInstance();
                if (attachActive && active != null && active.hasActiveSession()) {
                    chapterId = active.getChapterId();
                    bookId = active.getBookId();
                    bookTitle = active.getBookTitle();
                    isQuickRead = active.isQuickRead();
                } else {
                    return false;
                }
            }
            if (!isQuickRead) {
                currentChapter = dbHelper.getChapter(chapterId);
                if (polishJobId.isEmpty() && bookId != -1) {
                    Book b = dbHelper.getBook(bookId);
                    if (b != null) polishJobId = b.getPolishJobId();
                }
            }
        }

        if (currentChapter == null) {
            return false;
        }

        tvPlayerChapterTitle.setText(currentChapter.getTitle());
        tvPlayerBookTitle.setText(bookTitle != null ? bookTitle : "speakAi Library");

        if (isQuickRead) {
            btnDownloadOffline.setVisibility(View.GONE);
        } else if (currentChapter.isDownloaded()
                && currentChapter.getLocalAudioPath() != null
                && new File(currentChapter.getLocalAudioPath()).exists()) {
            btnDownloadOffline.setVisibility(View.VISIBLE);
            btnDownloadOffline.setText(R.string.downloaded);
            btnDownloadOffline.setEnabled(false);
        } else {
            btnDownloadOffline.setVisibility(View.VISIBLE);
            btnDownloadOffline.setText(R.string.download_offline);
            btnDownloadOffline.setEnabled(true);
        }
        return true;
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
        swBackgroundPlay = findViewById(R.id.swBackgroundPlay);
        btnDownloadOffline = findViewById(R.id.btnDownloadOffline);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrevChunk = findViewById(R.id.btnPrevChunk);
        btnNextChunk = findViewById(R.id.btnNextChunk);
        btnBackPlayer = findViewById(R.id.btnBackPlayer);

        btnBackPlayer.setOnClickListener(v -> finish());
    }

    private void setupLanguageSpinner() {
        String[] languages = {
                "Hindi (हिन्दी)", "Marathi (मराठी)", "Gujarati (ગુજરાતી)",
                "Bengali (বাংলা)", "Tamil (தமிழ்)", "Telugu (తెలుగు)", "English (Indian)"
        };

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, languages);
        spLanguage.setAdapter(adapter);

        spLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSyncingControls) return;
                String newCode = LANG_CODES[position];
                if (!newCode.equals(selectedLanguage)) {
                    selectedLanguage = newCode;
                    if (audioService != null && !audioService.isOfflineMode()) {
                        audioService.changeVoiceOrLanguage(selectedLanguage, selectedGender);
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
            if (isSyncingControls || !isChecked) return;
            selectedGender = (checkedId == R.id.btnMale) ? "male" : "female";
            if (audioService != null && !audioService.isOfflineMode()) {
                audioService.changeVoiceOrLanguage(selectedLanguage, selectedGender);
            }
        });

        tgSpeed.check(R.id.btnSpeed10);
        tgSpeed.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isSyncingControls || !isChecked) return;
            if (checkedId == R.id.btnSpeed08) currentSpeed = 0.8f;
            else if (checkedId == R.id.btnSpeed10) currentSpeed = 1.0f;
            else if (checkedId == R.id.btnSpeed12) currentSpeed = 1.2f;
            else if (checkedId == R.id.btnSpeed15) currentSpeed = 1.5f;

            if (audioService != null) {
                audioService.setSpeed(currentSpeed);
            }
        });
    }

    private void setupBackgroundSwitch() {
        swBackgroundPlay.setChecked(SpeakAiApp.isBackgroundPlayEnabled(this));
        swBackgroundPlay.setOnCheckedChangeListener((buttonView, isChecked) ->
                SpeakAiApp.setBackgroundPlayEnabled(AudioPlayerActivity.this, isChecked)
        );
    }

    private void setupActionButtons() {
        btnPlayPause.setOnClickListener(v -> {
            if (audioService != null) audioService.togglePlayPause();
        });

        btnPrevChunk.setOnClickListener(v -> {
            if (audioService != null) audioService.prevChunk();
        });

        btnNextChunk.setOnClickListener(v -> {
            if (audioService != null) audioService.nextChunk();
        });

        btnDownloadOffline.setOnClickListener(v -> startOfflineDownload());
    }

    private void onServiceReady() {
        if (audioService == null) return;

        if (attachActive || audioService.isSameChapterLoaded(chapterId, isQuickRead)) {
            syncUiFromService();
            return;
        }

        startPlaybackFlow();
    }

    private void startPlaybackFlow() {
        if (!isQuickRead && currentChapter.isDownloaded()
                && currentChapter.getLocalAudioPath() != null
                && new File(currentChapter.getLocalAudioPath()).exists()) {
            audioService.startOfflineChapter(
                    bookId,
                    chapterId,
                    bookTitle,
                    currentChapter.getTitle(),
                    currentChapter.getContentText(),
                    currentChapter.getLocalAudioPath(),
                    currentSpeed
            );
        } else if (!isQuickRead && !"ready".equals(currentChapter.getCleanStatus())) {
            waitForChapterReady();
        } else {
            startOnlineChapterPlayback();
        }
    }

    private void startOnlineChapterPlayback() {
        if (audioService == null) return;
        audioService.startOnlineChapter(
                bookId,
                chapterId,
                bookTitle,
                currentChapter.getTitle(),
                currentChapter.getContentText(),
                isQuickRead,
                selectedLanguage,
                selectedGender,
                currentSpeed
        );
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
                            startOnlineChapterPlayback();
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
                            startOnlineChapterPlayback();
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
                startOnlineChapterPlayback();
            } else if ("processing".equals(refreshed.getCleanStatus())) {
                tvChunkProgress.setText("Processing chapter...");
            }
        }
    }

    private void syncUiFromService() {
        if (audioService == null || !audioService.hasActiveSession()) {
            btnPlayPause.setImageResource(R.drawable.ic_play);
            pbBuffering.setVisibility(View.GONE);
            return;
        }

        tvPlayerChapterTitle.setText(audioService.getChapterTitle());
        tvPlayerBookTitle.setText(audioService.getBookTitle());
        String display = audioService.getCurrentDisplayText();
        if (display != null && !display.isEmpty()) {
            tvCurrentTextChunk.setText(display);
        }

        if (audioService.isOfflineMode()) {
            tvChunkProgress.setText("Offline Playback");
            pbPlaybackProgress.setProgress(100);
        } else {
            int cur = audioService.getCurrentChunkIndex() + 1;
            int tot = Math.max(1, audioService.getTotalChunks());
            tvChunkProgress.setText("Chunk " + cur + " of " + tot);
            pbPlaybackProgress.setProgress(audioService.getProgressPercent());
        }

        pbBuffering.setVisibility(audioService.isBuffering() ? View.VISIBLE : View.GONE);
        btnPlayPause.setImageResource(audioService.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);

        isSyncingControls = true;
        selectedLanguage = audioService.getSelectedLanguage();
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(selectedLanguage)) {
                spLanguage.setSelection(i);
                break;
            }
        }
        selectedGender = audioService.getSelectedGender();
        tgGender.check("male".equals(selectedGender) ? R.id.btnMale : R.id.btnFemale);

        currentSpeed = audioService.getCurrentSpeed();
        if (currentSpeed <= 0.85f) tgSpeed.check(R.id.btnSpeed08);
        else if (currentSpeed >= 1.4f) tgSpeed.check(R.id.btnSpeed15);
        else if (currentSpeed >= 1.15f) tgSpeed.check(R.id.btnSpeed12);
        else tgSpeed.check(R.id.btnSpeed10);
        isSyncingControls = false;
    }

    @Override
    public void onPlaybackStateChanged() {
        runOnUiThread(this::syncUiFromService);
    }

    @Override
    public void onPlaybackError(String error) {
        runOnUiThread(() -> {
            syncUiFromService();
            Toast.makeText(AudioPlayerActivity.this, error, Toast.LENGTH_SHORT).show();
        });
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
    protected void onDestroy() {
        AudioPlayerService.removeStateListener(this);
        ChapterSyncManager.getInstance(this).removeListener(this);
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
        super.onDestroy();
    }
}
