package ru.frolov.tvvolumebooster;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;

        if (context.getSharedPreferences("boost", Context.MODE_PRIVATE).getBoolean("running", false)) {
            Intent boost = new Intent(context, BoostService.class);
            boost.setAction(BoostService.ACTION_RESTORE);
            startSafe(context, boost);
        }
        if (BluetoothPrefs.enabled(context) && !BluetoothPrefs.address(context).isEmpty()) {
            startSafe(context, new Intent(context, BluetoothMonitorService.class));
        }
    }
    private void startSafe(Context context, Intent service) {
        try {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        } catch (RuntimeException e) {
            Log.w("TVAudioCenter", "YaOS blocked automatic service start", e);
        }
    }
}
