package ru.btzakrepitel.tv;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final int LIGHT=0xFFF3F7FF, ACCENT=0xFF79E1D1, MUTED=0xFFB7C5D6, RED=0xFFD32F2F;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String, Nearby> nearby=new HashMap<>();
    private TextView status, scanStatus, logs;
    private LinearLayout devices;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private AlertDialog permissionDialog;
    private String currentMissing="";
    private boolean visible=false, receiverRegistered=false, permissionRequestActive=false;
    private boolean firstScan=false, scanning=false;
    private static final class Nearby {
        final BluetoothDevice device;
        long seen;
        Nearby(BluetoothDevice d,long time) { device=d; seen=time; }
    }
    private final Runnable refresh=new Runnable() {
        @Override public void run() {
            if (!visible) return;
            validatePermissions();
            showStatus();
            updateDeviceList();
            ui.postDelayed(this,3000);
        }
    };
    private final BroadcastReceiver scanReceiver=new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String action=i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                try {
                    BluetoothDevice d=i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d!=null && d.getAddress()!=null) {
                        nearby.put(d.getAddress(),new Nearby(d,SystemClock.elapsedRealtime()));
                        updateDeviceList();
                    }
                } catch (SecurityException e) { scanStatus.setText("Нет доступа к Bluetooth"); }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning=false;
                updateDeviceList();
            } else if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                validatePermissions();
            }
        }
    };
    private final BluetoothProfile.ServiceListener profileListener=new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile,BluetoothProfile proxy) {
            if (profile!=BluetoothProfile.A2DP) return;
            a2dp=proxy;
            collectConnected();
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile==BluetoothProfile.A2DP) a2dp=null;
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        adapter=BluetoothAdapter.getDefaultAdapter();
        makeUi();
        IntentFilter f=new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_FOUND);
        f.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        f.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(scanReceiver,f);
        receiverRegistered=true;
        if (Prefs.enabled(this) && !Prefs.address(this).isEmpty() && hasBluetoothConnect())
            startMonitor(false);
    }
    @Override protected void onResume() {
        super.onResume();
        visible=true;
        firstScan=false;
        ui.removeCallbacks(refresh);
        ui.post(refresh);
    }
    @Override protected void onPause() {
        visible=false;
        ui.removeCallbacks(refresh);
        cancelScan();
        super.onPause();
    }
    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(scanReceiver);
        if (adapter!=null && a2dp!=null) adapter.closeProfileProxy(BluetoothProfile.A2DP,a2dp);
        if (permissionDialog!=null) permissionDialog.dismiss();
        super.onDestroy();
    }
    private int px(int n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private TextView label(String s,int sp,int color) {
        TextView v=new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(color);
        v.setPadding(0,px(6),0,px(6));
        return v;
    }
    private Button button(String title,Runnable click) {
        Button v=new Button(this);
        v.setAllCaps(false); v.setText(title); v.setTextSize(17);
        v.setOnClickListener(w->click.run());
        return v;
    }
    private void control(LinearLayout box,String title,Runnable action) {
        box.addView(button(title,action));
    }
    private void makeUi() {
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF111C29);
        LinearLayout main=new LinearLayout(this);
        main.setOrientation(LinearLayout.VERTICAL);
        main.setPadding(px(42),px(24),px(42),px(32));
        scroll.addView(main);
        setContentView(scroll);
        main.addView(label("BT ЗАКРЕПИТЕЛЬ",29,ACCENT));
        main.addView(label("Красная кнопка 📌 слева закрепляет устройство, обнаруженное сейчас рядом.",18,LIGHT));
        main.addView(label("DEXP / YaOS • "+Build.MODEL+" • Android "+Build.VERSION.RELEASE,14,MUTED));
        status=label("",18,LIGHT); main.addView(status);
        main.addView(label("ДОСТУПНЫЕ УСТРОЙСТВА РЯДОМ",21,ACCENT));
        scanStatus=label("Подготовка поиска...",16,MUTED); main.addView(scanStatus);
        devices=new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        main.addView(devices);
        control(main,"↻ Найти устройства заново",this::startScan);
        control(main,"Открыть настройки Bluetooth",this::openBluetoothSettings);
        main.addView(label("УПРАВЛЕНИЕ",21,ACCENT));
        control(main,"▶ Включить автоматическое переподключение",() -> {
            if (Prefs.address(this).isEmpty()) { toast("Сначала выберите колонку красной кнопкой"); return; }
            if (!hasBluetoothConnect()) { validatePermissions(); return; }
            Prefs.setEnabled(this,true); startMonitor(true); showStatus();
        });
        control(main,"↻ Подключить сейчас",() -> {
            if (Prefs.address(this).isEmpty()) { toast("Устройство не закреплено"); return; }
            Prefs.setEnabled(this,true); startMonitor(true);
        });
        control(main,"■ Остановить наблюдение",() -> {
            Prefs.setEnabled(this,false);
            stopService(new Intent(this,MonitorService.class));
            Prefs.status(this,"Мониторинг выключен"); showStatus();
        });
        control(main,"Снять закрепление",() -> {
            stopService(new Intent(this,MonitorService.class));
            Prefs.unpin(this); updateDeviceList(); showStatus();
        });
        main.addView(label("ДИАГНОСТИКА",21,ACCENT));
        logs=label("",14,LIGHT); main.addView(logs);
        main.addView(label("Если сопряжённая колонка не обнаруживается, включите её и повторите поиск. Некоторые модели не отвечают на поиск, пока не переведены в режим обнаружения. Автоподключение может блокироваться прошивкой YaOS.",14,MUTED));
    }
    private boolean granted(String name) {
        return checkSelfPermission(name)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasBluetoothConnect() {
        return Build.VERSION.SDK_INT>=31 ? granted(Manifest.permission.BLUETOOTH_CONNECT)
            : granted(Manifest.permission.BLUETOOTH);
    }
    private boolean hasScanPermission() {
        return Build.VERSION.SDK_INT>=31 ? granted(Manifest.permission.BLUETOOTH_SCAN)
            : granted(Manifest.permission.ACCESS_FINE_LOCATION);
    }
    private String missing() {
        if (Build.VERSION.SDK_INT>=31) {
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT))
                return "BLUETOOTH_CONNECT";
            if (!granted(Manifest.permission.BLUETOOTH_SCAN))
                return "BLUETOOTH_SCAN";
        } else if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            return "ACCESS_FINE_LOCATION";
        }
        if (adapter==null) return "NO_ADAPTER";
        try {
            if (!adapter.isEnabled()) return "BLUETOOTH_DISABLED";
        } catch (SecurityException e) { return "BLUETOOTH_CONNECT"; }
        if (Build.VERSION.SDK_INT<=30) {
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
            if (lm!=null && Build.VERSION.SDK_INT>=28 && !lm.isLocationEnabled())
                return "LOCATION_DISABLED";
        }
        return "";
    }
    private void validatePermissions() {
        String missing=missing();
        if (missing.isEmpty()) {
            if (permissionDialog!=null) { permissionDialog.dismiss(); permissionDialog=null; currentMissing=""; }
            if (!firstScan && visible) {
                firstScan=true;
                if (adapter!=null && a2dp==null) {
                    try { adapter.getProfileProxy(this,profileListener,BluetoothProfile.A2DP); }
                    catch (SecurityException ignored) { }
                }
                startScan();
                showBatteryRecommendation();
            }
            return;
        }
        firstScan=false;
        cancelScan();
        if (permissionRequestActive || (permissionDialog!=null && currentMissing.equals(missing))) return;
        if (permissionDialog!=null) { permissionDialog.dismiss(); permissionDialog=null; }
        currentMissing=missing;
        String[] message=explain(missing);
        AlertDialog dlg=new AlertDialog.Builder(this)
            .setTitle("Требуется разрешение")
            .setMessage(message[0])
            .setCancelable(false)
            .setPositiveButton(message[1],(v,w)->requestMissing(missing))
            .create();
        permissionDialog=dlg;
        dlg.setOnDismissListener(v-> { if (permissionDialog==dlg) permissionDialog=null; });
        dlg.show();
    }
    private String[] explain(String missing) {
        switch (missing) {
            case "ACCESS_FINE_LOCATION": return new String[]{
                "Для поиска Bluetooth-устройств поблизости Android 11 требует разрешение «Местоположение». Геопозиция приложением не сохраняется.",
                "Разрешить"};
            case "BLUETOOTH_CONNECT": return new String[]{
                "Нужен доступ к подключению Bluetooth-устройств.", "Разрешить"};
            case "BLUETOOTH_SCAN": return new String[]{
                "Нужен доступ к поиску Bluetooth-устройств поблизости.", "Разрешить"};
            case "BLUETOOTH_DISABLED": return new String[]{
                "Bluetooth выключен. Перейдите в настройки телевизора и включите его.", "Открыть настройки"};
            case "LOCATION_DISABLED": return new String[]{
                "Android 11 требует включить системную геолокацию для поиска устройств Bluetooth. Само приложение координаты не использует.", "Открыть настройки"};
            default: return new String[]{"На телевизоре не найден Bluetooth-адаптер. Проверьте устройство.", "Открыть настройки"};
        }
    }
    private void requestMissing(String kind) {
        try {
            switch (kind) {
                case "ACCESS_FINE_LOCATION":
                    requestSystemPermission(Manifest.permission.ACCESS_FINE_LOCATION);
                    return;
                case "BLUETOOTH_CONNECT":
                case "BLUETOOTH_SCAN":
                    requestSystemPermission("android.permission."+kind); return;
                case "BLUETOOTH_DISABLED":
                    try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); }
                    catch (RuntimeException e) { openBluetoothSettings(); }
                    return;
                case "LOCATION_DISABLED":
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); return;
                default: openBluetoothSettings();
            }
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+getPackageName()))); }
            catch (RuntimeException ignored) { toast("Системные настройки недоступны"); }
        }
    }
    private void requestSystemPermission(String permission) {
        String key="asked_"+permission;
        boolean asked=getPreferences(MODE_PRIVATE).getBoolean(key,false);
        // После постоянного отказа штатный запрос больше не показывается.
        if (asked && !shouldShowRequestPermissionRationale(permission)) {
            openAppPermissions();
            return;
        }
        getPreferences(MODE_PRIVATE).edit().putBoolean(key,true).apply();
        permissionRequestActive=true;
        requestPermissions(new String[]{permission},100);
    }
    private void openAppPermissions() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:"+getPackageName())));
        } catch (RuntimeException e) { toast("Раздел разрешений недоступен"); }
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] names,int[] result) {
        super.onRequestPermissionsResult(requestCode,names,result);
        if (requestCode==100) {
            permissionRequestActive=false;
            ui.postDelayed(this::validatePermissions,350);
        }
    }
    private void startScan() {
        if (!visible || !missing().isEmpty()) { validatePermissions(); return; }
        cancelScan();
        nearby.clear();
        updateDeviceList();
        if (adapter==null) return;
        try {
            scanning=adapter.startDiscovery();
            scanStatus.setText(scanning?"Поиск устройств поблизости…":"Не удалось начать поиск. Попробуйте ещё раз.");
            collectConnected();
        } catch (SecurityException e) {
            scanStatus.setText("Поиск недоступен: нет разрешения");
            validatePermissions();
        }
    }
    private void cancelScan() {
        if (adapter==null || !hasScanPermission()) return;
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); }
        catch (SecurityException ignored) { }
        scanning=false;
    }
    private void collectConnected() {
        if (a2dp==null || !hasBluetoothConnect()) return;
        try {
            for (BluetoothDevice d:a2dp.getConnectedDevices())
                nearby.put(d.getAddress(),new Nearby(d,SystemClock.elapsedRealtime()));
            updateDeviceList();
        } catch (SecurityException ignored) { }
    }
    private void updateDeviceList() {
        if (devices==null) return;
        devices.removeAllViews();
        if (!missing().isEmpty()) { scanStatus.setText("Поиск доступен после предоставления разрешений"); return; }
        long now=SystemClock.elapsedRealtime();
        List<Nearby> matches=new ArrayList<>();
        for (Nearby n:nearby.values()) {
            if (now-n.seen<45_000) matches.add(n);
        }
        matches.sort(Comparator.comparing(n->{
            try { String name=n.device.getName(); return name==null?"":name.toLowerCase(); }
            catch (SecurityException e) { return ""; }
        }));
        scanStatus.setText(scanning?"Поиск устройств поблизости…":
            (matches.isEmpty()?"Рядом ничего не обнаружено. Включите колонку и повторите поиск.":
            "Обнаружено устройств: "+matches.size()));
        for (Nearby n:matches) {
            BluetoothDevice d=n.device;
            try {
                String rawName=d.getName();
                final String name=(rawName==null || rawName.isEmpty())?"Устройство без имени":rawName;
                String address=d.getAddress();
                boolean bonded=d.getBondState()==BluetoothDevice.BOND_BONDED;
                boolean pinned=address.equalsIgnoreCase(Prefs.address(this));
                LinearLayout row=new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(0,px(4),0,px(4));
                Button pin=button("📌",()->pinDevice(d));
                pin.setContentDescription("Закрепить устройство "+name);
                GradientDrawable bg=new GradientDrawable();
                bg.setColor(RED);
                bg.setCornerRadius(px(12));
                pin.setBackground(bg);
                pin.setTextColor(Color.WHITE);
                pin.setTextSize(24);
                pin.setEnabled(bonded);
                if (!bonded) pin.setAlpha(0.4f);
                LinearLayout.LayoutParams iconParams=new LinearLayout.LayoutParams(px(72),px(58));
                row.addView(pin,iconParams);
                TextView title=label("   "+name+(pinned?"  ✓ закреплено":"")+
                    (!bonded?"  (не сопряжено)":""),
                    18,pinned?ACCENT:LIGHT);
                title.setSingleLine(false);
                row.addView(title,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1));
                devices.addView(row);
            } catch (SecurityException ignored) { }
        }
    }
    private void pinDevice(BluetoothDevice d) {
        try {
            Nearby n=nearby.get(d.getAddress());
            if (n==null || SystemClock.elapsedRealtime()-n.seen>45_000) {
                toast("Устройство уже не обнаруживается. Повторите поиск."); startScan(); return;
            }
            if (d.getBondState()!=BluetoothDevice.BOND_BONDED) {
                toast("Сначала сопрягите устройство через настройки ТВ"); return;
            }
            cancelScan(); // Поиск мешает аудиоподключению.
            String name=d.getName()==null?d.getAddress():d.getName();
            Prefs.pin(this,d.getAddress(),name);
            Prefs.addLog(this,"Закреплено: "+name);
            startMonitor(true);
            showStatus();
            updateDeviceList();
            toast("Закреплено: "+name);
        } catch (SecurityException e) { validatePermissions(); }
    }
    private void startMonitor(boolean immediate) {
        Intent i=new Intent(this,MonitorService.class);
        if (immediate) i.setAction(MonitorService.ACTION_RETRY);
        try {
            if (Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
        } catch (RuntimeException e) { Prefs.addLog(this,"Ошибка службы: "+e.getClass().getSimpleName()); }
    }
    private void showStatus() {
        if (status==null) return;
        status.setText("📌 "+(Prefs.address(this).isEmpty()?"Устройство не закреплено":Prefs.name(this))
          +"\nМониторинг: "+(Prefs.enabled(this)?"включён":"выключен")
          +"\n"+Prefs.status(this));
        logs.setText(Prefs.log(this).isEmpty()?"Событий нет":Prefs.log(this));
    }
    private void openBluetoothSettings() {
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { toast("Настройки недоступны"); }
        }
    }
    private void showBatteryRecommendation() {
        // Оптимизация батареи — рекомендация, а не разрешение Bluetooth.
        // На некоторых Яндекс ТВ этот раздел отсутствует: не блокируем приложение навсегда.
        if (getPreferences(MODE_PRIVATE).getBoolean("battery_prompt_seen",false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean("battery_prompt_seen",true).apply();
        PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
        if (pm==null || pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        new AlertDialog.Builder(this)
            .setTitle("Работа в фоне")
            .setMessage("Рекомендуется разрешить приложению работу без ограничений энергосбережения. Это повышает надёжность постоянного Bluetooth-подключения. Если у YaOS нет такого пункта, приложение продолжит работать без него.")
            .setPositiveButton("Перейти", (dlg,which)-> {
                try { startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:"+getPackageName()))); }
                catch (RuntimeException e) { toast("На YaOS нет этой настройки"); }
            })
            .setNegativeButton("Пропустить",null).show();
    }
    private void toast(String s) { Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
}
