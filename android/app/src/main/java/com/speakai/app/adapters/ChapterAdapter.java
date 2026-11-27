package com.speakai.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.speakai.app.R;
import com.speakai.app.models.Chapter;

import java.util.List;

public class ChapterAdapter extends RecyclerView.Adapter<ChapterAdapter.ChapterViewHolder> {

    public interface OnChapterClickListener {
        void onChapterClick(Chapter chapter);
    }

    private final List<Chapter> chapters;
    private final OnChapterClickListener listener;

    public ChapterAdapter(List<Chapter> chapters, OnChapterClickListener listener) {
        this.chapters = chapters;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ChapterViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chapter, parent, false);
        return new ChapterViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChapterViewHolder holder, int position) {
        Chapter chapter = chapters.get(position);

        if (chapter.getChapterIndex() == 0) {
            holder.tvChapterNumber.setText("≡");
            holder.tvChapterTitle.setText(chapter.getTitle());
            holder.pbChapterProgress.setVisibility(View.GONE);
            holder.ivDownloadStatus.setVisibility(View.GONE);
            holder.btnPlayChapter.setVisibility(View.GONE);
            holder.tvChapterProgressPercent.setText("View Index");
            holder.tvChapterProgressPercent.setTextColor(holder.itemView.getContext().getColor(R.color.accent));

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onChapterClick(chapter);
            });
            return;
        }

        holder.pbChapterProgress.setVisibility(View.VISIBLE);
        holder.btnPlayChapter.setVisibility(View.VISIBLE);
        holder.tvChapterNumber.setText(String.valueOf(chapter.getChapterIndex()));
        holder.tvChapterTitle.setText(chapter.getTitle());
        holder.pbChapterProgress.setProgress(chapter.getProgressPercent());

        String cleanStatus = chapter.getCleanStatus();
        if ("processing".equals(cleanStatus)) {
            holder.tvChapterProgressPercent.setText("Processing...");
            holder.tvChapterProgressPercent.setTextColor(holder.itemView.getContext().getColor(R.color.amber_accent));
        } else if ("pending".equals(cleanStatus)) {
            holder.tvChapterProgressPercent.setText("Queued");
            holder.tvChapterProgressPercent.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary));
        } else {
            holder.tvChapterProgressPercent.setText(chapter.getProgressPercent() + "%");
            holder.tvChapterProgressPercent.setTextColor(holder.itemView.getContext().getColor(R.color.text_secondary));
        }

        if (chapter.isDownloaded()) {
            holder.ivDownloadStatus.setVisibility(View.VISIBLE);
        } else {
            holder.ivDownloadStatus.setVisibility(View.GONE);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onChapterClick(chapter);
        });

        holder.btnPlayChapter.setOnClickListener(v -> {
            if (listener != null) listener.onChapterClick(chapter);
        });
    }

    @Override
    public int getItemCount() {
        return chapters.size();
    }

    static class ChapterViewHolder extends RecyclerView.ViewHolder {
        TextView tvChapterNumber, tvChapterTitle, tvChapterProgressPercent;
        ProgressBar pbChapterProgress;
        ImageView ivDownloadStatus, btnPlayChapter;

        public ChapterViewHolder(@NonNull View itemView) {
            super(itemView);
            tvChapterNumber = itemView.findViewById(R.id.tvChapterNumber);
            tvChapterTitle = itemView.findViewById(R.id.tvChapterTitle);
            tvChapterProgressPercent = itemView.findViewById(R.id.tvChapterProgressPercent);
            pbChapterProgress = itemView.findViewById(R.id.pbChapterProgress);
            ivDownloadStatus = itemView.findViewById(R.id.ivDownloadStatus);
            btnPlayChapter = itemView.findViewById(R.id.btnPlayChapter);
        }
    }
}
