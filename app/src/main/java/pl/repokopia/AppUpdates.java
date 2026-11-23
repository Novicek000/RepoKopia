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
    private static final String VERSION_PATTERN="v?[0-9]+(\\.[0-9]+){0,3}";

    static HttpURLConnection connection(String url) throws IOException {
        HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent","RepoKopia Android (https://github.com/"+REPO+")");
        return h;
    }

    static final class Release {
        final String tag,asset;
        Release(String tag,String asset){this.tag=tag;this.asset=asset;}
    }

    private static boolean newer(String tag,String installed) throws IOException {
        if(!tag.matches(VERSION_PATTERN))throw new IOException("Nieznany format wersji: "+tag);
        return compare(tag,installed)>0;
    }

    private static Release publicRelease(String installed) throws IOException {
        HttpURLConnection page=connection(RELEASES+"latest");
        String tag;
        try{
            page.setInstanceFollowRedirects(false);
            page.setRequestMethod("HEAD");
            int code=page.getResponseCode();
            if(code==404)return new Release("","");
            String location=page.getHeaderField("Location");
            if(code<300 || code>=400 || location==null)
                throw new IOException("Nie udało się odczytać strony najnowszego wydania (HTTP "+code+").");
            String target=new URL(page.getURL(),location).toString();
            if(target.equals(RELEASES.substring(0,RELEASES.length()-1)))return new Release("","");
            if(!target.startsWith(RELEASES+"tag/"))throw new IOException("Nieznany adres wydania GitHub.");
            tag=target.substring((RELEASES+"tag/").length());
        }finally{page.disconnect();}
        if(!newer(tag,installed))return new Release(tag,"");
        String url=RELEASES+"download/"+tag+"/RepoKopia-"+tag.replaceFirst("^v","")+".apk";
        HttpURLConnection apk=connection(url);
        try{
            apk.setRequestMethod("HEAD");
            int code=apk.getResponseCode();
            if(code!=200)throw new IOException("Nie udało się potwierdzić pliku APK dla "+tag+" (HTTP "+code+").");
        }finally{apk.disconnect();}
        return new Release(tag,url);
    }

    static Release latest(String installed) throws Exception {
        HttpURLConnection h=connection("https://api.github.com/repos/"+REPO+"/releases/latest");
        h.setRequestProperty("Accept","application/vnd.github+json");
        try{
            int code=h.getResponseCode();
            if(code==403 || code==429)return publicRelease(installed);
            if(code==404)return new Release("","");
            if(code!=200)throw new IOException("GitHub zwrócił HTTP "+code);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream in=h.getInputStream()){Reports.copy(in,bytes);}
            JSONObject release=new JSONObject(bytes.toString("UTF-8"));
            String tag=release.getString("tag_name");
            if(!newer(tag,installed))return new Release(tag,"");
            JSONArray assets=release.getJSONArray("assets");
            for(int i=0;i<assets.length();i++){
                String url=assets.getJSONObject(i).optString("browser_download_url");
                if(url.startsWith(RELEASES+"download/"+tag+"/") && url.endsWith(".apk"))return new Release(tag,url);
            }
            throw new IOException("W wydaniu "+tag+" brakuje APK.");
        }finally{h.disconnect();}
    }

    static void check(Context c) throws Exception {
        String installed=c.getPackageManager().getPackageInfo(c.getPackageName(),0).versionName;
        Release release=latest(installed);
        String tag=release.tag,asset=release.asset;
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
