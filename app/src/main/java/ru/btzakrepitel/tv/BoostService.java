package ru.btzakrepitel.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.audiofx.LoudnessEnhancer;
import android.os.Build;
import android.os.IBinder;

/**
 * Global output booster, as in the supplied Audio TV APK.
 * Actual device support is detected; never report success if the
 * firmware refuses system audio session 0 or effect control.
 */
public final class BoostService extends Service {
    public static final String ACTION_SET="ru.btzakrepitel.tv.BOOST_SET";
    public static final String ACTION_STOP="ru.btzakrepitel.tv.BOOST_STOP";
    private static final String CHANNEL="audio_tv_boost";
    private static final int NOTIFICATION=8101;
    private LoudnessEnhancer enhancer;

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(manager!=null)manager.createNotificationChannel(
            new NotificationChannel(CHANNEL,"Усиление звука",NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent,int flags,int id) {
        String action=intent==null?"":intent.getAction();
        if(ACTION_STOP.equals(action)) {
            stopEffect();
            getSharedPreferences("boost",MODE_PRIVATE).edit()
                .putBoolean("running",false).putInt("level",0)
                .putString("status","Усиление отключено").apply();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        SharedPreferences p=getSharedPreferences("boost",MODE_PRIVATE);
        int gain=intent!=null && ACTION_SET.equals(action)
            ?intent.getIntExtra("gain",0):p.getInt("level",0);
        if(gain!=6 && gain!=10 && gain!=16 && gain!=20) {
            stopSelf();
            return START_NOT_STICKY;
        }
        PendingIntent open=PendingIntent.getActivity(this,0,
            new Intent(this,MainActivity.class),
            PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification n=new Notification.Builder(this,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentTitle("Аудио ТВ · усиление")
            .setContentText("Режим +" + gain + " дБ")
            .setContentIntent(open).setOngoing(true).build();
        startForeground(NOTIFICATION,n);
        try {
            if(enhancer==null)enhancer=new LoudnessEnhancer(0);
            if(!enhancer.hasControl())throw new IllegalStateException("Аудиоэффект не контролируется приложением");
            enhancer.setTargetGain(gain*100);
            int result=enhancer.setEnabled(true);
            if(result!=0 || !enhancer.getEnabled())
                throw new IllegalStateException("Прошивка отклонила аудиоэффект");
            p.edit().putBoolean("running",true).putInt("level",gain)
                .putString("status","Усиление +"+gain+" дБ включено").apply();
            return START_STICKY;
        }catch(RuntimeException e) {
            p.edit().putBoolean("running",false).putInt("level",0)
                .putString("status","Не удалось включить: "+e.getClass().getSimpleName()).apply();
            stopEffect();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
    }

    private void stopEffect(){
        if(enhancer!=null){
            try{enhancer.setEnabled(false);}catch(RuntimeException ignored){}
            try{enhancer.release();}catch(RuntimeException ignored){}
            enhancer=null;
        }
    }
    @Override public void onDestroy(){stopEffect();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
