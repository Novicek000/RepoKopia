package pl.repokopia;

import java.io.*;
import java.util.concurrent.TimeUnit;

final class Download {
    interface Listener { void update(long received, long total); }

    static int percent(long received, long total) {
        return total <= 0 ? -1 : (int)Math.min(100, 100.0 * received / total);
    }

    static void copy(InputStream in, OutputStream out, long total, Listener listener) throws IOException {
        listener.update(0,total);
        byte[] buffer=new byte[65536];
        long received=0, lastUpdate=System.nanoTime(), lastShown=0;
        int n;
        while((n=in.read(buffer))!=-1){
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException();
            out.write(buffer,0,n);
            received+=n;
            long now=System.nanoTime();
            if(now-lastUpdate>=TimeUnit.SECONDS.toNanos(2)){
                listener.update(received,total);
                lastUpdate=now;
                lastShown=received;
            }
        }
        if(total>=0 && received!=total)throw new EOFException("Pobrana paczka ma niezgodny rozmiar.");
        if(received!=lastShown)listener.update(received,total);
    }
}
