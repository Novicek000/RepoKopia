package pl.repokopia;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import java.util.*;
import java.util.concurrent.*;

final class Sync {
    static final ScheduledExecutorService worker=Executors.newSingleThreadScheduledExecutor();
    static final int NOTICE=24, JOB=25;
    static final Handler main=new Handler(Looper.getMainLooper());
    // Registered and notified on the main thread while the list is visible.
    static final Set<Runnable> statusListeners=new HashSet<>();
    static final ConcurrentMap<String,FutureTask<Void>> manual=new ConcurrentHashMap<>();
    static volatile Progress progress;
    static volatile String working;
    static volatile boolean saving;
    static final class Progress {
        final String id, repo;
        final long received,total;
        Progress(String id,String repo,long received,long total){
            this.id=id;this.repo=repo;this.received=received;this.total=total;
        }
        String label(Context c){
            String size=Formatter.formatShortFileSize(c,received);
            int percent=Download.percent(received,total);
            return percent<0?"Pobrano "+size:"Pobrano "+percent+"% • "+size+" z "+Formatter.formatShortFileSize(c,total);
        }
    }
    static void progress(Context c,Progress next){
        if(next==null && progress==null)return;
        progress=next;
        StatusWidget.changed(c);
        refreshNotice(c,next);
    }
    private static void refreshNotice(Context c,Progress next){
        main.post(()->{
            boolean foreground=c instanceof Monitor || (c==c.getApplicationContext() && Monitor.active);
            if(progress!=next || !enabled(c) || foreground!=Monitor.active)return;
            String idle=foreground?"Monitorowanie repozytoriów w tle":"Aktywny tryb okresowy Androida";
            String line=next==null?idle:next.repo+" • "+next.label(c);
            c.getSystemService(NotificationManager.class).notify(foreground?NOTICE:NOTICE+1,notification(c,line,next));
        });
    }
    static List<String> ids(Context c) {
        ArrayList<String> result=new ArrayList<>(Mirror.prefs(c).getStringSet("repos",Collections.emptySet()));
        Collections.sort(result); return result;
    }
    static boolean enabled(Context c){return Mirror.prefs(c).getBoolean("enabled",false);}
    static boolean cycle(Context c) {
        for(String id:ids(c)){
            if(!enabled(c) || Thread.currentThread().isInterrupted()) return false;
            if(!manual.containsKey(id))syncOne(c,id);
        }
        return true;
    }
    static void refresh(Context context,String id){
        Context app=context.getApplicationContext();
        if(id.equals(working))return;
        FutureTask<Void> task=new FutureTask<Void>(()->{
            if(ids(app).contains(id))syncOne(app,id);
            return null;
        }){
            @Override protected void done(){
                manual.remove(id,this);
                StatusWidget.changed(app);
            }
        };
        if(manual.putIfAbsent(id,task)==null){
            StatusWidget.changed(app);
            worker.execute(task);
        }
    }
    static void cancelRefresh(String id){
        FutureTask<Void> task=manual.get(id);
        if(task!=null)task.cancel(true);
    }
    private static void syncOne(Context c,String id){
        Mirror m=new Mirror(c,id);
        if(System.currentTimeMillis()<m.p.getLong("retry",0))return;
        working=id;
        StatusWidget.changed(c);
        boolean failed=false;
        try {
            m.run(); Mirror.check();
            m.p.edit().putString("status","Aktualne").putLong("checked",System.currentTimeMillis()).apply();
        } catch(Exception e){
            if(Thread.currentThread().isInterrupted())return;
            failed=true;
            RepoApp.app(c).event("Synchronizacja "+m.repo+": "+Reports.trace(e));
            String message=e.getMessage();
            m.p.edit().putString("status",message==null?"Nie udało się pobrać kopii.":message).apply();
        } finally {
            working=null; saving=false; StatusWidget.changed(c);
        }
        if(m.checkStarted){
            Mirror.prefs(c).edit().putLong("lastAttempt",System.currentTimeMillis())
                .putBoolean("lastAttemptFailed",failed).apply();
            refreshNotice(c,null);
        }
    }
    static int minutes(Context c){return Math.max(1,Math.min(60,Mirror.prefs(c).getInt("minutes",5)));}
    static void schedule(Context c) {
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        long period=TimeUnit.MINUTES.toMillis(Math.max(15,minutes(c)));
        JobInfo pending=scheduler.getPendingJob(JOB);
        if(pending==null || pending.getIntervalMillis()!=period) scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(c,Periodic.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(period).setPersisted(true).build());
    }
    static Notification notification(Context c,String line) {
        return notification(c,line,null);
    }
    static Notification notification(Context c,String line,Progress transfer) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sync","Synchronizacja",NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,Main.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent dismissed=PendingIntent.getBroadcast(c,0,new Intent(c,NoticeDismissed.class),PendingIntent.FLAG_IMMUTABLE);
        int icon=transfer==null?R.drawable.ic_status_folder:android.R.drawable.stat_notify_sync;
        Notification.Builder builder=new Notification.Builder(c,"sync").setSmallIcon(icon)
            .setContentTitle("RepoKopia • synchronizacja").setContentText(line).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setDeleteIntent(dismissed);
        SharedPreferences prefs=Mirror.prefs(c);
        long checked=prefs.getLong("lastAttempt",0);
        if(checked==0){
            // Versions before 1.8 kept only successful checks, separately for each repository.
            for(String id:ids(c)){
                checked=Math.max(checked,c.getSharedPreferences("repo-"+id,0).getLong("checked",0));
            }
        }
        if(checked>0){
            String time=android.text.format.DateFormat.getTimeFormat(c).format(new Date(checked));
            String prefix=prefs.getBoolean("lastAttemptFailed",false)?"Nieudana próba o ":"Sprawdzono o ";
            builder.setSubText(prefix+time).setWhen(checked).setShowWhen(false);
        }else builder.setSubText("Przed pierwszym sprawdzeniem").setShowWhen(false);
        if(transfer!=null){
            int percent=Download.percent(transfer.received,transfer.total);
            builder.setProgress(100,Math.max(0,percent),percent<0);
            if(android.os.Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        Notification notice=builder.build();
        notice.flags|=Notification.FLAG_NO_CLEAR;
        return notice;
    }
}
