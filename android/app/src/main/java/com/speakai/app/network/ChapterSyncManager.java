package com.speakai.app.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.speakai.app.database.AppDatabaseHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class ChapterSyncManager {

    public interface SyncListener {
        void onSyncUpdated(long bookId);
    }

    private static ChapterSyncManager instance;
    private final Context appContext;
    private final AppDatabaseHelper dbHelper;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Long, String> activeJobs = new HashMap<>();
    private final Set<SyncListener> listeners = new HashSet<>();

    private ChapterSyncManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.dbHelper = new AppDatabaseHelper(this.appContext);
    }

    public static synchronized ChapterSyncManager getInstance(Context context) {
        if (instance == null) {
            instance = new ChapterSyncManager(context);
        }
        return instance;
    }

    public synchronized void addListener(SyncListener listener) {
        if (listener != null) listeners.add(listener);
    }

    public synchronized void removeListener(SyncListener listener) {
        listeners.remove(listener);
    }

    private void notifyListeners(long bookId) {
        handler.post(() -> {
            Set<SyncListener> snapshot;
            synchronized (ChapterSyncManager.this) {
                snapshot = new HashSet<>(listeners);
            }
            for (SyncListener l : snapshot) {
                l.onSyncUpdated(bookId);
            }
        });
    }

    public synchronized void startSync(long bookId, String polishJobId) {
        if (polishJobId == null || polishJobId.trim().isEmpty()) return;
        if (polishJobId.equals(activeJobs.get(bookId))) return;

        activeJobs.put(bookId, polishJobId);
        schedulePoll(bookId, polishJobId, 500);
    }

    public synchronized boolean isSyncing(long bookId) {
        return activeJobs.containsKey(bookId);
    }

    private void schedulePoll(final long bookId, final String polishJobId, long delayMs) {
        handler.postDelayed(() -> pollOnce(bookId, polishJobId), delayMs);
    }

    private void pollOnce(final long bookId, final String polishJobId) {
        synchronized (this) {
            if (!polishJobId.equals(activeJobs.get(bookId))) {
                return;
            }
        }

        ApiClient.getInstance(appContext).getPolishStatus(polishJobId, new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                schedulePoll(bookId, polishJobId, 2000);
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (response.code() == 404) {
                    synchronized (ChapterSyncManager.this) {
                        activeJobs.remove(bookId);
                    }
                    dbHelper.clearBookPolishJob(bookId);
                    notifyListeners(bookId);
                    return;
                }

                if (!response.isSuccessful()) {
                    schedulePoll(bookId, polishJobId, 2000);
                    return;
                }

                try {
                    String body = response.body().string();
                    JSONObject json = new JSONObject(body);
                    boolean done = json.optBoolean("done", false);

                    JSONArray newlyReady = json.optJSONArray("newly_ready");
                    boolean changed = false;
                    if (newlyReady != null) {
                        for (int i = 0; i < newlyReady.length(); i++) {
                            JSONObject chObj = newlyReady.getJSONObject(i);
                            int chIndex = chObj.getInt("chapter_index");
                            String cleanContent = chObj.getString("content");
                            dbHelper.updateChapterCleanedText(bookId, chIndex, cleanContent, "ready");
                            changed = true;
                        }
                    }

                    JSONObject statuses = json.optJSONObject("chapter_statuses");
                    if (statuses != null) {
                        Iterator<String> keys = statuses.keys();
                        while (keys.hasNext()) {
                            String key = keys.next();
                            String st = statuses.optString(key, "pending");
                            if ("processing".equals(st)) {
                                try {
                                    dbHelper.updateChapterCleanStatus(bookId, Integer.parseInt(key), "processing");
                                    changed = true;
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }

                    if (changed) {
                        notifyListeners(bookId);
                    }

                    if (done) {
                        synchronized (ChapterSyncManager.this) {
                            activeJobs.remove(bookId);
                        }
                        dbHelper.clearBookPolishJob(bookId);
                        notifyListeners(bookId);
                    } else {
                        schedulePoll(bookId, polishJobId, 1200);
                    }
                } catch (Exception e) {
                    schedulePoll(bookId, polishJobId, 2000);
                }
            }
        });
    }
}
