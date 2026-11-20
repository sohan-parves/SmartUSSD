package com.sohan.smartussd.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sohan.smartussd.R;

/**
 * Full-page custom screen for timeouts and failures: pulsing status
 * icon, title, message, a "session reset" note and two actions.
 */
public class ErrorView extends ScrollView {

    public enum Kind { TIMEOUT, ERROR, INFO }

    private final View pulse;
    private final View ring;
    private final ImageView icon;
    private final TextView title;
    private final TextView message;
    private final TextView note;
    private final Button primary;
    private final Button secondary;
    private final LinearLayout steps;

    private AnimatorSet pulseAnimator;

    public ErrorView(Context context) {
        this(context, null);
    }

    public ErrorView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setFillViewport(true);
        setOverScrollMode(OVER_SCROLL_NEVER);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER);

        int pad = (int) dp(28);
        content.setPadding(pad, pad, pad, pad);

        addView(content, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LayoutInflater.from(context).inflate(R.layout.view_error, content, true);

        steps = findViewById(R.id.errorSteps);

        pulse = findViewById(R.id.errorPulse);
        ring = findViewById(R.id.errorRing);
        icon = findViewById(R.id.errorIcon);
        title = findViewById(R.id.errorTitle);
        message = findViewById(R.id.errorMessage);
        note = findViewById(R.id.errorNote);
        primary = findViewById(R.id.errorPrimary);
        secondary = findViewById(R.id.errorSecondary);
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void show(
            Kind kind,
            String titleText,
            String messageText,
            String noteText,
            String primaryLabel,
            Runnable onPrimary,
            String secondaryLabel,
            Runnable onSecondary
    ) {
        show(kind, titleText, messageText, noteText, null,
                primaryLabel, onPrimary, secondaryLabel, onSecondary);
    }

    /** @param guide optional numbered how-to steps shown in a card */
    public void show(
            Kind kind,
            String titleText,
            String messageText,
            String noteText,
            String[] guide,
            String primaryLabel,
            Runnable onPrimary,
            String secondaryLabel,
            Runnable onSecondary
    ) {

        boolean timeout = kind == Kind.TIMEOUT;

        int accentRes = kind == Kind.TIMEOUT ? R.color.warning
                : kind == Kind.INFO ? R.color.accent : R.color.error;
        int softRes = kind == Kind.TIMEOUT ? R.color.warning_soft
                : kind == Kind.INFO ? R.color.accent_soft : R.color.error_soft;

        int accent = getContext().getColor(accentRes);
        int soft = getContext().getColor(softRes);

        bindGuide(guide);

        scrollTo(0, 0);

        ring.setBackground(circle(soft, accent, 2));
        pulse.setBackground(circle(0x00000000, accent, 2));

        icon.setImageResource(timeout ? R.drawable.ic_timeout
                : kind == Kind.INFO ? R.drawable.ic_info : R.drawable.ic_error);
        icon.setImageTintList(ColorStateList.valueOf(accent));

        title.setText(titleText);
        message.setText(messageText);

        if (noteText == null || noteText.isEmpty()) {
            note.setVisibility(GONE);
        } else {
            note.setText(noteText);
            note.setVisibility(VISIBLE);
        }

        bindButton(primary, primaryLabel, onPrimary);
        bindButton(secondary, secondaryLabel, onSecondary);

        animate().cancel();
        setAlpha(0f);
        setVisibility(VISIBLE);
        animate().alpha(1f).setDuration(260).start();

        // Icon pops in, then the ring keeps a calm pulse going.
        icon.setScaleX(0.4f);
        icon.setScaleY(0.4f);
        icon.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.2f))
                .start();

        startPulse();
    }

    public void hide() {

        if (getVisibility() != VISIBLE) {
            return;
        }

        stopPulse();

        animate().cancel();
        animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> setVisibility(GONE))
                .start();
    }

    /** Hides at once, without animation (used when state is reset). */
    public void hideNow() {
        stopPulse();
        animate().cancel();
        setVisibility(GONE);
    }

    private void bindGuide(String[] guide) {

        steps.removeAllViews();

        if (guide == null || guide.length == 0) {
            steps.setVisibility(GONE);
            return;
        }

        steps.setVisibility(VISIBLE);

        Context c = getContext();

        for (int i = 0; i < guide.length; i++) {

            // RelativeLayout sizes the wrapped text at its real width in
            // one pass, so multi-line steps are never clipped.
            RelativeLayout row = new RelativeLayout(c);

            TextView badge = new TextView(c);
            badge.setId(View.generateViewId());
            badge.setText(String.valueOf(i + 1));
            badge.setGravity(Gravity.CENTER);
            badge.setTextColor(c.getColor(R.color.on_accent));
            badge.setTextSize(13);
            badge.setTypeface(badge.getTypeface(), android.graphics.Typeface.BOLD);
            badge.setBackgroundResource(R.drawable.bg_menu_number);

            row.addView(badge, new RelativeLayout.LayoutParams(
                    (int) dp(28), (int) dp(28)));

            TextView t = new TextView(c);
            t.setText(guide[i]);
            t.setTextColor(c.getColor(R.color.text_body));
            t.setTextSize(14);
            t.setLineSpacing(dp(3), 1f);
            t.setIncludeFontPadding(true);

            RelativeLayout.LayoutParams tp = new RelativeLayout.LayoutParams(
                    RelativeLayout.LayoutParams.MATCH_PARENT,
                    RelativeLayout.LayoutParams.WRAP_CONTENT);
            tp.addRule(RelativeLayout.END_OF, badge.getId());
            tp.setMarginStart((int) dp(12));
            tp.topMargin = (int) dp(2);

            row.addView(t, tp);

            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);

            if (i > 0) {
                rp.topMargin = (int) dp(14);
            }

            steps.addView(row, rp);
        }
    }

    private void bindButton(Button b, String label, Runnable action) {

        if (label == null || action == null) {
            b.setVisibility(GONE);
            return;
        }

        b.setText(label);
        b.setVisibility(VISIBLE);
        b.setOnClickListener(v -> action.run());
    }

    private GradientDrawable circle(int fill, int stroke, int strokeDp) {

        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        d.setStroke((int) dp(strokeDp), stroke);
        return d;
    }

    private void startPulse() {

        stopPulse();

        ObjectAnimator sx = ObjectAnimator.ofFloat(pulse, View.SCALE_X, 1f, 1.45f);
        ObjectAnimator sy = ObjectAnimator.ofFloat(pulse, View.SCALE_Y, 1f, 1.45f);
        ObjectAnimator a = ObjectAnimator.ofFloat(pulse, View.ALPHA, 0.55f, 0f);

        for (ObjectAnimator o : new ObjectAnimator[]{sx, sy, a}) {
            o.setRepeatCount(ValueAnimator.INFINITE);
        }

        pulseAnimator = new AnimatorSet();
        pulseAnimator.playTogether(sx, sy, a);
        pulseAnimator.setDuration(1800);
        pulseAnimator.start();
    }

    private void stopPulse() {

        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        stopPulse();
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
