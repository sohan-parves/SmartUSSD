package com.sohan.smartussd;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;

import com.sohan.smartussd.models.USSDMenuItem;
import com.sohan.smartussd.ui.ResponsePanel;
import com.sohan.smartussd.ussd.USSDAccessibilityService;
import com.sohan.smartussd.ussd.USSDManager;
import com.sohan.smartussd.ussd.USSDSession;

public class USSDActivity extends Activity {

    private ResponsePanel panel;

    private USSDManager ussdManager;

    private USSDSession session;

    private int subscriptionId;

    private String pathCode = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_ussd);

        panel = new ResponsePanel(findViewById(R.id.rootFrame));

        panel.setCloseAction("Done", this::finish);

        ussdManager = new USSDManager(this);

        session = new USSDSession();

        subscriptionId = getIntent().getIntExtra(
                "subscriptionId", -1);

        String code = getIntent().getStringExtra("ussdCode");

        if (subscriptionId < 0 || code == null) {

            finish();
            return;
        }

        pathCode = code;

        session.start(subscriptionId, code);

        if (!USSDAccessibilityService.isEnabled(this)) {
            panel.showHelperGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    this::finish);
            return;
        }

        sendRequest(code);
    }

    private void sendRequest(String code) {

        panel.showLoading("Contacting network");
        panel.setLoadingCancel(this::finish);

        ussdManager.sendRequest(
                subscriptionId,
                code,
                new USSDManager.USSDCallback() {

                    @Override
                    public void onSuccess(String response) {

                        if (USSDManager.isNetworkError(response)) {
                            showRefused(response);
                            return;
                        }

                        session.addResponse(response);

                        panel.showResponse(response,
                                USSDActivity.this::onMenuChosen);
                    }

                    @Override
                    public void onFailure(int errorCode) {

                        Log.w("SmartUSSD", "USSD failed: " + errorCode
                                + " " + USSDManager.describe(errorCode));

                        showFailure(errorCode);
                    }

                    @Override
                    public void onSessionClosed() {
                        finish();
                    }
                });
    }

    private void showRefused(String text) {

        ussdManager.cancelSession();

        final String firstCode = getIntent().getStringExtra("ussdCode");

        panel.showError(
                USSDManager.isServiceUnavailable(text),
                USSDManager.refusedTitle(text),
                USSDManager.refusedMessage(text),
                "\u2713  Session reset \u2014 ready for a new code",
                "Try again",
                () -> {
                    pathCode = firstCode;
                    sendRequest(firstCode);
                },
                "Close",
                this::finish);
    }

    private void showFailure(int errorCode) {

        // Reset: stale timers, listeners and the open system dialog.
        ussdManager.cancelSession();

        if (USSDManager.canFixWithAccessibility(this, errorCode)) {

            panel.showHelperGuide(
                    () -> startActivity(new Intent(
                            Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                    "Close",
                    this::finish);

            return;
        }

        final boolean timeout = USSDManager.isTimeout(errorCode);

        final String firstCode = getIntent().getStringExtra("ussdCode");

        panel.showError(
                timeout,
                USSDManager.title(errorCode),
                USSDManager.describe(errorCode),
                "✓  Session reset — ready for a new code",
                "Try again",
                () -> {
                    pathCode = firstCode;
                    sendRequest(firstCode);
                },
                "Close",
                this::finish);
    }

    private void onMenuChosen(USSDMenuItem item) {

        String selected = item.getNumber();

        session.addRequest(selected);

        if (ussdManager.hasDialerSession()) {

            // Real USSD session: type the choice into the system dialog.
            panel.showChoiceLoading(selected);

            if (!ussdManager.reply(selected)) {

                panel.hideLoading();
            }

            return;
        }

        String base = pathCode.endsWith("#")
                ? pathCode.substring(0, pathCode.length() - 1)
                : pathCode;

        if (item.isBack()) {

            int cut = base.lastIndexOf('*');

            if (cut <= 0) {
                return;
            }

            pathCode = base.substring(0, cut) + "#";

        } else if (selected.matches("\\d+")) {

            pathCode = base + "*" + selected + "#";

        } else {

            // * and # need the live session (helper); nothing to show.
            return;
        }

        sendRequest(pathCode);
    }

    @Override
    protected void onDestroy() {

        super.onDestroy();

        if (session != null) {
            session.end();
        }

        if (ussdManager != null) {
            ussdManager.cancelSession();
        }
    }
}
