package ru.btzakrepitel.tv;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Prefs {
    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("bt_pinned", Context.MODE_PRIVATE);
    }
    static String address(Context c) { return prefs(c).getString("address", ""); }
    static String name(Context c) { return prefs(c).getString("name", ""); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("enabled", false); }
    static String status(Context c) { return prefs(c).getString("status", "Ожидание"); }
    static String log(Context c) { return prefs(c).getString("log", ""); }
    static void status(Context c, String s) { prefs(c).edit().putString("status", s).apply(); }
    static void pin(Context c, String addr, String name) {
        prefs(c).edit().putString("address", addr).putString("name", name)
            .putBoolean("enabled", true).apply();
    }
    static void setEnabled(Context c, boolean value) {
        prefs(c).edit().putBoolean("enabled", value).apply();
    }
    static void unpin(Context c) {
        prefs(c).edit().remove("address").remove("name").putBoolean("enabled",false).apply();
    }
    static synchronized void addLog(Context c, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String[] lines = (time+" — "+message+"\n"+log(c)).split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i=0; i<Math.min(lines.length,25); i++) sb.append(lines[i]).append("\n");
        prefs(c).edit().putString("log",sb.toString()).putString("status",message).apply();
    }
}
