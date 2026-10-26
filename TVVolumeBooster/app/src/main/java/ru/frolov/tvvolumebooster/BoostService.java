package ru.frolov.tvvolumebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

public final class BoostService extends Service {
    public static final String ACTION_SET = "ru.frolov.tvvolumebooster.SET";
    public static final String ACTION_STOP = "ru.frolov.tvvolumebooster.STOP";
    public static final String ACTION_RESTORE = "ru.frolov.tvvolumebooster.RESTORE";
    public static final String EXTRA_LEVEL = "level";

    private static final String CHANNEL = "tv_volume_booster";
    private static final int NOTIFICATION_ID = 1001;
    // 20*log10(amplitude), in millibels (100mB = 1dB).
    private static final int[] GAINS_MB = { 0, 352, 602, 954, 1204 };
    private LoudnessEnhancer effect;
    private SharedPreferences preferences;

    @Override public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("boost", MODE_PRIVATE);
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Усиление звука", NotificationManager.IMPORTANCE_LOW));
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : ACTION_RESTORE;
        if (ACTION_STOP.equals(action)) {
            preferences.edit().putBoolean("running", false).putInt("level", 0)
                    .putString("status", "Усиление отключено").apply();
            releaseEffect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        int level = ACTION_SET.equals(action) && intent != null
                ? intent.getIntExtra(EXTRA_LEVEL, 1)
                : preferences.getInt("level", 1);
        if (level < 1 || level > 4) level = 1;

        startForeground(NOTIFICATION_ID, makeNotification(level));
        try {
            // Session 0 = global mix when supported by vendor firmware.
            if (effect == null) effect = new LoudnessEnhancer(0);
            if (!effect.hasControl()) {
                throw new IllegalStateException("Нет управления системным аудиоэффектом");
            }
            effect.setTargetGain(GAINS_MB[level]);
            int code = effect.setEnabled(true);
            if (code != AudioEffect.SUCCESS || !effect.getEnabled()) {
                throw new IllegalStateException("Аудиоэффект отклонён прошивкой (" + code + ")");
            }
            preferences.edit().putBoolean("running", true).putInt("level", level)
                    .putString("status", "Эффект активирован. Проверьте звук в фильме: Яндекс ТВ может обходить обработку.")
                    .apply();
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            manager.notify(NOTIFICATION_ID, makeNotification(level));
            return START_STICKY;
        } catch (Exception e) {
            Log.e("TVVolumeBooster", "System-wide enhancement unavailable", e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            preferences.edit().putBoolean("running", false).putInt("level", 0)
                    .putString("status", "Не удалось включить: " + message).apply();
            releaseEffect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private Notification makeNotification(int level) {
        Intent start = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, start,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        return builder.setContentTitle("Усилитель громкости ТВ")
                .setContentText("Усиление ×" + (level == 1 ? "1,5" : Integer.toString(level)))
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
    }

    private void releaseEffect() {
        if (effect != null) {
            try { effect.setEnabled(false); } catch (Exception ignored) { }
            effect.release();
            effect = null;
        }
    }

    @Override public void onDestroy() {
        releaseEffect();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
