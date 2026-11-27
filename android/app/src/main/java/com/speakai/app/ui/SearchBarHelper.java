package com.speakai.app.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;

import com.speakai.app.R;

/**
 * Reusable live search-bar controller with auto clear button for library and chapter lists.
 */
public class SearchBarHelper {

    public interface QueryListener {
        void onQueryChanged(String query);
    }

    public static void attach(View searchRoot, String hint, QueryListener listener) {
        EditText etQuery = searchRoot.findViewById(R.id.etSearchQuery);
        ImageButton btnClear = searchRoot.findViewById(R.id.btnClearSearch);

        if (hint != null) {
            etQuery.setHint(hint);
        }

        btnClear.setOnClickListener(v -> etQuery.setText(""));

        etQuery.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String q = s != null ? s.toString().trim() : "";
                btnClear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
                if (listener != null) listener.onQueryChanged(q);
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
}
