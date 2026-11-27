package com.speakai.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;

import com.speakai.app.AudioPlayerActivity;
import com.speakai.app.R;
import com.speakai.app.services.AudioPlayerService;

/**
 * Shared sticky bottom mini-player controller for library and book-detail screens.
 * Mirrors active service state and provides immediate Pause/Resume and Stop controls.
 */
public class MiniPlayerController implements AudioPlayerService.StateListener {

    private final Activity activity;
    private final View root;
    private final View body;
    private final TextView tvTitle;
    private final TextView tvStatus;
    private final ImageButton btnPlayPause;
    private final ImageButton btnStop;

    public MiniPlayerController(Activity activity, View miniPlayerRoot) {
        this.activity = activity;
        this.root = miniPlayerRoot;
        this.body = miniPlayerRoot.findViewById(R.id.miniPlayerBody);
        this.tvTitle = miniPlayerRoot.findViewById(R.id.tvMiniChapterTitle);
        this.tvStatus = miniPlayerRoot.findViewById(R.id.tvMiniBookStatus);
        this.btnPlayPause = miniPlayerRoot.findViewById(R.id.btnMiniPlayPause);
        this.btnStop = miniPlayerRoot.findViewById(R.id.btnMiniStop);

        setupActions();
    }

    private void setupActions() {
        body.setOnClickListener(v -> {
            AudioPlayerService service = AudioPlayerService.getActiveInstance();
            if (service == null || !service.hasActiveSession()) return;

            Intent intent = new Intent(activity, AudioPlayerActivity.class);
            intent.putExtra("attach_active", true);
            intent.putExtra("is_quick_read", service.isQuickRead());
            intent.putExtra("chapter_id", service.getChapterId());
            intent.putExtra("book_id", service.getBookId());
            intent.putExtra("book_title", service.getBookTitle());
            activity.startActivity(intent);
        });

        btnPlayPause.setOnClickListener(v -> {
            AudioPlayerService service = AudioPlayerService.getActiveInstance();
            if (service != null && service.hasActiveSession()) {
                service.togglePlayPause();
            }
        });

        btnStop.setOnClickListener(v -> {
            AudioPlayerService service = AudioPlayerService.getActiveInstance();
            if (service != null) {
                service.stopPlayback();
            }
            refresh();
        });
    }

    public void onResume() {
        AudioPlayerService.addStateListener(this);
        refresh();
    }

    public void onPause() {
        AudioPlayerService.removeStateListener(this);
    }

    public void refresh() {
        AudioPlayerService service = AudioPlayerService.getActiveInstance();
        if (service == null || !service.hasActiveSession()) {
            root.setVisibility(View.GONE);
            return;
        }

        root.setVisibility(View.VISIBLE);
        tvTitle.setText(service.getChapterTitle());

        String stateLabel = service.isBuffering() ? "Loading..."
                : (service.isPlaying() ? "Playing" : "Paused");
        String bookName = service.getBookTitle();
        tvStatus.setText(bookName != null && !bookName.isEmpty() ? (stateLabel + " • " + bookName) : stateLabel);

        btnPlayPause.setImageResource(service.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
    }

    @Override
    public void onPlaybackStateChanged() {
        activity.runOnUiThread(this::refresh);
    }

    @Override
    public void onPlaybackError(String error) {
        activity.runOnUiThread(this::refresh);
    }
}
