package pl.repokopia;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

final class Reports {
    final File directory;
    Reports(File directory){this.directory=directory;}
    private void prepare() throws IOException {
        if(!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Brak miejsca na raport.");
    }
    static String timestamp(){
        return new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss_SSS",Locale.ROOT).format(new Date());
    }
    static String trace(Throwable e){
        StringWriter text=new StringWriter();
        e.printStackTrace(new PrintWriter(text));
        return text.toString();
    }
    synchronized void event(String message) throws IOException {
        prepare();
        File journal=new File(directory,"events.txt"), previous=new File(directory,"previous.txt");
        if(journal.length()>131072 && !journal.renameTo(previous))
            throw new IOException("Nie można obrócić dziennika.");
        try(FileOutputStream out=new FileOutputStream(journal,true)){
            out.write((timestamp()+"  "+message+"\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    File crash(String body) throws IOException {
        prepare();
        String name="awaria-"+timestamp()+"-"+UUID.randomUUID();
        File tmp=new File(directory,name+".tmp"), ready=new File(directory,name+".txt");
        try(FileOutputStream out=new FileOutputStream(tmp)){
            out.write(body.getBytes(StandardCharsets.UTF_8));out.getFD().sync();
        }
        if(!tmp.renameTo(ready)) throw new IOException("Nie można zatwierdzić raportu.");
        return ready;
    }
    File[] pending() throws IOException {
        prepare();
        File[] reports=directory.listFiles((d,n)->n.startsWith("awaria-") && n.endsWith(".txt"));
        if(reports==null)throw new IOException("Brak dostępu do raportów.");
        Arrays.sort(reports);return reports;
    }
    synchronized void delivered(File report) throws IOException {
        File latest=new File(directory,"latest.txt");
        if(latest.exists() && latest.lastModified()>report.lastModified()){
            if(!report.delete())throw new IOException("Nie udało się potwierdzić zapisu raportu.");
        }else if(!report.renameTo(latest))throw new IOException("Nie udało się potwierdzić zapisu raportu.");
    }
    synchronized void journal(OutputStream out) throws IOException {
        for(String name:Arrays.asList("previous.txt","events.txt","latest.txt")){
            File f=new File(directory,name);
            if(f.exists())try(InputStream in=new FileInputStream(f)){copy(in,out);}
        }
    }
    static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
    }
}
