package com.sohan.smartussd.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.sohan.smartussd.R;

import java.util.Random;

/**
 * Card-style loading block: animated loader + message with animated dots.
 * Call {@link #show(String)} / {@link #hide()}.
 */
public class LoadingView extends LinearLayout {

    private final TextView title;

    private final TextView subtitle;

    private final TextView cancelButton;

    private final View pulseLoader;
    private final View skeletonBox;
    private final View choiceGroup;
    private final TextView choiceBadge;
    private final View choiceHalo;
    private final View choiceSweep;

    private ObjectAnimator sweepAnimator;
    private ObjectAnimator haloAnimator;

    private boolean choiceMode = false;

    private final View progressTrack;
    private final View progressFill;
    private final TextView tipText;
    private final View stepsBox;
    private final TextView step1, step2, step3;

    private ValueAnimator progressAnimator;

    /* ---- background motion: radar ripples + drifting particles ---- */

    private static final int PARTICLES = 16;

    private final Paint fxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] partX = new float[PARTICLES];
    private final float[] partOffset = new float[PARTICLES];
    private final float[] partSpeed = new float[PARTICLES];
    private final float[] partSize = new float[PARTICLES];

    private ValueAnimator fxAnimator;
    private float fxT = 0f;

    private long shownAt = 0;
    private int tipIndex = 0;
    private String stageText = "";
    private boolean ticking = false;

    /** Friendly things to read while the network thinks. */
    private static final String[] TIPS = {
            "Tip: tap a menu card, no need to type the number",
            "Tip: # usually goes back and + opens the main menu",
            "USSD works even without mobile data",
            "Your last used SIM is remembered for next time",
            "Tip: long-press \u232B to clear the whole code at once"
    };

    /** Second line, by how long we have been waiting (seconds). */
    private static String stageFor(long seconds) {

        if (seconds < 4) return "This usually takes a few seconds";
        if (seconds < 9) return "Waiting for your operator to reply";
        if (seconds < 16) return "The network is a bit busy \u2014 still on it";

        return "Almost there, thanks for your patience";
    }

    /** 1 tick per second: stage text, elapsed hint and the tip rotation. */
    private final Runnable ticker = new Runnable() {

        @Override
        public void run() {

            if (!ticking) {
                return;
            }

            long seconds = (System.currentTimeMillis() - shownAt) / 1000;

            renderSteps(seconds);

            String stage = stageFor(seconds);

            if (!stage.equals(stageText)) {
                stageText = stage;
                swapText(subtitle, stage);
            }

            if (seconds > 0 && seconds % 5 == 0) {
                tipIndex = (tipIndex + 1) % TIPS.length;
                swapText(tipText, TIPS[tipIndex]);
            }

            postDelayed(this, 1000);
        }
    };

    private void startFx() {

        stopFx();

        fxAnimator = ValueAnimator.ofFloat(0f, 1f);
        fxAnimator.setDuration(3600);
        fxAnimator.setInterpolator(new LinearInterpolator());
        fxAnimator.setRepeatCount(ValueAnimator.INFINITE);
        fxAnimator.addUpdateListener(a -> {
            fxT = (float) a.getAnimatedValue();
            invalidate();
        });
        fxAnimator.start();
    }

    private void stopFx() {

        if (fxAnimator != null) {
            fxAnimator.cancel();
            fxAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas c) {

        super.onDraw(c);

        if (fxAnimator == null || getWidth() == 0) {
            return;
        }

        final int w = getWidth();
        final int h = getHeight();

        final View anchor = choiceMode ? choiceGroup : pulseLoader;

        final float cx = anchor.getLeft() + anchor.getWidth() / 2f;
        final float cy = anchor.getTop() + anchor.getHeight() / 2f;

        final int accent = getContext().getColor(R.color.accent) & 0x00FFFFFF;

        // Radar ripples growing out of the loader
        fxPaint.setStyle(Paint.Style.STROKE);
        fxPaint.setStrokeWidth(dp(1.5f));

        float start = anchor.getWidth() * 0.55f;
        float end = Math.min(w, h) * 0.52f;

        for (int i = 0; i < 3; i++) {

            float ph = (fxT + i / 3f) % 1f;
            float eased = 1f - (1f - ph) * (1f - ph);

            fxPaint.setColor(((int) (60 * (1f - ph)) << 24) | accent);

            c.drawCircle(cx, cy, start + (end - start) * eased, fxPaint);
        }

        // Soft particles drifting upward
        fxPaint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < PARTICLES; i++) {

            float y = ((partOffset[i] - fxT * partSpeed[i]) % 1f + 1f) % 1f;

            float x = partX[i] * w + (float) Math.sin(
                    fxT * 2 * Math.PI * partSpeed[i] + i) * dp(10);

            int alpha = (int) (80 * Math.sin(Math.PI * y));

            fxPaint.setColor((alpha << 24) | accent);

            c.drawCircle(x, y * h, partSize[i], fxPaint);
        }
    }

    /** 0 = waiting, 1 = in progress, 2 = done. */
    private void setStep(TextView t, String label, int state) {

        String icon = state == 2 ? "\u2713" : state == 1 ? "\u25CF" : "\u25CB";

        t.setText(icon + "   " + label);

        Object prev = t.getTag();

        if (prev == null || (Integer) prev != state) {

            t.setTag(state);

            // A finished step pops, so the list feels alive.
            if (prev != null && state == 2) {

                t.setPivotX(0f);
                t.setPivotY(t.getHeight() / 2f);
                t.setScaleX(1.18f);
                t.setScaleY(1.18f);

                t.animate().scaleX(1f).scaleY(1f).setDuration(320)
                        .setInterpolator(
                                new android.view.animation.OvershootInterpolator(2f))
                        .start();
            }
        }

        t.setTextColor(getContext().getColor(
                state == 2 ? R.color.success
                        : state == 1 ? R.color.text_primary
                        : R.color.text_faint));
    }

    /** Checklist that ticks itself off while the network works. */
    private void renderSteps(long s) {

        setStep(step1, "Request sent", 2);

        setStep(step2, "Reaching your operator", s >= 3 ? 2 : 1);

        setStep(step3,
                s >= 3 ? "Waiting for the reply  \u00B7  " + s + "s"
                        : "Waiting for the reply",
                s >= 3 ? 1 : 0);
    }

    /** Quick cross-fade so changing text never jumps. */
    private void swapText(TextView view, String text) {

        view.animate().cancel();

        view.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            view.setText(text);
            view.animate().alpha(1f).setDuration(200).start();
        }).start();
    }

    private void startExtras() {

        stopExtras();

        shownAt = System.currentTimeMillis();
        stageText = stageFor(0);
        tipIndex = (int) (shownAt / 1000 % TIPS.length);

        tipText.animate().cancel();
        tipText.setAlpha(1f);
        tipText.setText(TIPS[tipIndex]);

        progressTrack.setVisibility(VISIBLE);
        stepsBox.setVisibility(VISIBLE);
        tipText.setVisibility(VISIBLE);

        step1.setTag(null);
        step2.setTag(null);
        step3.setTag(null);

        renderSteps(0);

        // Eases toward the end but never quite arrives: it always feels
        // like something is moving, even on a slow network.
        progressFill.setPivotX(0f);
        progressFill.setScaleX(0.04f);

        progressAnimator = ValueAnimator.ofFloat(0.04f, 0.95f);
        progressAnimator.setDuration(30_000);
        progressAnimator.setInterpolator(
                new android.view.animation.DecelerateInterpolator(2.2f));
        progressAnimator.addUpdateListener(a ->
                progressFill.setScaleX((float) a.getAnimatedValue()));
        progressAnimator.start();

        ticking = true;
        removeCallbacks(ticker);
        postDelayed(ticker, 1000);
    }

    private void stopExtras() {

        ticking = false;
        removeCallbacks(ticker);

        if (progressAnimator != null) {
            progressAnimator.cancel();
            progressAnimator = null;
        }

        tipText.animate().cancel();
        subtitle.animate().cancel();
        subtitle.setAlpha(1f);
    }

    private void hideExtras() {

        stopExtras();

        progressTrack.setVisibility(GONE);
        stepsBox.setVisibility(GONE);
        tipText.setVisibility(GONE);
    }

    private ValueAnimator dotsAnimator;

    private final View[] bars;

    private final ObjectAnimator[] barAnimators = new ObjectAnimator[3];

    private String message = "Loading";

    public LoadingView(Context context) {
        this(context, null);
    }

    public LoadingView(Context context, AttributeSet attrs) {
        super(context, attrs);

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        LayoutInflater.from(context)
                .inflate(R.layout.view_loading, this, true);

        title = findViewById(R.id.loadingTitle);
        subtitle = findViewById(R.id.loadingSubtitle);

        cancelButton = findViewById(R.id.loadingCancel);

        setWillNotDraw(false);

        Random rnd = new Random(7);

        for (int i = 0; i < PARTICLES; i++) {
            partX[i] = rnd.nextFloat();
            partOffset[i] = rnd.nextFloat();
            partSpeed[i] = 1 + rnd.nextInt(2);          // 1 or 2 loops
            partSize[i] = dp(1.5f) + rnd.nextFloat() * dp(2.5f);
        }

        progressTrack = findViewById(R.id.progressTrack);
        progressFill = findViewById(R.id.progressFill);
        tipText = findViewById(R.id.tipText);
        stepsBox = findViewById(R.id.stepsBox);
        step1 = findViewById(R.id.step1);
        step2 = findViewById(R.id.step2);
        step3 = findViewById(R.id.step3);

        pulseLoader = findViewById(R.id.pulseLoader);
        skeletonBox = findViewById(R.id.skeletonBox);
        choiceGroup = findViewById(R.id.choiceGroup);
        choiceBadge = findViewById(R.id.choiceBadge);
        choiceHalo = findViewById(R.id.choiceHalo);
        choiceSweep = findViewById(R.id.choiceSweep);

        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor(Color.TRANSPARENT);
        halo.setStroke((int) dp(2), context.getColor(R.color.accent));
        choiceHalo.setBackground(halo);

        bars = new View[]{
                findViewById(R.id.skeleton1),
                findViewById(R.id.skeleton2),
                findViewById(R.id.skeleton3)
        };
    }

    /** Replaces the grey second line under the loader title. */
    public void setSubtitle(String text) {
        subtitle.setText(text);
    }

    /** Shows a Cancel button under the loader; null hides it. */
    public void setCancelAction(Runnable action) {

        if (action == null) {
            cancelButton.setVisibility(GONE);
            return;
        }

        cancelButton.setVisibility(VISIBLE);
        cancelButton.setOnClickListener(v -> action.run());
    }

    /** Loader 1: dialling a code / contacting the network. */
    public void show(String message) {

        choiceMode = false;

        pulseLoader.setVisibility(VISIBLE);
        skeletonBox.setVisibility(VISIBLE);
        choiceGroup.setVisibility(GONE);

        present(message, "This usually takes a few seconds");
    }

    /**
     * Loader 2: a menu choice or reply is being sent. Shows the picked
     * option in a glowing badge with a sweeping progress bar.
     */
    public void showChoice(String choice) {

        choiceMode = true;

        pulseLoader.setVisibility(GONE);
        skeletonBox.setVisibility(GONE);
        choiceGroup.setVisibility(VISIBLE);

        String label = choice == null ? "" : choice.trim();

        if (label.length() > 4) {
            label = label.substring(0, 3) + "…";
        }

        choiceBadge.setText(label);

        present("Sending option", "Waiting for the next menu");
    }

    private void present(String message, String subtitleText) {

        this.message = message;

        animate().cancel();

        if (getVisibility() != VISIBLE) {
            setAlpha(0f);
            setTranslationY(dp(10));
            setVisibility(VISIBLE);
        }

        animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .start();

        subtitle.setText(subtitleText);

        startDots();
        startFx();

        if (choiceMode) {
            hideExtras();
            stopBars();
            startChoice();
        } else {
            startExtras();
            stopChoice();
            startBars();
        }
    }

    public boolean isShowing() {
        return getVisibility() == VISIBLE;
    }

    public void hide() {

        if (getVisibility() != VISIBLE) {
            return;
        }

        stopExtras();

        animate().cancel();

        animate()
                .alpha(0f)
                .translationY(dp(6))
                .setDuration(180)
                .withEndAction(() -> {
                    setVisibility(GONE);
                    stopDots();
                    stopBars();
                    stopChoice();
                })
                .start();
    }

    /** Hides at once, without the fade (used when state is reset). */
    public void hideNow() {

        stopExtras();
        animate().cancel();
        setVisibility(GONE);
        stopDots();
        stopBars();
        stopChoice();
    }

    private void startChoice() {

        stopChoice();

        sweepAnimator = ObjectAnimator.ofFloat(
                choiceSweep, View.TRANSLATION_X, 0f, dp(120));
        sweepAnimator.setDuration(900);
        sweepAnimator.setRepeatCount(ValueAnimator.INFINITE);
        sweepAnimator.setRepeatMode(ValueAnimator.REVERSE);
        sweepAnimator.start();

        android.animation.PropertyValuesHolder sx =
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.35f);
        android.animation.PropertyValuesHolder sy =
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.35f);
        android.animation.PropertyValuesHolder al =
                android.animation.PropertyValuesHolder.ofFloat(View.ALPHA, 0.8f, 0f);

        haloAnimator = ObjectAnimator.ofPropertyValuesHolder(choiceHalo, sx, sy, al);
        haloAnimator.setDuration(1200);
        haloAnimator.setRepeatCount(ValueAnimator.INFINITE);
        haloAnimator.start();
    }

    private void stopChoice() {

        if (sweepAnimator != null) {
            sweepAnimator.cancel();
            sweepAnimator = null;
        }

        if (haloAnimator != null) {
            haloAnimator.cancel();
            haloAnimator = null;
        }
    }

    private void startDots() {

        stopDots();

        dotsAnimator = ValueAnimator.ofInt(0, 4);
        dotsAnimator.setDuration(1400);
        dotsAnimator.setInterpolator(new LinearInterpolator());
        dotsAnimator.setRepeatCount(ValueAnimator.INFINITE);
        dotsAnimator.addUpdateListener(a ->
                renderDots((int) a.getAnimatedValue()));
        dotsAnimator.start();

        renderDots(0);
    }

    /** Softly pulsing placeholder lines, one after the other. */
    private void startBars() {

        stopBars();

        for (int i = 0; i < bars.length; i++) {

            ObjectAnimator a = ObjectAnimator.ofFloat(
                    bars[i], View.ALPHA, 0.25f, 0.9f);

            a.setDuration(900);
            a.setStartDelay(i * 180L);
            a.setRepeatCount(ValueAnimator.INFINITE);
            a.setRepeatMode(ValueAnimator.REVERSE);
            a.start();

            barAnimators[i] = a;
        }
    }

    private void stopBars() {

        for (int i = 0; i < barAnimators.length; i++) {

            if (barAnimators[i] != null) {
                barAnimators[i].cancel();
                barAnimators[i] = null;
            }

            bars[i].setAlpha(1f);
        }
    }

    private void stopDots() {

        stopFx();

        if (dotsAnimator != null) {
            dotsAnimator.cancel();
            dotsAnimator = null;
        }
    }

    /** Shows 0..3 dots; unseen dots are transparent so text never jumps. */
    private void renderDots(int visibleDots) {

        int shown = Math.min(visibleDots, 3);

        SpannableString text = new SpannableString(message + "...");

        int start = message.length() + shown;

        if (start < text.length()) {
            text.setSpan(
                    new ForegroundColorSpan(Color.TRANSPARENT),
                    start,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );
        }

        title.setText(text);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopExtras();
        stopDots();
        stopBars();
        stopChoice();
        super.onDetachedFromWindow();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
