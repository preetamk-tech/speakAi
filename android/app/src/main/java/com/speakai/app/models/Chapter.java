package com.speakai.app.models;

public class Chapter {
    private long id;
    private long bookId;
    private int chapterIndex;
    private String title;
    private String contentText;
    private int progressPercent;
    private int lastChunkIndex;
    private boolean isDownloaded;
    private String localAudioPath;
    private String cleanStatus = "ready";

    public Chapter() {}

    public Chapter(long id, long bookId, int chapterIndex, String title, String contentText,
                   int progressPercent, int lastChunkIndex, boolean isDownloaded, String localAudioPath, String cleanStatus) {
        this.id = id;
        this.bookId = bookId;
        this.chapterIndex = chapterIndex;
        this.title = title;
        this.contentText = contentText;
        this.progressPercent = progressPercent;
        this.lastChunkIndex = lastChunkIndex;
        this.isDownloaded = isDownloaded;
        this.localAudioPath = localAudioPath;
        this.cleanStatus = cleanStatus != null ? cleanStatus : "ready";
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getBookId() { return bookId; }
    public void setBookId(long bookId) { this.bookId = bookId; }

    public int getChapterIndex() { return chapterIndex; }
    public void setChapterIndex(int chapterIndex) { this.chapterIndex = chapterIndex; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }

    public int getLastChunkIndex() { return lastChunkIndex; }
    public void setLastChunkIndex(int lastChunkIndex) { this.lastChunkIndex = lastChunkIndex; }

    public boolean isDownloaded() { return isDownloaded; }
    public void setDownloaded(boolean downloaded) { isDownloaded = downloaded; }

    public String getLocalAudioPath() { return localAudioPath; }
    public void setLocalAudioPath(String localAudioPath) { this.localAudioPath = localAudioPath; }

    public String getCleanStatus() { return cleanStatus; }
    public void setCleanStatus(String cleanStatus) { this.cleanStatus = cleanStatus != null ? cleanStatus : "ready"; }
}
