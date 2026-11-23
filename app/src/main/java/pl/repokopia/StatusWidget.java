package pl.repokopia;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class StatusWidget extends AppWidgetProvider {
    private static final AtomicBoolean queued=new AtomicBoolean();

    static void changed(Context context){
        if(!queued.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        Sync.main.postDelayed(()->{
            queued.set(false);
            for(Runnable listener:Sync.statusListeners)listener.run();
            AppWidgetManager manager=AppWidgetManager.getInstance(app);
            int[] ids=manager.getAppWidgetIds(new ComponentName(app,StatusWidget.class));
            update(app,manager,ids);
        },250);
    }

    @Override public void onUpdate(Context c,AppWidgetManager manager,int[] ids){
        update(c,manager,ids);
    }

    @Override public void onAppWidgetOptionsChanged(Context c,AppWidgetManager manager,int id,Bundle options){
        update(c,manager,new int[]{id});
    }

    private static void update(Context c,AppWidgetManager manager,int[] widgets){
        if(widgets.length==0)return;
        RemoteViews views=new RemoteViews(c.getPackageName(),R.layout.status_widget);
        RemoteViews compact=new RemoteViews(c.getPackageName(),R.layout.status_widget_compact);
        List<String> ids=Sync.ids(c);
        boolean enabled=Sync.enabled(c);
        String working=Sync.working;
        Sync.Progress transfer=Sync.progress;
        String title, detail="", note="";
        int checked=0, errors=0;
        long lastCheck=0;
        boolean downloading=false;
        if(ids.isEmpty()){
            title="Brak repozytoriów";
            detail="Dotknij, aby dodać pierwszą kopię.";
        }else if(working!=null && ids.contains(working)){
            SharedPreferences p=c.getSharedPreferences("repo-"+working,0);
            detail=p.getString("repo","");
            downloading=transfer!=null && working.equals(transfer.id);
            if(downloading){
                title="Pobieranie";
                note=transfer.label(c);
                int percent=Download.percent(transfer.received,transfer.total);
                compact.setProgressBar(R.id.widget_bar,100,Math.max(0,percent),percent<0);
                views.setProgressBar(R.id.widget_bar,100,Math.max(0,percent),percent<0);
            }else title=Sync.saving?"Zapisywanie plików":"Sprawdzanie zmian";
        }else{
            for(String id:ids){
                SharedPreferences p=c.getSharedPreferences("repo-"+id,0);
                String status=p.getString("status","");
                if(status.equals("Aktualne"))checked++;
                else if(!status.isEmpty() && !status.equals("Zapisywanie plików…") && !status.startsWith("Pobieranie wersji ")){
                    if(errors++==0)detail=p.getString("repo","")+" • "+status;
                }
                lastCheck=Math.max(lastCheck,p.getLong("checked",0));
            }
            if(!enabled)title="Wstrzymano";
            else if(errors>0)title="Błędy synchronizacji: "+errors;
            else title=Monitor.active?"Monitorowanie w tle":"Tryb okresowy";
            if(errors==0)detail="Aktualne kopie: "+checked+" z "+ids.size();
            if(lastCheck>0)note="Ostatni sukces: "+DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(lastCheck));
        }
        views.setTextViewText(R.id.widget_status,title);
        views.setTextViewText(R.id.widget_detail,detail);
        views.setTextViewText(R.id.widget_note,note);
        views.setViewVisibility(R.id.widget_note,note.isEmpty()?View.GONE:View.VISIBLE);
        views.setViewVisibility(R.id.widget_bar,downloading?View.VISIBLE:View.GONE);
        Intent open=new Intent(c,Main.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent launch=PendingIntent.getActivity(c,1,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        views.setOnClickPendingIntent(R.id.widget_root,launch);
        compact.setOnClickPendingIntent(R.id.widget_root,launch);
        String shortTitle=title;
        if(downloading){
            int percent=Download.percent(transfer.received,transfer.total);
            if(percent>=0)shortTitle+=" "+percent+"%";
        }else if(errors>0 && enabled)shortTitle="Błędy: "+errors;
        else if(title.equals("Monitorowanie w tle"))shortTitle="W tle • "+checked+"/"+ids.size();
        compact.setTextViewText(R.id.widget_status,shortTitle);
        compact.setContentDescription(R.id.widget_root,title+". "+detail+". "+note);
        compact.setViewVisibility(R.id.widget_bar,downloading?View.VISIBLE:View.GONE);

        if(Build.VERSION.SDK_INT>=31){
            Map<SizeF,RemoteViews> layouts=new HashMap<>();
            layouts.put(new SizeF(100,40),compact);
            layouts.put(new SizeF(FULL_WIDTH,FULL_HEIGHT),views);
            manager.updateAppWidget(widgets,new RemoteViews(layouts));
        }else{
            for(int id:widgets){
                Bundle bounds=manager.getAppWidgetOptions(id);
                int minW=bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH,FULL_WIDTH);
                int maxW=bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH,minW);
                int minH=bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT,FULL_HEIGHT);
                int maxH=bounds.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT,minH);
                RemoteViews portrait=fitsFull(minW,maxH)?views:compact;
                RemoteViews landscape=fitsFull(maxW,minH)?views:compact;
                manager.updateAppWidget(id,new RemoteViews(landscape,portrait));
            }
        }
    }

    private static final int FULL_WIDTH=155,FULL_HEIGHT=110;

    private static boolean fitsFull(int width,int height){
        return width>=FULL_WIDTH && height>=FULL_HEIGHT;
    }
}
