package ru.dexp.gappssetup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;

public class MainActivity extends Activity {
    static WeakReference<MainActivity> current = new WeakReference<>(null);
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Button[] buttons = new Button[3];
    private TextView status;
    private boolean busy;
    private static final String[] FILES = {"gsf.apk", "gms.apk", "store.apk"};
    private static final String[] NAMES = {
            "1. Google Services Framework",
            "2. Google Play Services",
            "3. Google Play Store"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        current = new WeakReference<>(this);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(getResources().getDisplayMetrics().density * 24);
        layout.setPadding(pad, pad, pad, pad);
        scroll.addView(layout);
        TextView title = new TextView(this);
        title.setText("Google Play для DEXP / Яндекс ТВ");
        title.setTextSize(24);
        layout.addView(title);
        TextView info = new TextView(this);
        info.setText("Устанавливайте по порядку: 1, 2, 3. Каждый раз подтверждайте установку и возвращайтесь сюда. Работа Google Play зависит от прошивки ТВ.");
        info.setTextSize(17);
        info.setPadding(0, pad, 0, pad);
        layout.addView(info);
        for (int i = 0; i < FILES.length; i++) {
            final int index = i;
            Button button = new Button(this);
            button.setAllCaps(false);
            button.setText(NAMES[i]);
            button.setTextSize(17);
            button.setFocusable(true);
            button.setOnClickListener(v -> install(index));
            layout.addView(button);
            buttons[i] = button;
        }
        Button permission = new Button(this);
        permission.setAllCaps(false);
        permission.setText("Разрешить установку из этого источника");
        permission.setOnClickListener(v -> allowInstall());
        layout.addView(permission);
        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, pad, 0, 0);
        layout.addView(status);
        setContentView(scroll);
        buttons[0].requestFocus();
        StringBuilder report = new StringBuilder("Установщик запущен. ");
        for (String name : FILES) {
            try (InputStream s = getAssets().open(name)) { report.append(name).append(": OK; "); }
            catch (Exception e) { report.append(name).append(": НЕТ; "); }
        }
        show(report.toString());
    }

    private void show(String message) { if (status != null) status.setText(message); }
    static void onInstallStatus(String message) {
        MainActivity a = current.get();
        if (a != null) a.runOnUiThread(() -> a.show(message));
    }

    private void allowInstall() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Разрешите установку в настройках Android", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void install(int index) {
        if (busy) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            show("Сначала разрешите установку из этого источника, затем повторите.");
            allowInstall();
            return;
        }
        busy = true;
        for (Button b : buttons) b.setEnabled(false);
        show("Подготовка: " + NAMES[index]);
        new Thread(() -> {
            try {
                PackageInstaller installer = getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                        PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                int id = installer.createSession(params);
                try (PackageInstaller.Session session = installer.openSession(id)) {
                    try (InputStream src = getAssets().open(FILES[index]);
                         OutputStream dst = session.openWrite("base.apk", 0, -1)) {
                        byte[] buf = new byte[65536];
                        int read;
                        long total = 0;
                        while ((read = src.read(buf)) != -1) {
                            dst.write(buf, 0, read);
                            total += read;
                        }
                        session.fsync(dst);
                        if (total < 200) throw new IllegalStateException("APK отсутствует или пустой");
                    }
                    Intent callback = new Intent(this, InstallResultReceiver.class)
                            .setAction("ru.dexp.gappssetup.INSTALL_RESULT")
                            .putExtra("component", NAMES[index]);
                    int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
                    PendingIntent pi = PendingIntent.getBroadcast(this, id, callback, flags);
                    session.commit(pi.getIntentSender());
                }
                ui.post(() -> show("Ожидание подтверждения: " + NAMES[index]));
            } catch (Exception e) {
                ui.post(() -> show("Ошибка: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
            } finally {
                ui.post(() -> { busy = false; for (Button b : buttons) b.setEnabled(true); });
            }
        }).start();
    }
    @Override public void onDestroy() {
        if (current.get() == this) current.clear();
        super.onDestroy();
    }
}
