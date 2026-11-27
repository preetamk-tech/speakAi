package com.speakai.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.speakai.app.AudioPlayerActivity;
import com.speakai.app.R;
import com.speakai.app.database.AppDatabaseHelper;
import com.speakai.app.network.ApiClient;

import org.json.JSONObject;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class AudioPlayerService extends Service {

    public static final String CHANNEL_ID = "speakai_playback_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_TOGGLE_PLAY = "com.speakai.action.TOGGLE_PLAY";
    public static final String ACTION_PAUSE = "com.speakai.action.PAUSE";
    public static final String ACTION_STOP = "com.speakai.action.STOP";

    public interface StateListener {
        void onPlaybackStateChanged();
        void onPlaybackError(String error);
    }

    private static volatile AudioPlayerService activeInstance;
    private static final Set<StateListener> stateListeners = new HashSet<>();

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AppDatabaseHelper dbHelper;
    private MediaPlayer mediaPlayer;

    private boolean isPrepared = false;
    private boolean hasActiveSession = false;
    private boolean isPlaying = false;
    private boolean isBuffering = false;
    private boolean isOfflineMode = false;
    private boolean isQuickRead = false;

    private long bookId = -1;
    private long chapterId = -1;
    private String bookTitle = "";
    private String chapterTitle = "Playing Story";
    private String fullChapterText = "";
    private String selectedLanguage = "hi";
    private String selectedGender = "female";
    private float currentSpeed = 1.0f;

    private String currentSessionId = null;
    private int currentChunkIndex = 0;
    private int totalChunks = 1;
    private String currentDisplayText = "";

    public class LocalBinder extends Binder {
        public AudioPlayerService getService() {
            return AudioPlayerService.this;
        }
    }

    public static AudioPlayerService getActiveInstance() {
        return activeInstance;
    }

    public static void addStateListener(StateListener listener) {
        synchronized (stateListeners) {
            if (listener != null) stateListeners.add(listener);
        }
    }

    public static void removeStateListener(StateListener listener) {
        synchronized (stateListeners) {
            stateListeners.remove(listener);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
        dbHelper = new AppDatabaseHelper(getApplicationContext());
        createNotificationChannel();
        initMediaPlayer();
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(buildAudioAttributes());

        mediaPlayer.setOnCompletionListener(mp -> handleTrackCompleted());
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            isPlaying = false;
            isBuffering = false;
            notifyError("Playback error: " + what);
            notifyStateChanged();
            return true;
        });
    }

    private AudioAttributes buildAudioAttributes() {
        return new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build();
    }

    public void startOfflineChapter(
            long bookId,
            long chapterId,
            String bookTitle,
            String chapterTitle,
            String fullText,
            String localFilePath,
            float speed
    ) {
        this.bookId = bookId;
        this.chapterId = chapterId;
        this.bookTitle = bookTitle != null ? bookTitle : "speakAi Library";
        this.chapterTitle = chapterTitle != null ? chapterTitle : "Chapter";
        this.fullChapterText = fullText != null ? fullText : "";
        this.isQuickRead = false;
        this.isOfflineMode = true;
        this.currentSessionId = null;
        this.currentChunkIndex = 0;
        this.totalChunks = 1;
        this.currentSpeed = speed;
        this.currentDisplayText = this.fullChapterText;
        this.hasActiveSession = true;
        this.isBuffering = true;

        notifyStateChanged();
        playAudioSource(localFilePath);
    }

    public void startOnlineChapter(
            long bookId,
            long chapterId,
            String bookTitle,
            String chapterTitle,
            String fullText,
            boolean isQuickRead,
            String language,
            String gender,
            float speed
    ) {
        this.bookId = bookId;
        this.chapterId = chapterId;
        this.bookTitle = bookTitle != null ? bookTitle : (isQuickRead ? "Quick Read" : "speakAi Library");
        this.chapterTitle = chapterTitle != null ? chapterTitle : "Chapter";
        this.fullChapterText = fullText != null ? fullText : "";
        this.isQuickRead = isQuickRead;
        this.isOfflineMode = false;
        this.selectedLanguage = language != null ? language : "hi";
        this.selectedGender = gender != null ? gender : "female";
        this.currentSpeed = speed;
        this.currentSessionId = null;
        this.currentChunkIndex = 0;
        this.totalChunks = 1;
        this.currentDisplayText = "Synthesizing narration voice...";
        this.hasActiveSession = true;

        startForeground(NOTIFICATION_ID, buildNotification(this.chapterTitle, true));
        startStreamingSessionInternal();
    }

    public void changeVoiceOrLanguage(String language, String gender) {
        this.selectedLanguage = language != null ? language : this.selectedLanguage;
        this.selectedGender = gender != null ? gender : this.selectedGender;
        if (!hasActiveSession || isOfflineMode) return;

        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        isPlaying = false;
        startStreamingSessionInternal();
    }

    private void startStreamingSessionInternal() {
        mainHandler.removeCallbacksAndMessages(null);
        isBuffering = true;
        currentDisplayText = "Synthesizing narration voice...";
        notifyStateChanged();

        final long targetChapterId = this.chapterId;
        ApiClient.getInstance(this).startSession(
                fullChapterText,
                selectedLanguage,
                selectedGender,
                "auto",
                new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        if (!hasActiveSession || targetChapterId != AudioPlayerService.this.chapterId) return;
                        isBuffering = false;
                        notifyError("Connection failed: " + e.getMessage());
                        notifyStateChanged();
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (!hasActiveSession || targetChapterId != AudioPlayerService.this.chapterId) return;
                        if (!response.isSuccessful()) {
                            isBuffering = false;
                            notifyError("Server error: " + response.code());
                            notifyStateChanged();
                            return;
                        }
                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            currentSessionId = json.getString("session_id");
                            totalChunks = Math.max(1, json.getInt("total_chunks"));
                            currentChunkIndex = 0;

                            JSONObject chunk0 = json.getJSONObject("chunk_0");
                            String audioUrl = chunk0.getString("audio_url");
                            String text = chunk0.optString("translated_text", chunk0.optString("original_text", ""));

                            currentDisplayText = text;
                            final String fullUrl = resolveAudioUrl(audioUrl);
                            final String sid = currentSessionId;
                            mainHandler.post(() -> {
                                if (hasActiveSession && sid != null && sid.equals(currentSessionId)) {
                                    playAudioSource(fullUrl);
                                }
                            });
                        } catch (Exception e) {
                            isBuffering = false;
                            notifyError("Session error: " + e.getMessage());
                            notifyStateChanged();
                        }
                    }
                }
        );
    }

    public void fetchAndPlayChunk(final int index) {
        if (!hasActiveSession || currentSessionId == null || isOfflineMode) return;
        if (index < 0 || index >= totalChunks) return;

        mainHandler.removeCallbacksAndMessages(null);
        currentChunkIndex = index;
        isBuffering = true;
        notifyStateChanged();

        final String expectedSessionId = currentSessionId;
        ApiClient.getInstance(this).getChunk(expectedSessionId, index, new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                if (!hasActiveSession || !expectedSessionId.equals(currentSessionId)) return;
                isBuffering = false;
                notifyError("Network error: " + e.getMessage());
                notifyStateChanged();
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!hasActiveSession || !expectedSessionId.equals(currentSessionId)) return;
                if (!response.isSuccessful()) {
                    isBuffering = false;
                    notifyStateChanged();
                    return;
                }
                try {
                    String body = response.body().string();
                    JSONObject chunk = new JSONObject(body);
                    String status = chunk.getString("status");

                    if ("ready".equals(status)) {
                        String audioUrl = chunk.getString("audio_url");
                        String text = chunk.optString("translated_text", chunk.optString("original_text", ""));
                        currentDisplayText = text;
                        final String fullUrl = resolveAudioUrl(audioUrl);
                        mainHandler.post(() -> {
                            if (hasActiveSession && expectedSessionId.equals(currentSessionId)) {
                                playAudioSource(fullUrl);
                            }
                        });
                    } else {
                        mainHandler.postDelayed(() -> fetchAndPlayChunk(index), 600);
                    }
                } catch (Exception e) {
                    isBuffering = false;
                    notifyStateChanged();
                }
            }
        });
    }

    public void prevChunk() {
        if (hasActiveSession && !isOfflineMode && currentChunkIndex > 0) {
            fetchAndPlayChunk(currentChunkIndex - 1);
        }
    }

    public void nextChunk() {
        if (hasActiveSession && !isOfflineMode && currentChunkIndex < totalChunks - 1) {
            fetchAndPlayChunk(currentChunkIndex + 1);
        }
    }

    private String resolveAudioUrl(String audioUrl) {
        return audioUrl.startsWith("http") ? audioUrl : (ApiClient.getInstance(this).getBaseUrl() + audioUrl);
    }

    private void playAudioSource(String audioSourceUrlOrPath) {
        try {
            isPrepared = false;
            if (mediaPlayer == null) {
                initMediaPlayer();
            }
            mediaPlayer.reset();
            mediaPlayer.setAudioAttributes(buildAudioAttributes());
            mediaPlayer.setDataSource(audioSourceUrlOrPath);
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                isBuffering = false;
                applySpeedInternal(mp, currentSpeed);
                mp.start();
                isPlaying = true;
                updateNotification(true);
                notifyStateChanged();
            });
            mediaPlayer.prepareAsync();
            startForeground(NOTIFICATION_ID, buildNotification(chapterTitle, true));
        } catch (Exception e) {
            isBuffering = false;
            isPlaying = false;
            notifyError(e.getMessage());
            notifyStateChanged();
        }
    }

    private void handleTrackCompleted() {
        if (!hasActiveSession) return;

        int completedChunks = currentChunkIndex + 1;
        int progress = (completedChunks * 100) / Math.max(1, totalChunks);
        if (!isQuickRead && dbHelper != null && chapterId > 0) {
            dbHelper.updateChapterProgress(chapterId, progress, currentChunkIndex);
        }

        if (!isOfflineMode && currentChunkIndex + 1 < totalChunks) {
            fetchAndPlayChunk(currentChunkIndex + 1);
        } else {
            isPlaying = false;
            isBuffering = false;
            if (!isQuickRead && dbHelper != null && chapterId > 0) {
                dbHelper.updateChapterProgress(chapterId, 100, currentChunkIndex);
            }
            updateNotification(false);
            notifyStateChanged();
        }
    }

    private void applySpeedInternal(MediaPlayer mp, float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed > 0) {
            try {
                PlaybackParams params = mp.getPlaybackParams();
                params.setSpeed(speed);
                mp.setPlaybackParams(params);
            } catch (Exception ignored) {}
        }
    }

    public void setSpeed(float speed) {
        this.currentSpeed = speed;
        if (mediaPlayer != null && isPrepared) {
            applySpeedInternal(mediaPlayer, speed);
        }
    }

    public void togglePlayPause() {
        if (!hasActiveSession) return;
        if (isPlaying) {
            pause();
        } else {
            resume();
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
        }
        isPlaying = false;
        updateNotification(false);
        notifyStateChanged();
    }

    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying() && isPrepared) {
            mediaPlayer.start();
            isPlaying = true;
            updateNotification(true);
            notifyStateChanged();
        }
    }

    public void stopPlayback() {
        mainHandler.removeCallbacksAndMessages(null);
        hasActiveSession = false;
        isPlaying = false;
        isBuffering = false;
        isPrepared = false;
        currentSessionId = null;

        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.reset();
            } catch (Exception ignored) {}
        }

        stopForeground(true);
        notifyStateChanged();
        stopSelf();
    }

    public boolean hasActiveSession() { return hasActiveSession; }
    public boolean isPlaying() { return isPlaying; }
    public boolean isBuffering() { return isBuffering; }
    public boolean isOfflineMode() { return isOfflineMode; }
    public boolean isQuickRead() { return isQuickRead; }
    public long getBookId() { return bookId; }
    public long getChapterId() { return chapterId; }
    public String getBookTitle() { return bookTitle; }
    public String getChapterTitle() { return chapterTitle; }
    public String getCurrentDisplayText() { return currentDisplayText; }
    public String getSelectedLanguage() { return selectedLanguage; }
    public String getSelectedGender() { return selectedGender; }
    public float getCurrentSpeed() { return currentSpeed; }
    public int getCurrentChunkIndex() { return currentChunkIndex; }
    public int getTotalChunks() { return totalChunks; }

    public int getProgressPercent() {
        if (isOfflineMode) return 100;
        return (currentChunkIndex * 100) / Math.max(1, totalChunks);
    }

    public boolean isSameChapterLoaded(long checkChapterId, boolean checkQuickRead) {
        if (!hasActiveSession) return false;
        if (checkQuickRead && isQuickRead) return true;
        return !checkQuickRead && !isQuickRead && checkChapterId > 0 && checkChapterId == this.chapterId;
    }

    private void notifyStateChanged() {
        mainHandler.post(() -> {
            Set<StateListener> snapshot;
            synchronized (stateListeners) {
                snapshot = new HashSet<>(stateListeners);
            }
            for (StateListener l : snapshot) l.onPlaybackStateChanged();
        });
    }

    private void notifyError(final String error) {
        mainHandler.post(() -> {
            Set<StateListener> snapshot;
            synchronized (stateListeners) {
                snapshot = new HashSet<>(stateListeners);
            }
            for (StateListener l : snapshot) l.onPlaybackError(error);
        });
    }

    private void updateNotification(boolean playing) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(chapterTitle, playing));
        }
    }

    private Notification buildNotification(String title, boolean playing) {
        Intent notificationIntent = new Intent(this, AudioPlayerActivity.class);
        notificationIntent.putExtra("attach_active", true);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent toggleIntent = new Intent(this, AudioPlayerService.class).setAction(ACTION_TOGGLE_PLAY);
        PendingIntent togglePending = PendingIntent.getService(
                this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, AudioPlayerService.class).setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                        playing ? "Pause" : "Play", togglePending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Story Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Audio narration controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            if (ACTION_TOGGLE_PLAY.equals(action)) {
                togglePlayPause();
            } else if (ACTION_PAUSE.equals(action)) {
                pause();
            } else if (ACTION_STOP.equals(action)) {
                stopPlayback();
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopPlayback();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (activeInstance == this) {
            activeInstance = null;
        }
        hasActiveSession = false;
        isPlaying = false;
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) mediaPlayer.stop();
                mediaPlayer.release();
            } catch (Exception ignored) {}
            mediaPlayer = null;
        }
        stopForeground(true);
        notifyStateChanged();
        super.onDestroy();
    }
}
