package com.vijay.localfileshare;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Shared look for every screen: colours, cards, buttons and small layout helpers. */
final class Ui {
    static final int BG = 0xFFF2F5FB;
    static final int SURFACE = 0xFFFFFFFF;
    static final int PRIMARY = 0xFF1463FF;
    static final int PRIMARY_SOFT = 0xFFE3ECFF;
    static final int INK = 0xFF14181F;
    static final int MUTED = 0xFF5D6675;
    static final int LINE = 0xFFE1E6EF;
    static final int DANGER = 0xFFD93025;
    static final int OK = 0xFF1E8E5A;

    static final int MATCH = LinearLayout.LayoutParams.MATCH_PARENT;
    static final int WRAP = LinearLayout.LayoutParams.WRAP_CONTENT;

    private Ui() {
    }

    static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context c, int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, radiusDp));
        return drawable;
    }

    static Drawable ripple(Context c, int color, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), shape(c, color, radiusDp), shape(c, 0xFFFFFFFF, radiusDp));
    }

    static TextView text(Context c, CharSequence value, float sp, int color, boolean bold) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    static TextView oneLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        return view;
    }

    static LinearLayout column(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout row(Context c) {
        LinearLayout layout = new LinearLayout(c);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }

    static LinearLayout card(Context c) {
        LinearLayout layout = column(c);
        layout.setBackground(shape(c, SURFACE, 24));
        int pad = dp(c, 20);
        layout.setPadding(pad, pad, pad, pad);
        return layout;
    }

    static TextView button(Context c, String label, boolean primary) {
        TextView view = text(c, label, 15, primary ? 0xFFFFFFFF : PRIMARY, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(c, 22), dp(c, 13), dp(c, 22), dp(c, 13));
        view.setBackground(ripple(c, primary ? PRIMARY : PRIMARY_SOFT, 28));
        view.setClickable(true);
        return view;
    }

    static void setEnabled(TextView button, boolean enabled) {
        button.setEnabled(enabled);
        button.setAlpha(enabled ? 1f : 0.4f);
    }

    static ImageView icon(Context c, int res, int tint) {
        ImageView view = new ImageView(c);
        view.setImageResource(res);
        view.setColorFilter(tint);
        return view;
    }

    /** An icon centred on a round, softly tinted background. */
    static ImageView iconCircle(Context c, int res, int sizeDp, int background, int tint) {
        ImageView view = icon(c, res, tint);
        int pad = dp(c, sizeDp * 0.27f);
        view.setPadding(pad, pad, pad, pad);
        view.setBackground(shape(c, background, sizeDp / 2f));
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return view;
    }

    static LinearLayout.LayoutParams lp(int width, int height) {
        return new LinearLayout.LayoutParams(width, height);
    }

    static LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, WRAP, 1);
    }

    static <T extends View> T margin(Context c, T view, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = view.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) view.getLayoutParams()
                : lp(MATCH, WRAP);
        params.setMargins(dp(c, left), dp(c, top), dp(c, right), dp(c, bottom));
        view.setLayoutParams(params);
        return view;
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }
}
