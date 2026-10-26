package ru.frolov.tvvolumebooster;

import android.content.Context;
import android.content.SharedPreferences;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class BluetoothPrefs {
    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("bt_pin_v1", Context.MODE_PRIVATE);
    }
    static String address(Context c) { return p(c).getString("address", ""); }
    static String name(Context c) { return p(c).getString("name", ""); }
    static boolean enabled(Context c) { return p(c).getBoolean("enabled", false); }
    static String status(Context c) { return p(c).getString("status", "Ожидание подключения"); }
    static String log(Context c) { return p(c).getString("log", ""); }

    static void pin(Context c, String address, String name) {
        p(c).edit().putString("address", address).putString("name", name)
                .putBoolean("enabled", true).putString("status", "Устройство закреплено").apply();
        addLog(c, "Закреплено: " + name);
    }
    static void unpin(Context c) {
        addLog(c, "Закрепление снято");
        p(c).edit().remove("address").remove("name").putBoolean("enabled", false)
                .putString("status", "Мониторинг отключён").apply();
    }
    static void status(Context c, String status) {
        p(c).edit().putString("status", status).apply();
    }
    static synchronized void addLog(Context c, String message) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String source = time + "  " + message + "\n" + log(c);
        String[] lines = source.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 15); i++) {
            if (!lines[i].isEmpty()) b.append(lines[i]).append("\n");
        }
        p(c).edit().putString("log", b.toString()).putString("status", message).apply();
    }
}
