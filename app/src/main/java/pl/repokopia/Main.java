package pl.repokopia;

import android.app.*;
import android.app.job.JobScheduler;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;

import android.view.*;
import android.widget.*;
import java.util.*;

public class Main extends Activity {
    static final int INK=0xff172f2d, TEAL=0xff11675d, MUTED=0xff49645e;
    LinearLayout page,list,home,settings;
    ScrollView scroll;
    Button all,checkUpdate,downloadUpdate;
    TextView updateStatus;
    TextView folder,summary,feedback;
    EditText input;
    Button toggle, pausePeriodic, logs;
    TextView logStatus;
    boolean pendingAdd;
    String last="";
    ProgressBar downloadBar;
    TextView downloadLabel;
    final Runnable refresh=this::render;
    int dp(int n){return Math.round(getResources().getDisplayMetrics().density*n);}
    GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    TextView label(LinearLayout box,String text,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        box.addView(t);return t;
    }
    void gap(LinearLayout box,int n){box.addView(new View(this),1,dp(n));}
    LinearLayout panel(LinearLayout parent){
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(dp(16),dp(14),dp(16),dp(14));
        box.setBackground(bg(Color.WHITE,14));parent.addView(box); return box;
    }
    Button button(LinearLayout box,String title,boolean primary,View.OnClickListener click){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setTextColor(primary?Color.WHITE:TEAL);
        b.setBackground(bg(primary?TEAL:0xffe4f1eb,10));b.setOnClickListener(click);box.addView(b);return b;
    }
    SharedPreferences global(){return Mirror.prefs(this);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved); getWindow().setStatusBarColor(INK);getWindow().setNavigationBarColor(INK);
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xfff3f6f2);
        page=new LinearLayout(this);page.setOrientation(1);page.setPadding(dp(20),dp(24),dp(20),dp(30));scroll.addView(page);setContentView(scroll);
        home=page;
        label(page,"RepoKopia",34,INK,true);label(page,"Kopie z GitHuba na Twoim telefonie",16,MUTED,false);gap(page,20);
        button(page,"Ustawienia",false,v->showSettings(true));gap(page,12);
        LinearLayout form=panel(page);label(form,"Dodaj repozytorium",20,INK,true);gap(form,8);
        input=new EditText(this);input.setSingleLine();input.setTextColor(INK);input.setHintTextColor(MUTED);
        input.setInputType(17);input.setHint("https://github.com/właściciel/repo");form.addView(input);
        folder=label(form,"",14,MUTED,false);gap(form,8);
        label(form,"Zmiany i usunięcia na GitHubie zastępują lokalną zawartość folderu repozytorium.",13,MUTED,false);gap(form,8);
        button(form,"Wybierz folder",false,v->{pendingAdd=false;pick();});gap(form,8);
        button(form,"Dodaj do kopii",true,v->add());
        feedback=label(form,"",14,TEAL,false);feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        gap(page,22);label(page,"Moje repozytoria",22,INK,true);
        summary=label(page,"",14,MUTED,false);gap(page,10);
        all=button(page,"Odśwież wszystko",true,v->{
            for(String id:Sync.ids(this))Sync.refresh(this,id);
            render();
        });gap(page,10);
        list=new LinearLayout(this);list.setOrientation(1);page.addView(list);
        toggle=button(page,"",false,v->{if(Monitor.active)pause();else start();});
        pausePeriodic=button(page,"Wstrzymaj synchronizację",false,v->pause());
        gap(page,10);
        settings=new LinearLayout(this);settings.setOrientation(1);settings.setPadding(dp(20),dp(24),dp(20),dp(30));
        label(settings,"Ustawienia",30,INK,true);gap(settings,12);
        button(settings,"Wróć do repozytoriów",false,v->showSettings(false));gap(settings,18);
        TextView interval=label(settings,"Sprawdzaj repozytoria co "+Sync.minutes(this)+" min",18,INK,true);
        SeekBar slider=new SeekBar(this);slider.setMax(59);slider.setProgress(Sync.minutes(this)-1);
        slider.setContentDescription("Częstotliwość synchronizacji w minutach");settings.addView(slider);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar bar){}
            public void onProgressChanged(SeekBar bar,int value,boolean user){
                interval.setText("Sprawdzaj repozytoria co "+(value+1)+" min");
                if(user){global().edit().putInt("minutes",value+1).apply();reschedule.removeCallbacks(applyInterval);reschedule.postDelayed(applyInterval,350);}
            }
            public void onStopTrackingTouch(SeekBar bar){}
        });
        label(settings,"W trybie okresowym Android sprawdza najczęściej co 15 min.",14,MUTED,false);
        gap(settings,20);label(settings,"Aktualizacje aplikacji",20,INK,true);
        label(settings,"RepoKopia sprawdza wydania na GitHubie raz dziennie.",14,MUTED,false);
        updateStatus=label(settings,"",15,TEAL,false);
        checkUpdate=button(settings,"Sprawdź aktualizacje teraz",false,v->checkUpdates());gap(settings,6);
        downloadUpdate=button(settings,"Pobierz aktualizację APK",true,v->{
            try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(global().getString("appAsset",""))));}
            catch(ActivityNotFoundException e){updateStatus.setText("Brak aplikacji do otwierania linków.");}
        });
        gap(settings,20);
        logs=button(settings,"Wyciągnij logi",false,v->exportLogs());
        logStatus=label(settings,"",14,MUTED,false);
        logStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if(Build.VERSION.SDK_INT>=35) scroll.setOnApplyWindowInsetsListener((v,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom); return insets;
        });
        if(saved!=null){input.setText(saved.getString("url"));pendingAdd=saved.getBoolean("pending");}
        AppUpdates.schedule(this);
        if(saved!=null && saved.getBoolean("settings"))showSettings(true);
        render();
    }
    final Handler reschedule=new Handler(Looper.getMainLooper());
    final Runnable applyInterval=()->{
        if(Sync.enabled(this)){
            Sync.schedule(this);
            if(Monitor.active)startService(new Intent(this,Monitor.class).setAction("interval"));
        }
        render();
    };
    void showSettings(boolean open){
        scroll.removeAllViews();scroll.addView(open?settings:home);scroll.scrollTo(0,0);
        if(open)updateInfo();else render();
    }
    void updateInfo(){
        String status=global().getString("appUpdateStatus","Jeszcze nie sprawdzono.");
        long checked=global().getLong("appUpdateChecked",0);
        if(checked>0)status+=" • "+java.text.DateFormat.getDateTimeInstance(3,3).format(new Date(checked));
        updateStatus.setText(status);
        downloadUpdate.setVisibility(global().getString("appAsset","").isEmpty()?View.GONE:View.VISIBLE);
    }
    void checkUpdates(){
        checkUpdate.setEnabled(false);updateStatus.setText("Sprawdzanie wydań…");
        AppUpdates.worker.execute(()->{
            try{AppUpdates.check(getApplicationContext());runOnUiThread(this::updateInfo);}
            catch(Exception e){runOnUiThread(()->updateStatus.setText("Błąd sprawdzania: "+e.getMessage()));}
            finally{runOnUiThread(()->checkUpdate.setEnabled(true));}
        });
    }
    @Override public void onBackPressed(){if(settings.getParent()!=null)showSettings(false);else super.onBackPressed();}
    protected void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putBoolean("settings",settings.getParent()!=null);b.putString("url",input.getText().toString());b.putBoolean("pending",pendingAdd);}
    protected void onResume(){super.onResume();Sync.statusListeners.add(refresh);render();}
    protected void onPause(){Sync.statusListeners.remove(refresh);super.onPause();}
    void exportLogs(){
        RepoApp app=RepoApp.app(this);
        if(app.grant().isEmpty()){
            logStatus.setText("Wybierz Documents lub Documents/GitClone dla raportów.");
            pickFolder(5);return;
        }
        logs.setEnabled(false);logStatus.setText("Zapisywanie raportu…");
        app.io.execute(()->{
            try{
                String file=app.export();
                runOnUiThread(()->{logs.setEnabled(true);logStatus.setText("Zapisano w Documents/GitClone/"+file);});
            }catch(Exception e){
                app.event("Błąd eksportu: "+Reports.trace(e));
                runOnUiThread(()->{logs.setEnabled(true);logStatus.setText("Nie udało się zapisać raportu: "+e.getMessage());});
            }
        });
    }
    void pick(){pickFolder(3);}
    void pickFolder(int request){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri("com.android.externalstorage.documents","primary:Documents"));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try{startActivityForResult(i,request);}
        catch(RuntimeException e){
            pendingAdd=false;
            RepoApp.app(this).event("Wybór folderu: "+Reports.trace(e));
            (request==5?logStatus:feedback).setText("Nie można otworzyć systemowego wyboru folderu. Sprawdź, czy aplikacja Pliki jest włączona.");
        }
    }
    protected void onActivityResult(int r,int code,Intent data){
        super.onActivityResult(r,code,data);
        if(r!=3 && r!=5)return;
        if(code!=RESULT_OK || data==null || data.getData()==null){pendingAdd=false;if(r==5)logStatus.setText("Anulowano zapis logów.");return;}
        try {
            Uri tree=data.getData();
            if(r==5 && !RepoApp.logFolder(tree)){logStatus.setText("Do zapisu logów wybierz Documents lub Documents/GitClone w pamięci telefonu.");return;}
            getContentResolver().takePersistableUriPermission(tree,data.getFlags() & 3);
            String id=DocumentsContract.getTreeDocumentId(tree);
            if(RepoApp.logFolder(tree)){
                global().edit().putString("logTree",tree.toString()).apply();
                RepoApp app=RepoApp.app(this);app.io.execute(app::retry);
            }
            if(r==5){exportLogs();return;}
            global().edit().putString("tree",tree.toString()).putBoolean("documents","primary:Documents".equals(id)).apply();
            if(pendingAdd){pendingAdd=false;add();}else render();
        }catch(Exception e){RepoApp.app(this).event("Dostęp do folderu: "+Reports.trace(e));(r==5?logStatus:feedback).setText("Nie udało się uzyskać dostępu do folderu.");}
    }
    String path(){
        String uri=global().getString("tree","");
        if(uri.isEmpty() || global().getBoolean("documents",false))return "Documents/GitClone";
        String id=DocumentsContract.getTreeDocumentId(Uri.parse(uri));return id.replaceFirst("^primary:","");
    }
    void add(){
        String repo;
        try{repo=Mirror.repository(input.getText().toString());}catch(Exception e){feedback.setText(e.getMessage());return;}
        RepoApp.app(this).event("Dodawanie repozytorium "+repo);
        String tree=global().getString("tree","");
        if(tree.isEmpty()){pendingAdd=true;feedback.setText("Zatwierdź Dokumenty, aby utworzyć folder GitClone.");pick();return;}
        String name=repo.substring(repo.indexOf('/')+1);
        List<String> ids=Sync.ids(this);
        for(String id:ids){
            SharedPreferences p=getSharedPreferences("repo-"+id,0);
            if(repo.equalsIgnoreCase(p.getString("repo",""))){feedback.setText("To repozytorium jest już na liście.");return;}
            if(tree.equals(p.getString("tree","")) && name.equalsIgnoreCase(p.getString("name",""))){
                feedback.setText("Inne repozytorium używa tej nazwy. Wybierz osobny folder.");return;
            }
        }
        String id=UUID.randomUUID().toString();
        getSharedPreferences("repo-"+id,0).edit().putString("repo",repo).putString("name",name).putString("tree",tree)
            .putBoolean("documents",global().getBoolean("documents",false)).putString("path",path()+"/"+name).commit();
        ids.add(id);global().edit().putStringSet("repos",new HashSet<>(ids)).commit(); StatusWidget.changed(this);
        input.setText("");feedback.setText("Dodano "+repo);render();start();
    }
    void start(){
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},4);return;
        }
        global().edit().putBoolean("enabled",true).apply();
        getSystemService(NotificationManager.class).cancel(Sync.NOTICE+1);
        try{startForegroundService(new Intent(this,Monitor.class));}
        catch(RuntimeException e){
            global().edit().putBoolean("enabled",false).apply(); StatusWidget.changed(this);
            RepoApp.app(this).event("Start usługi: "+Reports.trace(e));
            feedback.setText("Android nie uruchomił monitorowania. Ponów z ekranu aplikacji.");
        }
        render();
    }
    public void onRequestPermissionsResult(int r,String[] perms,int[] grants){
        super.onRequestPermissionsResult(r,perms,grants);
        if(r==4 && grants.length>0 && grants[0]==0)start();
        else feedback.setText("Włącz powiadomienia dla RepoKopii w ustawieniach Androida, aby uruchomić monitorowanie.");
    }
    void pause(){
        for(String id:Sync.manual.keySet())Sync.cancelRefresh(id);
        global().edit().putBoolean("enabled",false).apply(); StatusWidget.changed(this);stopService(new Intent(this,Monitor.class));
        getSystemService(JobScheduler.class).cancel(Sync.JOB);getSystemService(NotificationManager.class).cancel(Sync.NOTICE+1);render();
    }
    void forget(String id,String repo){
        new AlertDialog.Builder(this).setTitle("Odłączyć "+repo+"?").setMessage("Pobrane pliki pozostaną na telefonie.")
          .setNegativeButton("Anuluj",null).setPositiveButton("Odłącz",(d,w)->{
            Sync.cancelRefresh(id);
            List<String> ids=Sync.ids(this);ids.remove(id);global().edit().putStringSet("repos",new HashSet<>(ids)).commit(); StatusWidget.changed(this);
            if(ids.isEmpty())pause();else if(Monitor.active)start();
            render();
          }).show();
    }
    void render(){
        folder.setText("Folder dla nowej kopii: "+path());
        List<String> ids=Sync.ids(this);
        boolean active=Monitor.active, enabled=Sync.enabled(this);
        summary.setText(ids.isEmpty()?"Brak dodanych repozytoriów":active?"Monitorowanie w tle • co "+Sync.minutes(this)+" min":enabled?"Monitorowanie okresowe Androida":"Monitorowanie wstrzymane");
        all.setEnabled(!ids.isEmpty());
        pausePeriodic.setVisibility(enabled && !active && !ids.isEmpty()?View.VISIBLE:View.GONE);
        toggle.setVisibility(ids.isEmpty()?View.GONE:View.VISIBLE);
        toggle.setText(active?"Wstrzymaj synchronizację":"Uruchom monitorowanie w tle");
        Sync.Progress transfer=Sync.progress;
        StringBuilder stamp=new StringBuilder(transfer==null?"":transfer.id);
        for(String id:ids){SharedPreferences p=getSharedPreferences("repo-"+id,0);stamp.append(id).append(id.equals(Sync.working)).append(Sync.manual.containsKey(id)).append(p.getString("status","")).append(p.getLong("checked",0)).append("/").append(p.getLong("lastDownloaded",0));}
        if(last.equals(stamp.toString())){updateBar(transfer);return;}
        downloadBar=null;downloadLabel=null;
        last=stamp.toString();list.removeAllViews();
        for(String id:ids){
            SharedPreferences p=getSharedPreferences("repo-"+id,0);String repo=p.getString("repo","");
            LinearLayout card=panel(list);label(card,repo,18,INK,true);label(card,p.getString("path",""),14,MUTED,false);gap(card,6);
            if(transfer!=null && transfer.id.equals(id)){
                downloadLabel=label(card,"",15,TEAL,true);
                downloadBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
                downloadBar.setMax(100);card.addView(downloadBar);
                updateBar(transfer);
            }else label(card,p.getString("status","Oczekuje na pobranie"),15,TEAL,true);
            long time=p.getLong("checked",0);
            if(time>0)label(card,"Sprawdzono "+java.text.DateFormat.getDateTimeInstance(3,3).format(new Date(time)),13,MUTED,false);
            long downloaded=p.getLong("lastDownloaded",0);
            String downloadTime;
            if(downloaded>0)downloadTime=java.text.DateFormat.getDateTimeInstance(
                java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT).format(new Date(downloaded));
            else if(p.contains("sha") || time>0)downloadTime="brak danych z poprzedniej wersji";
            else downloadTime="jeszcze nie pobrano";
            label(card,"Ostatnie pobranie: "+downloadTime,13,MUTED,false);
            gap(card,8);
            boolean checking=id.equals(Sync.working);
            boolean queued=Sync.manual.containsKey(id);
            String refreshText=checking?"Odświeżanie…":queued?"W kolejce…":"Odśwież";
            Button refreshButton=button(card,refreshText,false,v->{
                long retry=p.getLong("retry",0);
                if(System.currentTimeMillis()<retry){
                    String timeText=android.text.format.DateFormat.getTimeFormat(this).format(new Date(retry));
                    Toast.makeText(this,"Limit GitHuba. Spróbuj po "+timeText,Toast.LENGTH_LONG).show();
                    return;
                }
                Sync.refresh(this,id);
                render();
            });
            refreshButton.setEnabled(!checking && !queued);
            gap(card,8);
            button(card,"Otwórz repozytorium",true,v->openRepository(repo));
            gap(card,8);button(card,"Odłącz",false,v->forget(id,repo));gap(list,10);
        }
    }
    private void openRepository(String repo){
        Uri link=Uri.parse("https://github.com/"+repo);
        try{
            startActivity(new Intent(Intent.ACTION_VIEW,link));
        }catch(ActivityNotFoundException e){
            Toast.makeText(this,"Brak aplikacji do otwierania linków.",Toast.LENGTH_LONG).show();
        }
    }
    void updateBar(Sync.Progress transfer){
        if(transfer==null || downloadBar==null)return;
        int percent=Download.percent(transfer.received,transfer.total);
        downloadBar.setIndeterminate(percent<0);
        if(percent>=0)downloadBar.setProgress(percent);
        downloadLabel.setText(transfer.label(this));
    }
}
