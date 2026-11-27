package com.speakai.app.network;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiClient {

    public static final String DEFAULT_BASE_URL = "https://speakai-ptk6.onrender.com";

    private static ApiClient instance;
    private final OkHttpClient httpClient;

    private ApiClient(Context context) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(300, TimeUnit.SECONDS)
                .writeTimeout(300, TimeUnit.SECONDS)
                .build();
    }

    public static synchronized ApiClient getInstance(Context context) {
        if (instance == null) {
            instance = new ApiClient(context);
        }
        return instance;
    }

    public String getBaseUrl() {
        return DEFAULT_BASE_URL;
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

    public void startParseJobWithPages(List<String> pages, String bookTitle, String mode, Callback callback) {
        JSONArray array = new JSONArray();
        for (String p : pages) {
            array.put(p != null ? p : "");
        }

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("pages_json", array.toString())
                .addFormDataPart("book_title", bookTitle != null ? bookTitle : "Document.txt")
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

    public void sendTutorMessage(
            String message,
            JSONArray history,
            String topic,
            String level,
            String gender,
            Callback callback
    ) {
        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("message", message != null ? message : "")
                .addFormDataPart("history_json", history != null ? history.toString() : "[]")
                .addFormDataPart("topic", topic != null ? topic : "Free Talk")
                .addFormDataPart("level", level != null ? level : "simple")
                .addFormDataPart("gender", gender != null ? gender : "female")
                .build();

        Request request = new Request.Builder()
                .url(getBaseUrl() + "/api/tutor/chat")
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
