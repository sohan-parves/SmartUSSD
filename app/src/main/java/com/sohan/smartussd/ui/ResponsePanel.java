package com.sohan.smartussd.ui;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import com.sohan.smartussd.R;
import com.sohan.smartussd.adapters.USSDMenuAdapter;
import com.sohan.smartussd.models.USSDMenuItem;
import com.sohan.smartussd.ussd.USSDResponseParser;

import java.util.List;

/**
 * Full-page loader and full-page result, shared by every screen.
 * Only the needed message text and menu buttons are shown.
 */
public class ResponsePanel {

    private final View root;
    private final LoadingView loading;
    private final ErrorView errorView;
    private final View page;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final LinearLayout menu;
    private final Button closeButton;

    private final USSDResponseParser parser = new USSDResponseParser();
    private final USSDMenuAdapter adapter;

    private Runnable onClose = this::hideResult;

    /** @param root view that contains include_result.xml */
    public ResponsePanel(View root) {

        this.root = root;

        loading = root.findViewById(R.id.loadingView);
        errorView = root.findViewById(R.id.errorView);
        page = root.findViewById(R.id.resultPage);
        scroll = root.findViewById(R.id.resultScroll);

        final View content = root.findViewById(R.id.resultContent);
        final View bar = root.findViewById(R.id.bottomBar);

        // Keep the last lines of content clear of the bottom bar.
        bar.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> content.setPadding(
                content.getPaddingLeft(),
                content.getPaddingTop(),
                content.getPaddingRight(),
                (b - t) + (int) (16 * root.getResources()
                        .getDisplayMetrics().density)));
        body = root.findViewById(R.id.bodyContainer);
        menu = root.findViewById(R.id.menuContainer);
        closeButton = root.findViewById(R.id.btnNewCode);

        adapter = new USSDMenuAdapter(root.getContext(), menu);
        adapter.setNavContainer(root.findViewById(R.id.navContainer));

        closeButton.setOnClickListener(v -> onClose.run());
    }

    public void setCloseAction(String label, Runnable action) {
        closeButton.setText(label);
        onClose = action;
    }

    public boolean isLoading() {
        return loading.isShowing();
    }

    public boolean isResultVisible() {
        return page.getVisibility() == View.VISIBLE;
    }

    public void showLoading(String message) {
        errorView.hideNow();
        loading.show(message);
    }

    /** Loader 2: shows the option that is being sent. */
    public void showChoiceLoading(String choice) {
        errorView.hideNow();
        loading.showChoice(choice);
    }

    /** Cancel button for the full-page loader. */
    public void setLoadingCancel(Runnable action) {
        loading.setCancelAction(action);
    }

    public boolean isErrorVisible() {
        return errorView.isShowing();
    }

    /**
     * Full-page timeout / error screen.
     *
     * @param timeout true for the amber timeout look, false for errors
     */
    public void showError(
            boolean timeout,
            String title,
            String message,
            String note,
            String primaryLabel,
            Runnable onPrimary,
            String secondaryLabel,
            Runnable onSecondary) {
        loading.hideNow();

        errorView.show(
                timeout ? ErrorView.Kind.TIMEOUT : ErrorView.Kind.ERROR,
                title, message, note,
                primaryLabel, onPrimary,
                secondaryLabel, onSecondary);
    }

    /** Friendly one-button screen that explains how to turn the helper on. */
    public void showHelperGuide(Runnable openSettings) {
        showHelperGuide(openSettings, null, null);
    }

    /** Same guide with an optional second button (e.g. "Later"). */
    public void showHelperGuide(
            Runnable openSettings,
            String secondaryLabel,
            Runnable onSecondary) {

        loading.hideNow();

        errorView.show(
                ErrorView.Kind.INFO,
                "Enable USSD helper",
                "One quick permission lets Smart USSD read your phone's USSD "
                        + "reply and show it here.",
                null,
                new String[] {
                        "Tap the button below to open Accessibility settings.",
                        "Open \"Installed apps\" (or \"Downloaded apps\") and "
                                + "choose Smart USSD.",
                        "Turn the switch ON and tap Allow.",
                        "Come back here to continue setup.",
                        "Tip: if the switch is greyed out, open Settings › Apps › "
                                + "Smart USSD, tap ⋮ and choose \"Allow restricted "
                                + "settings\"."
                },
                "Enable USSD helper",
                openSettings,
                secondaryLabel,
                onSecondary);
    }

    /**
     * Guide when the service stopped ("Not working. Tap for info.") and needs a
     * quick toggle.
     */
    public void showHelperRestartGuide(
            Runnable openSettings,
            String secondaryLabel,
            Runnable onSecondary) {
        loading.hideNow();

        errorView.show(
                ErrorView.Kind.TIMEOUT,
                "Restart USSD helper",
                "The helper service stopped when the app was closed (\"Not working. Tap for info.\"). A quick OFF/ON toggle will reactivate it.",
                null,
                new String[] {
                        "Tap the button below to open Accessibility settings.",
                        "Tap Smart USSD (which says \"Not working. Tap for info.\").",
                        "Turn the switch OFF, then turn it back ON.",
                        "Come back here and your USSD will dial automatically.",
                        "Tip: In Settings › Apps › Smart USSD, turn on \"Autostart\" and set Battery Saver to \"No restrictions\" so it never stops."
                },
                "Open Accessibility settings",
                openSettings,
                secondaryLabel,
                onSecondary);
    }

    public void hideError() {
        errorView.hide();
    }

    /** Drops loader, result page and error screen at once. */
    public void resetAll() {

        loading.hideNow();
        errorView.hideNow();

        page.animate().cancel();
        page.setVisibility(View.GONE);
    }

    public void hideLoading() {
        loading.hide();
    }

    public void showResponse(
            String response,
            USSDMenuAdapter.OnMenuClickListener listener) {

        List<USSDMenuItem> items = parser.parse(response);

        String body = items.isEmpty()
                ? (response == null ? "" : response.trim())
                : parser.bodyText(response);

        errorView.hideNow();

        bindText(body, !items.isEmpty());

        adapter.setItems(items, listener);

        scroll.scrollTo(0, 0);

        page.animate().cancel();

        page.setAlpha(0f);
        page.setTranslationY(
                10 * root.getResources().getDisplayMetrics().density);
        page.setVisibility(View.VISIBLE);

        page.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .start();

        loading.hide();
    }

    public void hideResult() {

        if (!isResultVisible()) {
            return;
        }

        page.animate().cancel();

        page.animate()
                .alpha(0f)
                .setDuration(140)
                .withEndAction(() -> page.setVisibility(View.GONE))
                .start();
    }

    private void bindText(String body, boolean hasMenu) {

        BodyRenderer.render(root.getContext(), this.body, body);

        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) menu.getLayoutParams();

        params.topMargin = (hasMenu && !body.isEmpty())
                ? (int) (18 * root.getResources().getDisplayMetrics().density)
                : 0;

        menu.setLayoutParams(params);
    }
}
