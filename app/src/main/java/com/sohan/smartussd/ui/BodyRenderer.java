package com.sohan.smartussd.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.sohan.smartussd.R;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a USSD reply body into a neat card: "Key: value" lines become
 * rows (muted label on the left, bold value on the right), everything
 * else stays as clean paragraph text.
 */
public final class BodyRenderer {

    private static final Pattern PAIR =
            Pattern.compile("^\\s*([^:]{1,30}?)\\s*:\\s*(.+?)\\s*$");

    /** Splits "Balance: TK 0.31, EXP: 09-09-26" into two pairs. */
    private static final Pattern PAIR_SPLIT =
            Pattern.compile(",\\s*(?=[^,:]{1,24}:)");

    /** Numbers, amounts, dates and phone numbers: 0.31, 09-09-26, 017... */
    private static final Pattern NUMBER =
            Pattern.compile("\\d+(?:[.,:/-]\\d+)*");

    private BodyRenderer() {
    }

    /** Makes every number bold and bright so it stands out in a sentence. */
    private static CharSequence highlightNumbers(Context c, String text) {

        SpannableString out = new SpannableString(text);

        Matcher m = NUMBER.matcher(text);

        while (m.find()) {

            out.setSpan(new ForegroundColorSpan(c.getColor(R.color.accent)),
                    m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

            out.setSpan(new StyleSpan(Typeface.BOLD),
                    m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return out;
    }

    public static void render(Context c, LinearLayout box, String body) {

        box.removeAllViews();

        if (body == null || body.trim().isEmpty()) {
            box.setVisibility(View.GONE);
            return;
        }

        box.setVisibility(View.VISIBLE);

        List<String[]> pairs = new ArrayList<>();
        List<String> plain = new ArrayList<>();

        for (String line : body.split("\\r?\\n")) {

            if (line.trim().isEmpty()) {
                continue;
            }

            boolean allPairs = true;
            List<String[]> found = new ArrayList<>();

            for (String part : PAIR_SPLIT.split(line)) {

                Matcher m = PAIR.matcher(part);

                if (m.matches()) {
                    found.add(new String[]{m.group(1), m.group(2)});
                } else {
                    allPairs = false;
                    break;
                }
            }

            if (allPairs && !found.isEmpty()) {
                pairs.addAll(found);
            } else {
                plain.add(line.trim());
            }
        }

        // Plain lines first (greetings, sentences), then the stat rows.
        if (!plain.isEmpty()) {

            TextView t = new TextView(c);
            t.setText(highlightNumbers(c, join(plain)));
            t.setTextColor(c.getColor(R.color.text_primary));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setLineSpacing(dp(c, 5), 1f);
            t.setTextIsSelectable(true);

            box.addView(t);
        }

        if (pairs.isEmpty()) {
            return;
        }

        LinearLayout rows = new LinearLayout(c);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackgroundResource(R.drawable.bg_stat_panel);
        rows.setPadding(dp(c, 16), dp(c, 4), dp(c, 16), dp(c, 4));

        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);

        rp.topMargin = plain.isEmpty() ? 0 : dp(c, 14);

        for (int i = 0; i < pairs.size(); i++) {

            if (i > 0) {
                View line = new View(c);
                line.setBackgroundColor(c.getColor(R.color.stroke));
                rows.addView(line, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 1));
            }

            rows.addView(row(c, pairs.get(i)[0], pairs.get(i)[1], i == 0));
        }

        box.addView(rows, rp);
    }

    private static View row(Context c, String key, String value, boolean hero) {

        RelativeLayout row = new RelativeLayout(c);
        row.setPadding(0, dp(c, hero ? 14 : 12), 0, dp(c, hero ? 14 : 12));

        TextView k = new TextView(c);
        k.setId(View.generateViewId());
        k.setText(key);
        k.setTextColor(c.getColor(R.color.text_muted));
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        RelativeLayout.LayoutParams kp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        kp.addRule(RelativeLayout.ALIGN_PARENT_START);
        kp.addRule(RelativeLayout.CENTER_VERTICAL);

        row.addView(k, kp);

        TextView v = new TextView(c);
        v.setText(value);
        v.setGravity(Gravity.END);
        v.setTextIsSelectable(true);
        v.setTypeface(v.getTypeface(), Typeface.BOLD);
        v.setTextColor(c.getColor(hero ? R.color.accent : R.color.text_primary));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, hero ? 20 : 16);

        RelativeLayout.LayoutParams vp = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT);
        vp.addRule(RelativeLayout.END_OF, k.getId());
        vp.addRule(RelativeLayout.CENTER_VERTICAL);
        vp.setMarginStart(dp(c, 12));

        row.addView(v, vp);

        return row;
    }

    private static String join(List<String> lines) {

        StringBuilder sb = new StringBuilder();

        for (String l : lines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }

        return sb.toString();
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }
}
