package ru.frolov.tvvolumebooster;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        StartupManager.startServices(context);
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean autoOpen = context.getSharedPreferences("tv_settings", Context.MODE_PRIVATE)
                .getBoolean("show_on_boot", true);
        // Best effort only. Android 10+ often blocks background UI starts.
        // Background services are started first and keep working without this window.
        if (boot && autoOpen) {
            try {
                Intent activity = new Intent(context, MainActivity.class);
                activity.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                context.startActivity(activity);
            } catch (RuntimeException e) {
                Log.i("TVAudio", "Android TV blocked boot-time foreground UI", e);
            }
        }
    }
}
