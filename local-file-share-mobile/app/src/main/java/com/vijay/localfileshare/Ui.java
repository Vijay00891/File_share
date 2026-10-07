package com.vijay.localfileshare;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
    // Set by applyTheme() to the light or dark palette; never cache these across screens.
    static int BG;
    static int SURFACE;
    static int PRIMARY;
    static int PRIMARY_SOFT;
    static int ON_PRIMARY;
    static int INK;
    static int MUTED;
    static int LINE;
    static int DANGER;
    static int OK;
    private static int RIPPLE;

    static {
        setPalette(false);
    }

    /** Follows the phone's light or dark setting. Call before building any views. */
    static void applyTheme(Context c) {
        int night = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        setPalette(night == Configuration.UI_MODE_NIGHT_YES);
    }

    private static void setPalette(boolean dark) {
        BG = dark ? 0xFF0F1218 : 0xFFF2F5FB;
        SURFACE = dark ? 0xFF1A1F29 : 0xFFFFFFFF;
        PRIMARY = dark ? 0xFF7AA2FF : 0xFF1463FF;
        PRIMARY_SOFT = dark ? 0xFF1F2C4D : 0xFFE3ECFF;
        ON_PRIMARY = dark ? 0xFF0B1220 : 0xFFFFFFFF;
        INK = dark ? 0xFFE8ECF4 : 0xFF14181F;
        MUTED = dark ? 0xFF9AA4B5 : 0xFF5D6675;
        LINE = dark ? 0xFF2A3140 : 0xFFE1E6EF;
        DANGER = dark ? 0xFFFF6B60 : 0xFFD93025;
        OK = dark ? 0xFF4CC38A : 0xFF1E8E5A;
        RIPPLE = dark ? 0x33FFFFFF : 0x22000000;
    }

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
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE), shape(c, color, radiusDp), shape(c, 0xFFFFFFFF, radiusDp));
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
        TextView view = text(c, label, 15, primary ? ON_PRIMARY : PRIMARY, true);
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
