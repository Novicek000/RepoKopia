package pl.repokopia;

import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.zip.*;

final class Mirror {
    static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("mirror", 0); }
    static String repository(String input) throws IOException {
        String s = input.trim().replaceFirst("^https://github\\.com/", "").replaceFirst("/$", "").replaceFirst("\\.git$", "");
        if (!s.matches("[a-zA-Z0-9-]+/[a-zA-Z0-9_.-]+") || s.endsWith("/.") || s.endsWith("/.."))
            throw new IOException("Wklej adres repozytorium, np. https://github.com/owner/repo");
        return s;
    }
    final Context c;
    final android.content.SharedPreferences p;
    final ContentResolver files;
    final String repo,id;
    final Uri tree;
    final Documents docs;
    Mirror(Context c, String id) {
        this.c=c; this.id=id; p=c.getSharedPreferences("repo-"+id,0); files=c.getContentResolver();
        repo=p.getString("repo", ""); tree=Uri.parse(p.getString("tree", ""));
        docs=new Documents(files,tree);
    }
    static void check() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
    HttpURLConnection connect(String url) throws IOException {
        HttpURLConnection h=(HttpURLConnection)new URL(url).openConnection();
        h.setConnectTimeout(15000); h.setReadTimeout(30000); h.setRequestProperty("User-Agent","RepoKopia/1.0");
        return h;
    }
    void response(HttpURLConnection h) throws IOException {
        int n=h.getResponseCode();
        if(n==403 || n==429) {
            long delay=Math.max(300000L, h.getHeaderFieldLong("Retry-After",0)*1000L);
            long reset=h.getHeaderFieldLong("X-RateLimit-Reset",0)*1000L;
            p.edit().putLong("retry",Math.max(System.currentTimeMillis()+delay,reset+1000)).apply();
        }
        if(n!=200) throw new IOException(n==404?"Nie znaleziono publicznego repozytorium.":"GitHub zwrócił HTTP "+n);
    }
    static void copy(InputStream in, OutputStream out, byte[] buf) throws IOException {
        int n;
        while((n=in.read(buf))!=-1) {check(); out.write(buf,0,n);}
    }
    boolean checkStarted;
    void run() throws Exception {
        if(System.currentTimeMillis()<p.getLong("retry",0)) throw new IOException("Oczekiwanie po ograniczeniu liczby zapytań GitHuba.");
        checkStarted=true;
        HttpURLConnection api=connect("https://github.com/"+repo+".git/info/refs?service=git-upload-pack");
        String sha;
        try {
            response(api);
            try(InputStream in=api.getInputStream()){sha=GitHead.read(in);}
        } finally {api.disconnect();}
        if(!sha.equals(p.getString("sha",""))) {
            File tmp=new File(c.getCacheDir(),"snapshot.zip");
            try {
                HttpURLConnection h=connect("https://codeload.github.com/"+repo+"/zip/"+sha);
                h.setRequestProperty("Accept-Encoding","identity");
                try {
                    response(h);
                    long total=h.getContentLengthLong();
                    try(InputStream in=h.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                        Download.copy(in,out,total,(bytes,size)->Sync.progress(c,new Sync.Progress(id,repo,bytes,size)));
                    }
                } finally {h.disconnect();Sync.progress(c,null);}
                Sync.saving=true; StatusWidget.changed(c);
                p.edit().putString("status","Zapisywanie plików…").apply();
                try(ZipFile z=new ZipFile(tmp)){ install(z); }
            } finally {tmp.delete();}
            check();
            if(!p.edit().putString("sha",sha).putLong("lastDownloaded",System.currentTimeMillis()).commit())
                throw new IOException("Nie zapisano stanu pobranej kopii.");
        }
    }
    Uri root() throws IOException {
        Uri parent=Documents.base(docs,p.getBoolean("documents",false));
        String name=repo.substring(repo.indexOf('/')+1);
        Documents.Node existing=docs.children(parent).get(name);
        String key="dest:"+tree+"|"+repo;
        if(existing!=null){
            if(!existing.dir || !existing.uri.toString().equals(prefs(c).getString(key,"")))
                throw new IOException("Nazwa "+name+" jest już zajęta. Wybierz inny folder.");
            return existing.uri;
        }
        Uri u=docs.create(parent,name,true);
        if(!prefs(c).edit().putString(key,u.toString()).commit()) throw new IOException("Nie zapisano lokalizacji kopii.");
        return u;
    }
    static class Entry {
        ZipEntry file;
        final Map<String,Entry> children=new LinkedHashMap<>();
    }
    void install(ZipFile z) throws IOException {
        Entry top=new Entry(); Enumeration<? extends ZipEntry> entries=z.entries();
        while(entries.hasMoreElements()){
            check(); ZipEntry item=entries.nextElement();
            String[] parts=item.getName().split("/",-1);
            if(parts.length<2) throw new IOException("Nieprawidłowy format archiwum.");
            Entry at=top;
            for(int i=1;i<parts.length;i++){
                String part=parts[i];
                if(part.isEmpty() && i==parts.length-1 && item.isDirectory()) break;
                if(part.isEmpty() || part.equals(".") || part.equals("..") || part.contains("\\") || at.file!=null)
                    throw new IOException("Nieprawidłowa ścieżka w archiwum.");
                at=at.children.computeIfAbsent(part,ignored->new Entry());
            }
            if(!item.isDirectory()) at.file=item;
        }
        check(); Uri target=root();
        if (!p.edit().remove("sha").commit()) throw new IOException("Nie zapisano stanu synchronizacji.");
        write(z,target,top,new byte[65536]);
    }
    void remove(Uri u) throws IOException {
        check(); if(!DocumentsContract.deleteDocument(files,u)) throw new IOException("Nie udało się usunąć starego pliku.");
    }
    void write(ZipFile z,Uri parent,Entry dir,byte[] buffer) throws IOException {
        Map<String,Documents.Node> old=docs.children(parent);
        for(Map.Entry<String,Entry> pair:dir.children.entrySet()){
            check(); String name=pair.getKey(); Entry item=pair.getValue(); boolean directory=item.file==null;
            Documents.Node node=old.remove(name);
            if(node!=null && node.dir!=directory){remove(node.uri); node=null;}
            Uri u=node==null?docs.create(parent,name,directory):node.uri;
            if(directory){write(z,u,item,buffer); continue;}
            try(InputStream in=z.getInputStream(item.file);OutputStream out=files.openOutputStream(u,"wt")){
                if(out==null) throw new IOException("Brak dostępu do zapisu: "+name);
                copy(in,out,buffer);
            }
        }
        for(Documents.Node stale:old.values()) remove(stale.uri);
    }
}
