package ru.frolov.tvvolumebooster;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class MainActivity extends Activity {
    private static final int BG = 0xFF09121F;
    private static final int CARD = 0xFF14293D;
    private static final int ACCENT = 0xFF43D9ED;
    private static final int PALE = 0xFFF2F7FF;
    private static final int SUB = 0xFFAAC0D1;
    private static final int RED = 0xFFD83C52;
    private static final int GREEN = 0xFF64E6B8;
    private static final int CODE_CONNECT = 201;
    private static final int CODE_SCAN = 202;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, BluetoothDevice> found = new HashMap<>();
    private BluetoothAdapter adapter;
    private BluetoothProfile a2dp;
    private boolean proxyRequested = false;
    private boolean scanning = false;
    private boolean visible = false;
    private boolean btPage = false;
    private boolean receiverRegistered = false;
    private LinearLayout root, page, deviceList;
    private TextView volumeStatus, volumeDetail;
    private TextView btTitle, btState, btLog, listHint;
    private Button tabVolume, tabBluetooth;
    private Button scanButton;
    private final Runnable statusTick = new Runnable() {
        @Override public void run() {
            if (!visible) return;
            if (btPage) updateBtStatus();
            else updateVolumeStatus();
            handler.postDelayed(this, 2500);
        }
    };

    private final BluetoothProfile.ServiceListener a2dpListener = new BluetoothProfile.ServiceListener() {
        @Override public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy;
                if (btPage) { renderDevices(); updateBtStatus(); }
            }
        }
        @Override public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null;
                proxyRequested = false;
                if (btPage) updateBtStatus();
            }
        }
    };
    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent i) {
            String action = i.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                if (!canConnect()) return;
                try {
                    BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (d != null && d.getAddress() != null) {
                        found.put(d.getAddress(), d);
                        if (btPage) renderDevices();
                    }
                } catch (SecurityException ignored) { }
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                if (btPage) renderDevices();
            } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED.equals(action)
                    || BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)
                    || BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                if (btPage) {
                    handler.postDelayed(() -> { ensureProfile(); renderDevices(); updateBtStatus(); }, 350);
                }
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        adapter = BluetoothAdapter.getDefaultAdapter();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(34), dp(18), dp(34), dp(18));
        root.setBackgroundColor(BG);
        setContentView(root);
        buildHeader();
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams pageParams = new LinearLayout.LayoutParams(-1, 0, 1f);
        root.addView(page, pageParams);

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(bluetoothEvents, filter);
        receiverRegistered = true;

        if (getIntent() != null && getIntent().getBooleanExtra("open_bt", false)) {
            openBluetooth();
        } else {
            openVolume();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        // Keep the chosen device connected even after the app was suspended.
        if (BluetoothPrefs.enabled(this) && !BluetoothPrefs.address(this).isEmpty()) {
            startMonitor(false);
        }
        handler.removeCallbacks(statusTick);
        handler.post(statusTick);
        if (btPage) {
            if (canConnect()) { ensureProfile(); renderDevices(); }
            else requestConnectPermission();
        }
    }
    @Override protected void onPause() {
        visible = false;
        handler.removeCallbacks(statusTick);
        cancelScan();
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (receiverRegistered) unregisterReceiver(bluetoothEvents);
        if (adapter != null && a2dp != null) {
            try { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp); }
            catch (SecurityException ignored) { }
        }
        super.onDestroy();
    }
    private void buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams headParams = new LinearLayout.LayoutParams(-1, dp(75));
        root.addView(header, headParams);

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.addView(label("АУДИО ТВ", 29, PALE, true));
        brand.addView(label("DEXP  /  ЯНДЕКС ТВ", 13, ACCENT, true));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1f));

        tabVolume = button("▰  УСИЛЕНИЕ ЗВУКА", CARD);
        tabBluetooth = button("BLUETOOTH", CARD);
        tabBluetooth.setCompoundDrawablesWithIntrinsicBounds(R.drawable.bluetooth_mark, 0, 0, 0);
        tabBluetooth.setCompoundDrawablePadding(dp(6));
        LinearLayout.LayoutParams tabParams1 = new LinearLayout.LayoutParams(dp(218), dp(54));
        tabParams1.rightMargin = dp(10);
        header.addView(tabVolume, tabParams1);
        header.addView(tabBluetooth, new LinearLayout.LayoutParams(dp(201), dp(54)));
        tabVolume.setOnClickListener(v -> openVolume());
        tabBluetooth.setOnClickListener(v -> openBluetooth());
    }
    private void openVolume() {
        btPage = false;
        stopPage();
        tabVolume.setBackground(buttonBackground(0xFF146779));
        tabBluetooth.setBackground(buttonBackground(CARD));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout inner = column();
        inner.setPadding(dp(18), dp(6), dp(18), dp(20));
        scroll.addView(inner);

        inner.addView(label("УСИЛИТЕЛЬ ГРОМКОСТИ", 30, PALE, true));
        margin(inner, label("Более сильное усиление без дополнительной динамической компрессии. "
                + "Настройка сохранится при переходе в другие приложения.", 18, SUB, false), 8);
        volumeStatus = label("", 21, ACCENT, true);
        margin(inner, volumeStatus, 17);
        volumeDetail = label("", 16, SUB, false);
        margin(inner, volumeDetail, 5);
        LinearLayout row1 = row();
        margin(inner, row1, 15);
        LinearLayout row2 = row();
        margin(inner, row2, 9);
        Button first = preset(row1, "×1,5", "+6 дБ  •  МЯГКО", 1, true);
        preset(row1, "×2", "+10 дБ  •  СРЕДНЕ", 2, false);
        preset(row2, "×3", "+16 дБ  •  СИЛЬНО", 3, true);
        preset(row2, "×4", "+20 дБ  •  МАКСИМУМ", 4, false);

        Button stop = button("■   ВЫКЛЮЧИТЬ УСИЛЕНИЕ", 0xFF823747);
        LinearLayout.LayoutParams offParams = new LinearLayout.LayoutParams(-1, dp(62));
        offParams.topMargin = dp(13);
        inner.addView(stop, offParams);
        stop.setOnClickListener(v -> {
            Intent intent = new Intent(this, BoostService.class);
            intent.setAction(BoostService.ACTION_STOP);
            startService(intent);
            handler.postDelayed(this::updateVolumeStatus, 280);
        });
        margin(inner, label("×1,5–×4 — ориентиры громкости на слух, а не гарантированное "
                + "увеличение в эти разы. Реально задаются +6/+10/+16/+20 дБ. "
                + "Android может ограничивать пики. При хрипах уменьшите усиление, "
                + "особенно в режимах ×3 и ×4.", 14, SUB, false), 15);
        updateVolumeStatus();
        first.requestFocus();
    }
    private Button preset(LinearLayout row, String level, String explanation, int mode, boolean left) {
        Button b = button(level + "\n" + explanation, CARD);
        b.setTextSize(23);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(94), 1f);
        if (left) p.rightMargin = dp(12);
        row.addView(b, p);
        b.setOnClickListener(v -> {
            Intent i = new Intent(this, BoostService.class);
            i.setAction(BoostService.ACTION_SET);
            i.putExtra(BoostService.EXTRA_LEVEL, mode);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
            else startService(i);
            volumeStatus.setText("ПРИМЕНЯЕТСЯ " + level + "...");
            handler.postDelayed(this::updateVolumeStatus, 400);
        });
        return b;
    }
    private void updateVolumeStatus() {
        if (volumeStatus == null || btPage) return;
        android.content.SharedPreferences p = getSharedPreferences("boost", MODE_PRIVATE);
        boolean enabled = p.getBoolean("running", false);
        int gain = p.getInt("level", 0);
        volumeStatus.setText(enabled ? "●   УСИЛЕНИЕ РАБОТАЕТ  ·  ×"
                + (gain == 1 ? "1,5" : String.valueOf(gain)) : "○   УСИЛЕНИЕ ВЫКЛЮЧЕНО");
        volumeDetail.setText(p.getString("status", "Выберите подходящий режим усиления.")
                + (enabled && gain >= 1 && gain <= 4
                ? "  Цель: +" + BoostService.GAINS_DB[gain] + " дБ, не измеренный результат."
                : ""));
    }
    private void openBluetooth() {
        btPage = true;
        stopPage();
        tabVolume.setBackground(buttonBackground(CARD));
        tabBluetooth.setBackground(buttonBackground(0xFF146779));
        page.addView(label("ЗАКРЕПЛЕНИЕ BLUETOOTH-УСТРОЙСТВА", 26, PALE, true));
        margin(page, label("Закрепи колонку один раз — приложение будет проверять соединение и пытаться восстановить его.",
                16, SUB, false), 4);
        LinearLayout columns = row();
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1f);
        cp.topMargin = dp(14);
        page.addView(columns, cp);

        LinearLayout left = column();
        left.setPadding(dp(14), dp(10), dp(14), dp(6));
        left.setBackground(round(CARD, 15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1.28f);
        lp.rightMargin = dp(16);
        columns.addView(left, lp);

        LinearLayout leftTop = row();
        leftTop.setGravity(Gravity.CENTER_VERTICAL);
        left.addView(leftTop, new LinearLayout.LayoutParams(-1, dp(50)));
        TextView leftLabel = label("АУДИОУСТРОЙСТВА", 19, PALE, true);
        leftTop.addView(leftLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        Button reload = button("⟳ ОБНОВИТЬ", 0xFF17566A);
        reload.setTextSize(14);
        leftTop.addView(reload, new LinearLayout.LayoutParams(dp(135), dp(44)));
        reload.setOnClickListener(v -> renderDevices());
        listHint = label("", 14, SUB, false);
        margin(left, listHint, 3);
        ScrollView listScroll = new ScrollView(this);
        listScroll.setFillViewport(true);
        LinearLayout.LayoutParams listP = new LinearLayout.LayoutParams(-1, 0, 1f);
        listP.topMargin = dp(7);
        left.addView(listScroll, listP);
        deviceList = column();
        listScroll.addView(deviceList);
        scanButton = button("⌁  НАЙТИ НОВЫЕ УСТРОЙСТВА", 0xFF17566A);
        LinearLayout.LayoutParams scanP = new LinearLayout.LayoutParams(-1, dp(57));
        scanP.topMargin = dp(7);
        left.addView(scanButton, scanP);
        scanButton.setOnClickListener(v -> beginScan());

        LinearLayout right = column();
        right.setPadding(dp(18), dp(13), dp(18), dp(12));
        right.setBackground(round(CARD, 15));
        columns.addView(right, new LinearLayout.LayoutParams(0, -1, 1f));
        right.addView(label("СОСТОЯНИЕ ПОДКЛЮЧЕНИЯ", 19, PALE, true));
        btTitle = label("", 23, ACCENT, true);
        margin(right, btTitle, 13);
        btState = label("", 16, PALE, false);
        margin(right, btState, 7);
        Button retry = button("↻  ПОДКЛЮЧИТЬ СЕЙЧАС", 0xFF17566A);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(58));
        rp.topMargin = dp(17);
        right.addView(retry, rp);
        retry.setOnClickListener(v -> {
            if (!BluetoothPrefs.enabled(this)) { toast("Сначала закрепите колонку слева"); return; }
            startMonitor(true);
            handler.postDelayed(this::updateBtStatus, 350);
        });
        Button unpin = button("✕  СНЯТЬ ЗАКРЕПЛЕНИЕ", 0xFF793747);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(-1, dp(58));
        up.topMargin = dp(8);
        right.addView(unpin, up);
        unpin.setOnClickListener(v -> {
            BluetoothPrefs.unpin(this);
            StartupManager.cancelWatchdog(this);
            Intent i = new Intent(this, BluetoothMonitorService.class);
            i.setAction(BluetoothMonitorService.ACTION_STOP);
            startService(i);
            renderDevices();
            updateBtStatus();
        });
        Button settings = button("⚙  НАСТРОЙКИ BLUETOOTH ТВ", 0xFF254459);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(58));
        sp.topMargin = dp(8);
        right.addView(settings, sp);
        settings.setOnClickListener(v -> bluetoothSettings());

        Button startOnBoot = button("", 0xFF254459);
        LinearLayout.LayoutParams startupP = new LinearLayout.LayoutParams(-1, dp(47));
        startupP.topMargin = dp(8);
        right.addView(startOnBoot, startupP);
        updateBootButton(startOnBoot);
        startOnBoot.setTextSize(14);
        startOnBoot.setOnClickListener(v -> {
            boolean enabled = getSharedPreferences("tv_settings", MODE_PRIVATE)
                    .getBoolean("show_on_boot", true);
            getSharedPreferences("tv_settings", MODE_PRIVATE).edit()
                    .putBoolean("show_on_boot", !enabled).apply();
            updateBootButton(startOnBoot);
        });
        Button battery = button("✓  РАЗРЕШИТЬ РАБОТУ В ФОНЕ", 0xFF254459);
        battery.setTextSize(14);
        LinearLayout.LayoutParams batteryP = new LinearLayout.LayoutParams(-1, dp(47));
        batteryP.topMargin = dp(7);
        right.addView(battery, batteryP);
        battery.setOnClickListener(v -> requestNoBatteryRestrictions());
        margin(right, label("ПОСЛЕДНИЕ СОБЫТИЯ", 15, SUB, true), 12);
        btLog = label("", 13, SUB, false);
        ScrollView logScroll = new ScrollView(this);
        logScroll.addView(btLog);
        right.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        updateBtStatus();
        renderDevices();
        if (!canConnect()) requestConnectPermission();
        else ensureProfile();
        reload.requestFocus();
    }
    private void updateBtStatus() {
        if (!btPage || btTitle == null) return;
        String selected = BluetoothPrefs.name(this);
        boolean pin = BluetoothPrefs.enabled(this) && !BluetoothPrefs.address(this).isEmpty();
        btTitle.setText(pin ? "●   " + selected : "○   УСТРОЙСТВО НЕ ЗАКРЕПЛЕНО");
        btState.setText("Мониторинг: " + (pin ? "ВКЛЮЧЁН" : "ВЫКЛЮЧЕН") + "\n"
                + "Статус: " + BluetoothPrefs.status(this));
        String log = BluetoothPrefs.log(this);
        String[] entries = log.split("\n");
        StringBuilder recent = new StringBuilder();
        for (int i = 0; i < Math.min(6, entries.length); i++) {
            if (!entries[i].trim().isEmpty()) recent.append(entries[i]).append("\n");
        }
        btLog.setText(recent.length() == 0 ? "Пока событий нет" : recent.toString());
    }
    private void renderDevices() {
        if (!btPage || deviceList == null) return;
        deviceList.removeAllViews();
        if (adapter == null) {
            deviceList.addView(label("На телевизоре не обнаружен Bluetooth.", 17, SUB, false));
            return;
        }
        if (!canConnect()) {
            deviceList.addView(label("Разрешите доступ к Bluetooth, чтобы видеть сопряжённые устройства.",
                    17, SUB, false));
            Button permit = button("РАЗРЕШИТЬ ДОСТУП", 0xFF17566A);
            margin(deviceList, permit, 14);
            permit.setOnClickListener(v -> requestConnectPermission());
            listHint.setText("Требуется системное разрешение Bluetooth");
            return;
        }
        try {
            if (!adapter.isEnabled()) {
                deviceList.addView(label("Bluetooth выключен. Включите его в настройках ТВ.",
                        17, SUB, false));
                listHint.setText("Bluetooth выключен");
                return;
            }
            Map<String, BluetoothDevice> merged = new HashMap<>(found);
            Set<BluetoothDevice> paired = adapter.getBondedDevices();
            for (BluetoothDevice d : paired) merged.put(d.getAddress(), d);
            if (a2dp != null) for (BluetoothDevice d : a2dp.getConnectedDevices()) {
                merged.put(d.getAddress(), d);
            }
            List<BluetoothDevice> sorted = new ArrayList<>(merged.values());
            final String pinned = BluetoothPrefs.address(this);
            sorted.sort(Comparator
                    .comparing((BluetoothDevice d) -> !pinned.equalsIgnoreCase(d.getAddress()))
                    .thenComparing(d -> !isConnected(d))
                    .thenComparing(d -> deviceName(d).toLowerCase(Locale.ROOT)));
            listHint.setText("Сопряжённые устройства отображаются всегда • " + sorted.size()
                    + (scanning ? " • поиск..." : ""));
            if (sorted.isEmpty()) {
                margin(deviceList, label("Список пуст. Сопрягите колонку в настройках ТВ "
                        + "или запустите поиск ниже.", 16, SUB, false), 20);
                return;
            }
            for (BluetoothDevice d : sorted) addDeviceCard(d);
        } catch (SecurityException e) {
            deviceList.addView(label("Не удалось получить список: проверьте разрешения.", 17, SUB, false));
        }
    }
    private void addDeviceCard(BluetoothDevice device) {
        String name = deviceName(device);
        String address = device.getAddress();
        boolean bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
        boolean connected = isConnected(device);
        boolean pinned = address.equalsIgnoreCase(BluetoothPrefs.address(this));
        LinearLayout card = row();
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(8), dp(7), dp(12), dp(7));
        card.setBackground(round(0xFF19354A, 13));
        LinearLayout.LayoutParams cardP = new LinearLayout.LayoutParams(-1, dp(99));
        cardP.bottomMargin = dp(8);
        deviceList.addView(card, cardP);
        Button pin = button(pinned ? "✓" : "📌", RED);
        pin.setTextSize(25);
        LinearLayout.LayoutParams pinP = new LinearLayout.LayoutParams(dp(65), dp(64));
        pinP.rightMargin = dp(8);
        card.addView(pin, pinP);
        pin.setEnabled(bonded);
        if (!bonded) pin.setAlpha(0.5f);
        pin.setOnClickListener(v -> selectDevice(device));
        String n = name.toLowerCase(Locale.ROOT).replace('ё', 'е');
        boolean jbl = n.equals("jbl flip 6");
        boolean yandex = n.equals("станция мини 3 про") || n.equals("яндекс станция мини 3 про")
                || n.equals("mini 3 pro");
        ImageView picture = new ImageView(this);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        picture.setImageResource(jbl ? R.drawable.photo_jbl_flip6 :
                yandex ? R.drawable.photo_yandex_mini3pro : R.drawable.bt_generic);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(91), dp(80));
        ip.rightMargin = dp(12);
        card.addView(picture, ip);
        LinearLayout details = column();
        TextView title = label(name, 19, PALE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title);
        details.addView(label(connected ? "● Подключено" :
                        (bonded ? "● Сопряжено" : "○ Не сопряжено"),
                14, connected ? GREEN : SUB, false));
        if (pinned) details.addView(label("ЗАКРЕПЛЕНО", 13, ACCENT, true));
        card.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));
        // Both the whole card and the red pin can be selected with D-pad.
        card.setFocusable(true);
        card.setTag(address);
        card.setOnFocusChangeListener((v, focused) ->
                card.setBackground(round(focused ? 0xFF276078 : 0xFF19354A, 13)));
        card.setOnClickListener(v -> selectDevice(device));
    }
    private void selectDevice(BluetoothDevice device) {
        try {
            if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
                toast("Сначала сопрягите устройство в настройках телевизора");
                bluetoothSettings();
                return;
            }
            cancelScan();
            BluetoothPrefs.pin(this, device.getAddress(), deviceName(device));
            StartupManager.scheduleWatchdog(this);
            startMonitor(true);
            renderDevices();
            updateBtStatus();
        } catch (SecurityException e) {
            requestConnectPermission();
        }
    }
    private void startMonitor(boolean retry) {
        Intent service = new Intent(this, BluetoothMonitorService.class);
        if (retry) service.setAction(BluetoothMonitorService.ACTION_RETRY);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (RuntimeException e) {
            BluetoothPrefs.addLog(this, "YaOS запретила запуск фоновой службы");
            updateBtStatus();
        }
    }
    private String deviceName(BluetoothDevice device) {
        try {
            String n = device.getName();
            return n == null || n.trim().isEmpty() ? "Bluetooth-устройство" : n.trim();
        } catch (SecurityException e) { return "Bluetooth-устройство"; }
    }
    private boolean isConnected(BluetoothDevice device) {
        if (a2dp == null || !canConnect()) return false;
        try { return a2dp.getConnectionState(device) == BluetoothProfile.STATE_CONNECTED; }
        catch (SecurityException e) { return false; }
    }
    private boolean canConnect() {
        return Build.VERSION.SDK_INT < 31 ||
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestConnectPermission() {
        if (Build.VERSION.SDK_INT >= 31 && !canConnect()) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, CODE_CONNECT);
        }
    }
    private void ensureProfile() {
        if (adapter == null || a2dp != null || proxyRequested || !canConnect()) return;
        try {
            if (adapter.isEnabled()) {
                proxyRequested = adapter.getProfileProxy(this, a2dpListener, BluetoothProfile.A2DP);
            }
        } catch (SecurityException ignored) {}
    }
    private boolean scanGranted() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }
    private void beginScan() {
        if (!canConnect()) { requestConnectPermission(); return; }
        if (!scanGranted()) {
            String p = Build.VERSION.SDK_INT >= 31 ?
                    Manifest.permission.BLUETOOTH_SCAN : Manifest.permission.ACCESS_FINE_LOCATION;
            requestPermissions(new String[]{p}, CODE_SCAN);
            return;
        }
        cancelScan();
        found.clear();
        try {
            scanning = adapter != null && adapter.isEnabled() && adapter.startDiscovery();
            if (!scanning) toast("Поиск не начался. Проверьте Bluetooth и геолокацию в настройках.");
            renderDevices();
            if (scanning) handler.postDelayed(() -> { cancelScan(); if (btPage) renderDevices(); }, 16000);
        } catch (SecurityException e) { toast("Для поиска требуется разрешение Bluetooth"); }
    }
    private void cancelScan() {
        if (adapter == null || !scanGranted()) { scanning = false; return; }
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); }
        catch (SecurityException ignored) {}
        scanning = false;
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == CODE_CONNECT && canConnect()) {
            ensureProfile();
            if (btPage) renderDevices();
        } else if (code == CODE_SCAN && scanGranted()) {
            beginScan();
        } else if (btPage) {
            renderDevices();
            toast("Разрешение не предоставлено. Его можно включить в настройках приложения.");
        }
    }
    private void updateBootButton(Button b) {
        boolean enabled = getSharedPreferences("tv_settings", MODE_PRIVATE)
                .getBoolean("show_on_boot", true);
        b.setText(enabled ? "✓  ОТКРЫВАТЬ ОКНО ПРИ СТАРТЕ ТВ: ДА"
                : "○  ОТКРЫВАТЬ ОКНО ПРИ СТАРТЕ ТВ: НЕТ");
    }

    private void requestNoBatteryRestrictions() {
        // Must be requested by user. Some Yandex TV firmwares omit this settings screen.
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
                if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    toast("Работа без ограничений энергосбережения уже разрешена");
                    return;
                }
                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
            }
        } catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (RuntimeException fail) {
                toast("YaOS не предоставляет настройки энергосбережения для этого приложения");
            }
        }
    }

    private void bluetoothSettings() {
        try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); }
        catch (RuntimeException e) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (RuntimeException ignored) { toast("Настройки Bluetooth недоступны"); }
        }
    }
    private void stopPage() {
        page.removeAllViews();
        deviceList = null;
        volumeStatus = null;
        volumeDetail = null;
        btTitle = null;
        btState = null;
        btLog = null;
    }
    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }
    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }
    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }
    private Button button(String text, int color) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(17);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(new ColorStateList(
                new int[][] {new int[] {android.R.attr.state_focused}, new int[] {}},
                new int[] {BG, PALE}));
        b.setBackground(buttonBackground(color));
        b.setFocusable(true);
        return b;
    }
    private StateListDrawable buttonBackground(int normal) {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_focused}, round(ACCENT, 13));
        d.addState(new int[]{android.R.attr.state_pressed}, round(ACCENT, 13));
        d.addState(new int[]{}, round(normal, 13));
        return d;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private void margin(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(top);
        parent.addView(child, p);
    }
    private int dp(int n) {
        return Math.round(n * getResources().getDisplayMetrics().density);
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
}
