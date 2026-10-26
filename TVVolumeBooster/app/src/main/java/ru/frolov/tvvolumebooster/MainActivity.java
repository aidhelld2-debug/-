package ru.frolov.tvvolumebooster;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(9, 18, 31);
    private static final int CYAN = Color.rgb(60, 209, 226);
    private TextView status;
    private TextView detail;
    private final Handler handler = new Handler();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BACKGROUND);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(52), dp(26), dp(52), dp(30));
        scroll.addView(root);
        setContentView(scroll);

        root.addView(label("DEXP  •  ЯНДЕКС ТВ   /   ЗВУК", 17, CYAN, true));
        marginTop(root, label("УСИЛИТЕЛЬ ГРОМКОСТИ", 34, Color.WHITE, true), 9);
        marginTop(root, label("Выберите усиление. Оно останется включённым после выхода из приложения.",
                18, Color.rgb(187, 201, 215), false), 5);
        status = label("", 21, CYAN, true);
        marginTop(root, status, 20);
        detail = label("", 15, Color.rgb(198, 208, 219), false);
        marginTop(root, detail, 3);

        LinearLayout row1 = row();
        marginTop(root, row1, 17);
        LinearLayout row2 = row();
        marginTop(root, row2, 6);
        Button first = preset(row1, "×1,5", "+50%  •  МЯГКО", 1);
        preset(row1, "×2", "+100%  •  СРЕДНЕ", 2);
        preset(row2, "×3", "+200%  •  СИЛЬНО", 3);
        preset(row2, "×4", "+300%  •  МАКСИМУМ", 4);

        Button stop = button("■  ВЫКЛЮЧИТЬ УСИЛЕНИЕ", false);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(68));
        stopParams.topMargin = dp(12);
        root.addView(stop, stopParams);
        stop.setOnClickListener(v -> {
            Intent service = new Intent(this, BoostService.class);
            service.setAction(BoostService.ACTION_STOP);
            startService(service);
            handler.postDelayed(this::refresh, 280);
        });
        marginTop(root, label("Внимание: ×1,5–×4 — заданное усиление цифрового сигнала, "
                + "не гарантия такой же воспринимаемой громкости. "
                + "На некоторых версиях Яндекс ТВ сторонние программы не могут обрабатывать системный звук. "
                + "При хрипах уменьшите уровень, чтобы не повредить динамики.",
                14, Color.rgb(157, 171, 185), false), 12);
        first.requestFocus();
        refresh();
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private Button preset(LinearLayout row, String multiplier, String description, int level) {
        Button b = button(multiplier + "\n" + description, true);
        b.setTextSize(23);
        b.setMinHeight(dp(87));
        b.setPadding(dp(10), dp(7), dp(10), dp(7));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(91), 1f);
        params.rightMargin = dp(level % 2 == 1 ? 10 : 0);
        row.addView(b, params);
        b.setOnClickListener(v -> {
            Intent service = new Intent(this, BoostService.class);
            service.setAction(BoostService.ACTION_SET);
            service.putExtra(BoostService.EXTRA_LEVEL, level);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            status.setText("ПРИМЕНЯЕТСЯ " + multiplier + "...");
            handler.postDelayed(this::refresh, 350);
        });
        return b;
    }

    private Button button(String text, boolean boost) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextSize(18);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setFocusable(true);
        b.setBackground(buttonBackground(boost));
        return b;
    }

    private StateListDrawable buttonBackground(boolean boost) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] { android.R.attr.state_focused }, shape(CYAN, dp(16)));
        states.addState(new int[] { android.R.attr.state_pressed }, shape(CYAN, dp(16)));
        states.addState(new int[0], shape(boost ? Color.rgb(27, 57, 76)
                : Color.rgb(111, 44, 54), dp(16)));
        return states;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void marginTop(LinearLayout parent, View view, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(top);
        parent.addView(view, params);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void refresh() {
        SharedPreferences prefs = getSharedPreferences("boost", MODE_PRIVATE);
        boolean running = prefs.getBoolean("running", false);
        int level = prefs.getInt("level", 0);
        status.setText(running ? "●  УСИЛЕНИЕ АКТИВНО   ·   ×"
                + (level == 1 ? "1,5" : Integer.toString(level)) : "○  УСИЛЕНИЕ ВЫКЛЮЧЕНО");
        detail.setText(prefs.getString("status",
                "Выберите режим. Для всех приложений требуется поддержка прошивкой."));
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }
}
