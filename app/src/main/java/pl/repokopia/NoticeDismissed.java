package pl.repokopia;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NoticeDismissed extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(!Sync.enabled(context))return;
        boolean foreground=Monitor.active;
        String idle=foreground?"Monitorowanie repozytoriów w tle":"Aktywny tryb okresowy Androida";
        Sync.Progress progress=Sync.progress;
        String line=progress==null?idle:progress.repo+" • "+progress.label(context);
        context.getSystemService(NotificationManager.class).notify(foreground?Sync.NOTICE:Sync.NOTICE+1,
            Sync.notification(context,line,progress));
    }
}
