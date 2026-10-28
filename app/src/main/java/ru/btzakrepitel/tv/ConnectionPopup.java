package ru.btzakrepitel.tv;

import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.Locale;

/** Small, non-interactive product popup on an actual A2DP connection event.
 * Android TV requires the user to grant "Display over other apps" for a
 * background service's window. Never fabricate battery percentages. */
final class ConnectionPopup {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static View current;
    private static Runnable hide;
    private static long lastShownAt;
    private static String lastAddress = "";
    private ConnectionPopup() {}

    static boolean isAllowed(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    static void show(Context context, BluetoothDevice device) {
        if (device == null) return;
        Context c = context.getApplicationContext();
        if (!isAllowed(c)) return;
        final String address;
        final String name;
        final int battery;
        try {
            address = device.getAddress();
            String n = device.getName();
            name = n == null || n.trim().isEmpty() ? "Bluetooth-устройство" : n.trim();
            // -1 means the Bluetooth stack did not report battery information.
            battery = Build.VERSION.SDK_INT >= 26 ? device.getBatteryLevel() : -1;
        } catch (SecurityException e) {
            return;
        }
        MAIN.post(() -> render(c, address, name, battery));
    }

    private static void render(Context c, String addr, String name, int battery) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (addr.equals(lastAddress) && now - lastShownAt < 6000) return;
        lastAddress = addr;
        lastShownAt = now;
        clear(c);
        final WindowManager wm = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
        if (wm == null || !isAllowed(c)) return;

        float density = c.getResources().getDisplayMetrics().density;
        int imageW = Math.round(184 * density);
        int imageH = Math.round(106 * density);
        int pad = Math.round(8 * density);

        LinearLayout popup = new LinearLayout(c);
        popup.setOrientation(LinearLayout.VERTICAL);
        popup.setGravity(Gravity.CENTER);
        popup.setPadding(pad, pad, pad, pad);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xCC071726);
        background.setCornerRadius(24 * density);
        popup.setBackground(background);

        ImageView picture = new ImageView(c);
        picture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        String normal = name.toLowerCase(Locale.ROOT).replace('ё','е');
        int image = normal.equals("jbl flip 6") ? R.drawable.product_jbl_flip6_blue :
            (normal.equals("станция мини 3 про") ||
             normal.equals("яндекс станция мини 3 про") ||
             normal.equals("mini 3 pro") ||
             normal.equals("станция mini 3 pro") ||
             normal.equals("яндекс станция mini 3 pro")) ? R.drawable.photo_yandex_mini3pro :
             R.drawable.bt_generic;
        picture.setImageResource(image);
        popup.addView(picture, new LinearLayout.LayoutParams(imageW,imageH));

        TextView caption = new TextView(c);
        String label = "Подключено";
        if (battery >= 0 && battery <= 100) label += "  ·  " + battery + "%";
        caption.setText(label);
        caption.setTextColor(Color.WHITE);
        caption.setGravity(Gravity.CENTER);
        caption.setTextSize(14);
        caption.setPadding(0,0,0,Math.round(3*density));
        popup.addView(caption, new LinearLayout.LayoutParams(-1,-2));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            Math.round(204*density), WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT>=26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = Math.round(34*density);
        try {
            wm.addView(popup,params);
            current = popup;
            hide = () -> clear(c);
            MAIN.postDelayed(hide,3500);
        } catch (RuntimeException e) {
            // Overlays can be blocked by the Android TV vendor even with permission.
            current = null;
        }
    }

    private static void clear(Context c) {
        if (hide != null) MAIN.removeCallbacks(hide);
        hide = null;
        if (current != null) {
            WindowManager wm = (WindowManager)c.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) try { wm.removeViewImmediate(current); } catch (RuntimeException ignored) {}
        }
        current = null;
    }
}
