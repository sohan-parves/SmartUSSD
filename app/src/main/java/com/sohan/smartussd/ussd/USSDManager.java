package com.sohan.smartussd.ussd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class USSDManager {

    /* Framework codes (TelephonyManager): -1 = failure, -2 = service unavailable */
    public static final int ERR_FRAMEWORK_FAILURE = -1;
    public static final int ERR_SERVICE_UNAVAILABLE = -2;

    /* App codes */
    public static final int ERR_NO_TELEPHONY = -101;
    public static final int ERR_NO_PERMISSION = -102;
    public static final int ERR_ILLEGAL_STATE = -103;
    public static final int ERR_UNSUPPORTED = -104;
    public static final int ERR_UNKNOWN = -105;
    public static final int ERR_TIMEOUT = -106;
    public static final int ERR_API_TOO_LOW = -107;
    public static final int ERR_INVALID_SIM = -108;

    private static final long FALLBACK_TIMEOUT_MS = 30000;

    /**
     * No answer after this long: quietly cancel and dial the same code
     * again behind the loader (the person never sees an error screen).
     */
    public static final long RETRY_AFTER_MS = 8000;

    /**
     * Silent re-dials before the timeout screen. 0 = auto retry is OFF
     * (it re-opened the system USSD dialog on some phones). Set to 1 or
     * 2 to turn it back on.
     */
    public static final int MAX_AUTO_RETRIES = 0;

    /** Longest a screen should wait: every attempt + its retries. */
    public static final long TOTAL_WAIT_MS = FALLBACK_TIMEOUT_MS + RETRY_AFTER_MS * MAX_AUTO_RETRIES + 4000;

    public interface USSDCallback {

        void onSuccess(String response);

        void onFailure(int errorCode);

        /** The session was closed from the overlay (Close / Cancel). */
        void onSessionClosed();
    }

    private final Context context;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Pending "network never answered" timer of the dialer fallback. */
    private Runnable pendingTimeout;

    /** Pending silent re-dial, and the id of the attempt it belongs to. */
    private Runnable pendingRetry;
    private int attemptSeq = 0;

    public USSDManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void sendRequest(
            int subscriptionId,
            String ussdCode,
            USSDCallback callback) {

        cancelRetry();

        startAttempt(subscriptionId, ussdCode, callback, 0);
    }

    /**
     * One dial attempt. If the network is still silent after
     * {@link #RETRY_AFTER_MS}, the attempt is dropped and a fresh one
     * starts in the background; the loader simply keeps spinning.
     */
    private void startAttempt(
            final int subscriptionId,
            final String ussdCode,
            final USSDCallback callback,
            final int retriesDone) {

        final int seq = ++attemptSeq;

        // Answers of an attempt we already dropped are ignored.
        USSDCallback guarded = new USSDCallback() {

            @Override
            public void onSuccess(String response) {
                if (seq != attemptSeq)
                    return;
                cancelRetry();
                callback.onSuccess(response);
            }

            @Override
            public void onFailure(int errorCode) {
                if (seq != attemptSeq)
                    return;
                cancelRetry();
                callback.onFailure(errorCode);
            }

            @Override
            public void onSessionClosed() {
                if (seq != attemptSeq)
                    return;
                cancelRetry();
                callback.onSessionClosed();
            }
        };

        if (retriesDone < MAX_AUTO_RETRIES) {

            Runnable retry = () -> {

                if (seq != attemptSeq) {
                    return;
                }

                pendingRetry = null;

                Log.i("SmartUSSD", "No answer yet, retrying quietly ("
                        + (retriesDone + 1) + "/" + MAX_AUTO_RETRIES + ")");

                retryQuietly(subscriptionId, ussdCode, callback,
                        retriesDone + 1);
            };

            pendingRetry = retry;
            mainHandler.postDelayed(retry, RETRY_AFTER_MS);
        }

        dispatch(subscriptionId, ussdCode, guarded);
    }

    private void retryQuietly(
            int subscriptionId,
            String ussdCode,
            USSDCallback callback,
            int retriesDone) {

        // Old fallback timer / listeners must not fire for the dropped try.
        clearTimers();
        USSDAccessibilityService.setEndListener(null);

        if (USSDAccessibilityService.isEnabled(context)) {

            // Close the stuck system dialog behind the loader, then dial.
            USSDAccessibilityService.restartQuietly(() -> startAttempt(subscriptionId, ussdCode,
                    callback, retriesDone));

        } else {

            startAttempt(subscriptionId, ussdCode, callback, retriesDone);
        }
    }

    private void cancelRetry() {

        if (pendingRetry != null) {
            mainHandler.removeCallbacks(pendingRetry);
            pendingRetry = null;
        }
    }

    private void dispatch(
            int subscriptionId,
            String ussdCode,
            USSDCallback callback) {

        if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {

            deliverFailure(callback, ERR_NO_PERMISSION);
            return;
        }

        if (subscriptionId < 0) {

            deliverFailure(callback, ERR_INVALID_SIM);
            return;
        }

        // Answers of an attempt that was already replaced are dropped.
        final int mySeq = attemptSeq;

        // Interactive menus need the dialer session (reply inside the
        // same USSD session). Use it whenever the helper is enabled.
        if (USSDAccessibilityService.isEnabled(context)
                && tryDialerFallback(subscriptionId, ussdCode, callback)) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {

            // sendUssdRequest needs API 26+, go straight to dialer fallback
            if (!tryDialerFallback(subscriptionId, ussdCode, callback)) {
                deliverFailure(callback, ERR_API_TOO_LOW);
            }
            return;
        }

        try {

            TelephonyManager baseManager = (TelephonyManager) context.getSystemService(
                    Context.TELEPHONY_SERVICE);

            if (baseManager == null) {

                deliverFailure(callback, ERR_NO_TELEPHONY);
                return;
            }

            TelephonyManager simManager = baseManager.createForSubscriptionId(subscriptionId);

            if (simManager == null) {

                deliverFailure(callback, ERR_NO_TELEPHONY);
                return;
            }

            simManager.sendUssdRequest(
                    ussdCode,

                    new TelephonyManager.UssdResponseCallback() {

                        @Override
                        public void onReceiveUssdResponse(
                                TelephonyManager manager,
                                String request,
                                CharSequence response) {

                            String result = response == null
                                    ? ""
                                    : response.toString();

                            mainHandler.post(() -> {
                                if (mySeq == attemptSeq) {
                                    callback.onSuccess(result);
                                }
                            });
                        }

                        @Override
                        public void onReceiveUssdResponseFailed(
                                TelephonyManager manager,
                                String request,
                                int failureCode) {

                            // API failed (very common on MIUI / some operators).
                            // Try the dialer + accessibility fallback.
                            mainHandler.post(() -> {

                                if (mySeq != attemptSeq) {
                                    return;
                                }

                                if (!tryDialerFallback(
                                        subscriptionId,
                                        ussdCode,
                                        callback)) {

                                    callback.onFailure(failureCode);
                                }
                            });
                        }
                    },

                    mainHandler);

        } catch (SecurityException e) {

            deliverFailure(callback, ERR_NO_PERMISSION);

        } catch (IllegalStateException e) {

            deliverFailure(callback, ERR_ILLEGAL_STATE);

        } catch (UnsupportedOperationException e) {

            deliverFailure(callback, ERR_UNSUPPORTED);

        } catch (Exception e) {

            deliverFailure(callback, ERR_UNKNOWN);
        }
    }

    /* ---------- Fallback: real call + Accessibility Service ---------- */

    private boolean tryDialerFallback(
            int subscriptionId,
            String ussdCode,
            USSDCallback callback
    ) {

        // Helper off: the system dialog could not be hidden or read,
        // so do not open it at all.
        if (!USSDAccessibilityService.isEnabled(context)) {
            return false;
        }

        // Never leave an older timer / listener behind.
        clearTimers();

        final AtomicBoolean received = new AtomicBoolean(false);

        final Runnable timeout = new Runnable() {

            @Override
            public void run() {

                if (received.get()) {
                    return;
                }

                pendingTimeout = null;

                // Close the system dialog too, so nothing stays half-open.
                USSDAccessibilityService.setEndListener(null);
                USSDAccessibilityService.cancelSession();

                callback.onFailure(ERR_TIMEOUT);
            }
        };

        pendingTimeout = timeout;

        // Persistent listener: delivers the first reply and every
        // follow-up reply of the same session.
        USSDAccessibilityService.setListener(text ->
                mainHandler.post(() -> {
                    received.set(true);
                    mainHandler.removeCallbacks(timeout);
                    pendingTimeout = null;
                    callback.onSuccess(text);
                })
        );

        // Overlay closed by the user, or its own reply timer expired.
        USSDAccessibilityService.setEndListener(timedOut ->
                mainHandler.post(() -> {
                    mainHandler.removeCallbacks(timeout);
                    pendingTimeout = null;

                    if (timedOut) {
                        callback.onFailure(ERR_TIMEOUT);
                    } else {
                        callback.onSessionClosed();
                    }
                })
        );

        mainHandler.postDelayed(timeout, FALLBACK_TIMEOUT_MS);

        // Hide the system dialog behind our loader from the very start.
        USSDAccessibilityService.showCover();

        try {

            Intent intent = new Intent(
                    Intent.ACTION_CALL,
                    Uri.parse("tel:" + Uri.encode(ussdCode))
            );

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION);

            attachSim(intent, subscriptionId);

            context.startActivity(intent);

            return true;

        } catch (Exception e) {

            clearTimers();
            USSDAccessibilityService.setListener(null);
            USSDAccessibilityService.setEndListener(null);
            return false;
        }
    }

    /** True while an interactive dialer-based session is open. */
    public boolean hasDialerSession() {
        return USSDAccessibilityService.hasActiveSession();
    }

    /** Reply inside the open session (menu number or text). */
    public boolean reply(String text) {
        return USSDAccessibilityService.reply(text);
    }

    /**
     * Close the open session (cancels the system dialog) and drop every
     * timer and listener, so the next command starts from a clean slate.
     */
    public void cancelSession() {

        cancelRetry();
        attemptSeq++; // late answers of an older attempt are ignored

        clearTimers();

        // We are the one cancelling: no callback for our own cancel.
        USSDAccessibilityService.setEndListener(null);

        if (USSDAccessibilityService.hasActiveSession()) {
            USSDAccessibilityService.cancelSession();
        } else {
            USSDAccessibilityService.setListener(null);

            // The session may be over while its system dialog is not.
            USSDAccessibilityService.closeLeftoverDialog();
        }
    }

    private void clearTimers() {

        if (pendingTimeout != null) {
            mainHandler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
    }

    private void attachSim(Intent intent, int subscriptionId) {

        int slot = -1;
        String iccId = null;

        try {

            SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);

            SubscriptionInfo info = sm.getActiveSubscriptionInfo(subscriptionId);

            if (info != null) {
                slot = info.getSimSlotIndex();
                iccId = info.getIccId();
            }

        } catch (SecurityException ignored) {
        }

        if (slot >= 0) {

            // Extras used by many OEM dialers (MIUI, Samsung, etc.)
            intent.putExtra("com.android.phone.force.slot", true);
            intent.putExtra("com.android.phone.extra.slot", slot);
            intent.putExtra("slot", slot);
            intent.putExtra("simSlot", slot);
            intent.putExtra("Cdma_Supp", true);
        }

        try {

            TelecomManager telecom = (TelecomManager) context.getSystemService(
                    Context.TELECOM_SERVICE);

            List<PhoneAccountHandle> accounts = telecom.getCallCapablePhoneAccounts();

            if (accounts == null || accounts.isEmpty()) {
                return;
            }

            PhoneAccountHandle chosen = null;

            for (PhoneAccountHandle handle : accounts) {

                String id = handle.getId();

                if (id == null)
                    continue;

                if (id.equals(String.valueOf(subscriptionId))
                        || (iccId != null && id.contains(iccId))) {

                    chosen = handle;
                    break;
                }
            }

            if (chosen == null && slot >= 0 && slot < accounts.size()) {
                chosen = accounts.get(slot);
            }

            if (chosen != null) {
                intent.putExtra(
                        TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE,
                        chosen);
            }

        } catch (SecurityException ignored) {
        }
    }

    /* ---------- Helpers ---------- */

    public static String describe(int code) {

        switch (code) {

            case ERR_FRAMEWORK_FAILURE:
                return "Network/modem rejected the USSD request (-1).\n"
                        + "Try the code in the phone dialer. If it works there, "
                        + "enable the Smart USSD accessibility service so the app "
                        + "can use the dialer as fallback.";

            case ERR_SERVICE_UNAVAILABLE:
                return "USSD service unavailable (-2). Check signal and try again.";

            case ERR_NO_PERMISSION:
                return "Phone permission missing (CALL_PHONE / READ_PHONE_STATE).";

            case ERR_NO_TELEPHONY:
                return "Telephony service not available on this device.";

            case ERR_ILLEGAL_STATE:
                return "Phone is not ready (airplane mode / no signal?).";

            case ERR_UNSUPPORTED:
                return "This device does not support USSD requests.";

            case ERR_TIMEOUT:
                return "The network did not answer in time.";

            case ERR_API_TOO_LOW:
                return "Android version too old and accessibility service is off.";

            case ERR_INVALID_SIM:
                return "Invalid SIM selected.";

            default:
                return "USSD request failed (code " + code + ").";
        }
    }

    /**
     * Generic system messages that mean "the network refused this code",
     * not a real reply. Shown as an error screen instead of a response.
     */
    public static boolean isNetworkError(String text) {

        if (text == null) {
            return false;
        }

        String t = text.toLowerCase();

        return isServiceUnavailable(text)
                || t.contains("connection problem")
                || t.contains("invalid mmi")
                || t.contains("invalid ussd")
                || t.contains("unknown application")
                || t.contains("ussd request failed")
                || t.contains("ussd failed");
    }

    /** "Sorry, Service not available" and its variants. */
    public static boolean isServiceUnavailable(String text) {

        if (text == null) {
            return false;
        }

        String t = text.toLowerCase();

        return t.contains("service not available")
                || t.contains("service is not available")
                || t.contains("service unavailable")
                || t.contains("service is unavailable")
                || t.contains("service temporarily");
    }

    /** Headline of the error screen for a refused / unavailable reply. */
    public static String refusedTitle(String text) {

        return isServiceUnavailable(text)
                ? "Service not available"
                : "Code not accepted";
    }

    /** Friendly body of the error screen for a refused reply. */
    public static String refusedMessage(String text) {

        String quote = text == null ? "" : text.trim();

        if (isServiceUnavailable(text)) {

            return "The operator's USSD service is not responding right "
                    + "now. This is usually temporary \u2014 check your "
                    + "signal and try again in a moment.\n\n\u201c"
                    + quote + "\u201d";
        }

        return "The network could not run this USSD code. Check the code "
                + "and your SIM balance, then try again.\n\n\u201c"
                + quote + "\u201d";
    }

    public static boolean isTimeout(int code) {
        return code == ERR_TIMEOUT;
    }

    /** Short headline for the error screen. */
    public static String title(int code) {

        switch (code) {

            case ERR_TIMEOUT:
                return "Request timed out";

            case ERR_NO_PERMISSION:
                return "Permission needed";

            case ERR_INVALID_SIM:
                return "Choose a SIM";

            case ERR_SERVICE_UNAVAILABLE:
                return "No service";

            default:
                return "Request failed";
        }
    }

    public static boolean canFixWithAccessibility(Context context, int code) {

        return !USSDAccessibilityService.isEnabled(context)
                && (code == ERR_FRAMEWORK_FAILURE
                        || code == ERR_SERVICE_UNAVAILABLE
                        || code == ERR_UNSUPPORTED
                        || code == ERR_API_TOO_LOW);
    }

    public static void promptEnableAccessibility(Activity activity) {

        new AlertDialog.Builder(activity)
                .setTitle("Enable USSD helper")
                .setMessage(
                        "Your phone blocked the direct USSD request. "
                                + "Enable \"Smart USSD\" in Accessibility settings "
                                + "so the app can read the USSD reply from the dialer.")
                .setPositiveButton("Open settings", (d, w) -> activity.startActivity(new Intent(
                        Settings.ACTION_ACCESSIBILITY_SETTINGS)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deliverFailure(
            USSDCallback callback,
            int errorCode) {

        mainHandler.post(() -> callback.onFailure(errorCode));
    }
}
