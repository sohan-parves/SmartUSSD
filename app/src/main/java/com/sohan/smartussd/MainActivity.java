package com.sohan.smartussd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.text.Editable;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.OvershootInterpolator;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Spinner;
import android.widget.TextView;
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
    /** Set only when the person chose "Later" / "Continue anyway". */
    private static final String KEY_HELPER_DISMISSED = "helper_dismissed";
    private static final String KEY_PHONE_PERM_REQUESTED = "phone_perm_requested";
    private static final String KEY_BATTERY_OPT_ASKED = "battery_opt_asked";
    private static final String KEY_AUTOSTART_ASKED = "autostart_asked";
    private static final String KEY_SIM = "last_sim_sub_id";

    /** Safety net: stop the loader if the network never answers. */
    private static final long BUSY_TIMEOUT_MS = USSDManager.TOTAL_WAIT_MS;

    private static final String HINT_CODE = "*121#";
    private static final String HINT_REPLY = "Reply";

    private TextView statusPill;
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

    /** True if the user opened App Settings to grant permissions manually. */
    private boolean awaitingSettings = false;

    /** The helper guide is offered once per app run when dialling. */
    private boolean helperPromptedThisRun = false;

    /** Bumped on every new request / cancel so stale callbacks are ignored. */
    private int requestId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        statusPill = findViewById(R.id.statusPill);
        simSpinner = findViewById(R.id.simSpinner);
        ussdInput = findViewById(R.id.ussdInput);
        btnSend = findViewById(R.id.btnSend);
        btnBackspace = findViewById(R.id.btnBackspace);
        dialPad = findViewById(R.id.dialPad);

        panel = new ResponsePanel(findViewById(R.id.rootFrame));

        ussdManager = new USSDManager(this);

        subscriptionManager = getSystemService(SubscriptionManager.class);

        setupKeypad();

        setupPermissionTriggers();

        runSplash(savedInstanceState == null);

        // Cancel button under the loader (same as pressing Back).
        panel.setLoadingCancel(() -> {
            resetSession();
            panel.hideLoading();
        });

        updateStatusPill();

        setupVersionFooter();

        btnSend.setOnClickListener(v -> onSendClicked());
    }

    private void setupVersionFooter() {
        TextView appVersionText = findViewById(R.id.appVersionText);
        if (appVersionText != null) {
            appVersionText.setText("Smart USSD • Version " + getAppVersionName());
        }
    }

    private String getAppVersionName() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                return getPackageManager().getPackageInfo(
                        getPackageName(),
                        PackageManager.PackageInfoFlags.of(0)).versionName;
            } else {
                return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            }
        } catch (Exception e) {
            return "1.0.0";
        }
    }

    /* ---------------- Launch splash ---------------- */

    /** Logo pops in, holds a moment, then fades away to reveal the app. */
    private void runSplash(boolean coldStart) {

        final View splash = findViewById(R.id.splashLayer);

        if (!coldStart) {
            splash.setVisibility(View.GONE);
            proceedWithNextPermissionStep();
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

        handler.postDelayed(() -> splash.animate()
                .alpha(0f)
                .setDuration(260)
                .withEndAction(() -> {
                    splash.setVisibility(View.GONE);
                    proceedWithNextPermissionStep();
                })
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
                    key);
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

    private void setupPermissionTriggers() {
        if (simSpinner != null) {
            simSpinner.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_UP) {
                    if (!hasRuntimePermissions()) {
                        requestOrExplainPermissions();
                        return true;
                    }
                }
                return false;
            });
        }

        View cardSim = findViewById(R.id.cardSim);
        if (cardSim != null) {
            cardSim.setOnClickListener(v -> {
                if (!hasRuntimePermissions()) {
                    requestOrExplainPermissions();
                }
            });
        }
    }

    private void updateStatusPill() {
        if (statusPill == null) {
            return;
        }

        if (!hasRuntimePermissions()) {
            statusPill.setText("● PERMISSION NEEDED");
            statusPill.setTextColor(getColor(R.color.error));
            statusPill.setBackground(pillDrawable(getColor(R.color.error_soft)));
            statusPill.setOnClickListener(v -> requestOrExplainPermissions());
        } else if (USSDAccessibilityService.isCrashedOrNotWorking(this)) {
            statusPill.setText("● RESTART HELPER");
            statusPill.setTextColor(getColor(R.color.error));
            statusPill.setBackground(pillDrawable(getColor(R.color.error_soft)));
            statusPill.setOnClickListener(v -> showHelperGuideManual());
        } else if (!USSDAccessibilityService.isEnabled(this)) {
            statusPill.setText("● HELPER OFF");
            statusPill.setTextColor(getColor(R.color.warning));
            statusPill.setBackground(pillDrawable(getColor(R.color.warning_soft)));
            statusPill.setOnClickListener(v -> showHelperGuideManual());
        } else {
            statusPill.setText("● READY");
            statusPill.setTextColor(getColor(R.color.success));
            statusPill.setBackground(pillDrawable(getColor(R.color.success_soft)));
            statusPill.setOnClickListener(
                    v -> Toast.makeText(this, "Smart USSD is ready: SIM & Helper active", Toast.LENGTH_SHORT).show());
        }
    }

    private GradientDrawable pillDrawable(int fillColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(50));
        d.setColor(fillColor);
        return d;
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private void showHelperGuideManual() {
        helperGuideShown = true;
        if (USSDAccessibilityService.isCrashedOrNotWorking(this)) {
            panel.showHelperRestartGuide(
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();
                        updateStatusPill();
                    });
        } else {
            panel.showHelperGuide(
                    () -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();
                        updateStatusPill();
                    });
        }
    }

    private void requestOrExplainPermissions() {
        if (hasRuntimePermissions()) {
            if (simList.isEmpty()) {
                loadSIMs();
            }
            updateStatusPill();
            return;
        }

        boolean hasRequestedBefore = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_PHONE_PERM_REQUESTED, false);

        boolean canAskAgain = false;
        String[] permissions = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
        };
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(permission)) {
                    canAskAgain = true;
                    break;
                }
            }
        }

        if (canAskAgain || !hasRequestedBefore) {
            checkPermissions();
        } else {
            showPermissionNeeded();
        }
    }

    private void checkPermissions() {

        List<String> missing = new ArrayList<>();

        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.CALL_PHONE);
        }
        if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!missing.isEmpty()) {

            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean(KEY_PHONE_PERM_REQUESTED, true).apply();

            requestPermissions(
                    missing.toArray(new String[0]),
                    REQUEST_PERMISSIONS);

        } else {

            loadSIMs();
            updateStatusPill();
            proceedWithNextPermissionStep();
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

            boolean phoneGranted = checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;

            if (phoneGranted) {

                if (permissionErrorShown) {
                    permissionErrorShown = false;
                    panel.hideError();
                }

                loadSIMs();
                updateStatusPill();

                if (pendingCode != null) {

                    // Continue the dial the person started.
                    String code = pendingCode;
                    pendingCode = null;
                    startNewCode(code);

                } else {
                    proceedWithNextPermissionStep();
                }

            } else {

                updateStatusPill();
                updateSimCardView();

                // Keep the typed code: it is sent as soon as the
                // permission is allowed (also after the settings page).
                showPermissionNeeded();
            }
        }
    }

    /**
     * Chained sequential permission setup: asks for each permission
     * one by one in priority order until everything is configured:
     * 1. Phone & Notification permissions (runtime prompt)
     * 2. Accessibility Service / USSD Helper (system settings)
     * 3. Battery Optimisation Exemption (system prompt)
     * 4. Autostart & Background settings (for Xiaomi/Redmi/POCO)
     */
    private void proceedWithNextPermissionStep() {
        if (isFinishing()) {
            return;
        }

        // 1. Phone permissions first
        if (!hasRuntimePermissions()) {
            requestOrExplainPermissions();
            return;
        }

        // 2. Accessibility USSD Helper next
        if (!USSDAccessibilityService.isEnabled(this)) {
            askForHelperOnce();
            return;
        }

        // 3. Battery optimisation exemption next
        if (needsBatteryOptimisation()) {
            requestBatteryOptimisation();
            return;
        }

        // 4. Xiaomi / MIUI autostart next
        if (needsXiaomiAutostart()) {
            promptXiaomiAutostart();
            return;
        }

        // All done!
        updateStatusPill();
        checkSetupComplete();
    }

    private void checkSetupComplete() {
        if (hasRuntimePermissions() && USSDAccessibilityService.isEnabled(this)) {
            SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (!prefs.getBoolean("setup_complete_toast_shown", false)) {
                prefs.edit().putBoolean("setup_complete_toast_shown", true).apply();
                Toast.makeText(this, "Smart USSD is ready to dial!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean hasRuntimePermissions() {

        return checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED;
    }

    /** Friendly screen when the phone permissions were refused. */
    private void showPermissionNeeded() {

        boolean canAskAgain = false;
        String[] permissions = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE
        };
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                if (shouldShowRequestPermissionRationale(permission)) {
                    canAskAgain = true;
                    break;
                }
            }
        }

        permissionErrorShown = true;

        if (canAskAgain) {
            panel.showError(
                    false,
                    "Permission needed",
                    "Smart USSD needs Phone permissions (Call & SIM access) "
                            + "to send your USSD code through the SIM you pick. "
                            + "Nothing is sent until you allow it.",
                    null,
                    "Allow permission",
                    () -> {
                        panel.hideError();
                        permissionErrorShown = false;
                        checkPermissions();
                    },
                    "Open app settings",
                    this::openAppSettings);
        } else {
            panel.showError(
                    false,
                    "Permission needed",
                    "Phone permission is required to detect SIM cards and dial "
                            + "USSD codes. Please turn ON Phone permission in App Settings.",
                    null,
                    "Open app settings",
                    this::openAppSettings,
                    "Close",
                    () -> {
                        permissionErrorShown = false;
                        pendingCode = null;
                        panel.hideError();
                        updateStatusPill();
                        updateSimCardView();
                    });
        }
    }

    private void openAppSettings() {
        awaitingSettings = true;
        startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    /**
     * The accessibility helper is the last permission the app needs. It
     * is offered once, right after the phone permissions.
     */
    /** True once the person chose "Later" / "Continue anyway" (saved for good). */
    private boolean helperDismissed() {

        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HELPER_DISMISSED, false);
    }

    /** Remembers for good: never show the helper guide again. */
    private void markHelperDismissed() {

        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_HELPER_DISMISSED, true).apply();
    }

    private void askForHelperOnce() {

        // Ask until the person decides; never when it is on,
        // and not twice in the same run.
        if (USSDAccessibilityService.isEnabled(this)
                || helperPromptedThisRun
                || isFinishing()) {
            return;
        }

        // Full-page step-by-step guide instead of a plain dialog.
        helperGuideShown = true;
        helperPromptedThisRun = true;

        if (USSDAccessibilityService.isCrashedOrNotWorking(this)) {
            panel.showHelperRestartGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();
                        updateStatusPill();
                    });
        } else {
            panel.showHelperGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();
                        updateStatusPill();
                    });
        }
    }

    private void updateSimCardView() {
        if (!hasRuntimePermissions()) {
            List<String> list = new ArrayList<>();
            list.add("⚠️ Tap to allow Phone permission");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_spinner,
                    list);
            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            simSpinner.setAdapter(adapter);
        } else if (simList.isEmpty()) {
            List<String> list = new ArrayList<>();
            list.add("No active SIM card found");
            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_spinner,
                    list);
            adapter.setDropDownViewResource(R.layout.item_spinner_dropdown);
            simSpinner.setAdapter(adapter);
        }
    }

    private void loadSIMs() {

        if (!hasRuntimePermissions()) {
            updateSimCardView();
            return;
        }

        if (subscriptionManager == null) {
            subscriptionManager = getSystemService(SubscriptionManager.class);
        }

        if (subscriptionManager == null) {
            updateSimCardView();
            return;
        }

        try {

            simList = subscriptionManager
                    .getActiveSubscriptionInfoList();

            if (simList == null || simList.isEmpty()) {

                simList = new ArrayList<>();
                updateSimCardView();
                return;
            }

            List<String> simNames = new ArrayList<>();

            for (SubscriptionInfo info : simList) {

                String name = "SIM " + (info.getSimSlotIndex() + 1)
                        + " - " + info.getDisplayName();

                simNames.add(name);
            }

            ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    this,
                    R.layout.item_spinner,
                    simNames);

            adapter.setDropDownViewResource(
                    R.layout.item_spinner_dropdown);

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
            updateSimCardView();
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
            requestOrExplainPermissions();
            return;
        }

        if (simList.isEmpty()) {
            loadSIMs();
        }

        if (simList.isEmpty()
                || simSpinner.getSelectedItemPosition() < 0) {

            Feedback.reject(simSpinner);

            panel.showError(
                    false,
                    "No SIM found",
                    "Smart USSD could not find an active SIM card. Insert a "
                            + "SIM (or turn it on in Settings), then dial again.",
                    null,
                    null,
                    null,
                    "Close",
                    panel::hideError);

            return;
        }

        // 2) USSD Helper (Accessibility Service) is required:
        // Never send request if Helper is OFF. Ask to enable it.
        if (!USSDAccessibilityService.isEnabled(this)) {
            pendingCode = code;
            helperGuideShown = true;
            if (USSDAccessibilityService.isCrashedOrNotWorking(this)) {
                panel.showHelperRestartGuide(
                        () -> startActivity(new Intent(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        "Close",
                        () -> {
                            helperGuideShown = false;
                            pendingCode = null;
                            panel.hideError();
                            updateStatusPill();
                        });
            } else {
                panel.showHelperGuide(
                        () -> startActivity(new Intent(
                                Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                        "Close",
                        () -> {
                            helperGuideShown = false;
                            pendingCode = null;
                            panel.hideError();
                            updateStatusPill();
                        });
            }
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

                        panel.showResponse(response, item -> sendChoice(item.getNumber(), item.isBack()));
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
                });
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
                panel::hideError);

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

        // Permission was taken away: ask again, then send the same code.
        if (errorCode == USSDManager.ERR_NO_PERMISSION
                && !hasRuntimePermissions()) {

            pendingCode = lastCode.isEmpty() ? null : lastCode;
            requestOrExplainPermissions();
            return;
        }

        if (USSDManager.canFixWithAccessibility(this, errorCode)) {

            helperGuideShown = true;

            panel.showHelperGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    () -> {
                        helperGuideShown = false;
                        panel.hideError();
                        updateStatusPill();
                    });

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
                panel::hideError);

        Feedback.reject(ussdInput);
    }

    @Override
    protected void onResume() {

        super.onResume();

        // Never leave the system "Carrier info" dialog behind our screens.
        if (!busy && !sessionActive) {

            if (ussdManager.hasDialerSession()) {

                // A session from before the app was closed is still open
                // (its system dialog would pop up again): end it now.
                ussdManager.cancelSession();
                panel.resetAll();

            } else {

                USSDAccessibilityService.closeLeftoverDialog();
            }
        }

        if (hasRuntimePermissions()) {
            if (permissionErrorShown) {
                permissionErrorShown = false;
                panel.hideError();
            }
            awaitingSettings = false;

            if (simList.isEmpty()) {
                loadSIMs();
            }
            updateStatusPill();

            if (helperGuideShown && USSDAccessibilityService.isEnabled(this)) {
                helperGuideShown = false;
                panel.hideError();
            }

            // If helper is enabled and there is a pending code, send it
            if (pendingCode != null && USSDAccessibilityService.isEnabled(this)) {
                String code = pendingCode;
                pendingCode = null;
                startNewCode(code);
            } else {
                View splash = findViewById(R.id.splashLayer);
                if (splash == null || splash.getVisibility() != View.VISIBLE) {
                    proceedWithNextPermissionStep();
                }
            }
        } else {
            updateStatusPill();
            updateSimCardView();

            if (awaitingSettings) {
                awaitingSettings = false;
                showPermissionNeeded();
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
            updateStatusPill();
            updateSimCardView();

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

    /* ---- Battery optimisation & Xiaomi autostart (one-time) ---- */

    private boolean needsBatteryOptimisation() {

        if (!USSDAccessibilityService.isEnabled(this)) {
            return false; // helper not on yet; ask later
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        if (prefs.getBoolean(KEY_BATTERY_OPT_ASKED, false)) {
            return false;
        }

        PowerManager pm = getSystemService(PowerManager.class);

        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            prefs.edit().putBoolean(KEY_BATTERY_OPT_ASKED, true).apply();
            return false;
        }

        return true;
    }

    /**
     * Ask the system to exempt the app from battery optimisation.
     * This prevents Xiaomi/MIUI from killing the foreground service
     * that keeps the accessibility helper alive.
     * Asked only once; the user can decline.
     */
    private void requestBatteryOptimisation() {

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_BATTERY_OPT_ASKED, true).apply();

        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "Battery opt intent failed", e);
        }
    }

    private boolean needsXiaomiAutostart() {

        if (!USSDAccessibilityService.isEnabled(this)) {
            return false;
        }

        String manufacturer = Build.MANUFACTURER.toLowerCase();

        if (!manufacturer.contains("xiaomi")
                && !manufacturer.contains("redmi")
                && !manufacturer.contains("poco")) {
            return false;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        return !prefs.getBoolean(KEY_AUTOSTART_ASKED, false);
    }

    /**
     * On Xiaomi / Redmi / POCO devices, show a one-time dialog guiding
     * the user to enable "Autostart" and set battery saver to
     * "No restrictions" so the system never kills our service.
     */
    private void promptXiaomiAutostart() {

        if (!USSDAccessibilityService.isEnabled(this)) {
            return;
        }

        if (!needsXiaomiAutostart()) {
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_AUTOSTART_ASKED, true).apply();

        new AlertDialog.Builder(this)
                .setTitle("Keep USSD helper running")
                .setMessage(
                        "Your " + Build.MANUFACTURER + " phone may stop "
                                + "the USSD helper when you close the app.\n\n"
                                + "To fix this permanently:\n"
                                + "1. Turn on \"Autostart\" for Smart USSD\n"
                                + "2. Set Battery Saver to \"No restrictions\"\n\n"
                                + "Tap \"Open settings\" to go there now.")
                .setPositiveButton("Open settings", (d, w) -> {
                    openXiaomiAutostart();
                })
                .setNegativeButton("Later", null)
                .show();
    }

    private void openXiaomiAutostart() {

        // Try the MIUI autostart manager
        ComponentName[] xiaomiComponents = {
                new ComponentName("com.miui.securitycenter",
                        "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                new ComponentName("com.miui.securitycenter",
                        "com.miui.powercenter.PowerSettings"),
        };

        for (ComponentName cn : xiaomiComponents) {
            try {
                Intent intent = new Intent();
                intent.setComponent(cn);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
        }

        // Fallback: open the app's own battery settings
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}