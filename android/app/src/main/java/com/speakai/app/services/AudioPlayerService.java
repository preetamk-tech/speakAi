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
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import com.speakai.app.AudioPlayerActivity;
import com.speakai.app.R;

public class AudioPlayerService extends Service {

    public static final String CHANNEL_ID = "speakai_playback_channel";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_PLAY = "com.speakai.action.PLAY";
    public static final String ACTION_PAUSE = "com.speakai.action.PAUSE";
    public static final String ACTION_STOP = "com.speakai.action.STOP";

    private final IBinder binder = new LocalBinder();
    private MediaPlayer mediaPlayer;
    private boolean isPrepared = false;
    private String currentTitle = "Playing Story";

    public interface PlaybackListener {
        void onPlaybackStarted();
        void onPlaybackPaused();
        void onPlaybackCompleted();
        void onPlaybackError(String error);
    }

    private PlaybackListener playbackListener;

    public class LocalBinder extends Binder {
        public AudioPlayerService getService() {
            return AudioPlayerService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        initMediaPlayer();
    }

    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );

        mediaPlayer.setOnPreparedListener(mp -> {
            isPrepared = true;
            mp.start();
            updateNotification(true);
            if (playbackListener != null) playbackListener.onPlaybackStarted();
        });

        mediaPlayer.setOnCompletionListener(mp -> {
            updateNotification(false);
            if (playbackListener != null) playbackListener.onPlaybackCompleted();
        });

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            if (playbackListener != null) playbackListener.onPlaybackError("Playback error: " + what);
            return false;
        });
    }

    public void setPlaybackListener(PlaybackListener listener) {
        this.playbackListener = listener;
    }

    public void playAudio(String audioSourceUrlOrPath, String title, float speed) {
        this.currentTitle = title != null ? title : "Playing Story";
        try {
            isPrepared = false;
            mediaPlayer.reset();
            mediaPlayer.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
            );
            mediaPlayer.setDataSource(audioSourceUrlOrPath);
            mediaPlayer.setOnPreparedListener(mp -> {
                isPrepared = true;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && speed > 0) {
                    try {
                        PlaybackParams params = mp.getPlaybackParams();
                        params.setSpeed(speed);
                        mp.setPlaybackParams(params);
                    } catch (Exception ignored) {}
                }
                mp.start();
                updateNotification(true);
                if (playbackListener != null) playbackListener.onPlaybackStarted();
            });
            mediaPlayer.prepareAsync();

            startForeground(NOTIFICATION_ID, buildNotification(currentTitle, true));
        } catch (Exception e) {
            if (playbackListener != null) playbackListener.onPlaybackError(e.getMessage());
        }
    }

    public void setSpeed(float speed) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && mediaPlayer != null && isPrepared) {
            try {
                PlaybackParams params = mediaPlayer.getPlaybackParams();
                params.setSpeed(speed);
                mediaPlayer.setPlaybackParams(params);
            } catch (Exception ignored) {}
        }
    }

    public void pause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            updateNotification(false);
            if (playbackListener != null) playbackListener.onPlaybackPaused();
        }
    }

    public void resume() {
        if (mediaPlayer != null && !mediaPlayer.isPlaying() && isPrepared) {
            mediaPlayer.start();
            updateNotification(true);
            if (playbackListener != null) playbackListener.onPlaybackStarted();
        }
    }

    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }

    private void updateNotification(boolean isPlaying) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(currentTitle, isPlaying));
        }
    }

    private Notification buildNotification(String title, boolean isPlaying) {
        Intent notificationIntent = new Intent(this, AudioPlayerActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(title)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pendingIntent)
                .setOngoing(isPlaying)
                .setOnlyAlertOnce(true)
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
            channel.setDescription("Background audio narration controls");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
