package com.sohan.smartussd.adapters;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sohan.smartussd.R;
import com.sohan.smartussd.models.USSDMenuItem;

import java.util.ArrayList;
import java.util.List;

public class USSDMenuAdapter {

    public interface OnMenuClickListener {
        void onMenuClick(USSDMenuItem item);
    }

    private final Context context;
    private final LinearLayout container;

    /** Optional separate holder for Back / Main (pinned to the bottom). */
    private LinearLayout navContainer;

    public USSDMenuAdapter(
            Context context,
            LinearLayout container
    ) {
        this.context = context;
        this.container = container;
    }

    /** Back / Main / Next go here instead of under the options. */
    public void setNavContainer(LinearLayout nav) {
        this.navContainer = nav;
    }

    public void setItems(
            List<USSDMenuItem> items,
            OnMenuClickListener listener
    ) {

        container.removeAllViews();

        if (navContainer != null) {
            navContainer.removeAllViews();
        }

        List<USSDMenuItem> regular = new ArrayList<>();
        List<USSDMenuItem> navigation = new ArrayList<>();

        for (USSDMenuItem item : items) {
            (item.isNavigation() ? navigation : regular).add(item);
        }

        int index = 0;

        for (USSDMenuItem item : regular) {

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(R.drawable.bg_menu_item);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setClickable(true);
            row.setFocusable(true);

            // Number badge
            TextView badge = new TextView(context);
            badge.setText(item.getNumber());
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(context.getColor(R.color.on_accent));
            badge.setTextSize(17);
            badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
            badge.setBackgroundResource(R.drawable.bg_menu_number);
            badge.setMinWidth(dp(40));
            badge.setMinHeight(dp(40));
            badge.setPadding(dp(4), 0, dp(4), 0);

            // Title
            TextView title = new TextView(context);
            title.setText(item.getTitle());
            title.setTextColor(context.getColor(R.color.text_primary));
            title.setTextSize(16);
            title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

            LinearLayout.LayoutParams titleParams =
                    new LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1f
                    );
            titleParams.setMarginStart(dp(14));
            titleParams.setMarginEnd(dp(8));

            // Chevron
            ImageView chevron = new ImageView(context);
            chevron.setImageResource(R.drawable.ic_chevron_right);
            chevron.setColorFilter(context.getColor(R.color.accent));
            chevron.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            row.addView(badge, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            row.addView(title, titleParams);
            row.addView(chevron, new LinearLayout.LayoutParams(dp(22), dp(22)));

            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                    );

            params.bottomMargin = dp(10);

            row.setLayoutParams(params);

            pressable(row);

            row.setOnClickListener(v -> {

                v.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);

                if (listener != null) {
                    listener.onMenuClick(item);
                }
            });

            // Quick staggered fade + slide-in (capped so long menus stay fast)
            row.setAlpha(0f);
            row.setTranslationY(dp(10));

            row.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(30L * Math.min(index, 6))
                    .setDuration(200)
                    .start();

            container.addView(row);

            index++;
        }

        addNavigationRow(navigation, listener, index);
    }

    /** Back / Next / Home options as a compact row of buttons. */
    private void addNavigationRow(
            List<USSDMenuItem> navigation,
            OnMenuClickListener listener,
            int index
    ) {

        if (navContainer != null) {
            navContainer.setVisibility(
                    navigation.isEmpty() ? View.GONE : View.VISIBLE);
        }

        if (navigation.isEmpty()) {
            return;
        }

        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);

        for (int i = 0; i < navigation.size(); i++) {

            USSDMenuItem item = navigation.get(i);

            TextView button = new TextView(context);

            button.setText(label(item));
            button.setGravity(Gravity.CENTER);
            button.setTextColor(context.getColor(R.color.accent));
            button.setTextSize(15);
            button.setTypeface(button.getTypeface(), android.graphics.Typeface.BOLD);
            button.setMaxLines(2);
            button.setIncludeFontPadding(false);
            button.setPadding(dp(6), dp(8), dp(6), dp(8));
            button.setBackgroundResource(R.drawable.bg_button_highlight);
            button.setClickable(true);
            button.setFocusable(true);

            // Same fixed height for every button, so they line up.
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            0,
                            dp(64),
                            1f
                    );

            if (i > 0) {
                params.setMarginStart(dp(10));
            }

            pressable(button);

            button.setOnClickListener(v -> {

                v.performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);

                if (listener != null) {
                    listener.onMenuClick(item);
                }
            });

            row.addView(button, params);
        }

        row.setAlpha(0f);
        row.setTranslationY(dp(10));

        row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(30L * Math.min(index, 6))
                .setDuration(200)
                .start();

        (navContainer != null ? navContainer : container).addView(
                row, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** Soft "press in" effect so every tap feels instant. */
    @SuppressLint("ClickableViewAccessibility")
    private void pressable(View v) {

        v.setOnTouchListener((view, e) -> {

            switch (e.getActionMasked()) {

                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.97f).scaleY(0.97f)
                            .setDuration(80).start();
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(120).start();
                    break;

                default:
                    break;
            }

            return false;
        });
    }

    /**
     * Two fixed lines so every navigation button looks the same:
     * the title on top, the key to press (0, #, *, +) below it.
     */
    private CharSequence label(USSDMenuItem item) {

        String prefix = item.isBack() ? "\u2039  "
                : item.getNumber().equals("+") ? "\u00AB  " : "";
        String suffix = item.getNumber().equals("*") ? "  \u203A" : "";

        String text = prefix + item.getTitle() + suffix + "\n";
        String number = item.getNumber();

        SpannableString out = new SpannableString(text + number);

        int start = text.length();
        int end = out.length();

        out.setSpan(new ForegroundColorSpan(
                        context.getColor(R.color.warning)),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new StyleSpan(Typeface.BOLD),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.3f),
                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        return out;
    }

    private int dp(float value) {

        return (int) (
                value * context.getResources()
                        .getDisplayMetrics().density
        );
    }
}