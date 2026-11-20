
package com.sohan.smartussd.ussd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class USSDSession {

    private int subscriptionId;

    private String currentCode = "";

    private String currentResponse = "";

    private boolean active = false;

    private final List<String> history =
            new ArrayList<>();

    public void start(int subscriptionId, String code) {

        this.subscriptionId = subscriptionId;
        this.currentCode = code;
        this.currentResponse = "";
        this.active = true;

        history.clear();

        history.add("Request: " + code);
    }

    public void addResponse(String response) {

        currentResponse = response;

        history.add("Response: " + response);
    }

    public void addRequest(String request) {

        currentCode = request;

        history.add("Request: " + request);
    }

    public int getSubscriptionId() {
        return subscriptionId;
    }

    public String getCurrentCode() {
        return currentCode;
    }

    public String getCurrentResponse() {
        return currentResponse;
    }

    public boolean isActive() {
        return active;
    }

    public List<String> getHistory() {

        return Collections.unmodifiableList(
                new ArrayList<>(history)
        );
    }

    public void end() {
        active = false;
    }

    public void clear() {

        active = false;
        currentCode = "";
        currentResponse = "";
        history.clear();
    }
}
