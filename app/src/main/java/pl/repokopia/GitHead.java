package pl.repokopia;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class GitHead {
    private static String packet(DataInputStream in) throws IOException {
        byte[] header=new byte[4]; in.readFully(header);
        int size;
        try {size=Integer.parseInt(new String(header,StandardCharsets.US_ASCII),16);}
        catch(NumberFormatException e){throw new IOException("Nieprawidłowa odpowiedź serwera Git.");}
        if(size==0)return "";
        if(size<4 || size>65520)throw new IOException("Nieprawidłowa długość pakietu Git.");
        byte[] data=new byte[size-4];in.readFully(data);return new String(data,StandardCharsets.US_ASCII);
    }
    static String read(InputStream stream) throws IOException {
        DataInputStream in=new DataInputStream(new BufferedInputStream(stream));
        if(!packet(in).equals("# service=git-upload-pack\n") || !packet(in).isEmpty())
            throw new IOException("GitHub nie zwrócił listy referencji Git.");
        String first=packet(in);
        if(first.length()<45 || !first.startsWith(" HEAD",40) || !first.substring(0,40).matches("[0-9a-f]{40}"))
            throw new IOException("Repozytorium nie ma dostępnego commita na gałęzi domyślnej.");
        return first.substring(0,40);
    }
}
