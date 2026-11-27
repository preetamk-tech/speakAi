package com.speakai.app.models;

public class Book {
    private long id;
    private String title;
    private String filename;
    private int totalChapters;
    private int overallProgress;
    private long createdAt;
    private String polishJobId = "";

    public Book() {}

    public Book(long id, String title, String filename, int totalChapters, int overallProgress, long createdAt, String polishJobId) {
        this.id = id;
        this.title = title;
        this.filename = filename;
        this.totalChapters = totalChapters;
        this.overallProgress = overallProgress;
        this.createdAt = createdAt;
        this.polishJobId = polishJobId != null ? polishJobId : "";
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public int getTotalChapters() { return totalChapters; }
    public void setTotalChapters(int totalChapters) { this.totalChapters = totalChapters; }

    public int getOverallProgress() { return overallProgress; }
    public void setOverallProgress(int overallProgress) { this.overallProgress = overallProgress; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public String getPolishJobId() { return polishJobId; }
    public void setPolishJobId(String polishJobId) { this.polishJobId = polishJobId != null ? polishJobId : ""; }
}
