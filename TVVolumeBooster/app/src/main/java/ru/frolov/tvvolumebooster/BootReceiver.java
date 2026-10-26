package ru.frolov.tvvolumebooster;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (!context.getSharedPreferences("boost", Context.MODE_PRIVATE)
                .getBoolean("running", false)) return;
        Intent service = new Intent(context, BoostService.class);
        service.setAction(BoostService.ACTION_RESTORE);
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (RuntimeException e) {
            Log.w("TVVolumeBooster", "Auto-restore not allowed on this TV", e);
        }
    }
}
