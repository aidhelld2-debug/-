package ru.btzakrepitel.tv;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Set;

public class MonitorService extends Service {
    public static final String ACTION_RETRY = "ru.btzakrepitel.tv.RETRY";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long delayMs = 20_000;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private boolean alive;
    private boolean registered;
    private long lastAttempt;
    private String lastMessage = "";
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            poll();
            if (alive) handler.postDelayed(this,delayMs);
        }
    };
    private final BluetoothProfile.ServiceListener profileListener =
        new BluetoothProfile.ServiceListener() {
            @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dp = proxy;
                    report("Профиль A2DP доступен");
                    handler.post(() -> poll());
                }
            }
            @Override public void onServiceDisconnected(int profile) {
                if (profile == BluetoothProfile.A2DP) {
                    a2dp = null;
                    report("Профиль A2DP отключился");
                }
            }
        };
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                handler.postDelayed(() -> { attachProfile(); poll(); },3000);
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(intent.getAction())) {
                try {
                    BluetoothDevice d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null) {
                        int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,-1);
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            if (d.getAddress().equalsIgnoreCase(Prefs.address(context)))
                                report("Колонка подключена");
                            // Show the actual connected speaker even if the pinned
                            // device is different. Only genuinely available battery data.
                            ConnectionPopup.show(context,d);
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED &&
                                   d.getAddress().equalsIgnoreCase(Prefs.address(context))) {
                            handler.postDelayed(() -> poll(),3000);
                        }
                    }
                } catch (SecurityException e) { report("Нет Bluetooth-разрешения"); }
            }
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        makeForeground();
        alive = true;
        adapter = BluetoothAdapter.getDefaultAdapter();
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bluetoothEvents,filter);
        registered = true;
        report("Мониторинг запущен");
        attachProfile();
        handler.post(tick);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if (!Prefs.enabled(this) || Prefs.address(this).isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_RETRY.equals(intent.getAction())) handler.post(() -> poll());
        return START_STICKY;
    }
    private boolean allowed() {
        if (Build.VERSION.SDK_INT >= 31)
            return checkSelfPermission("android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission("android.permission.BLUETOOTH") == PackageManager.PERMISSION_GRANTED;
    }
    private void attachProfile() {
        if (adapter == null || a2dp != null || !allowed()) return;
        try {
            if (adapter.isEnabled())
                adapter.getProfileProxy(this,profileListener,BluetoothProfile.A2DP);
        } catch (SecurityException e) { report("Доступ к A2DP запрещён"); }
    }
    private void poll() {
        if (!alive || !Prefs.enabled(this) || Prefs.address(this).isEmpty()) return;
        if (!allowed()) { report("Bluetooth-разрешение отсутствует"); return; }
        if (adapter == null) { report("Bluetooth-адаптер не найден"); return; }
        try {
            if (!adapter.isEnabled()) { report("Bluetooth выключен"); return; }
            // Поиск активных устройств в интерфейсе может мешать A2DP-соединению.
            if (adapter.isDiscovering()) { report("Ожидание окончания поиска устройств"); return; }
            if (a2dp == null) { attachProfile(); report("Ожидание A2DP"); return; }
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            BluetoothDevice target = null;
            for (BluetoothDevice d : bonded)
                if (Prefs.address(this).equalsIgnoreCase(d.getAddress())) {
                    target = d; break;
                }
            if (target == null) {
                report("Сопряжение потеряно. Подключите колонку в настройках ТВ");
                return;
            }
            int state = a2dp.getConnectionState(target);
            if (state == BluetoothProfile.STATE_CONNECTED) {
                report("✓ Подключено: "+Prefs.name(this)); return;
            }
            if (state == BluetoothProfile.STATE_CONNECTING) {
                report("Ожидание завершения подключения"); return;
            }
            long now=SystemClock.elapsedRealtime();
            if (now-lastAttempt < 12_000 && lastAttempt != 0) return;
            lastAttempt=now;
            report("Попытка подключения: "+Prefs.name(this));
            connect(target);
        } catch (SecurityException e) {
            report("Доступ к Bluetooth заблокирован: "+e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            report("Сбой Bluetooth: "+e.getClass().getSimpleName());
        }
    }
    private void connect(BluetoothDevice device) {
        try {
            // Скрытый метод: недоступен в стандартном Android SDK.
            Method m = a2dp.getClass().getMethod("connect",BluetoothDevice.class);
            Object result=m.invoke(a2dp,device);
            report(Boolean.TRUE.equals(result)?"Запрос отправлен":"A2DP отклонил запрос");
        } catch (NoSuchMethodException e) {
            report("Прошивка скрывает метод A2DP.connect");
        } catch (IllegalAccessException e) {
            report("Прошивка блокирует вызов A2DP.connect");
        } catch (InvocationTargetException e) {
            Throwable t=e.getCause()==null?e:e.getCause();
            report("A2DP: "+t.getClass().getSimpleName());
        } catch (RuntimeException e) {
            report("Вызов A2DP заблокирован: "+e.getClass().getSimpleName());
        }
    }
    private void report(String message) {
        Prefs.status(this,message);
        if (!message.equals(lastMessage)) {
            lastMessage=message;
            Prefs.addLog(this,message);
        }
    }
    private void makeForeground() {
        NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("bt_pin","Подключение Bluetooth",
            NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open, PendingIntent.FLAG_UPDATE_CURRENT |
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification notification=new Notification.Builder(this,"bt_pin")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("BT Закрепитель")
            .setContentText("Автоматическое восстановление Bluetooth")
            .setContentIntent(pi).setOngoing(true).build();
        startForeground(7001,notification);
    }
    @Override public void onDestroy() {
        alive=false;
        handler.removeCallbacksAndMessages(null);
        if (registered) unregisterReceiver(bluetoothEvents);
        if (adapter!=null && a2dp!=null) adapter.closeProfileProxy(BluetoothProfile.A2DP,a2dp);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
