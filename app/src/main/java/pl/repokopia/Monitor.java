package pl.repokopia;

import android.app.*;
import android.content.*;
import android.os.*;
import java.util.concurrent.*;

public class Monitor extends Service {
    static volatile boolean active;
    private ScheduledFuture<?> task;
    public int onStartCommand(Intent i,int flags,int id){
        if(!Sync.enabled(this)){stopSelf();StatusWidget.changed(this);return START_NOT_STICKY;}
        try{startForeground(Sync.NOTICE,Sync.notification(this,"Monitorowanie repozytoriów w tle"));}
        catch(RuntimeException e){
            RepoApp.app(this).event("Uruchamianie powiadomienia usługi: "+Reports.trace(e));
            Mirror.prefs(this).edit().putBoolean("enabled",false).apply();
            stopSelf();StatusWidget.changed(this);return START_NOT_STICKY;
        }
        getSystemService(NotificationManager.class).cancel(Sync.NOTICE+1);
        active=true; StatusWidget.changed(this);
        try{Sync.schedule(this);}
        catch(RuntimeException e){RepoApp.app(this).event("Planowanie zadania okresowego: "+Reports.trace(e));}
        RepoApp.app(this).event("Uruchomiono monitorowanie w tle.");
        boolean changed=i!=null && "interval".equals(i.getAction());
        if(task!=null)task.cancel(!changed);
        int minutes=Sync.minutes(this);
        task=Sync.worker.scheduleWithFixedDelay(()->Sync.cycle(this),changed?minutes:0,minutes,TimeUnit.MINUTES);
        return START_STICKY;
    }
    @Override public void onTimeout(int startId,int fgsType){
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        active=false;
        if(Sync.enabled(this))getSystemService(NotificationManager.class).notify(Sync.NOTICE+1,Sync.notification(this,"Aktywny tryb okresowy Androida"));
    }
    public void onDestroy(){active=false; StatusWidget.changed(this); if(task!=null) task.cancel(true); super.onDestroy();}
    public IBinder onBind(Intent i){return null;}
}
