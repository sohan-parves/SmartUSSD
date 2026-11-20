package com.sohan.smartussd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.Toast;

import com.sohan.smartussd.ui.DialPadView;
import com.sohan.smartussd.ui.Feedback;
import com.sohan.smartussd.ui.ResponsePanel;
import com.sohan.smartussd.ussd.USSDAccessibilityService;
import com.sohan.smartussd.ussd.USSDManager;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String TAG = "SmartUSSD";

    private static final int REQUEST_PERMISSIONS = 100;

    private static final String PREFS = "smart_ussd";
    private static final String KEY_HELPER_ASKED = "helper_asked";
    private static final String KEY_SIM = "last_sim_sub_id";

    /** Safety net: stop the loader if the network never answers. */
    private static final long BUSY_TIMEOUT_MS = USSDManager.TOTAL_WAIT_MS;

    private static final String HINT_CODE = "*121#";
    private static final String HINT_REPLY = "Reply";

    private Spinner simSpinner;
    private EditText ussdInput;
    private Button btnSend;
    private ImageButton btnBackspace;
    private DialPadView dialPad;
    private ResponsePanel panel;

    private SubscriptionManager subscriptionManager;

    private USSDManager ussdManager;

    private List<SubscriptionInfo> simList = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable busyTimeout = this::onTimeout;

    private int subscriptionId = -1;

    /** Code chained with menu choices when no interactive session exists. */
    private String pathCode = "";

    /** The code the user originally dialled, kept for "Try again". */
    private String lastCode = "";

    /** True after a code was answered: the input then sends replies. */
    private boolean sessionActive = false;

    private boolean busy = false;

    /** True while the "enable helper" guide is on screen. */
    private boolean helperGuideShown = false;

    /** Code typed before the permissions were granted: sent afterwards. */
    private String pendingCode = null;

    /** True while the "permission needed" screen is on screen. */
    private boolean permissionErrorShown = false;

    /** The helper guide is offered once per app run when dialling. */
    private boolean helperPromptedThisRun = false;

    /** Bumped on every new request / cancel so stale callbacks are ignored. */
    private int requestId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        simSpinner = findViewById(R.id.simSpinner);
        ussdInput = findViewById(R.id.ussdInput);
        btnSend = findViewById(R.id.btnSend);
        btnBackspace = findViewById(R.id.btnBackspace);
        dialPad = findViewById(R.id.dialPad);

        panel = new ResponsePanel(findViewById(R.id.rootFrame));

        ussdManager = new USSDManager(this);

        subscriptionManager =
                getSystemService(SubscriptionManager.class);

        setupKeypad();

        runSplash(savedInstanceState == null);

        // Cancel button under the loader (same as pressing Back).
        panel.setLoadingCancel(() -> {
            resetSession();
            panel.hideLoading();
        });

        // Ask for everything the app needs, right at the first start.
        checkPermissions();

        btnSend.setOnClickListener(v -> onSendClicked());
    }

    /* ---------------- Launch splash ---------------- */

    /** Logo pops in, holds a moment, then fades away to reveal the app. */
    private void runSplash(boolean coldStart) {

        final View splash = findViewById(R.id.splashLayer);

        if (!coldStart) {
            splash.setVisibility(View.GONE);
            return;
        }

        final View logo = findViewById(R.id.splashLogo);
        final View name = findViewById(R.id.splashName);
        final View tag = findViewById(R.id.splashTag);

        logo.setScaleX(0.7f);
        logo.setScaleY(0.7f);
        logo.setAlpha(0f);
        name.setAlpha(0f);
        tag.setAlpha(0f);

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(380)
                .setInterpolator(new OvershootInterpolator(1.6f))
                .start();

        name.animate().alpha(1f).setStartDelay(150).setDuration(300).start();
        tag.animate().alpha(1f).setStartDelay(260).setDuration(300).start();

        handler.postDelayed(() ->
                        splash.animate()
                                .alpha(0f)
                                .setDuration(260)
                                .withEndAction(() -> splash.setVisibility(View.GONE))
                                .start(),
                850);
    }

    /* ---------------- Custom keypad (0-9, *, #) ---------------- */

    private void setupKeypad() {

        // Our own keypad replaces the system keyboard.
        ussdInput.setShowSoftInputOnFocus(false);
        ussdInput.requestFocus();

        dialPad.setOnKeyListener(key -> {

            int start = Math.max(ussdInput.getSelectionStart(), 0);
            int end = Math.max(ussdInput.getSelectionEnd(), 0);

            ussdInput.getText().replace(
                    Math.min(start, end),
                    Math.max(start, end),
                    key
            );
        });

        btnBackspace.setOnClickListener(v -> {

            Editable text = ussdInput.getText();

            int start = ussdInput.getSelectionStart();
            int end = ussdInput.getSelectionEnd();

            if (start != end && start >= 0) {
                text.delete(Math.min(start, end), Math.max(start, end));
            } else if (start > 0) {
                text.delete(start - 1, start);
            }
        });

        btnBackspace.setOnLongClickListener(v -> {
            ussdInput.setText("");
            return true;
        });
    }

    /* ---------------- Permissions & SIMs ---------------- */

    private void checkPermissions() {

        String[] permissions = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
        };

        List<String> missing = new ArrayList<>();

        for (String permission : permissions) {

            if (checkSelfPermission(permission)
                    != PackageManager.PERMISSION_GRANTED) {

                missing.add(permission);
            }
        }

        if (!missing.isEmpty()) {

            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_PERMISSIONS
            );

        } else {

            loadSIMs();
            askForHelperOnce();
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {

            boolean granted = grantResults.length > 0;

            for (int result : grantResults) {

                if (result != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }

            if (granted) {

                loadSIMs();

                if (pendingCode != null) {

                    // Continue the dial the person started.
                    String code = pendingCode;
                    pendingCode = null;
                    startNewCode(code);

                } else {
                    askForHelperOnce();
                }

            } else {

                pendingCode = null;
                showPermissionNeeded();
            }
        }
    }

    private boolean hasRuntimePermissions() {

        return checkSelfPermission(Manifest.permission.CALL_PHONE)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Friendly screen when the phone permissions were refused. */
    private void showPermissionNeeded() {

        boolean canAskAgain =
                shouldShowRequestPermissionRationale(
                        Manifest.permission.CALL_PHONE)
                        || shouldShowRequestPermissionRationale(
                        Manifest.permission.READ_PHONE_STATE);

        permissionErrorShown = true;

        panel.showError(
                false,
                "Permission needed",
                "Smart USSD needs the Phone permission to send your USSD "
                        + "code through the SIM you pick. Nothing is sent "
                        + "until you allow it.",
                null,
                canAskAgain ? "Allow permission" : "Open app settings",
                () -> {

                    panel.hideError();
                    permissionErrorShown = false;

                    if (canAskAgain) {
                        checkPermissions();
                    } else {
                        startActivity(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse(
                                        "package:" + getPackageName())));
                    }
                },
                "Close",
                () -> {
                    permissionErrorShown = false;
                    panel.hideError();
                });
    }

    /**
     * The accessibility helper is the last permission the app needs. It
     * is offered once, right after the phone permissions.
     */
    private void askForHelperOnce() {

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (USSDAccessibilityService.isEnabled(this)
                || prefs.getBoolean(KEY_HELPER_ASKED, false)
                || isFinishing()) {
            return;
        }

        prefs.edit().putBoolean(KEY_HELPER_ASKED, true).apply();

        // Full-page step-by-step guide instead of a plain dialog.
        helperGuideShown = true;
        helperPromptedThisRun = true;

        panel.showHelperGuide(
                () -> startActivity(new Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                "Later",
                () -> {
                    helperGuideShown = false;
                    panel.hideError();
                });
    }

    private void loadSIMs() {

        try {

            simList =
                    subscriptionManager
                            .getActiveSubscriptionInfoList();

            if (simList == null || simList.isEmpty()) {

                simList = new ArrayList<>();
                return;
            }

            List<String> simNames = new ArrayList<>();

            for (SubscriptionInfo info : simList) {

                String name =
                        "SIM " + (info.getSimSlotIndex() + 1)
                                + " - " + info.getDisplayName();

                simNames.add(name);
            }

            ArrayAdapter<String> adapter =
                    new ArrayAdapter<>(
                            this,
                            R.layout.item_spinner,
                            simNames
                    );

            adapter.setDropDownViewResource(
                    R.layout.item_spinner_dropdown
            );

            simSpinner.setAdapter(adapter);

            // Re-select the SIM used last time.
            int saved = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getInt(KEY_SIM, -1);

            for (int i = 0; i < simList.size(); i++) {
                if (simList.get(i).getSubscriptionId() == saved) {
                    simSpinner.setSelection(i);
                    break;
                }
            }

        } catch (SecurityException e) {

            Log.w(TAG, "SIM list not permitted", e);
        }
    }

    /* ---------------- Sending ---------------- */

    private void onSendClicked() {

        if (busy) {
            return;
        }

        String text = ussdInput.getText().toString().trim();

        if (text.isEmpty()) {
            Feedback.reject(ussdInput);
            return;
        }

        if (text.startsWith("*") && text.endsWith("#")) {

            startNewCode(text);

        } else if (sessionActive) {

            sendChoice(text, false, true);
            ussdInput.setText("");

        } else {

            Feedback.reject(ussdInput);
        }
    }

    private void startNewCode(String code) {

        // 1) Permissions first: nothing is sent before they are granted.
        if (!hasRuntimePermissions()) {

            pendingCode = code;
            checkPermissions();
            return;
        }

        if (simList.isEmpty()) {
            loadSIMs();
        }

        if (simList.isEmpty()
                || simSpinner.getSelectedItemPosition() < 0) {

            Feedback.reject(simSpinner);
            return;
        }

        // 2) The USSD helper (Accessibility) gives the full experience:
        //    offer it once per run, with a way to continue without it.
        if (!USSDAccessibilityService.isEnabled(this)
                && !helperPromptedThisRun) {

            helperPromptedThisRun = true;
            helperGuideShown = true;
            pendingCode = code;

            panel.showHelperGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Continue anyway",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();

                        String c = pendingCode;
                        pendingCode = null;

                        if (c != null) {
                            startNewCode(c);
                        }
                    });

            return;
        }

        pendingCode = null;

        subscriptionId = simList
                .get(simSpinner.getSelectedItemPosition())
                .getSubscriptionId();

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(KEY_SIM, subscriptionId).apply();

        // Drop any older session before starting a new one.
        ussdManager.cancelSession();

        pathCode = code;
        lastCode = code;
        sessionActive = false;

        performRequest(code);
    }

    private void performRequest(String code) {
        performRequest(code, null);
    }

    /** @param choice picked menu option, or null for a freshly dialled code */
    private void performRequest(String code, String choice) {

        final int id = ++requestId;

        setBusy(true, choice);

        ussdManager.sendRequest(
                subscriptionId,
                code,
                new USSDManager.USSDCallback() {

                    @Override
                    public void onSuccess(String response) {

                        if (id != requestId) {
                            return;
                        }

                        if (USSDManager.isNetworkError(response)) {
                            onNetworkRefused(response);
                            return;
                        }

                        setBusy(false);

                        sessionActive = true;
                        ussdInput.setText("");
                        ussdInput.setHint(HINT_REPLY);

                        panel.showResponse(response, item ->
                                sendChoice(item.getNumber(), item.isBack()));
                    }

                    @Override
                    public void onFailure(int errorCode) {

                        if (id != requestId) {
                            return;
                        }

                        Log.w(TAG, "USSD failed: " + errorCode + " "
                                + USSDManager.describe(errorCode));

                        if (USSDManager.isTimeout(errorCode)) {
                            onTimeout();
                        } else {
                            onError(errorCode);
                        }
                    }

                    @Override
                    public void onSessionClosed() {

                        if (id != requestId) {
                            return;
                        }

                        // User closed the overlay: back to a clean dialer.
                        resetSession();
                        panel.resetAll();
                    }
                }
        );
    }

    /**
     * Sends a menu choice or typed reply: inside the live session when
     * the helper is on, otherwise by chaining it onto the code.
     */
    private void sendChoice(String choice, boolean isBack) {
        sendChoice(choice, isBack, false);
    }

    /** @param typed true for free text (PIN, amount): never shown on screen */
    private void sendChoice(String choice, boolean isBack, boolean typed) {

        if (ussdManager.hasDialerSession()) {

            setBusy(true, typed ? "•••" : choice);

            if (!ussdManager.reply(choice)) {

                setBusy(false);
                Feedback.reject(ussdInput);
            }

            return;
        }

        // Chained mode: *121# + 1 -> *121*1#
        String base = pathCode.endsWith("#")
                ? pathCode.substring(0, pathCode.length() - 1)
                : pathCode;

        if (isBack) {

            int cut = base.lastIndexOf('*');

            if (cut > 0) {
                pathCode = base.substring(0, cut) + "#";
                performRequest(pathCode, choice);
            }

            return;
        }

        if (!choice.matches("\\d+")) {

            // * and # need the live session (helper); nothing to show.
            Feedback.reject(ussdInput);
            return;
        }

        pathCode = base + "*" + choice + "#";

        performRequest(pathCode, typed ? "•••" : choice);
    }

    /* ---------------- Timeout / error / reset ---------------- */

    /**
     * Puts every piece of state back to "ready for a new command":
     * stale callbacks, timers, the open USSD session, typed text.
     */
    private void resetSession() {

        requestId++;

        handler.removeCallbacks(busyTimeout);

        // Closes the system dialog / overlay and drops all listeners.
        ussdManager.cancelSession();

        busy = false;
        sessionActive = false;
        pathCode = "";

        btnSend.setEnabled(true);

        ussdInput.setText("");
        ussdInput.setHint(HINT_CODE);
    }

    /** The network answered with a generic "invalid code / problem" text. */
    private void onNetworkRefused(String text) {

        resetSession();

        final String retryCode = lastCode;

        panel.showError(
                USSDManager.isServiceUnavailable(text),
                USSDManager.refusedTitle(text),
                USSDManager.refusedMessage(text),
                "\u2713  Session reset \u2014 ready for a new code",
                retryCode.isEmpty() ? null : "Try again  " + retryCode,
                () -> {
                    panel.hideError();
                    startNewCode(retryCode);
                },
                "Dial a new code",
                panel::hideError
        );

        Feedback.reject(ussdInput);
    }

    /**
     * Timeout: no error screen. Everything is reset and the person
     * lands on the clean home page, ready for a new code.
     */
    private void onTimeout() {

        Log.w(TAG, "USSD timed out");

        resetSession();

        // Forget everything that belonged to the timed-out request.
        lastCode = "";
        pendingCode = null;
        helperGuideShown = false;
        permissionErrorShown = false;

        // Drop loader, result page and error screen -> home page.
        panel.resetAll();

        ussdInput.setText("");
        ussdInput.setHint(HINT_CODE);

        Toast.makeText(this, "Request timed out. Session reset.",
                Toast.LENGTH_SHORT).show();
    }

    private void onError(int errorCode) {

        resetSession();

        if (USSDManager.canFixWithAccessibility(this, errorCode)) {

            helperGuideShown = true;

            panel.showHelperGuide(() ->
                    startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)));

            return;
        }

        final String retryCode = lastCode;

        panel.showError(
                false,
                USSDManager.title(errorCode),
                USSDManager.describe(errorCode),
                "✓  Session reset — ready for a new code",
                retryCode.isEmpty() ? null : "Try again  " + retryCode,
                () -> {
                    panel.hideError();
                    startNewCode(retryCode);
                },
                "Dial a new code",
                panel::hideError
        );

        Feedback.reject(ussdInput);
    }

    @Override
    protected void onResume() {

        super.onResume();

        // Never leave the system "Carrier info" dialog behind our screens.
        if (!busy && !sessionActive) {
            USSDAccessibilityService.closeLeftoverDialog();
        }

        // Back from app settings with the permissions allowed.
        if (permissionErrorShown && hasRuntimePermissions()) {

            permissionErrorShown = false;
            panel.hideError();
            loadSIMs();
        }

        // Back from Accessibility settings with the helper on: continue.
        if (helperGuideShown
                && USSDAccessibilityService.isEnabled(this)) {

            helperGuideShown = false;
            panel.hideError();

            // Helper just turned on: send the code the person dialled.
            if (pendingCode != null) {

                String code = pendingCode;
                pendingCode = null;
                startNewCode(code);
            }
        }
    }

    /* ---------------- UI helpers ---------------- */

    private void setBusy(boolean value) {
        setBusy(value, null);
    }

    /** @param choice null = dial loader, otherwise the "sending choice" loader */
    private void setBusy(boolean value, String choice) {

        busy = value;

        btnSend.setEnabled(!value);

        handler.removeCallbacks(busyTimeout);

        if (value) {

            if (choice == null) {
                panel.showLoading("Contacting network");
            } else {
                panel.showChoiceLoading(choice);
            }

            handler.postDelayed(busyTimeout, BUSY_TIMEOUT_MS);

        } else {
            panel.hideLoading();
        }
    }

    @Override
    public void onBackPressed() {

        if (panel.isErrorVisible()) {

            helperGuideShown = false;
            permissionErrorShown = false;
            pendingCode = null;
            panel.hideError();

        } else if (panel.isLoading()) {

            // Cancel the running request.
            resetSession();
            panel.hideLoading();

        } else if (panel.isResultVisible()) {

            panel.hideResult();

        } else {

            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        handler.removeCallbacks(busyTimeout);

        if (ussdManager != null) {
            ussdManager.cancelSession();
        }
    }
}