package pl.repokopia;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class AppUpdates extends JobService {
    private static final int JOB=26, NOTICE=27;
    static final String REPO="Novicek000/RepoKopia";
    static final String RELEASES="https://github.com/"+REPO+"/releases/";
    static final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> running;

    static void schedule(Context c){
        JobScheduler scheduler=c.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(JOB)==null){
            scheduler.schedule(new JobInfo.Builder(JOB,new ComponentName(c,AppUpdates.class))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true)
                .setPeriodic(TimeUnit.DAYS.toMillis(1)).build());
        }
    }
    static int compare(String left,String right){
        String[] a=left.replaceFirst("^v","").split("\\."), b=right.replaceFirst("^v","").split("\\.");
        for(int i=0;i<Math.max(a.length,b.length);i++){
            int result=Integer.compare(i<a.length?Integer.parseInt(a[i]):0,i<b.length?Integer.parseInt(b[i]):0);
            if(result!=0)return result;
        }
        return 0;
    }
    static void check(Context c) throws Exception {
        HttpURLConnection h=(HttpURLConnection)new URL("https://api.github.com/repos/"+REPO+"/releases/latest").openConnection();
        h.setConnectTimeout(15000);h.setReadTimeout(20000);
        h.setRequestProperty("Accept","application/vnd.github+json");
        String tag="",asset="";
        try{
            int code=h.getResponseCode();
            if(code!=404){
                if(code!=200)throw new IOException("GitHub zwrócił HTTP "+code);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream in=h.getInputStream()){Reports.copy(in,bytes);}
                JSONObject release=new JSONObject(bytes.toString("UTF-8"));
                tag=release.getString("tag_name");
                if(!tag.matches("v?[0-9]+(\\.[0-9]+){0,3}"))throw new IOException("Nieznany format wersji: "+tag);
                String installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;
                if(compare(tag,installed)>0){
                    JSONArray assets=release.getJSONArray("assets");
                    for(int i=0;i<assets.length();i++){
                        String url=assets.getJSONObject(i).optString("browser_download_url");
                        if(url.startsWith(RELEASES+"download/"+tag+"/") && url.endsWith(".apk")){asset=url;break;}
                    }
                    if(asset.isEmpty())throw new IOException("W wydaniu "+tag+" brakuje APK.");
                }
            }
        }finally{h.disconnect();}
        String status=!asset.isEmpty()?"Dostępna aktualizacja "+tag:tag.isEmpty()?"Brak opublikowanych wydań.":"Masz aktualną wersję.";
        Mirror.prefs(c).edit().putString("appAsset",asset).putString("appUpdateStatus",status)
            .putLong("appUpdateChecked",System.currentTimeMillis()).apply();
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(asset.isEmpty()){nm.cancel(NOTICE);return;}
        nm.createNotificationChannel(new NotificationChannel("updates","Aktualizacje aplikacji",NotificationManager.IMPORTANCE_DEFAULT));
        Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(asset));
        PendingIntent tap=PendingIntent.getActivity(c,NOTICE,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(NOTICE,new Notification.Builder(c,"updates").setSmallIcon(R.drawable.ic_status_folder)
            .setContentTitle("RepoKopia • "+status).setContentText("Dotknij, aby pobrać APK z GitHuba.")
            .setContentIntent(tap).setOnlyAlertOnce(true).setAutoCancel(true).build());
    }
    public boolean onStartJob(JobParameters params){
        long previous=Mirror.prefs(this).getLong("appUpdateChecked",0);
        if(System.currentTimeMillis()-previous<TimeUnit.HOURS.toMillis(23))return false;
        running=worker.submit(()->{
            boolean retry=false;
            try{check(this);}catch(Exception e){retry=true;RepoApp.app(this).event("Aktualizacja aplikacji: "+e.getMessage());}
            final boolean again=retry;
            if(!Thread.currentThread().isInterrupted())Sync.main.post(()->jobFinished(params,again));
        });
        return true;
    }
    public boolean onStopJob(JobParameters p){if(running!=null)running.cancel(true);return true;}
}
