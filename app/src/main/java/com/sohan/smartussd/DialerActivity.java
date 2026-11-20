package com.sohan.smartussd;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import com.sohan.smartussd.ui.Feedback;

import com.sohan.smartussd.ui.DialPadView;

public class DialerActivity extends Activity {

    private EditText codeInput;

    private int subscriptionId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_dialer);

        codeInput = findViewById(R.id.codeInput);

        // Custom keypad only - no system keyboard.
        codeInput.setShowSoftInputOnFocus(false);
        codeInput.requestFocus();

        subscriptionId = getIntent().getIntExtra(
                "subscriptionId", -1
        );

        DialPadView dialPad = findViewById(R.id.dialPad);

        dialPad.setOnKeyListener(key -> {

            int start = Math.max(codeInput.getSelectionStart(), 0);
            int end = Math.max(codeInput.getSelectionEnd(), 0);

            codeInput.getText().replace(
                    Math.min(start, end),
                    Math.max(start, end),
                    key
            );

            codeInput.setError(null);
        });

        ImageButton backspace = findViewById(R.id.btnBackspace);

        backspace.setOnClickListener(v -> {

            Editable text = codeInput.getText();

            int start = codeInput.getSelectionStart();
            int end = codeInput.getSelectionEnd();

            if (start != end && start >= 0) {
                text.delete(Math.min(start, end), Math.max(start, end));
            } else if (start > 0) {
                text.delete(start - 1, start);
            }
        });

        backspace.setOnLongClickListener(v -> {
            codeInput.setText("");
            return true;
        });

        Button send = findViewById(R.id.btnDialSend);

        send.setOnClickListener(v -> openUSSD());
    }

    private void openUSSD() {

        String code = codeInput.getText()
                .toString().trim();

        if (subscriptionId < 0) {

            Feedback.reject(codeInput);

            return;
        }

        if (!code.startsWith("*")
                || !code.endsWith("#")) {

            Feedback.reject(codeInput);
            return;
        }

        Intent intent = new Intent(
                this,
                USSDActivity.class
        );

        intent.putExtra("subscriptionId", subscriptionId);
        intent.putExtra("ussdCode", code);

        startActivity(intent);
    }
}
