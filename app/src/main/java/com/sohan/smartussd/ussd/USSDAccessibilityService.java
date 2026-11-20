package com.sohan.smartussd.ussd;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.sohan.smartussd.R;
import com.sohan.smartussd.adapters.USSDMenuAdapter;
import com.sohan.smartussd.models.USSDMenuItem;
import com.sohan.smartussd.ui.BodyRenderer;
import com.sohan.smartussd.ui.DialPadView;
import com.sohan.smartussd.ui.LoadingView;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps the system USSD dialog open, reads its text, and shows the reply
 * as a Smart USSD overlay with tappable menu buttons. A tap types the
 * number into the hidden dialog and presses Send.
 */
public class USSDAccessibilityService extends AccessibilityService {

    public interface Listener {
        void onUssdText(String text);
    }

    /** Called when the session ends from inside the overlay. */
    public interface EndListener {
        /** @param timedOut true when the network never answered in time */
        void onSessionEnd(boolean timedOut);
    }

    private static volatile Listener listener;
    private static volatile EndListener endListener;
    private static volatile USSDAccessibilityService instance;

    /** Last time one of OUR sessions started, ended or was cancelled. */
    private static volatile long lastOwnActivity = 0;

    /** Our sessions left a dialog behind only within this window. */
    private static final long GUARD_WINDOW_MS = 120_000;

    private static void touch() {
        lastOwnActivity = System.currentTimeMillis();
    }

    /** Bumped for every new session so a slow close never hits the next one. */
    private static volatile int sessionGen = 0;

    /**
     * Covers the screen with the Smart USSD loader right away, so the
     * system "Carrier info" dialog is never visible.
     */
    public static void showCover() {

        final USSDAccessibilityService s = instance;

        touch();

        if (s != null) {
            s.handler.post(() -> {

                if (s.overlay == null) {
                    s.buildOverlay();
                }

                s.showLoader("Contacting network");
            });
        }
    }

    public static void setListener(Listener l) {

        listener = l;

        touch();

        if (l != null) {
            sessionGen++;
        }

        USSDAccessibilityService s = instance;

        if (l != null && s != null) {
            // New session: forget the previous dialog text so an identical
            // first menu is not mistaken for a duplicate event.
            s.lastText = "";
            s.lastReplyTime = 0;
        }
    }

    public static void setEndListener(EndListener l) {
        endListener = l;
    }

    public static boolean hasActiveSession() {
        return listener != null && instance != null;
    }

    public static boolean isEnabled(Context context) {

        String enabled = Settings.Secure.getString(
                context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (TextUtils.isEmpty(enabled)) {
            return false;
        }

        String me = new ComponentName(
                context, USSDAccessibilityService.class
        ).flattenToString();

        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me)) {
                return true;
            }
        }

        return false;
    }

    /** Type a menu number / text into the system dialog and press Send. */
    public static boolean reply(String text) {

        USSDAccessibilityService s = instance;

        return s != null && s.performReply(text);
    }

    /**
     * Closes the stuck system dialog while the loader stays on screen
     * (no overlay removal, no "bring app to front"), then runs
     * {@code afterClosed}. Used for the silent auto-retry.
     */
    public static void restartQuietly(Runnable afterClosed) {

        USSDAccessibilityService s = instance;

        if (s == null) {
            listener = null;
            afterClosed.run();
            return;
        }

        s.quietRestart(afterClosed);
    }

    /**
     * Closes a system USSD dialog ("Carrier info") that is still open
     * after a session ended, timed out or failed. Does nothing while a
     * session is running, or when no such dialog exists.
     */
    public static void closeLeftoverDialog() {

        USSDAccessibilityService s = instance;

        if (s != null) {
            s.sweepLeftover();
        }
    }

    /** Cancel the running USSD session and remove the overlay. */
    public static void cancelSession() {

        USSDAccessibilityService s = instance;

        if (s != null) {
            s.endSession(true, false);
        } else {
            listener = null;
        }
    }

    /* ------------------------------------------------------------ */

    private final Handler handler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private View overlay;
    private LinearLayout menuBox;
    private LinearLayout bodyBox;
    private LinearLayout inputBox;
    private TextView inputDisplay;
    private boolean ending = false;
    private LinearLayout actionBox;
    private LinearLayout navBox;
    private LinearLayout bottomBar;
    private FrameLayout loadingHolder;
    private LoadingView loadingView;
    private View contentColumn;
    private USSDMenuAdapter menuAdapter;

    /** Network never answered: close everything and report a timeout. */
    private final Runnable loaderTimeout = () -> endSession(true, true);

    private volatile String lastText = "";
    private volatile long lastReplyTime = 0;

    private final USSDResponseParser parser = new USSDResponseParser();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        handler.removeCallbacks(scanDialog);
        removeOverlay();
        instance = null;
        listener = null;
        endListener = null;
        return super.onUnbind(intent);
    }

    private boolean scanQueued = false;
    private boolean guardQueued = false;
    private boolean sweeping = false;

    private final Runnable scanDialog = () -> {
        scanQueued = false;
        processDialog();
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {

        if (event == null) {
            return;
        }

        if (!isPhonePackage(event.getPackageName())) {
            return;
        }

        // No session of ours is running, yet a system USSD dialog opened
        // (a late network reply, a dialog that would not close): cover and
        // close it so the phone's popup is never seen.
        if (listener == null) {

            if (!ending && !sweeping && !guardQueued
                    && System.currentTimeMillis() - lastOwnActivity
                    < GUARD_WINDOW_MS) {

                guardQueued = true;

                handler.postDelayed(() -> {
                    guardQueued = false;
                    sweepLeftover();
                }, 10);
            }

            return;
        }

        // The system fires bursts of events per dialog change; read the
        // dialog once per burst, after it settles.
        if (!scanQueued) {
            scanQueued = true;
            handler.postDelayed(scanDialog, 15);
        }
    }

    private void processDialog() {

        if (listener == null) {
            return;
        }

        AccessibilityNodeInfo root = findDialogRoot();

        if (root == null) {
            return;
        }

        List<String> texts = new ArrayList<>();
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        AccessibilityNodeInfo[] edit = new AccessibilityNodeInfo[1];

        collect(root, texts, buttons, edit);

        String message = TextUtils.join("\n", texts).trim();

        if (message.isEmpty()) {
            return;
        }

        // "USSD code running..." is only a waiting message, not a reply:
        // keep the full-page loader up instead of showing it as a result.
        if (isWaitingText(message)) {

            if (overlay == null) {
                buildOverlay();
            }

            if (loadingHolder != null
                    && loadingHolder.getVisibility() != View.VISIBLE) {
                showLoader("Contacting network");
            }

            return;
        }

        // The network refused the code: hand it to the app as a failure
        // and close quietly behind the loader.
        if (USSDManager.isNetworkError(message)) {

            Listener el = listener;

            if (el != null) {
                el.onUssdText(message);
            }

            if (overlay == null) {
                buildOverlay();
            }

            showLoader("Please wait");
            return;
        }

        long now = System.currentTimeMillis();

        if (message.equals(lastText) && now - lastReplyTime > 0
                && now - lastReplyTime < 2500 && lastReplyTime != 0) {
            // Dialog has not changed yet after our reply
            return;
        }

        if (message.equals(lastText) && lastReplyTime == 0) {
            return; // duplicate event for the same dialog
        }

        lastText = message;
        lastReplyTime = 0;

        List<USSDMenuItem> items = parser.parse(message);

        boolean hasInput = edit[0] != null;

        Listener l = listener;

        if (l != null) {
            l.onUssdText(message);
        }

        // Back / Next / Home / Main are navigation, not answers: a page
        // with only those (e.g. "Enter Amount  # Back") still needs typing.
        boolean hasChoices = false;

        for (USSDMenuItem item : items) {
            if (!item.isNavigation()) {
                hasChoices = true;
                break;
            }
        }

        // Free text (amount, PIN...) is typed on our own keypad, so the
        // native dialog never has to be shown.
        showOverlay(message, items, !hasInput, hasInput && !hasChoices);
    }

    /* ---------------- Reply / cancel ---------------- */

    private boolean performReply(String text) {

        AccessibilityNodeInfo root = findDialogRoot();

        if (root == null) {
            return false;
        }

        List<String> texts = new ArrayList<>();
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        AccessibilityNodeInfo[] edit = new AccessibilityNodeInfo[1];

        collect(root, texts, buttons, edit);

        if (edit[0] == null) {
            return false;
        }

        Bundle args = new Bundle();

        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
        );

        edit[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);

        AccessibilityNodeInfo send = null;

        for (AccessibilityNodeInfo b : buttons) {

            if (!isCancelText(b.getText())) {
                send = b;
                break;
            }
        }

        if (send == null) {
            return false;
        }

        final AccessibilityNodeInfo sendButton = send;

        lastReplyTime = System.currentTimeMillis();

        handler.postDelayed(() ->
                sendButton.performAction(AccessibilityNodeInfo.ACTION_CLICK),
                20
        );

        return true;
    }

    private void sweepLeftover() {

        // A live session (or a close already in progress) owns the dialog.
        if (ending || sweeping || listener != null) {
            return;
        }

        AccessibilityNodeInfo root = findDialogRoot();

        if (root == null) {
            return;
        }

        List<String> texts = new ArrayList<>();
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        AccessibilityNodeInfo[] edit = new AccessibilityNodeInfo[1];

        collect(root, texts, buttons, edit);

        // Only a real USSD dialog (it always has a Cancel / OK button);
        // never touch the phone's normal dialer screen.
        boolean looksLikeDialog = false;

        for (AccessibilityNodeInfo b : buttons) {
            CharSequence t = b.getText();
            if (isCancelText(t) || (t != null
                    && t.toString().equalsIgnoreCase("ok"))) {
                looksLikeDialog = true;
                break;
            }
        }

        if (!looksLikeDialog) {
            return;
        }

        final int gen = sessionGen;

        sweeping = true;
        touch();

        // Hide it behind the loader while it closes.
        if (overlay == null) {
            buildOverlay();
        }

        if (overlay != null) {
            showLoader("Closing session");
        }

        closeDialog(true, gen, 0, () -> {

            sweeping = false;

            if (gen != sessionGen) {
                // A new session started meanwhile: leave its cover alone.
                return;
            }

            // No bringAppToFront: if the person left the app, a late
            // dialog must not drag it back to the screen.
            removeOverlay();
        });
    }

    private void quietRestart(final Runnable afterClosed) {

        // Stop reading the old dialog; the loader cover stays up.
        listener = null;
        endListener = null;

        handler.removeCallbacks(scanDialog);
        scanQueued = false;

        if (overlay == null) {
            buildOverlay();
        }

        if (overlay != null) {
            showRetryLoader();
        }

        final int gen = sessionGen;

        closeDialog(true, gen, 0, () -> {

            lastText = "";
            lastReplyTime = 0;

            afterClosed.run();
        });
    }

    private void endSession(boolean cancelDialog) {
        endSession(cancelDialog, false);
    }

    private void endSession(boolean cancelDialog, boolean timedOut) {

        if (ending) {
            return;
        }

        ending = true;

        touch();

        handler.removeCallbacks(loaderTimeout);

        final int gen = sessionGen;

        // Stop reading events; keep the loader cover up while the system
        // dialog closes, so "Carrier info" never flashes on screen.
        listener = null;

        if (overlay != null) {
            showLoader("Closing session");
            handler.removeCallbacks(loaderTimeout);
        }

        closeDialog(cancelDialog, gen, 0, () -> {

            ending = false;

            if (gen != sessionGen) {
                // A new session already started: leave its cover alone.
                return;
            }

            removeOverlay();

            lastText = "";
            lastReplyTime = 0;

            EndListener end = endListener;
            endListener = null;

            bringAppToFront();

            if (end != null) {
                end.onSessionEnd(timedOut);
            }
        });
    }

    /** Clicks Cancel/OK until the system dialog is really gone. */
    private void closeDialog(
            boolean cancelDialog,
            int gen,
            int attempt,
            Runnable done
    ) {

        if (gen != sessionGen) {
            done.run();
            return;
        }

        AccessibilityNodeInfo root = findDialogRoot();

        if (root == null || attempt >= 40) {
            done.run();
            return;
        }

        List<String> texts = new ArrayList<>();
        List<AccessibilityNodeInfo> buttons = new ArrayList<>();
        AccessibilityNodeInfo[] edit = new AccessibilityNodeInfo[1];

        collect(root, texts, buttons, edit);

        boolean clicked = false;

        for (AccessibilityNodeInfo b : buttons) {

            if (isCancelText(b.getText()) == cancelDialog) {
                clicked = b.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                if (clicked) break;
            }
        }

        if (!clicked && !buttons.isEmpty()) {
            buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } else if (!clicked || attempt >= 3) {
            performGlobalAction(GLOBAL_ACTION_BACK);
        }

        handler.postDelayed(
                () -> closeDialog(cancelDialog, gen, attempt + 1, done),
                70);
    }

    private void bringAppToFront() {

        try {

            Intent i = getPackageManager()
                    .getLaunchIntentForPackage(getPackageName());

            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }

        } catch (Exception ignored) {
        }
    }

    /* ---------------- Overlay UI ---------------- */

    private void showOverlay(
            String message,
            List<USSDMenuItem> items,
            boolean finalMessage,
            boolean needsInput
    ) {

        if (windowManager == null) {
            return;
        }

        if (overlay == null) {
            buildOverlay();
        }

        if (overlay == null) {
            return;
        }

        // Only the needed text; the option lines become buttons.
        String body = items.isEmpty()
                ? message.trim()
                : parser.bodyText(message);

        BodyRenderer.render(this, bodyBox, body);

        inputBox.setVisibility(needsInput ? View.VISIBLE : View.GONE);
        inputDisplay.setText("");

        if (needsInput) {
            styleInputFor(message);
        }

        LinearLayout.LayoutParams menuParams =
                (LinearLayout.LayoutParams) menuBox.getLayoutParams();

        menuParams.topMargin = (!items.isEmpty() && !body.isEmpty())
                ? dp(18) : 0;

        menuBox.setLayoutParams(menuParams);

        menuAdapter.setItems(items, item -> {

            showLoader("Sending your choice", item.getNumber());

            if (!performReply(item.getNumber())) {
                hideLoader();
            }
        });

        actionBox.removeAllViews();

        if (finalMessage) {

            Button close = makeActionButton("Close", true);

            close.setOnClickListener(v -> endSession(false));

            actionBox.addView(close, actionParams());

        } else {

            Button cancel = makeActionButton("Cancel session", false);

            cancel.setOnClickListener(v -> endSession(true));

            actionBox.addView(cancel, actionParams());
        }

        // New page: slide/fade the content in, then drop the loader.
        contentColumn.animate().cancel();
        contentColumn.setAlpha(0f);
        contentColumn.setTranslationY(dp(10));
        contentColumn.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start();

        hideLoader();
    }

    private void buildOverlay() {

        try {

            FrameLayout frame = new FrameLayout(this);
            frame.setBackgroundResource(R.drawable.bg_screen);

            // ---- content ----
            ScrollView scroll = new ScrollView(this);
            scroll.setFillViewport(true);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);

            LinearLayout column = new LinearLayout(this);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(
                    dp(20),
                    systemBarHeight("status_bar_height") + dp(12),
                    dp(20),
                    systemBarHeight("navigation_bar_height") + dp(20));

            column.addView(buildHeader());

            TextView pageTitle = new TextView(this);
            pageTitle.setText("Response");
            pageTitle.setTextColor(getColor(R.color.text_primary));
            pageTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
            pageTitle.setPadding(0, 0, 0, dp(18));
            column.addView(pageTitle);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundResource(R.drawable.bg_card);
            card.setPadding(dp(20), dp(20), dp(20), dp(20));

            bodyBox = new LinearLayout(this);
            bodyBox.setOrientation(LinearLayout.VERTICAL);
            card.addView(bodyBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            menuBox = new LinearLayout(this);
            menuBox.setOrientation(LinearLayout.VERTICAL);
            card.addView(menuBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            column.addView(card, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            buildInputBox();

            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            ip.topMargin = dp(16);
            card.addView(inputBox, ip);

            scroll.addView(column);
            scroll.setClipToPadding(false);

            frame.addView(scroll, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            // Back / Main and Close / Cancel always sit at the bottom.
            navBox = new LinearLayout(this);
            navBox.setOrientation(LinearLayout.VERTICAL);
            navBox.setVisibility(View.GONE);

            actionBox = new LinearLayout(this);
            actionBox.setOrientation(LinearLayout.VERTICAL);

            bottomBar = new LinearLayout(this);
            bottomBar.setOrientation(LinearLayout.VERTICAL);
            bottomBar.setBackgroundColor(getColor(R.color.bg_bottom));
            bottomBar.setPadding(
                    dp(20), dp(12), dp(20),
                    systemBarHeight("navigation_bar_height") + dp(12));

            bottomBar.addView(navBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            bottomBar.addView(actionBox, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            frame.addView(bottomBar, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM));

            // Content scrolls clear of the pinned bar.
            final int sideTop = systemBarHeight("status_bar_height") + dp(12);

            bottomBar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                    column.setPadding(dp(20), sideTop, dp(20),
                            (b - t) + dp(16)));

            contentColumn = column;

            menuAdapter = new USSDMenuAdapter(this, menuBox);
            menuAdapter.setNavContainer(navBox);

            // ---- full-page loader (with an escape hatch) ----
            loadingHolder = new FrameLayout(this);
            loadingHolder.setBackgroundResource(R.drawable.bg_screen);
            loadingHolder.setClickable(true);
            loadingHolder.setVisibility(View.GONE);

            loadingView = new LoadingView(this);

            loadingHolder.addView(loadingView, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            TextView cancel = new TextView(this);
            cancel.setText("Cancel");
            cancel.setTextColor(getColor(R.color.text_muted));
            cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            cancel.setTypeface(Typeface.DEFAULT_BOLD);
            cancel.setGravity(Gravity.CENTER);
            cancel.setPadding(dp(28), dp(14), dp(28), dp(14));
            cancel.setBackgroundResource(R.drawable.bg_button_secondary);
            cancel.setOnClickListener(v -> endSession(true));

            FrameLayout.LayoutParams cancelParams =
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);

            cancelParams.bottomMargin =
                    systemBarHeight("navigation_bar_height") + dp(32);

            loadingHolder.addView(cancel, cancelParams);

            frame.addView(loadingHolder, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE
            );

            lp.gravity = Gravity.TOP | Gravity.START;

            windowManager.addView(frame, lp);

            overlay = frame;

        } catch (Exception e) {
            overlay = null;
        }
    }

    /** Hint text, and dots instead of digits when a PIN is asked. */
    private void styleInputFor(String message) {

        String m = message.toLowerCase();

        boolean secret = m.matches("(?s).*\\b(pin|password|passcode)\\b.*");

        inputDisplay.setTransformationMethod(secret
                ? android.text.method.PasswordTransformationMethod.getInstance()
                : null);

        String hint;

        if (secret) {
            hint = "Enter PIN";
        } else if (m.contains("amount") || m.contains("taka")) {
            hint = "Enter amount";
        } else if (m.contains("number") || m.contains("mobile")
                || m.contains("msisdn")) {
            hint = "Enter number";
        } else {
            hint = "Type reply";
        }

        inputDisplay.setHint(hint);
    }

    /** Own keypad for replies that need typing (amount, PIN, number). */
    private void buildInputBox() {

        inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.VERTICAL);
        inputBox.setVisibility(View.GONE);

        FrameLayout field = new FrameLayout(this);

        inputDisplay = new TextView(this);
        inputDisplay.setGravity(Gravity.CENTER);
        inputDisplay.setBackgroundResource(R.drawable.bg_input);
        inputDisplay.setTextColor(getColor(R.color.text_primary));
        inputDisplay.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        inputDisplay.setTypeface(Typeface.DEFAULT_BOLD);
        inputDisplay.setLetterSpacing(0.12f);
        inputDisplay.setHint("Type reply");
        inputDisplay.setHintTextColor(getColor(R.color.text_faint));
        inputDisplay.setSingleLine(true);
        inputDisplay.setPadding(dp(52), 0, dp(52), 0);

        field.addView(inputDisplay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(64)));

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_backspace);
        back.setPadding(dp(12), dp(12), dp(12), dp(12));
        back.setOnClickListener(v -> {
            CharSequence t = inputDisplay.getText();
            if (t.length() > 0) {
                inputDisplay.setText(t.subSequence(0, t.length() - 1));
            }
        });
        back.setOnLongClickListener(v -> {
            inputDisplay.setText("");
            return true;
        });

        field.addView(back, new FrameLayout.LayoutParams(
                dp(48), dp(64), Gravity.END));

        inputBox.addView(field);

        DialPadView pad = new DialPadView(this);
        pad.setOnKeyListener(key -> {
            if (inputDisplay.getText().length() < 40) {
                inputDisplay.append(key);
            }
        });

        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pp.topMargin = dp(12);
        inputBox.addView(pad, pp);

        Button send = makeActionButton("Send reply  →", true);
        send.setOnClickListener(v -> {

            String text = inputDisplay.getText().toString();

            if (text.isEmpty()) {
                return;
            }

            showLoader("Sending your reply", "•••");

            if (!performReply(text)) {
                hideLoader();
            }
        });

        inputBox.addView(send, actionParams());
    }

    private View buildHeader() {

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(22));

        ImageView logo = new ImageView(this);
        logo.setBackgroundResource(R.drawable.bg_logo);
        logo.setImageResource(R.drawable.ic_logo_glyph);
        logo.setPadding(dp(3), dp(3), dp(3), dp(3));

        row.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView name = new TextView(this);
        name.setText("Smart USSD");
        name.setTextColor(getColor(R.color.text_primary));
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        texts.addView(name);

        TextView tagline = new TextView(this);
        tagline.setText("Your SIM, Your Control");
        tagline.setTextColor(getColor(R.color.text_muted));
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        texts.addView(tagline);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        textParams.setMarginStart(dp(12));

        row.addView(texts, textParams);

        return row;
    }

    /* ---------------- Loader ---------------- */

    private void showLoader(String message) {
        showLoader(message, null);
    }

    /** Dial loader whose second line says we are trying again. */
    private void showRetryLoader() {

        showLoader("Contacting network");

        if (loadingView != null) {
            loadingView.setSubtitle(
                    "Taking longer than usual \u2014 trying again");
        }
    }

    /** @param choice null = dial loader, else the "sending choice" loader */
    private void showLoader(String message, String choice) {

        if (loadingHolder == null) {
            return;
        }

        loadingHolder.animate().cancel();

        if (loadingHolder.getVisibility() != View.VISIBLE) {
            loadingHolder.setAlpha(0f);
            loadingHolder.setVisibility(View.VISIBLE);
        }

        loadingHolder.animate().alpha(1f).setDuration(140).start();

        if (choice == null) {
            loadingView.show(message);
        } else {
            loadingView.showChoice(choice);
        }

        // Safety net so the user is never stuck behind the loader.
        handler.removeCallbacks(loaderTimeout);
        handler.postDelayed(loaderTimeout, 30_000);
    }

    private void hideLoader() {

        handler.removeCallbacks(loaderTimeout);

        if (loadingHolder == null
                || loadingHolder.getVisibility() != View.VISIBLE) {
            return;
        }

        loadingHolder.animate().cancel();

        loadingHolder.animate()
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> loadingHolder.setVisibility(View.GONE))
                .start();

        loadingView.hide();
    }

    private void removeOverlay() {

        handler.removeCallbacks(loaderTimeout);

        if (overlay != null && windowManager != null) {

            try {
                windowManager.removeView(overlay);
            } catch (Exception ignored) {
            }
        }

        overlay = null;
        loadingHolder = null;
        loadingView = null;
    }

    private Button makeActionButton(String text, boolean primary) {

        Button b = new Button(this);

        b.setText(text);
        b.setAllCaps(false);
        b.setStateListAnimator(null);
        b.setElevation(0f);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setBackgroundResource(primary
                ? R.drawable.bg_button_primary
                : R.drawable.bg_button_secondary);
        b.setTextColor(getColor(primary
                ? R.color.on_accent
                : R.color.text_body));

        return b;
    }

    private LinearLayout.LayoutParams actionParams() {

        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
        );

        p.topMargin = dp(16);

        return p;
    }

    private int systemBarHeight(String name) {

        int id = getResources().getIdentifier(name, "dimen", "android");

        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    /* ---------------- Node helpers ---------------- */

    private AccessibilityNodeInfo findDialogRoot() {

        try {

            List<AccessibilityWindowInfo> windows = getWindows();

            if (windows != null) {

                for (AccessibilityWindowInfo w : windows) {

                    AccessibilityNodeInfo r = w.getRoot();

                    if (r != null && isPhonePackage(r.getPackageName())) {
                        return r;
                    }
                }
            }

        } catch (Exception ignored) {
        }

        AccessibilityNodeInfo r = getRootInActiveWindow();

        return (r != null && isPhonePackage(r.getPackageName())) ? r : null;
    }

    private boolean isPhonePackage(CharSequence pkg) {

        if (pkg == null) return false;

        String p = pkg.toString().toLowerCase();

        return p.contains("phone")
                || p.contains("telecom")
                || p.contains("dialer")
                || p.contains("incallui");
    }

    /** Interim system texts shown while the network is still working. */
    private boolean isWaitingText(String message) {

        String m = message.trim().toLowerCase();

        if (m.length() > 60) {
            return false;
        }

        return m.contains("code running")
                || m.contains("ussd running")
                || m.contains("please wait")
                || m.contains("processing")
                || m.contains("loading")
                || m.equals("running...")
                || m.equals("running");
    }

    private boolean isCancelText(CharSequence t) {

        if (t == null) return false;

        String s = t.toString().toLowerCase();

        return s.equals("cancel") || s.equals("dismiss")
                || s.equals("close") || s.equals("exit");
    }

    private void collect(
            AccessibilityNodeInfo node,
            List<String> texts,
            List<AccessibilityNodeInfo> buttons,
            AccessibilityNodeInfo[] edit
    ) {

        if (node == null) return;

        String c = node.getClassName() == null
                ? "" : node.getClassName().toString();

        CharSequence text = node.getText();

        String id = node.getViewIdResourceName() == null
                ? "" : node.getViewIdResourceName().toLowerCase();

        if (c.endsWith("EditText")) {

            if (edit[0] == null) edit[0] = node;

        } else if (c.endsWith("Button")) {

            buttons.add(node);

        } else if (c.endsWith("TextView")
                && text != null
                && text.length() > 0
                && !id.contains("title")
                && !id.contains("icon")) {

            texts.add(text.toString());
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            collect(node.getChild(i), texts, buttons, edit);
        }
    }

    @Override
    public void onInterrupt() {
    }
}
