package ru.frolov.tvvolumebooster;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class BluetoothMonitorService extends Service {
    static final String ACTION_RETRY = "ru.frolov.tvvolumebooster.BT_RETRY";
    static final String ACTION_STOP = "ru.frolov.tvvolumebooster.BT_STOP";
    private static final long RETRY_MS = 20_000;
    private static final int NOTIF_ID = 7201;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private boolean running;
    private boolean receiverRegistered;
    private boolean proxyRequested;
    private long lastAttempt;
    private String lastReport = "";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            checkConnection();
            if (running) handler.postDelayed(this, RETRY_MS);
        }
    };
    private final BluetoothProfile.ServiceListener listener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy;
                report("Профиль Bluetooth-аудио доступен");
                handler.post(() -> checkConnection());
            }
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null;
                proxyRequested = false;
                report("Bluetooth-аудио переподключается");
            }
        }
    };
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                handler.postDelayed(() -> { ensureProxy(); checkConnection(); }, 2000);
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                try {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && d.getAddress().equalsIgnoreCase(BluetoothPrefs.address(c))) {
                        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            report("✓ Подключено: " + BluetoothPrefs.name(c));
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            handler.postDelayed(() -> checkConnection(), 2500);
                        }
                    }
                } catch (SecurityException e) { report("Нет разрешения Bluetooth"); }
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(
                "bt_monitor_v1", "Закрепление Bluetooth", NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, "bt_monitor_v1")
                .setContentTitle("Аудио ТВ — закрепление Bluetooth")
                .setContentText("Контроль подключения колонки")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(NOTIF_ID, notification);
        adapter = BluetoothAdapter.getDefaultAdapter();
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        f.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(receiver, f);
        receiverRegistered = true;
        running = true;
        ensureProxy();
        handler.post(ticker);
    }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        if ((i != null && ACTION_STOP.equals(i.getAction()))
                || !BluetoothPrefs.enabled(this) || BluetoothPrefs.address(this).isEmpty()) {
            running = false;
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (i != null && ACTION_RETRY.equals(i.getAction())) {
            lastAttempt = 0;
            handler.post(() -> checkConnection());
        }
        return START_STICKY;
    }
    private boolean permission() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
    }
    private void ensureProxy() {
        if (adapter == null || a2dp != null || proxyRequested || !permission()) return;
        try {
            if (adapter.isEnabled()) {
                proxyRequested = adapter.getProfileProxy(this, listener, BluetoothProfile.A2DP);
            }
        } catch (SecurityException e) { report("Нет доступа к A2DP"); }
    }
    private void checkConnection() {
        if (!running || !BluetoothPrefs.enabled(this) || BluetoothPrefs.address(this).isEmpty()) return;
        if (!permission()) { report("Разрешите доступ к Bluetooth в приложении"); return; }
        if (adapter == null) { report("Bluetooth-адаптер не обнаружен"); return; }
        try {
            if (!adapter.isEnabled()) { report("Bluetooth выключен на телевизоре"); return; }
            // Do not call isDiscovering(): it needs BLUETOOTH_SCAN on Android 12+,
            // but background reconnection only requires the CONNECT permission.
            if (a2dp == null) { ensureProxy(); report("Ожидание профиля A2DP"); return; }
            BluetoothDevice selected = null;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (BluetoothPrefs.address(this).equalsIgnoreCase(device.getAddress())) {
                    selected = device;
                    break;
                }
            }
            if (selected == null) {
                report("Сопряжение потеряно — подключите колонку в настройках ТВ");
                return;
            }
            int state = a2dp.getConnectionState(selected);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                report("✓ Подключено: " + BluetoothPrefs.name(this));
                return;
            }
            if (state == BluetoothProfile.STATE_CONNECTING) {
                report("Идёт подключение колонки");
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (lastAttempt != 0 && now - lastAttempt < 12_000) return;
            lastAttempt = now;
            report("Попытка соединения: " + BluetoothPrefs.name(this));
            connect(selected);
        } catch (SecurityException e) {
            report("Подключение запрещено настройками");
        } catch (RuntimeException e) {
            report("Ошибка Bluetooth: " + e.getClass().getSimpleName());
        }
    }
    private void connect(BluetoothDevice target) {
        try {
            // A2DP.connect is a hidden API. Some YaOS builds deny it.
            Method method = a2dp.getClass().getMethod("connect", BluetoothDevice.class);
            Object result = method.invoke(a2dp, target);
            report(Boolean.TRUE.equals(result) ? "Запрос подключения отправлен"
                    : "Прошивка отклонила подключение");
        } catch (NoSuchMethodException e) {
            report("Прошивка не предоставляет функцию A2DP.connect");
        } catch (IllegalAccessException e) {
            report("Прошивка блокирует A2DP.connect");
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Log.w("BTAutoConnect", "Connect rejected", cause);
            report("Ошибка A2DP: " + cause.getClass().getSimpleName());
        } catch (RuntimeException e) {
            report("Автоподключение ограничено прошивкой");
        }
    }
    private void report(String s) {
        BluetoothPrefs.status(this, s);
        if (!s.equals(lastReport)) {
            lastReport = s;
            BluetoothPrefs.addLog(this, s);
        }
    }
    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(receiver);
        if (adapter != null && a2dp != null) {
            try { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); }
            catch (SecurityException ignored) {}
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
