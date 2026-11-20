package com.sohan.smartussd.ussd;

import com.sohan.smartussd.models.USSDMenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class USSDResponseParser {

    /** "1. Balance", "2) Offers", "0. Back", "*. Next", "#. Home" */
    private static final Pattern MENU_PATTERN =
            Pattern.compile(
                    "(?m)^\\s*([0-9\u09E6-\u09EF]{1,3}|[*#+])\\s*[.)\\-:=\u2013\u2014\u0964]\\s*(.+?)\\s*$"
            );

    /** "0 Back", "* Next", "# Home" (no separator, navigation words only) */
    private static final Pattern NAV_PATTERN =
            Pattern.compile(
                    "(?mi)^\\s*([0\u09E6*#+])\\s+((?:back|next|more|home|main|menu|prev|previous|exit)\\b.*?)\\s*$"
            );

    public List<USSDMenuItem> parse(String response) {

        List<USSDMenuItem> items = new ArrayList<>();

        if (response == null || response.trim().isEmpty()) {
            return items;
        }

        collect(MENU_PATTERN, response, items);
        collect(NAV_PATTERN, response, items);

        return items;
    }

    private void collect(
            Pattern pattern,
            String response,
            List<USSDMenuItem> items
    ) {

        Matcher matcher = pattern.matcher(response);

        while (matcher.find()) {

            String number = normalizeDigits(matcher.group(1).trim());
            String title = matcher.group(2).trim();

            if (title.isEmpty() || contains(items, number)) {
                continue;
            }

            items.add(new USSDMenuItem(number, title));
        }
    }

    /** Bangla digits (\u09E6-\u09EF) -> 0-9, because the reply must be sent in ASCII. */
    static String normalizeDigits(String s) {

        StringBuilder sb = new StringBuilder(s.length());

        for (char ch : s.toCharArray()) {
            sb.append(ch >= '\u09E6' && ch <= '\u09EF'
                    ? (char) ('0' + (ch - '\u09E6'))
                    : ch);
        }

        return sb.toString();
    }

    private boolean contains(List<USSDMenuItem> items, String number) {

        for (USSDMenuItem item : items) {
            if (item.getNumber().equals(number)) {
                return true;
            }
        }

        return false;
    }

    /** The response without the menu option lines (those become buttons). */
    public String bodyText(String response) {

        if (response == null) {
            return "";
        }

        StringBuilder body = new StringBuilder();

        for (String line : response.split("\\r?\\n")) {

            if (MENU_PATTERN.matcher(line).find()
                    || NAV_PATTERN.matcher(line).find()) {
                continue;
            }

            if (line.trim().isEmpty() && body.length() == 0) {
                continue;
            }

            body.append(line).append('\n');
        }

        return body.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    public boolean hasMenu(String response) {
        return !parse(response).isEmpty();
    }
}
