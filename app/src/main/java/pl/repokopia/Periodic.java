package pl.repokopia;

import android.app.job.*;
import java.util.concurrent.*;

public class Periodic extends JobService {
    private Future<?> task;
    public boolean onStartJob(JobParameters params){
        if(Monitor.active || !Sync.enabled(this)) return false;
        getSystemService(android.app.NotificationManager.class).notify(Sync.NOTICE+1,Sync.notification(this,"Aktywny tryb okresowy Androida"));
        task=Sync.worker.submit(()->{
            Sync.cycle(this);
            if(!Thread.currentThread().isInterrupted()) new android.os.Handler(getMainLooper()).post(()->jobFinished(params,false));
        });
        return true;
    }
    public boolean onStopJob(JobParameters params){if(task!=null) task.cancel(true); return true;}
}
