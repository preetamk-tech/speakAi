package com.speakai.app.utils;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persists language, voice gender, and playback speed preferences per book,
 * falling back to the user's most recent global defaults for new books and Quick Read.
 */
public class VoicePrefsHelper {

    private static final String PREF_NAME = "speakai_voice_prefs";
    private static final String KEY_GLOBAL_LANG = "global_lang";
    private static final String KEY_GLOBAL_GENDER = "global_gender";
    private static final String KEY_GLOBAL_SPEED = "global_speed";

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static String getLanguage(Context context, long bookId) {
        SharedPreferences prefs = getPrefs(context);
        String fallback = prefs.getString(KEY_GLOBAL_LANG, "hi");
        if (bookId > 0) {
            return prefs.getString("book_" + bookId + "_lang", fallback);
        }
        return fallback;
    }

    public static String getGender(Context context, long bookId) {
        SharedPreferences prefs = getPrefs(context);
        String fallback = prefs.getString(KEY_GLOBAL_GENDER, "female");
        if (bookId > 0) {
            return prefs.getString("book_" + bookId + "_gender", fallback);
        }
        return fallback;
    }

    public static float getSpeed(Context context, long bookId) {
        SharedPreferences prefs = getPrefs(context);
        float fallback = prefs.getFloat(KEY_GLOBAL_SPEED, 1.0f);
        if (bookId > 0) {
            return prefs.getFloat("book_" + bookId + "_speed", fallback);
        }
        return fallback;
    }

    public static void savePreferences(Context context, long bookId, String lang, String gender, float speed) {
        SharedPreferences.Editor editor = getPrefs(context).edit();
        if (lang != null && !lang.isEmpty()) {
            editor.putString(KEY_GLOBAL_LANG, lang);
            if (bookId > 0) editor.putString("book_" + bookId + "_lang", lang);
        }
        if (gender != null && !gender.isEmpty()) {
            editor.putString(KEY_GLOBAL_GENDER, gender);
            if (bookId > 0) editor.putString("book_" + bookId + "_gender", gender);
        }
        if (speed > 0f) {
            editor.putFloat(KEY_GLOBAL_SPEED, speed);
            if (bookId > 0) editor.putFloat("book_" + bookId + "_speed", speed);
        }
        editor.apply();
    }
}
