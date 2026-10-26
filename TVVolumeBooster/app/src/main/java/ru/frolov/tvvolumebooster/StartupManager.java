package ru.frolov.tvvolumebooster;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

public final class StartupManager {
    static final int JOB_ID = 7152;
    private StartupManager() {}

    static boolean shouldMonitor(Context c) {
        return BluetoothPrefs.enabled(c) && !BluetoothPrefs.address(c).isEmpty();
    }
    static void startServices(Context c) {
        if (shouldMonitor(c)) {
            start(c, new Intent(c, BluetoothMonitorService.class));
            scheduleWatchdog(c);
        } else {
            cancelWatchdog(c);
        }
        // Restore the exact, previously chosen loudness level, never activate it unrequested.
        if (c.getSharedPreferences("boost", Context.MODE_PRIVATE).getBoolean("running", false)) {
            Intent i = new Intent(c, BoostService.class);
            i.setAction(BoostService.ACTION_RESTORE);
            start(c, i);
        }
    }
    static boolean start(Context c, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
            return true;
        } catch (RuntimeException e) {
            Log.w("TVAudio", "Start not allowed by firmware", e);
            return false;
        }
    }
    static void scheduleWatchdog(Context c) {
        if (!shouldMonitor(c)) { cancelWatchdog(c); return; }
        JobScheduler jobs = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs == null) return;
        try {
            JobInfo job = new JobInfo.Builder(JOB_ID,
                    new ComponentName(c, WatchdogJob.class))
                    .setPeriodic(15 * 60_000L)
                    .setPersisted(true)
                    .build();
            jobs.schedule(job);
        } catch (RuntimeException e) {
            Log.w("TVAudio", "Watchdog not available", e);
        }
    }
    static void cancelWatchdog(Context c) {
        JobScheduler jobs = (JobScheduler)c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        if (jobs != null) jobs.cancel(JOB_ID);
    }
}
