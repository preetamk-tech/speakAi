package com.speakai.app.adapters;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.speakai.app.R;
import com.speakai.app.models.Book;

import java.util.List;

public class BookAdapter extends RecyclerView.Adapter<BookAdapter.BookViewHolder> {

    public interface OnBookClickListener {
        void onBookClick(Book book);
        void onDeleteClick(Book book);
    }

    private final List<Book> books;
    private final OnBookClickListener listener;

    public BookAdapter(List<Book> books, OnBookClickListener listener) {
        this.books = books;
        this.listener = listener;
    }

    @NonNull
    @Override
    public BookViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_book, parent, false);
        return new BookViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull BookViewHolder holder, int position) {
        Book book = books.get(position);
        holder.tvTitle.setText(book.getTitle());

        if (book.isProcessing()) {
            int amberColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.amber_accent);
            String stage = book.getProcessingStage().isEmpty() ? "Processing in background..." : book.getProcessingStage();
            holder.tvChapters.setText(stage);
            holder.progressBar.setProgress(book.getProcessingProgress());
            holder.progressBar.setProgressTintList(ColorStateList.valueOf(amberColor));

            String eta = book.getProcessingEta();
            String statusTxt = eta.isEmpty()
                    ? (book.getProcessingProgress() + "%")
                    : (book.getProcessingProgress() + "% • " + eta);
            holder.tvProgress.setText(statusTxt);
            holder.tvProgress.setTextColor(amberColor);
            holder.btnDelete.setImageResource(R.drawable.ic_close);
            holder.itemView.setAlpha(0.92f);
        } else {
            int primaryColor = ContextCompat.getColor(holder.itemView.getContext(), R.color.primary);
            holder.tvChapters.setText(book.getTotalChapters() + " Chapters");
            holder.tvProgress.setText(book.getOverallProgress() + "% Completed");
            holder.tvProgress.setTextColor(primaryColor);
            holder.progressBar.setProgress(book.getOverallProgress());
            holder.progressBar.setProgressTintList(ColorStateList.valueOf(primaryColor));
            holder.btnDelete.setImageResource(R.drawable.ic_delete);
            holder.itemView.setAlpha(1.0f);
        }

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onBookClick(book);
        });

        holder.btnDelete.setOnClickListener(v -> {
            if (listener != null) listener.onDeleteClick(book);
        });
    }

    @Override
    public int getItemCount() {
        return books.size();
    }

    static class BookViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle, tvChapters, tvProgress;
        ProgressBar progressBar;
        ImageButton btnDelete;

        public BookViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvBookTitle);
            tvChapters = itemView.findViewById(R.id.tvChaptersCount);
            tvProgress = itemView.findViewById(R.id.tvProgressPercent);
            progressBar = itemView.findViewById(R.id.pbBookProgress);
            btnDelete = itemView.findViewById(R.id.btnDeleteBook);
        }
    }
}
