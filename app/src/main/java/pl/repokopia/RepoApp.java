package pl.repokopia;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.util.Log;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class RepoApp extends Application {
    final ExecutorService io=Executors.newSingleThreadExecutor();
    Reports reports;
    static RepoApp app(Context c){return (RepoApp)c.getApplicationContext();}
    public void onCreate(){
        super.onCreate();
        reports=new Reports(new File(getFilesDir(),"diagnostics"));
        Thread.UncaughtExceptionHandler system=Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{
            try{
                File crash=reports.crash(header()+"Wątek: "+thread.getName()+"\n"+Reports.trace(error));
                Thread writer=new Thread(()->{try{deliver(crash);}catch(Exception ignored){}}, "Eksport raportu");
                writer.start(); writer.join(900);
            }catch(Throwable ignored){}
            finally{if(system!=null)system.uncaughtException(thread,error);}
        });
        io.execute(()->{
            if(Build.VERSION.SDK_INT>=30)try{systemCrashes();}catch(Exception e){Log.w("RepoKopia","Historia procesu",e);}
            event("Uruchomiono proces aplikacji.");retry();
        });
    }
    String header(){
        return "RepoKopia 1.12 • "+Reports.timestamp()+"\nAndroid "+Build.VERSION.RELEASE+" (API "+Build.VERSION.SDK_INT+") • "+Build.MANUFACTURER+" "+Build.MODEL+"\n";
    }
    void event(String s){
        try{reports.event(s);}catch(IOException e){Log.w("RepoKopia","Zapis dziennika",e);}
    }
    void systemCrashes() throws Exception {
        SharedPreferences p=Mirror.prefs(this);
        long since=p.getLong("lastSystemExit",0),latest=since;
        long updated=getPackageManager().getPackageInfo(getPackageName(),0).lastUpdateTime;
        for(ApplicationExitInfo exit:getSystemService(ActivityManager.class).getHistoricalProcessExitReasons(getPackageName(),0,32)){
            if(exit.getTimestamp()<=since)continue;
            int reason=exit.getReason();
            boolean previousVersionCrash=reason==ApplicationExitInfo.REASON_CRASH && exit.getTimestamp()<updated;
            if(previousVersionCrash || reason==ApplicationExitInfo.REASON_CRASH_NATIVE || reason==ApplicationExitInfo.REASON_ANR){
                reports.crash(header()+"Raport systemowy: "+(reason==ApplicationExitInfo.REASON_ANR?"ANR":previousVersionCrash?"awaria poprzedniej wersji":"awaria natywna")
                    +"\nCzas zdarzenia: "+new java.util.Date(exit.getTimestamp())+"\n"+exit.getDescription()+"\n");
            }
            latest=Math.max(latest,exit.getTimestamp());
        }
        p.edit().putLong("lastSystemExit",latest).apply();
    }
    static boolean logFolder(Uri tree){
        if(!"com.android.externalstorage.documents".equals(tree.getAuthority()))return false;
        String id=DocumentsContract.getTreeDocumentId(tree);
        return id.equals("primary:Documents") || id.equals("primary:Documents/GitClone");
    }
    String grant(){
        SharedPreferences p=Mirror.prefs(this);
        String uri=p.getString("logTree",p.getString("tree",""));
        if(uri.isEmpty() || !logFolder(Uri.parse(uri)))return "";
        for(UriPermission permission:getContentResolver().getPersistedUriPermissions())
            if(permission.isWritePermission() && permission.isReadPermission() && uri.equals(permission.getUri().toString()))return uri;
        return "";
    }
    private Uri target(String name) throws IOException {
        String grant=grant(); if(grant.isEmpty())throw new IOException("Zatwierdź dostęp do Documents lub Documents/GitClone.");
        Uri tree=Uri.parse(grant);Documents d=new Documents(getContentResolver(),tree);
        Uri root=Documents.base(d,DocumentsContract.getTreeDocumentId(tree).equals("primary:Documents"));
        Documents.Node prior=d.children(root).get(name);
        if(prior!=null && prior.dir)throw new IOException("Nazwa raportu jest zajęta przez folder.");
        return prior==null?d.create(root,name,false):prior.uri;
    }
    synchronized void deliver(File f) throws IOException {
        if(!f.exists())return;
        try(InputStream in=new FileInputStream(f);OutputStream out=getContentResolver().openOutputStream(target(f.getName()),"wt")){
            if(out==null)throw new IOException("Folder GitClone jest niedostępny.");
            Reports.copy(in,out);
        }
        reports.delivered(f);
    }
    void retry(){
        try{for(File f:reports.pending())deliver(f);}catch(Exception e){Log.w("RepoKopia","Raport oczekuje na zapis w GitClone",e);}
    }
    String export() throws IOException {
        for(File f:reports.pending())deliver(f);
        String name="logi-"+Reports.timestamp()+"-"+java.util.UUID.randomUUID()+".txt";
        try(OutputStream out=getContentResolver().openOutputStream(target(name),"wt")){
            if(out==null)throw new IOException("Nie można otworzyć pliku raportu.");
            out.write((header()+"\nDziennik aplikacji\n").getBytes(StandardCharsets.UTF_8));
            reports.journal(out);
        }
        return name;
    }
}
