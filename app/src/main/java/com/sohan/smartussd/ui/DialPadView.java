package com.sohan.smartussd.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.Button;
import android.widget.LinearLayout;

import com.sohan.smartussd.R;

/** Minimal in-app keypad: only 0-9, * and #. */
public class DialPadView extends LinearLayout {

    public interface OnKeyListener {
        void onKey(String key);
    }

    private static final String[][] KEYS = {
            {"1", "2", "3"},
            {"4", "5", "6"},
            {"7", "8", "9"},
            {"*", "0", "#"}
    };

    private OnKeyListener listener;

    public DialPadView(Context context) {
        this(context, null);
    }

    public DialPadView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setOrientation(VERTICAL);

        for (String[] keys : KEYS) {

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(HORIZONTAL);

            for (String key : keys) {
                row.addView(createKey(context, key), keyParams());
            }

            addView(row, new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));
        }
    }

    public void setOnKeyListener(OnKeyListener listener) {
        this.listener = listener;
    }

    private Button createKey(Context context, String key) {

        Button button = new Button(context);

        button.setText(key);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(24);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setStateListAnimator(null);
        button.setElevation(0f);
        button.setBackgroundResource(R.drawable.bg_dial_key);

        boolean special = key.equals("*") || key.equals("#");

        button.setTextColor(context.getColor(
                special ? R.color.accent : R.color.text_primary));

        button.setOnClickListener(v -> {

            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);

            if (listener != null) {
                listener.onKey(key);
            }
        });

        return button;
    }

    private LayoutParams keyParams() {

        int margin = (int) (5 * getResources().getDisplayMetrics().density);
        int height = (int) (54 * getResources().getDisplayMetrics().density);

        LayoutParams params = new LayoutParams(0, height, 1f);
        params.setMargins(margin, margin, margin, margin);

        return params;
    }
}
