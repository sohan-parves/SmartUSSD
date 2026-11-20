
package com.sohan.smartussd.models;

public class USSDMenuItem {

    private final String number;
    private final String title;
    private final String value;

    public USSDMenuItem(String number, String title) {
        this.number = number;
        this.title = title;
        this.value = number;
    }

    public String getNumber() {
        return number;
    }

    public String getTitle() {
        return title;
    }

    public String getValue() {
        return value;
    }

    /** *, # and + are always navigation; 0 only when it says back/home/etc. */
    public boolean isNavigation() {

        if (number.equals("*") || number.equals("#") || number.equals("+")) {
            return true;
        }

        return number.equals("0") && title.toLowerCase().matches(
                ".*\\b(back|home|main|menu|exit|previous|prev)\\b.*");
    }

    public boolean isBack() {

        return title.toLowerCase().matches(
                ".*\\b(back|previous|prev)\\b.*");
    }
}
