package com.speakai.app;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.speakai.app.services.AudioPlayerService;

/**
 * Tracks foreground vs background transitions so playback pauses automatically on
 * minimize or screen-off unless the user explicitly enables Background Play.
 */
public class SpeakAiApp extends Application implements Application.ActivityLifecycleCallbacks {

    private static final String PREF_NAME = "speakai_prefs";
    private static final String KEY_BACKGROUND_PLAY = "background_play_enabled";

    private int startedActivityCount = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
    }

    public static boolean isBackgroundPlayEnabled(Context context) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getBoolean(KEY_BACKGROUND_PLAY, false);
    }

    public static void setBackgroundPlayEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BACKGROUND_PLAY, enabled).apply();
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        startedActivityCount++;
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {}

    @Override
    public void onActivityPaused(@NonNull Activity activity) {}

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (!activity.isChangingConfigurations()) {
            startedActivityCount = Math.max(0, startedActivityCount - 1);
            if (startedActivityCount == 0 && !isBackgroundPlayEnabled(this)) {
                AudioPlayerService service = AudioPlayerService.getActiveInstance();
                if (service != null && service.isPlaying()) {
                    service.pause();
                }
            }
        }
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {}

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {}
}
