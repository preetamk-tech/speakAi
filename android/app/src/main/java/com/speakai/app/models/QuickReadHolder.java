package com.speakai.app.models;

public class QuickReadHolder {
    private static String title = "Quick Read";
    private static String content = "";

    public static void set(String docTitle, String docContent) {
        title = docTitle != null ? docTitle : "Quick Read";
        content = docContent != null ? docContent : "";
    }

    public static String getTitle() {
        return title;
    }

    public static String getContent() {
        return content;
    }

    public static void clear() {
        title = "Quick Read";
        content = "";
    }
}
