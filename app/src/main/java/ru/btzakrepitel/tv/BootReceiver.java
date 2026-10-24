package ru.btzakrepitel.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
                && Prefs.enabled(c) && !Prefs.address(c).isEmpty()) {
            try {
                Intent service = new Intent(c, MonitorService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(service);
                else c.startService(service);
            } catch (RuntimeException e) {
                Prefs.addLog(c, "Автозапуск отклонён: " + e.getClass().getSimpleName());
            }
        }
    }
}
