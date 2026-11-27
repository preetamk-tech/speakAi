package com.speakai.app.network;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {

    private static final String PREF_NAME = "speakai_prefs";
    private static final String KEY_BASE_URL = "base_server_url";
    
    public static final String DEFAULT_BASE_URL = "http://10.106.206.128:8000";
    public static final String WIFI_FALLBACK_URL = "http://10.106.206.128:8000";

    private static ApiClient instance;
    private final OkHttpClient httpClient;
    private final SharedPreferences prefs;

    private ApiClient(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .addInterceptor(chain -> {
                    Request request = chain.request();
                    try {
                        return chain.proceed(request);
                    } catch (java.io.IOException originalException) {
                        String url = request.url().toString();
                        if (url.contains("127.0.0.1:8000") || url.contains("localhost:8000") || url.contains("10.0.2.2:8000")) {
                            String retryUrl = url
                                    .replace("127.0.0.1:8000", "10.106.206.128:8000")
                                    .replace("localhost:8000", "10.106.206.128:8000")
                                    .replace("10.0.2.2:8000", "10.106.206.128:8000");
                            Request retryRequest = request.newBuilder().url(retryUrl).build();
                            return chain.proceed(retryRequest);
                        }
                        throw originalException;
                    }
                })
                .build();
    }

    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new ApiClient(context);
        }
        return instance;
    }

    public String getBaseUrl() {
        String saved = prefs.getString(KEY_BASE_URL, null);
        if (saved == null || saved.equals("http://10.0.2.2:8000") || saved.equals("http://127.0.0.1:8000")) {
            return DEFAULT_BASE_URL;
        }
        return saved;
    }

    public void setBaseUrl(String url) {
        if (url != null && !url.trim().isEmpty()) {
            String clean = url.trim();
            if (clean.endsWith("/")) {
                clean = clean.substring(0, clean.length() - 1);
            }
            prefs.edit().putString(KEY_BASE_URL, clean).apply();
        }
    }

    public void startParseJob(File file, String mimeType, String mode, Callback callback) {
        MediaType mediaType = MediaType.parse(mimeType != null ? mimeType : "application/octet-stream");
        RequestBody fileBody = RequestBody.create(file, mediaType);

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", file.getName(), fileBody)
                .addFormDataPart("mode", mode)
                .build();

        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/parse/start")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void getParseStatus(String jobId, Callback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/parse/status/" + jobId)
                .get()
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void getPolishStatus(String polishJobId, Callback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/polish/" + polishJobId + "/status")
                .get()
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void prioritizeChapter(String polishJobId, int chapterIndex, Callback callback) {
        RequestBody emptyBody = RequestBody.create(new byte[0], null);
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/polish/" + polishJobId + "/prioritize/" + chapterIndex)
                .post(emptyBody)
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void polishSingleChapter(String rawText, String bookTitle, String chapterTitle, Callback callback) {
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("raw_text", rawText)
                .addFormDataPart("book_title", bookTitle != null ? bookTitle : "Book")
                .addFormDataPart("chapter_title", chapterTitle != null ? chapterTitle : "Chapter")
                .build();

        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/polish/chapter")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void startSession(String chapterText, String language, String gender, String mode, Callback callback) {
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("raw_text", chapterText)
                .addFormDataPart("language", language)
                .addFormDataPart("gender", gender)
                .addFormDataPart("mode", mode)
                .build();

        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/session/start")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void getChunk(String sessionId, int index, Callback callback) {
        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/session/" + sessionId + "/chunk/" + index)
                .get()
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public void synthesizeFullChapter(String rawText, String language, String gender, Callback callback) {
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("raw_text", rawText)
                .addFormDataPart("language", language)
                .addFormDataPart("gender", gender)
                .build();

        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/chapter/synthesize_full")
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(callback);
    }

    public interface DownloadListener {
        void onProgress(int progress);
        void onSuccess(File downloadedFile);
        void onError(String message);
    }

    public void downloadFile(String audioUrl, File destinationFile, DownloadListener listener) {
        String fullUrl = audioUrl.startsWith("http") ? audioUrl : (getBaseUrl() + audioUrl);

        Request request = new Request.Builder().url(fullUrl).build();
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException e) {
                if (listener != null) listener.onError(e.getMessage());
            }

            @Override
            public void onResponse(Call call, Response response) {
                if (!response.isSuccessful()) {
                    if (listener != null) listener.onError("HTTP " + response.code());
                    return;
                }

                try {
                    destinationFile.getParentFile().mkdirs();
                    InputStream is = response.body().byteStream();
                    FileOutputStream fos = new FileOutputStream(destinationFile);
                    byte[] buffer = new byte[4096];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                    }
                    fos.flush();
                    fos.close();
                    is.close();

                    if (listener != null) listener.onSuccess(destinationFile);
                } catch (Exception ex) {
                    if (listener != null) listener.onError(ex.getMessage());
                }
            }
        });
    }
}
