package com.speakai.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.speakai.app.adapters.TutorMessageAdapter;
import com.speakai.app.models.TutorMessage;
import com.speakai.app.network.ApiClient;
import com.speakai.app.services.AudioPlayerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class EnglishTutorActivity extends AppCompatActivity implements TutorMessageAdapter.OnTutorActionListener {

    private static final String PREF_NAME = "speakai_tutor_prefs";
    private static final String KEY_HISTORY = "tutor_messages";
    private static final String KEY_TOPIC = "tutor_topic";
    private static final String KEY_LEVEL = "tutor_level";
    private static final String KEY_GENDER = "tutor_gender";
    private static final String KEY_MIC_LANG = "tutor_mic_lang";
    private static final String KEY_AUTO_MIC = "tutor_auto_mic";

    private final List<TutorMessage> messages = new ArrayList<>();
    private TutorMessageAdapter adapter;
    private SharedPreferences prefs;

    private RecyclerView rvChat;
    private EditText etMessage;
    private ImageButton btnSend, btnBack, btnClear;
    private FloatingActionButton btnMic;
    private TextView tvStatus, btnSummary, btnLevelToggle, btnVoiceToggle, btnMicLangToggle;
    private TextView chipTopicFree, chipTopicDaily, chipTopicInterview, chipTopicFriends;
    private SwitchCompat swAutoMic;

    private String currentTopic = "Free Talk";
    private String currentLevel = "simple";
    private String currentGender = "female";
    private String micLanguage = "en-IN";
    private boolean isListening = false;
    private boolean isSending = false;
    private boolean isForeground = false;

    private SpeechRecognizer speechRecognizer;
    private MediaPlayer tutorPlayer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<String> micPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_english_tutor);

        prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        micPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (granted) {
                        startListeningInternal();
                    } else {
                        Toast.makeText(this, "Microphone permission is needed to speak.", Toast.LENGTH_SHORT).show();
                    }
                }
        );

        initViews();
        loadSavedSettingsAndChat();
        pauseBookAudioIfPlaying();
    }

    @Override
    protected void onResume() {
        super.onResume();
        isForeground = true;
    }

    @Override
    protected void onPause() {
        isForeground = false;
        stopListening();
        stopTutorAudio();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        if (speechRecognizer != null) {
            try { speechRecognizer.destroy(); } catch (Exception ignored) {}
            speechRecognizer = null;
        }
        if (tutorPlayer != null) {
            try { tutorPlayer.release(); } catch (Exception ignored) {}
            tutorPlayer = null;
        }
        super.onDestroy();
    }

    private void pauseBookAudioIfPlaying() {
        AudioPlayerService service = AudioPlayerService.getActiveInstance();
        if (service != null && service.isPlaying()) {
            service.pause();
        }
    }

    private void initViews() {
        rvChat = findViewById(R.id.rvTutorChat);
        etMessage = findViewById(R.id.etTutorMessage);
        btnSend = findViewById(R.id.btnTutorSend);
        btnMic = findViewById(R.id.btnTutorMic);
        btnBack = findViewById(R.id.btnBackTutor);
        btnClear = findViewById(R.id.btnTutorClear);
        btnSummary = findViewById(R.id.btnTutorSummary);
        tvStatus = findViewById(R.id.tvTutorStatus);

        chipTopicFree = findViewById(R.id.chipTopicFree);
        chipTopicDaily = findViewById(R.id.chipTopicDaily);
        chipTopicInterview = findViewById(R.id.chipTopicInterview);
        chipTopicFriends = findViewById(R.id.chipTopicFriends);

        btnLevelToggle = findViewById(R.id.btnTutorLevelToggle);
        btnVoiceToggle = findViewById(R.id.btnTutorVoiceToggle);
        btnMicLangToggle = findViewById(R.id.btnTutorMicLangToggle);
        swAutoMic = findViewById(R.id.swAutoMic);

        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvChat.setLayoutManager(lm);
        adapter = new TutorMessageAdapter(messages, this);
        rvChat.setAdapter(adapter);

        btnBack.setOnClickListener(v -> finish());
        btnSummary.setOnClickListener(v -> showSessionSummaryDialog());
        btnClear.setOnClickListener(v -> confirmClearChat());

        chipTopicFree.setOnClickListener(v -> selectTopic("Free Talk"));
        chipTopicDaily.setOnClickListener(v -> selectTopic("Daily Life"));
        chipTopicInterview.setOnClickListener(v -> selectTopic("Job Interview"));
        chipTopicFriends.setOnClickListener(v -> selectTopic("Office & Friends"));

        btnLevelToggle.setOnClickListener(v -> {
            currentLevel = "simple".equals(currentLevel) ? "medium" : "simple";
            prefs.edit().putString(KEY_LEVEL, currentLevel).apply();
            updateOptionButtonsUi();
        });

        btnVoiceToggle.setOnClickListener(v -> {
            currentGender = "female".equals(currentGender) ? "male" : "female";
            prefs.edit().putString(KEY_GENDER, currentGender).apply();
            updateOptionButtonsUi();
        });

        btnMicLangToggle.setOnClickListener(v -> {
            micLanguage = "en-IN".equals(micLanguage) ? "hi-IN" : "en-IN";
            prefs.edit().putString(KEY_MIC_LANG, micLanguage).apply();
            updateOptionButtonsUi();
            Toast.makeText(this,
                    "en-IN".equals(micLanguage) ? "Mic set to English (India)" : "Mic set to Hindi/Hinglish",
                    Toast.LENGTH_SHORT).show();
        });

        swAutoMic.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(KEY_AUTO_MIC, isChecked).apply()
        );

        etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && !s.toString().trim().isEmpty();
                btnSend.setVisibility(hasText ? View.VISIBLE : View.GONE);
            }
        });

        btnSend.setOnClickListener(v -> {
            String txt = etMessage.getText().toString().trim();
            if (!txt.isEmpty()) {
                etMessage.setText("");
                submitUserMessage(txt);
            }
        });

        btnMic.setOnClickListener(v -> {
            if (isListening) {
                stopListening();
            } else {
                startListeningWithPermissionCheck();
            }
        });
    }

    private void loadSavedSettingsAndChat() {
        currentTopic = prefs.getString(KEY_TOPIC, "Free Talk");
        currentLevel = prefs.getString(KEY_LEVEL, "simple");
        currentGender = prefs.getString(KEY_GENDER, "female");
        micLanguage = prefs.getString(KEY_MIC_LANG, "en-IN");
        swAutoMic.setChecked(prefs.getBoolean(KEY_AUTO_MIC, false));

        selectTopic(currentTopic);
        updateOptionButtonsUi();

        messages.clear();
        String raw = prefs.getString(KEY_HISTORY, "");
        if (!raw.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    messages.add(new TutorMessage(
                            o.optBoolean("isUser", false),
                            o.optString("text", ""),
                            o.optString("corrected", ""),
                            o.optString("tip", ""),
                            o.optString("betterWord", ""),
                            o.optString("audioUrl", "")
                    ));
                }
            } catch (Exception ignored) {}
        }

        if (messages.isEmpty()) {
            addGreetingMessage();
        } else {
            adapter.notifyDataSetChanged();
            rvChat.scrollToPosition(messages.size() - 1);
        }
    }

    private void addGreetingMessage() {
        messages.add(new TutorMessage(
                false,
                "Hi! I am your English Buddy. Tell me anything about your day in English or Hinglish. I will chat with you and gently fix mistakes.",
                "", "", "", ""
        ));
        adapter.notifyDataSetChanged();
    }

    private void saveChatHistory() {
        JSONArray arr = new JSONArray();
        int start = Math.max(0, messages.size() - 30);
        for (int i = start; i < messages.size(); i++) {
            TutorMessage m = messages.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("isUser", m.isUser());
                o.put("text", m.getText());
                o.put("corrected", m.getCorrected());
                o.put("tip", m.getTip());
                o.put("betterWord", m.getBetterWord());
                o.put("audioUrl", m.getAudioUrl());
                arr.put(o);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString(KEY_HISTORY, arr.toString()).apply();
    }

    private void selectTopic(String topic) {
        currentTopic = topic;
        prefs.edit().putString(KEY_TOPIC, topic).apply();
        styleChip(chipTopicFree, "Free Talk".equals(topic));
        styleChip(chipTopicDaily, "Daily Life".equals(topic));
        styleChip(chipTopicInterview, "Job Interview".equals(topic));
        styleChip(chipTopicFriends, "Office & Friends".equals(topic));
    }

    private void styleChip(TextView chip, boolean selected) {
        chip.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_unselected);
        chip.setTextColor(ContextCompat.getColor(this, selected ? R.color.primary : R.color.text_secondary));
    }

    private void updateOptionButtonsUi() {
        btnLevelToggle.setText("simple".equals(currentLevel) ? "Level: Simple" : "Level: Medium");
        btnVoiceToggle.setText("female".equals(currentGender) ? "Voice: Female" : "Voice: Male");
        btnMicLangToggle.setText("en-IN".equals(micLanguage) ? "Mic: EN-IN" : "Mic: HI-Mix");
    }

    private void startListeningWithPermissionCheck() {
        if (isSending) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startListeningInternal();
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startListeningInternal() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition not available. Please type below.", Toast.LENGTH_SHORT).show();
            return;
        }

        pauseBookAudioIfPlaying();
        stopTutorAudio();

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override
                public void onReadyForSpeech(Bundle params) {
                    isListening = true;
                    updateMicUi(true, "Listening... speak in English or Hinglish");
                }

                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {
                    updateMicUi(false, "Processing speech...");
                }

                @Override
                public void onError(int error) {
                    isListening = false;
                    updateMicUi(false, null);
                    if (error != SpeechRecognizer.ERROR_CLIENT && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        Toast.makeText(EnglishTutorActivity.this, "Could not hear clearly. Try again or type.", Toast.LENGTH_SHORT).show();
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    isListening = false;
                    updateMicUi(false, null);
                    ArrayList<String> matches = results != null
                            ? results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            : null;
                    if (matches != null && !matches.isEmpty()) {
                        String spoken = matches.get(0).trim();
                        etMessage.setText("");
                        if (!spoken.isEmpty()) {
                            submitUserMessage(spoken);
                        }
                    }
                }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    ArrayList<String> matches = partialResults != null
                            ? partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            : null;
                    if (matches != null && !matches.isEmpty()) {
                        etMessage.setText(matches.get(0));
                        etMessage.setSelection(etMessage.getText().length());
                    }
                }

                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, micLanguage);
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);

        try {
            isListening = true;
            updateMicUi(true, "Listening... speak now");
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            isListening = false;
            updateMicUi(false, null);
            Toast.makeText(this, "Mic unavailable right now.", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopListening() {
        isListening = false;
        if (speechRecognizer != null) {
            try { speechRecognizer.stopListening(); } catch (Exception ignored) {}
        }
        updateMicUi(false, null);
    }

    private void updateMicUi(boolean listening, String statusText) {
        int color = ContextCompat.getColor(this, listening ? R.color.error_red : R.color.primary);
        btnMic.setBackgroundTintList(ColorStateList.valueOf(color));
        if (statusText != null && !statusText.isEmpty()) {
            tvStatus.setText(statusText);
            tvStatus.setVisibility(View.VISIBLE);
        } else if (!isSending) {
            tvStatus.setVisibility(View.GONE);
        }
    }

    private void submitUserMessage(final String text) {
        if (isSending) return;
        isSending = true;

        JSONArray historyPayload = buildRecentHistoryJson();

        messages.add(new TutorMessage(true, text, "", "", "", ""));
        adapter.notifyItemInserted(messages.size() - 1);
        rvChat.scrollToPosition(messages.size() - 1);
        saveChatHistory();

        tvStatus.setText("Buddy is thinking...");
        tvStatus.setVisibility(View.VISIBLE);

        ApiClient.getInstance(this).sendTutorMessage(
                text, historyPayload, currentTopic, currentLevel, currentGender,
                new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        runOnUiThread(() -> {
                            isSending = false;
                            tvStatus.setVisibility(View.GONE);
                            Toast.makeText(EnglishTutorActivity.this, "Connection failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        });
                    }

                    @Override
                    public void onResponse(Call call, Response response) {
                        if (!response.isSuccessful()) {
                            runOnUiThread(() -> {
                                isSending = false;
                                tvStatus.setVisibility(View.GONE);
                                Toast.makeText(EnglishTutorActivity.this, "Server error: " + response.code(), Toast.LENGTH_SHORT).show();
                            });
                            return;
                        }

                        try {
                            String body = response.body().string();
                            JSONObject json = new JSONObject(body);
                            String reply = json.optString("reply", "");
                            String corrected = json.optString("corrected", "");
                            String tip = json.optString("tip", "");
                            String betterWord = json.optString("better_word", "");
                            String audioUrl = json.optString("audio_url", "");

                            runOnUiThread(() -> {
                                isSending = false;
                                tvStatus.setVisibility(View.GONE);

                                TutorMessage tutorMsg = new TutorMessage(
                                        false, reply, corrected, tip, betterWord, audioUrl
                                );
                                messages.add(tutorMsg);
                                adapter.notifyItemInserted(messages.size() - 1);
                                rvChat.scrollToPosition(messages.size() - 1);
                                saveChatHistory();

                                if (!audioUrl.isEmpty()) {
                                    playTutorAudio(audioUrl, true);
                                }
                            });
                        } catch (Exception ex) {
                            runOnUiThread(() -> {
                                isSending = false;
                                tvStatus.setVisibility(View.GONE);
                                Toast.makeText(EnglishTutorActivity.this, "Parse error: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
                            });
                        }
                    }
                }
        );
    }

    private JSONArray buildRecentHistoryJson() {
        JSONArray arr = new JSONArray();
        int start = Math.max(0, messages.size() - 8);
        for (int i = start; i < messages.size(); i++) {
            TutorMessage m = messages.get(i);
            try {
                JSONObject o = new JSONObject();
                o.put("role", m.isUser() ? "user" : "tutor");
                o.put("text", m.getText());
                arr.put(o);
            } catch (Exception ignored) {}
        }
        return arr;
    }

    private void playTutorAudio(String audioUrl, final boolean triggerAutoMicAfter) {
        stopTutorAudio();
        pauseBookAudioIfPlaying();

        String fullUrl = audioUrl.startsWith("http")
                ? audioUrl
                : (ApiClient.getInstance(this).getBaseUrl() + audioUrl);

        try {
            tutorPlayer = new MediaPlayer();
            tutorPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build());
            tutorPlayer.setDataSource(fullUrl);
            tutorPlayer.setOnPreparedListener(MediaPlayer::start);
            tutorPlayer.setOnCompletionListener(mp -> {
                if (triggerAutoMicAfter && isForeground && swAutoMic.isChecked() && !isListening && !isSending) {
                    mainHandler.postDelayed(this::startListeningWithPermissionCheck, 450);
                }
            });
            tutorPlayer.prepareAsync();
        } catch (Exception ignored) {}
    }

    private void stopTutorAudio() {
        if (tutorPlayer != null) {
            try {
                if (tutorPlayer.isPlaying()) tutorPlayer.stop();
                tutorPlayer.reset();
                tutorPlayer.release();
            } catch (Exception ignored) {}
            tutorPlayer = null;
        }
    }

    @Override
    public void onReplayAudio(TutorMessage message) {
        if (message != null && !message.getAudioUrl().isEmpty()) {
            playTutorAudio(message.getAudioUrl(), false);
        }
    }

    @Override
    public void onPracticeRepeat(String correctedSentence) {
        Toast.makeText(this, "Say: \"" + correctedSentence + "\"", Toast.LENGTH_LONG).show();
        startListeningWithPermissionCheck();
    }

    private void showSessionSummaryDialog() {
        StringBuilder sb = new StringBuilder();
        int fixCount = 0;
        for (TutorMessage m : messages) {
            if (m.isUser()) continue;
            if (!m.getCorrected().isEmpty() || !m.getBetterWord().isEmpty()) {
                fixCount++;
                sb.append(fixCount).append(". ");
                if (!m.getCorrected().isEmpty()) {
                    sb.append(m.getCorrected()).append("\n");
                }
                if (!m.getTip().isEmpty()) {
                    sb.append("   Tip: ").append(m.getTip()).append("\n");
                }
                if (!m.getBetterWord().isEmpty()) {
                    sb.append("   Word: ").append(m.getBetterWord()).append("\n");
                }
                sb.append("\n");
            }
        }

        if (fixCount == 0) {
            sb.append("No corrections yet! Chat with your English Buddy to collect tips and better sentences here.");
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_index_viewer, null);
        TextView tvHeader = dialogView.findViewById(R.id.tvIndexHeader);
        TextView tvSub = dialogView.findViewById(R.id.tvIndexSubtitle);
        TextView tvContent = dialogView.findViewById(R.id.tvIndexContent);

        tvHeader.setText(R.string.tutor_summary);
        tvSub.setText(fixCount + " Corrections & Vocabulary Tips");
        tvContent.setText(sb.toString().trim());

        new AlertDialog.Builder(this)
                .setView(dialogView)
                .setPositiveButton("Close", null)
                .show();
    }

    private void confirmClearChat() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.tutor_clear)
                .setMessage("Start a fresh conversation?")
                .setPositiveButton("Clear", (d, w) -> {
                    stopListening();
                    stopTutorAudio();
                    messages.clear();
                    prefs.edit().remove(KEY_HISTORY).apply();
                    addGreetingMessage();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
