package ru.dexp.gappssetup;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class FireInstallerActivity extends Activity {
    static FireInstallerActivity active;
    final Handler main = new Handler(Looper.getMainLooper());
    TextView status;
    final ArrayList<Button> buttons = new ArrayList<>();
    volatile boolean working = false;
    static final String[] LABELS = {
        "1. Google Services Framework 10",
        "2. Google Account Manager 7.1.2",
        "3. Google Play Services 24.35.30",
        "4. Google Play Store 42.6.23"
    };
    static final String[] PACKAGES = {
        "com.google.android.gsf", "com.google.android.gsf.login",
        "com.google.android.gms", "com.android.vending"
    };
    static final String[][] ASSETS = {
        {"gsf/gsf.apk"},
        {"account/account.apk"},
        {"gms/base.apk", "gms/split_config.xxxhdpi.apk", "gms/split_config.en.apk", "gms/split_config.ru.apk"},
        {"store/base.apk", "store/split_config.armeabi_v7a.apk",
         "store/split_phonesky_data_loader.apk", "store/split_phonesky_data_loader.config.armeabi_v7a.apk",
         "store/split_config.en.apk", "store/split_config.ru.apk"}
    };
    void info(String msg) {main.post(() -> {if(status!=null)status.setText(msg);});}
    void enabled(boolean enable){main.post(() -> {for(Button b:buttons)b.setEnabled(enable);});}
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        active=this;
        int p=(int)(24*getResources().getDisplayMetrics().density);
        LinearLayout root=new LinearLayout(this);
        root.setPadding(p,p,p,p);root.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);
        TextView title=new TextView(this);title.setText("Google Play для DEXP (Fire-Tools)");title.setTextSize(23);root.addView(title);
        TextView detail=new TextView(this);detail.setText(
            "Android 11 / armeabi-v7a.\n"+
            "Это другая версия магазина, для обычного Android, а не Android TV. "+
            "Если компонент уже установлен в более новой версии, сначала удалите его кнопкой удаления. "+
            "Удаление стирает его данные. Все установки подтверждаются стандартным окном Android. "+
            "Наличие системных ограничений YaOS по-прежнему может помешать работе Google Play.");
        detail.setTextSize(16);detail.setPadding(0,p/2,0,p/2);root.addView(detail);
        for(int i=0;i<LABELS.length;i++){
            final int idx=i;
            TextView header=new TextView(this);header.setText(LABELS[i]+installedVersion(PACKAGES[i]));header.setTextSize(17);
            header.setPadding(0,p/3,0,0);root.addView(header);
            Button install=new Button(this);install.setText("Установить "+LABELS[i]);install.setTextSize(15);install.setAllCaps(false);
            install.setOnClickListener(v->install(idx));root.addView(install);buttons.add(install);
            Button uninstall=new Button(this);uninstall.setText("Удалить предыдущую версию: "+LABELS[i]);
            uninstall.setTextSize(14);uninstall.setAllCaps(false);
            uninstall.setOnClickListener(v->uninstall(idx));root.addView(uninstall);buttons.add(uninstall);
        }
        Button permission=new Button(this);
        permission.setText("Разрешить установку APK из этого источника");
        permission.setAllCaps(false);
        permission.setOnClickListener(v->allowInstall());
        root.addView(permission);
        status=new TextView(this);status.setTextSize(16);status.setPadding(0,p/2,0,p/2);
        root.addView(status);
        setContentView(scroll);
        buttons.get(0).requestFocus();
        StringBuilder ready=new StringBuilder("Проверка вложенных файлов:\n");
        for(int i=0;i<LABELS.length;i++){
            try{for(String name:ASSETS[i])try(InputStream s=getAssets().open(name)){if(s.read()==-1)throw new IllegalArgumentException("empty");}
                ready.append("✓ ").append(LABELS[i]).append("\n");
            }catch(Exception e){ready.append("✗ ").append(LABELS[i]).append(": ").append(e.getMessage()).append("\n");}
        }
        info(ready.toString());
    }
    String installedVersion(String p){
        try{PackageInfo info=getPackageManager().getPackageInfo(p,0);
            return "\nУстановлено: "+info.versionName;
        }catch(Exception e){return "\nНе установлено";}
    }
    void allowInstall(){
        if(Build.VERSION.SDK_INT>=26){
            try{startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())));}
            catch(Exception e){info("Не удалось открыть разрешения: "+e.getMessage());}
        }
    }
    void uninstall(int i){
        if(working)return;
        try{
            Intent intent=new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+PACKAGES[i]));
            startActivity(intent);
            info("Подтвердите удаление: "+LABELS[i]+". Потом установите её новой кнопкой.");
        }catch(Exception e){info("Ошибка удаления: "+e.getMessage());}
    }
    void install(int i){
        if(working)return;
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()){
            info("Сначала разрешите установку APK из этого источника.");allowInstall();return;
        }
        working=true;enabled(false);info("Подготовка "+LABELS[i]+"...");
        new Thread(()->{
            PackageInstaller.Session session=null;
            try {
                PackageInstaller installer=getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(PACKAGES[i]);
                int sid=installer.createSession(params);
                session=installer.openSession(sid);
                int index=0;
                for(String asset:ASSETS[i]){
                    String entry=asset.substring(asset.lastIndexOf('/')+1);
                    try(InputStream input=getAssets().open(asset);
                        OutputStream output=session.openWrite(entry,0,-1)){
                        byte[] bytes=new byte[65536];int n;long total=0;
                        while((n=input.read(bytes))!=-1){output.write(bytes,0,n);total+=n;}
                        session.fsync(output);
                        if(total<512)throw new IllegalArgumentException("Invalid APK asset "+asset);
                    }
                    index++;
                    final int done=index;
                    info("Подготовлено "+done+"/"+ASSETS[i].length+" APK для "+LABELS[i]);
                }
                Intent receiver=new Intent(this,InstallResultReceiver.class)
                    .setAction("ru.dexp.gappssetup.INSTALL_RESULT")
                    .putExtra("component",LABELS[i]);
                int flags=PendingIntent.FLAG_UPDATE_CURRENT;
                if(Build.VERSION.SDK_INT>=31) flags|=PendingIntent.FLAG_MUTABLE;
                PendingIntent result=PendingIntent.getBroadcast(this,sid,receiver,flags);
                session.commit(result.getIntentSender());
                info("Ожидаем подтверждения установки "+LABELS[i]);
            }catch(Exception e){
                info("Ошибка установки "+LABELS[i]+": "+e.getClass().getSimpleName()+" "+e.getMessage());
                if(session!=null){try{session.abandon();}catch(Exception ignored){}}
            }finally{
                if(session!=null){try{session.close();}catch(Exception ignored){}}
                working=false;enabled(true);
            }
        }).start();
    }
    static void reportResult(String msg){if(active!=null)active.info(msg);}
    @Override protected void onDestroy(){if(active==this)active=null;super.onDestroy();}
}
