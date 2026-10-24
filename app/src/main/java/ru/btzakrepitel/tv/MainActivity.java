package ru.btzakrepitel.tv;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final int LIGHT = 0xFFF3F7FF, ACCENT = 0xFF79E1D1, MUTED=0xFFB7C5D6;
    private TextView status,permissions,logs;
    private LinearLayout devices;
    private boolean visible=false;
    private boolean requested=false;
    private final Runnable refresher=new Runnable() {
        @Override public void run() {
            showStatus();
            if (visible) ui.postDelayed(this,3000);
        }
    };
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        drawUi();
        checkPermissions();
        listDevices();
        showStatus();
        if (Prefs.enabled(this) && !Prefs.address(this).isEmpty() && permitted())
            startMonitor(false);
    }
    @Override protected void onResume() {
        super.onResume();
        visible=true;
        checkPermissions();
        listDevices();
        ui.removeCallbacks(refresher);
        ui.post(refresher);
    }
    @Override protected void onPause() {
        visible=false;
        ui.removeCallbacks(refresher);
        super.onPause();
    }
    private int d(float dp) { return (int)(dp*getResources().getDisplayMetrics().density+0.5f); }
    private TextView text(String s,int size,int color) {
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setPadding(0,d(5),0,d(5));
        return t;
    }
    private Button button(String name, Runnable action) {
        Button b=new Button(this);
        b.setAllCaps(false);
        b.setText(name);
        b.setTextSize(18);
        b.setFocusable(true);
        b.setOnClickListener(v -> action.run());
        return b;
    }
    private void addButton(LinearLayout box,String name,Runnable action) {
        box.addView(button(name,action));
    }
    private void drawUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF111C29);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(d(42),d(26),d(42),d(30));
        scroll.addView(box);
        setContentView(scroll);
        box.addView(text("BT ЗАКРЕПИТЕЛЬ",31,ACCENT));
        box.addView(text("Закрепите колонку — приложение будет повторять подключение каждые 20 секунд, пока она не подключится.",18,LIGHT));
        box.addView(text("DEXP / YaOS • "+Build.MODEL+" • Android "+Build.VERSION.RELEASE,15,MUTED));
        status=text("",19,LIGHT);
        box.addView(status);
        permissions=text("",16,MUTED);
        box.addView(permissions);
        addButton(box,"Проверить / запросить разрешения",() -> {
            requested=false;
            checkPermissions();
            showStatus();
        });
        box.addView(text("СОПРЯЖЁННЫЕ BLUETOOTH-УСТРОЙСТВА",21,ACCENT));
        box.addView(text("Если колонки здесь нет, сначала подключите её через настройки телевизора.",16,MUTED));
        devices=new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        box.addView(devices);
        addButton(box,"Обновить список устройств",this::listDevices);
        addButton(box,"Открыть Bluetooth-настройки ТВ",this::openBluetoothSettings);
        box.addView(text("УПРАВЛЕНИЕ",21,ACCENT));
        addButton(box,"▶ Включить постоянное переподключение",() -> {
            if (Prefs.address(this).isEmpty()) { toast("Сначала закрепите устройство"); return; }
            if (!permitted()) { checkPermissions(); return; }
            Prefs.setEnabled(this,true);
            startMonitor(true);
            showStatus();
        });
        addButton(box,"↻ Попробовать подключиться сейчас",() -> {
            if (Prefs.address(this).isEmpty()) { toast("Сначала закрепите устройство"); return; }
            if (!permitted()) { checkPermissions(); return; }
            Prefs.setEnabled(this,true);
            startMonitor(true);
        });
        addButton(box,"■ Остановить наблюдение",() -> {
            Prefs.setEnabled(this,false);
            stopService(new Intent(this,MonitorService.class));
            Prefs.status(this,"Наблюдение отключено");
            showStatus();
        });
        addButton(box,"Снять закрепление",() -> {
            stopService(new Intent(this,MonitorService.class));
            Prefs.unpin(this);
            listDevices();
            showStatus();
        });
        addButton(box,"Разрешить работу без ограничений батареи",this::askBatteryExemption);
        box.addView(text("ДИАГНОСТИКА",21,ACCENT));
        logs=text("",15,LIGHT);
        box.addView(logs);
        box.addView(text("Важно: подключение использует скрытый метод Android A2DP. На некоторых версиях YaOS он запрещён. Приложение не удаляет сопряжения и не требует root.",15,MUTED));
    }
    private boolean permitted() {
        if (Build.VERSION.SDK_INT>=31)
            return checkSelfPermission("android.permission.BLUETOOTH_CONNECT")==PackageManager.PERMISSION_GRANTED;
        return checkSelfPermission("android.permission.BLUETOOTH")==PackageManager.PERMISSION_GRANTED
            && checkSelfPermission("android.permission.BLUETOOTH_ADMIN")==PackageManager.PERMISSION_GRANTED;
    }
    private void checkPermissions() {
        if (!permitted() && Build.VERSION.SDK_INT>=31 && !requested) {
            requested=true;
            requestPermissions(new String[]{"android.permission.BLUETOOTH_CONNECT"},42);
        }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if (requestCode==42) {
            Prefs.addLog(this,permitted()?"Bluetooth-разрешение получено":"Bluetooth-разрешение не предоставлено");
            listDevices();
            showStatus();
        }
    }
    private void listDevices() {
        if (devices==null) return;
        devices.removeAllViews();
        if (!permitted()) {
            devices.addView(text("Нет разрешения Bluetooth",18,LIGHT));
            return;
        }
        BluetoothAdapter bt=BluetoothAdapter.getDefaultAdapter();
        if (bt==null) {
            devices.addView(text("Bluetooth-адаптер не обнаружен",18,LIGHT));
            return;
        }
        try {
            Set<BluetoothDevice> bonded=bt.getBondedDevices();
            if (bonded==null || bonded.isEmpty()) {
                devices.addView(text("Список сопряжённых устройств пуст",18,LIGHT));
                return;
            }
            List<BluetoothDevice> list=new ArrayList<>(bonded);
            list.sort(Comparator.comparing(x -> x.getName()==null?"":x.getName().toLowerCase()));
            for (BluetoothDevice dev:list) {
                String mac=dev.getAddress();
                String name=dev.getName()==null?"Без имени":dev.getName();
                boolean pinned=mac.equalsIgnoreCase(Prefs.address(this));
                addButton(devices,(pinned?"★ Закреплено: ":"☆ Закрепить: ")+name,() -> {
                    Prefs.pin(this,mac,name);
                    Prefs.addLog(this,"Устройство закреплено: "+name);
                    startMonitor(true);
                    listDevices();
                    showStatus();
                    askBatteryExemption();
                });
            }
        } catch (SecurityException e) {
            devices.addView(text("Прошивка запретила доступ к Bluetooth",18,LIGHT));
        }
    }
    private void startMonitor(boolean retryNow) {
        Intent service=new Intent(this,MonitorService.class);
        if (retryNow) service.setAction(MonitorService.ACTION_RETRY);
        try {
            if (Build.VERSION.SDK_INT>=26) startForegroundService(service);
            else startService(service);
        } catch (RuntimeException e) {
            Prefs.addLog(this,"Служба не запустилась: "+e.getClass().getSimpleName());
        }
    }
    private void openBluetoothSettings() {
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { toast("Настройки Bluetooth недоступны"); }
        }
    }
    private String batteryState() {
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            return pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName())
                ?"✓ Батарея: работа без ограничений"
                :"⚠ Батарея: возможны ограничения";
        } catch (RuntimeException e) { return "⚠ Состояние энергосбережения неизвестно"; }
    }
    private void askBatteryExemption() {
        try {
            PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
            if (pm!=null && pm.isIgnoringBatteryOptimizations(getPackageName())) return;
            Intent intent=new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:"+getPackageName()));
            startActivity(intent);
        } catch (RuntimeException e) {
            toast("Прошивка ТВ не поддерживает запрос. Проверьте настройки питания вручную.");
        }
    }
    private void showStatus() {
        if (status==null) return;
        status.setText((Prefs.address(this).isEmpty()?"★ Устройство не выбрано":"★ Закреплено: "+Prefs.name(this))
            +"\nМониторинг: "+(Prefs.enabled(this)?"ВКЛ, каждые 20 секунд":"ВЫКЛ")
            +"\nСостояние: "+Prefs.status(this));
        boolean service=checkSelfPermission("android.permission.FOREGROUND_SERVICE")==PackageManager.PERMISSION_GRANTED;
        boolean boot=checkSelfPermission("android.permission.RECEIVE_BOOT_COMPLETED")==PackageManager.PERMISSION_GRANTED;
        permissions.setText("Bluetooth: "+(permitted()?"✓ разрешён":"✕ требуется разрешение")
            +"\nФоновая служба: "+(service?"✓ разрешена":"✕ нет разрешения")
            +"\nАвтозапуск: "+(boot?"✓ разрешение есть":"✕ нет разрешения")
            +"\n"+batteryState());
        logs.setText(Prefs.log(this).isEmpty()?"Событий пока нет":Prefs.log(this));
    }
    private void toast(String msg) { Toast.makeText(this,msg,Toast.LENGTH_LONG).show(); }
}
