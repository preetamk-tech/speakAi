package com.speakai.app.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.speakai.app.R;
import com.speakai.app.models.TutorMessage;

import java.util.List;

public class TutorMessageAdapter extends RecyclerView.Adapter<TutorMessageAdapter.TutorViewHolder> {

    public interface OnTutorActionListener {
        void onReplayAudio(TutorMessage message);
        void onPracticeRepeat(String correctedSentence);
    }

    private final List<TutorMessage> messages;
    private final OnTutorActionListener listener;

    public TutorMessageAdapter(List<TutorMessage> messages, OnTutorActionListener listener) {
        this.messages = messages;
        this.listener = listener;
    }

    @NonNull
    @Override
    public TutorViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_tutor_message, parent, false);
        return new TutorViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TutorViewHolder holder, int position) {
        TutorMessage msg = messages.get(position);

        if (msg.isUser()) {
            holder.layoutUserBubble.setVisibility(View.VISIBLE);
            holder.cardTutorBubble.setVisibility(View.GONE);
            holder.tvUserMessage.setText(msg.getText());
            return;
        }

        holder.layoutUserBubble.setVisibility(View.GONE);
        holder.cardTutorBubble.setVisibility(View.VISIBLE);
        holder.tvTutorReply.setText(msg.getText());

        boolean hasCorrected = !msg.getCorrected().isEmpty();
        boolean hasTip = !msg.getTip().isEmpty();
        boolean hasWord = !msg.getBetterWord().isEmpty();

        if (hasCorrected || hasTip || hasWord) {
            holder.layoutCorrectionBox.setVisibility(View.VISIBLE);

            if (hasCorrected) {
                holder.tvTutorCorrected.setVisibility(View.VISIBLE);
                holder.tvTutorCorrected.setText("You can say: \"" + msg.getCorrected() + "\"");
                holder.btnPracticeRepeat.setVisibility(View.VISIBLE);
                holder.btnPracticeRepeat.setOnClickListener(v -> {
                    if (listener != null) listener.onPracticeRepeat(msg.getCorrected());
                });
            } else {
                holder.tvTutorCorrected.setVisibility(View.GONE);
                holder.btnPracticeRepeat.setVisibility(View.GONE);
            }

            if (hasTip) {
                holder.tvTutorTip.setVisibility(View.VISIBLE);
                holder.tvTutorTip.setText("Tip: " + msg.getTip());
            } else {
                holder.tvTutorTip.setVisibility(View.GONE);
            }

            if (hasWord) {
                holder.tvTutorBetterWord.setVisibility(View.VISIBLE);
                holder.tvTutorBetterWord.setText("Word: " + msg.getBetterWord());
            } else {
                holder.tvTutorBetterWord.setVisibility(View.GONE);
            }
        } else {
            holder.layoutCorrectionBox.setVisibility(View.GONE);
        }

        if (!msg.getAudioUrl().isEmpty()) {
            holder.btnReplayAudio.setVisibility(View.VISIBLE);
            holder.btnReplayAudio.setOnClickListener(v -> {
                if (listener != null) listener.onReplayAudio(msg);
            });
        } else {
            holder.btnReplayAudio.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class TutorViewHolder extends RecyclerView.ViewHolder {
        LinearLayout layoutUserBubble, layoutCorrectionBox;
        CardView cardTutorBubble;
        TextView tvUserMessage, tvTutorCorrected, tvTutorTip, tvTutorBetterWord, btnPracticeRepeat, tvTutorReply;
        ImageButton btnReplayAudio;

        TutorViewHolder(@NonNull View itemView) {
            super(itemView);
            layoutUserBubble = itemView.findViewById(R.id.layoutUserBubble);
            cardTutorBubble = itemView.findViewById(R.id.cardTutorBubble);
            layoutCorrectionBox = itemView.findViewById(R.id.layoutCorrectionBox);
            tvUserMessage = itemView.findViewById(R.id.tvUserMessage);
            tvTutorCorrected = itemView.findViewById(R.id.tvTutorCorrected);
            tvTutorTip = itemView.findViewById(R.id.tvTutorTip);
            tvTutorBetterWord = itemView.findViewById(R.id.tvTutorBetterWord);
            btnPracticeRepeat = itemView.findViewById(R.id.btnPracticeRepeat);
            tvTutorReply = itemView.findViewById(R.id.tvTutorReply);
            btnReplayAudio = itemView.findViewById(R.id.btnReplayTutorAudio);
        }
    }
}
