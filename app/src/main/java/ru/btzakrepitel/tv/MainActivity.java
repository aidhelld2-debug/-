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
import android.widget.ImageView;
import android.view.View;
import android.graphics.Typeface;
import java.util.HashSet;
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
    private TextView status, scanStatus, logs, countLabel, bluetoothPill;
    private LinearLayout devices;
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private AlertDialog permissionDialog;
    private String currentMissing="";
    private boolean visible=false, receiverRegistered=false, permissionRequestActive=false;
    private boolean firstScan=false, scanning=false;
    private String deviceSignature="";
    private final int BG=0xFF061320, PANEL=0xFF0E2234, CARD=0xFF112A3E, BORDER=0xFF274257;
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
                        deviceSignature="";
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
        ui.postDelayed(this::offerOverlayPermission,1600);
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
    // Interface for television remotes: large cards and a strong, unmistakable focus ring.
    private GradientDrawable background(int color, int strokeColor, int strokeWidth, int radius) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(px(radius));
        if(strokeWidth>0) d.setStroke(px(strokeWidth),strokeColor);
        return d;
    }
    private void focusStyle(View view, boolean isFocused, int defaultColor, int radius) {
        view.setBackground(background(isFocused?0xFF19435B:defaultColor,
            isFocused?0xFF41F3F4:BORDER,isFocused?3:1,radius));
        view.setScaleX(isFocused?1.015f:1f);
        view.setScaleY(isFocused?1.015f:1f);
        view.setElevation(px(isFocused?14:2));
    }

    /** Neutral grey button with an actual red round insert when pinned.
     * No checkmark, emoji or font-dependent glyph. */
    private android.graphics.drawable.Drawable pinSquare(boolean pinned, boolean focused) {
        GradientDrawable grey=background(focused?0xFF647583:0xFF56616D,
            focused?0xFF41F3F4:0xFF7C8996,focused?3:1,12);
        if(!pinned)return grey;
        GradientDrawable red=new GradientDrawable();
        red.setShape(GradientDrawable.OVAL);
        red.setColor(0xFFF3364E);
        red.setStroke(px(2),0xFFFF8B98);
        android.graphics.drawable.LayerDrawable combo=
            new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{grey,red});
        combo.setLayerInset(1,px(19),px(19),px(19),px(19));
        return combo;
    }

    private Button tvAction(String title, String detail, int backgroundColor, Runnable click) {
        Button b=button(title+(detail.isEmpty()?"":"\n"+detail),click);
        b.setTextSize(16);
        b.setTextColor(LIGHT);
        b.setPadding(px(8),px(10),px(8),px(10));
        b.setFocusable(true);
        focusStyle(b,false,backgroundColor,12);
        b.setOnFocusChangeListener((v,focused)->focusStyle(v,focused,backgroundColor,12));
        return b;
    }
    private LinearLayout panel() {
        LinearLayout p=new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(px(18),px(14),px(18),px(14));
        p.setBackground(background(PANEL,BORDER,1,20));
        return p;
    }
    private TextView heading(String s) {
        TextView t=label(s,23,LIGHT);
        t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private void actionRow(LinearLayout outer, String name1,String caption1,int bg1,Runnable action1,
        String name2,String caption2,int bg2,Runnable action2) {
        LinearLayout row=new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams first=new LinearLayout.LayoutParams(0,px(86),1);
        first.rightMargin=px(8);
        row.addView(tvAction(name1,caption1,bg1,action1),first);
        row.addView(tvAction(name2,caption2,bg2,action2),
            new LinearLayout.LayoutParams(0,px(86),1));
        LinearLayout.LayoutParams wrapper=new LinearLayout.LayoutParams(-1,-2);
        wrapper.topMargin=px(8);
        outer.addView(row,wrapper);
    }
    private void makeUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(px(27),px(18),px(27),px(20));
        setContentView(root);

        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo=new ImageView(this);
        logo.setImageResource(ru.btzakrepitel.tv.R.drawable.bt_generic);
        logo.setBackground(background(0xFF123F61,0xFF21547A,1,15));
        logo.setPadding(px(10),px(10),px(10),px(10));
        bar.addView(logo,new LinearLayout.LayoutParams(px(61),px(61)));
        LinearLayout titles=new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(px(15),0,0,0);
        TextView title=label("Аудио ТВ",31,LIGHT);
        title.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        titles.addView(title);
        titles.addView(label("Усиление звука и подключение Bluetooth",16,MUTED));
        bar.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        bluetoothPill=label("ᛒ Bluetooth",17,ACCENT);
        bluetoothPill.setPadding(px(18),px(10),px(18),px(10));
        bluetoothPill.setBackground(background(0xFF063B3A,0xFF087A75,1,22));
        bar.addView(bluetoothPill);
        root.addView(bar,new LinearLayout.LayoutParams(-1,px(80)));

        LinearLayout two=new LinearLayout(this);
        two.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams twoParams=new LinearLayout.LayoutParams(-1,0,1);
        twoParams.topMargin=px(13);
        root.addView(two,twoParams);

        LinearLayout left=new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams leftParams=new LinearLayout.LayoutParams(0,-1,1.54f);
        leftParams.rightMargin=px(16);
        two.addView(left,leftParams);
        LinearLayout leftHeader=new LinearLayout(this);
        leftHeader.setOrientation(LinearLayout.HORIZONTAL);
        leftHeader.setGravity(Gravity.CENTER_VERTICAL);
        leftHeader.addView(heading("Доступные устройства"),new LinearLayout.LayoutParams(0,-2,1));
        countLabel=label("Поиск…",15,MUTED);
        leftHeader.addView(countLabel);
        left.addView(leftHeader);
        scanStatus=label("Поиск Bluetooth-устройств поблизости",15,MUTED);
        left.addView(scanStatus);
        ScrollView listScroll=new ScrollView(this);
        listScroll.setFillViewport(false);
        listScroll.setClipToPadding(false);
        listScroll.setPadding(px(4),px(5),px(5),px(5));
        devices=new LinearLayout(this);
        devices.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(devices);
        LinearLayout.LayoutParams listParams=new LinearLayout.LayoutParams(-1,0,1);
        listParams.topMargin=px(7);
        left.addView(listScroll,listParams);

        ScrollView rightScroll=new ScrollView(this);
        rightScroll.setFillViewport(false);
        rightScroll.setClipToPadding(false);
        LinearLayout right=new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        rightScroll.addView(right);
        two.addView(rightScroll,new LinearLayout.LayoutParams(0,-1,1.0f));
        LinearLayout state=panel();
        state.addView(heading("Состояние"));
        status=label("",17,LIGHT);
        status.setLineSpacing(px(5),1.0f);
        LinearLayout.LayoutParams stateTextParams=new LinearLayout.LayoutParams(-1,-2);
        stateTextParams.topMargin=px(4);
        state.addView(status,stateTextParams);
        right.addView(state);

        LinearLayout actions=panel();
        actions.addView(heading("Действия"));
        actionRow(actions,"↻ Обновить поиск","Найти рядом",0xFF104366,this::startScan,
            "↗ Подключить сейчас","Повторить запрос",0xFF075045,()->{
                if(Prefs.address(this).isEmpty()){toast("Сначала закрепите устройство");return;}
                Prefs.setEnabled(this,true); startMonitor(true);showStatus();
            });
        actionRow(actions,"⚙ Настройки Bluetooth","Настройки телевизора",0xFF26384A,
            this::openBluetoothSettings,
            "📌 Снять закрепление","Открепить устройство",0xFF5A1F2B,()->{
                stopService(new Intent(this,MonitorService.class));
                Prefs.unpin(this);deviceSignature="";updateDeviceList();showStatus();
            });
        LinearLayout.LayoutParams actionsParams=new LinearLayout.LayoutParams(-1,-2);
        actionsParams.topMargin=px(13);
        right.addView(actions,actionsParams);


        LinearLayout boostPanel=panel();
        boostPanel.addView(heading("Усилитель громкости"));
        TextView boostStatus=label("Проверка эффекта…",14,MUTED);
        int storedBoost=getSharedPreferences("boost",MODE_PRIVATE).getInt("level",0);
        boolean boosting=getSharedPreferences("boost",MODE_PRIVATE).getBoolean("running",false);
        boostStatus.setText(boosting?"Активно: +"+storedBoost+" дБ":
            "Выключен · зависит от поддержки аудиоэффекта телевизором");
        boostPanel.addView(boostStatus);
        LinearLayout boostRow=new LinearLayout(this);
        boostRow.setOrientation(LinearLayout.HORIZONTAL);
        for(int gain:new int[]{6,10,16,20}) {
            Button levelButton=tvAction("+"+gain+" дБ","",0xFF16485B,()->{
                Intent effect=new Intent(this,BoostService.class);
                effect.setAction(BoostService.ACTION_SET);
                effect.putExtra("gain",gain);
                try{ if(Build.VERSION.SDK_INT>=26) startForegroundService(effect);
                    else startService(effect);
                    boostStatus.setText("Запрос +"+gain+" дБ отправлен");
                }catch(RuntimeException e){boostStatus.setText("Эффект недоступен: "+e.getClass().getSimpleName());}
            });
            LinearLayout.LayoutParams weight=new LinearLayout.LayoutParams(0,px(64),1);
            weight.rightMargin=px(4);
            boostRow.addView(levelButton,weight);
        }
        boostPanel.addView(boostRow);
        boostPanel.addView(tvAction("■ Выключить усиление","",0xFF472A36,()->{
            Intent effect=new Intent(this,BoostService.class);
            effect.setAction(BoostService.ACTION_STOP);
            try{startService(effect);}catch(RuntimeException ignored){}
            boostStatus.setText("Усиление выключено");
        }));
        LinearLayout.LayoutParams boostParams=new LinearLayout.LayoutParams(-1,-2);
        boostParams.topMargin=px(13);
        right.addView(boostPanel,boostParams);
        LinearLayout diagnostics=panel();
        diagnostics.addView(heading("Журнал"));
        logs=label("",14,MUTED);
        logs.setMaxLines(4);
        diagnostics.addView(logs);
        LinearLayout.LayoutParams logParams=new LinearLayout.LayoutParams(-1,0,1);
        logParams.topMargin=px(13);
        right.addView(diagnostics,logParams);
        // No permissions/status panel. Missing critical permissions always open an Android TV dialog.
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
    private void offerOverlayPermission() {
        if (!visible || !missing().isEmpty() || ConnectionPopup.isAllowed(this)) return;
        final String key = "overlay_prompt_seen";
        if (getPreferences(MODE_PRIVATE).getBoolean(key,false)) return;
        getPreferences(MODE_PRIVATE).edit().putBoolean(key,true).apply();
        new AlertDialog.Builder(this)
            .setTitle("Окно подключения")
            .setMessage("Чтобы показывать маленькое всплывающее изображение подключённой колонки поверх главного экрана ТВ, разрешите отображение поверх других приложений. Если телевизор не поддерживает это разрешение, автоматическое подключение продолжит работать.")
            .setNegativeButton("Не сейчас",null)
            .setPositiveButton("Разрешить",(dialog,which)->{
                try {
                    Intent i=new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:"+getPackageName()));
                    startActivity(i);
                } catch (RuntimeException e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:"+getPackageName())));
                    } catch (RuntimeException ignored) { toast("Настройки наложения недоступны"); }
                }
            }).show();
    }
    private void startScan() {
        if (!visible || !missing().isEmpty()) { validatePermissions(); return; }
        cancelScan();
        nearby.clear();
        deviceSignature="";
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
            deviceSignature="";
            updateDeviceList();
        } catch (SecurityException ignored) { }
    }
    private void updateDeviceList() {
        if(devices==null)return;
        if(!missing().isEmpty()){
            if(deviceSignature.equals("permission")) return;
            deviceSignature="permission";
            devices.removeAllViews();
            countLabel.setText("");
            scanStatus.setText("Поиск возобновится после разрешения доступа");
            return;
        }
        long now=SystemClock.elapsedRealtime();
        List<Nearby> found=new ArrayList<>();
        for(Nearby n:nearby.values()) if(now-n.seen<45_000) found.add(n);
        found.sort(Comparator.comparing(n->{
            try{return n.device.getName()==null?"":n.device.getName().toLowerCase();}
            catch(SecurityException ex){return "";}
        }));
        StringBuilder signature=new StringBuilder();
        for(Nearby n:found) {
            try {
                signature.append(n.device.getAddress()).append(n.device.getBondState())
                    .append(n.device.getAddress().equalsIgnoreCase(Prefs.address(this))).append(';');
            }catch(SecurityException ignored){}
        }
        String sign=signature.toString();
        if(sign.equals(deviceSignature))return; // never steal D-pad focus each refresh
        String focusedTag=null;
        View prevFocus=getCurrentFocus();
        if(prevFocus!=null && prevFocus.getTag() instanceof String)
            focusedTag=(String)prevFocus.getTag();
        deviceSignature=sign;
        devices.removeAllViews();
        countLabel.setText("Найдено: "+found.size());
        scanStatus.setText(scanning?"Поиск устройств…":
            (found.isEmpty()?"Включите колонку и повторите поиск.":"Серый квадрат — закрепление; красная круглая кнопка — закреплено."));
        if(found.isEmpty()){
            LinearLayout empty=panel();
            TextView bluetooth=label("ᛒ",50,ACCENT);
            bluetooth.setGravity(Gravity.CENTER); empty.addView(bluetooth);
            TextView emptyTitle=heading("Устройства не найдены");
            emptyTitle.setGravity(Gravity.CENTER);empty.addView(emptyTitle);
            TextView explain=label("Включите колонку. Если она не появилась, переведите её в режим обнаружения Bluetooth.",16,MUTED);
            explain.setGravity(Gravity.CENTER);empty.addView(explain);
            devices.addView(empty);
            return;
        }
        for(Nearby n:found) {
            BluetoothDevice d=n.device;
            try {
                String rawName=d.getName();
                final String name=(rawName==null||rawName.trim().isEmpty())
                    ? "Bluetooth-устройство":rawName.trim();
                String address=d.getAddress();
                boolean bonded=d.getBondState()==BluetoothDevice.BOND_BONDED;
                boolean pinned=address.equalsIgnoreCase(Prefs.address(this));

                LinearLayout card=new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(px(12),px(9),px(12),px(9));
                card.setFocusable(false);
                focusStyle(card,false,CARD,17);

                // Always show a neutral grey pin control. Pinned = RED ROUND
                // BUTTON, NOT a check mark or a red square.
                Button pin=button("",()->{
                    if(address.equalsIgnoreCase(Prefs.address(this))) unpinDevice(address);
                    else pinDevice(d);
                });
                pin.setId(View.generateViewId());
                pin.setTag(address+"|pin");
                pin.setContentDescription(pinned?"Открепить "+name:"Закрепить "+name);

                pin.setPadding(0,0,0,0);
                pin.setFocusable(true);
                pin.setBackground(pinSquare(pinned,false));
                card.addView(pin,new LinearLayout.LayoutParams(px(65),px(65)));

                LinearLayout body=new LinearLayout(this);
                body.setId(View.generateViewId());
                body.setTag(address+"|body");
                body.setOrientation(LinearLayout.HORIZONTAL);
                body.setGravity(Gravity.CENTER_VERTICAL);
                body.setPadding(px(5),0,0,0);
                body.setFocusable(true);
                body.setClickable(true);
                body.setOnClickListener(v->{
                    if(!address.equalsIgnoreCase(Prefs.address(this))) pinDevice(d);
                    else startMonitor(true);
                });
                body.setNextFocusLeftId(pin.getId());
                pin.setNextFocusRightId(body.getId());
                body.setOnFocusChangeListener((v,focus)->focusStyle(card,focus,CARD,17));
                pin.setOnFocusChangeListener((v,focus)->{
                    focusStyle(card,focus,CARD,17);
                    pin.setBackground(pinSquare(pinned,focus));
                });

                String normalized=name.toLowerCase(java.util.Locale.ROOT).replace('ё','е');
                boolean jbl=normalized.equals("jbl flip 6");
                boolean mini=normalized.equals("станция мини 3 про")
                    || normalized.equals("яндекс станция мини 3 про")
                    || normalized.equals("mini 3 pro")
                    || normalized.equals("станция mini 3 pro")
                    || normalized.equals("яндекс станция mini 3 pro");
                if(jbl||mini) {
                    ImageView product=new ImageView(this);
                    product.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    product.setImageResource(jbl?R.drawable.product_jbl_flip6_blue:
                        R.drawable.photo_yandex_mini3pro);
                    LinearLayout.LayoutParams img=new LinearLayout.LayoutParams(
                        jbl?px(138):px(112),px(82));
                    img.leftMargin=px(7);
                    img.rightMargin=px(8);
                    body.addView(product,img);
                } else {
                    ImageView generic=new ImageView(this);
                    generic.setImageResource(R.drawable.bt_generic);
                    generic.setPadding(px(12),px(12),px(12),px(12));
                    generic.setBackground(background(0xFF1A3D56,0xFF28485A,1,16));
                    LinearLayout.LayoutParams img=new LinearLayout.LayoutParams(px(70),px(70));
                    img.leftMargin=px(10);img.rightMargin=px(12);
                    body.addView(generic,img);
                }
                LinearLayout textColumn=new LinearLayout(this);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                TextView deviceName=label(name,20,LIGHT);
                deviceName.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
                textColumn.addView(deviceName);
                boolean connected=isConnectedA2dp(address);
                String line=connected?"● Подключено":bonded?"● Сопряжено · рядом":"● Не сопряжено";
                textColumn.addView(label(line,15,
                    connected?0xFF39D5FF:bonded?0xFF32E7B5:MUTED));
                if(pinned)textColumn.addView(label("Закреплено",13,ACCENT));
                body.addView(textColumn,new LinearLayout.LayoutParams(0,-2,1));
                body.addView(label("›",35,0xFF8DAFC9));
                card.addView(body,new LinearLayout.LayoutParams(0,-1,1));
                LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,px(102));
                cardParams.bottomMargin=px(12);
                devices.addView(card,cardParams);
                if((address+"|pin").equals(focusedTag)) pin.requestFocus();
                else if((address+"|body").equals(focusedTag)) body.requestFocus();
            } catch(SecurityException ignored) {}
        }
    }

    private boolean isConnectedA2dp(String addr) {
        if(a2dp==null||!hasBluetoothConnect())return false;
        try {
            for(BluetoothDevice device:a2dp.getConnectedDevices())
                if(addr.equalsIgnoreCase(device.getAddress()))return true;
        } catch(SecurityException ignored){}
        return false;
    }
    private void unpinDevice(String address) {
        if (!address.equalsIgnoreCase(Prefs.address(this))) return;
        stopService(new Intent(this,MonitorService.class));
        Prefs.unpin(this);
        Prefs.addLog(this,"Закрепление снято");
        deviceSignature="";
        updateDeviceList();
        showStatus();
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
            deviceSignature="";
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
        if(status==null)return;
        status.setText(
          "Закреплено:    "+(Prefs.address(this).isEmpty()?"—":Prefs.name(this))
          +"\nМониторинг:   "+(Prefs.enabled(this)?"● включён":"○ выключен")
          +"\nСтатус:          "+Prefs.status(this));
        String[] lines=Prefs.log(this).split("\n");
        StringBuilder summary=new StringBuilder();
        for(int i=0;i<Math.min(4,lines.length);i++) {
            if(!lines[i].trim().isEmpty())summary.append("• ").append(lines[i]).append("\n");
        }
        logs.setText(summary.length()==0?"Событий пока нет":summary.toString());
        if(bluetoothPill!=null) {
            boolean on=false;
            try{on=adapter!=null&&adapter.isEnabled();}catch(SecurityException ignored){}
            bluetoothPill.setText(on?"ᛒ  Bluetooth: ВКЛ":"ᛒ  Bluetooth: ВЫКЛ");
            bluetoothPill.setTextColor(on?ACCENT:0xFFFF8A98);
        }
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
