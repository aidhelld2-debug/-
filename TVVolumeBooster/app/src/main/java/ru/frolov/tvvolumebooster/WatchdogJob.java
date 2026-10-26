package ru.frolov.tvvolumebooster;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Intent;
import android.util.Log;

public final class WatchdogJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        if (StartupManager.shouldMonitor(this)) {
            Intent i = new Intent(this, BluetoothMonitorService.class);
            i.setAction(BluetoothMonitorService.ACTION_RETRY);
            if (!StartupManager.start(this, i)) {
                BluetoothPrefs.status(this,
                        "Ограничение YaOS: автоматическое возобновление службы запрещено");
            }
        }
        jobFinished(params, false);
        return false;
    }
    @Override public boolean onStopJob(JobParameters params) { return false; }
}
