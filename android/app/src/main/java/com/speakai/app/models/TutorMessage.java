package com.speakai.app.models;

public class TutorMessage {
    private boolean isUser;
    private String text;
    private String corrected;
    private String tip;
    private String betterWord;
    private String audioUrl;

    public TutorMessage(boolean isUser, String text, String corrected, String tip, String betterWord, String audioUrl) {
        this.isUser = isUser;
        this.text = text != null ? text : "";
        this.corrected = corrected != null ? corrected : "";
        this.tip = tip != null ? tip : "";
        this.betterWord = betterWord != null ? betterWord : "";
        this.audioUrl = audioUrl != null ? audioUrl : "";
    }

    public boolean isUser() { return isUser; }
    public String getText() { return text; }
    public String getCorrected() { return corrected; }
    public String getTip() { return tip; }
    public String getBetterWord() { return betterWord; }
    public String getAudioUrl() { return audioUrl; }
}
