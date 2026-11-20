package com.sohan.smartussd.ui;

import android.animation.ObjectAnimator;
import android.view.HapticFeedbackConstants;
import android.view.View;

/** Quiet feedback for things that went wrong: a shake and a buzz, no text. */
public final class Feedback {

    private Feedback() {
    }

    public static void reject(View view) {

        float d = view.getResources().getDisplayMetrics().density;

        ObjectAnimator.ofFloat(
                view, View.TRANSLATION_X,
                0f, -14f * d, 14f * d, -10f * d, 10f * d, -5f * d, 5f * d, 0f)
                .setDuration(380)
                .start();

        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
    }
}
