package ru.dexp.gappssetup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        int result = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String part = intent.getStringExtra("component");
        if (part == null) part = "Компонент";
        if (result == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                    MainActivity.onInstallStatus("Подтвердите установку: " + part);
                } catch (Exception ex) {
                    MainActivity.onInstallStatus("Не удалось открыть установку: " + ex.getMessage());
                }
            } else {
                MainActivity.onInstallStatus("Система не предоставила подтверждение: " + part);
            }
        } else if (result == PackageInstaller.STATUS_SUCCESS) {
            MainActivity.onInstallStatus("Установлено: " + part);
            Toast.makeText(context, "Установлено: " + part, Toast.LENGTH_LONG).show();
        } else {
            String reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            MainActivity.onInstallStatus("Ошибка установки " + part + ": " + result + " / " + reason);
            Toast.makeText(context, "Ошибка: " + reason, Toast.LENGTH_LONG).show();
        }
    }
}
