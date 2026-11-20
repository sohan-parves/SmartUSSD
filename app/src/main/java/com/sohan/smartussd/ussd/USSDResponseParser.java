package com.sohan.smartussd.ussd;

import com.sohan.smartussd.models.USSDMenuItem;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class USSDResponseParser {

    private static final String DIGIT = "[0-9\u09E6-\u09EF]";

    /** Optional list bullet in front of a line: "- ", "• ", "* " is NOT one (it is a key). */
    private static final String BULLET = "(?:[\u2022\u00B7\u25AA\u25CF\\-]\\s*)?";

    /** "1. Balance", "2) Offers", "3]Packs", "0. Back", "*. Next", "#. Home" */
    private static final Pattern MENU_PATTERN =
            Pattern.compile(
                    "^\\s*" + BULLET + "(" + DIGIT + "{1,3}|[*#+])\\s*[.)\\]>|\\-:=\u2013\u2014\u0964]\\s*(.+?)\\s*$"
            );

    /** "0 Back", "* Next", "# Home" (no separator, navigation words only) */
    private static final Pattern NAV_PATTERN =
            Pattern.compile(
                    "(?i)^\\s*" + BULLET + "([0\u09E6*#+])\\s+((?:back|next|more|home|main|menu|prev|previous|exit)\\b.*?)\\s*$"
            );

    /** "1 Send Money" (no separator). Title must not start with a digit. */
    private static final Pattern PLAIN_PATTERN =
            Pattern.compile(
                    "^\\s*" + BULLET + "(" + DIGIT + "{1,2})\\s+(?=[^0-9\u09E6-\u09EF\\s])(.+?)\\s*$"
            );

    /** One option inside a line that holds several: "1.Balance 2.Packs 3.Offers" */
    private static final Pattern INLINE_PATTERN =
            Pattern.compile(
                    "(?<![\\w.])(" + DIGIT + "{1,2}|[*#])\\s*[.)\\]>|:=\\-]\\s*(?=[^0-9\u09E6-\u09EF\\s])"
                            + "(.+?)(?=\\s+(?:" + DIGIT + "{1,2}|[*#])\\s*[.)\\]>|:=\\-]\\s*[^\\s]|\\s*$)"
            );

    /** What one pass over a reply found. */
    private static final class Analysis {

        final List<USSDMenuItem> items = new ArrayList<>();

        /** Lines that are option lines (they become buttons, not body). */
        final boolean[] consumed;

        final String[] lines;

        Analysis(String[] lines) {
            this.lines = lines;
            this.consumed = new boolean[lines.length];
        }
    }

    public List<USSDMenuItem> parse(String response) {

        if (response == null || response.trim().isEmpty()) {
            return new ArrayList<>();
        }

        return analyze(response).items;
    }

    private Analysis analyze(String response) {

        Analysis a = new Analysis(response.split("\\r\\n|\\r|\\n"));

        // Pass 1: one option per line, with a separator ("1. Balance").
        for (int i = 0; i < a.lines.length; i++) {

            String line = a.lines[i];

            Matcher m = MENU_PATTERN.matcher(line);

            if (!m.find()) {
                m = NAV_PATTERN.matcher(line);

                if (!m.find()) {
                    continue;
                }
            }

            add(a, i, m.group(1), m.group(2));
        }

        if (countRegular(a.items) >= 2) {
            return a;
        }

        // Pass 2a: several options on one line ("1.Balance 2.Packs").
        for (int i = 0; i < a.lines.length; i++) {

            if (a.consumed[i]) {
                continue;
            }

            List<String[]> found = new ArrayList<>();

            int firstStart = -1;

            Matcher m = INLINE_PATTERN.matcher(a.lines[i]);

            while (m.find()) {

                if (firstStart < 0) {
                    firstStart = m.start();
                }

                found.add(new String[]{m.group(1), m.group(2)});
            }

            if (found.size() >= 2) {

                // Text in front of the first option ("Choose 1) A 2) B")
                // stays in the body; the options become buttons.
                String prefix = a.lines[i].substring(0, firstStart).trim();

                for (String[] f : found) {
                    add(a, i, f[0], f[1]);
                }

                a.lines[i] = prefix;
                a.consumed[i] = prefix.isEmpty();
            }
        }

        if (countRegular(a.items) >= 2) {
            return a;
        }

        // Pass 2b: "1 Send Money" lines without any separator. Accepted
        // only as a run that counts up (1, 2, 3...), so a line such as
        // "30 days validity" is never mistaken for a menu option.
        List<Integer> run = new ArrayList<>();
        int last = -1;

        for (int i = 0; i < a.lines.length; i++) {

            if (a.consumed[i]) {
                continue;
            }

            Matcher m = PLAIN_PATTERN.matcher(a.lines[i]);

            if (!m.find()) {
                continue;
            }

            int n = Integer.parseInt(normalizeDigits(m.group(1)));

            if (run.isEmpty()) {

                if (n <= 1) {
                    run.add(i);
                    last = n;
                }

            } else if (n == last + 1) {

                run.add(i);
                last = n;
            }
        }

        if (run.size() >= 2) {

            for (int i : run) {

                Matcher m = PLAIN_PATTERN.matcher(a.lines[i]);

                if (m.find()) {
                    add(a, i, m.group(1), m.group(2));
                }
            }
        }

        return a;
    }

    private void add(Analysis a, int line, String rawNumber, String rawTitle) {

        String number = normalizeDigits(rawNumber.trim());
        String title = rawTitle.trim();

        a.consumed[line] = true;

        if (title.isEmpty() || contains(a.items, number)) {
            return;
        }

        a.items.add(new USSDMenuItem(number, title));
    }

    private int countRegular(List<USSDMenuItem> items) {

        int n = 0;

        for (USSDMenuItem item : items) {
            if (!item.isNavigation()) {
                n++;
            }
        }

        return n;
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

        Analysis a = analyze(response);

        StringBuilder body = new StringBuilder();

        for (int i = 0; i < a.lines.length; i++) {

            if (a.consumed[i]) {
                continue;
            }

            String line = a.lines[i];

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