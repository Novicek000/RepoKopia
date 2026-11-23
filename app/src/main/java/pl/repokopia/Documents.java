package pl.repokopia;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;

final class Documents {
    final ContentResolver files;
    final Uri tree;
    Documents(ContentResolver files,Uri tree){this.files=files;this.tree=tree;}
    Uri doc(String id){return DocumentsContract.buildDocumentUriUsingTree(tree,id);}
    static final class Node {
        final Uri uri; final boolean dir;
        Node(Uri uri,boolean dir){this.uri=uri; this.dir=dir;}
    }
    Map<String,Node> children(Uri parent) throws IOException {
        Map<String,Node> result=new HashMap<>();
        Uri listing=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));
        try(Cursor cur=files.query(listing,new String[]{Document.COLUMN_DOCUMENT_ID,Document.COLUMN_DISPLAY_NAME,Document.COLUMN_MIME_TYPE},null,null,null)){
            if(cur==null || cur.getExtras().getBoolean(DocumentsContract.EXTRA_LOADING)) throw new IOException("Folder jest niedostępny.");
            while(cur.moveToNext()) {
                if(result.put(cur.getString(1),new Node(doc(cur.getString(0)),Document.MIME_TYPE_DIR.equals(cur.getString(2))))!=null)
                    throw new IOException("Folder zawiera powtórzoną nazwę.");
            }
        }
        return result;
    }
    Uri create(Uri parent,String name,boolean dir) throws IOException {
        String mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT));
        // An unmapped MIME avoids a provider appending a guessed extension to source files.
        Uri u=DocumentsContract.createDocument(files,parent,dir?Document.MIME_TYPE_DIR:(mime==null?"application/x-repokopia-file":mime),name);
        if(u==null) throw new IOException("Nie udało się utworzyć: "+name);
        try(Cursor cur=files.query(u,new String[]{Document.COLUMN_DISPLAY_NAME},null,null,null)){
            if(cur==null || !cur.moveToFirst() || !name.equals(cur.getString(0)))
                throw new IOException("Pamięć telefonu nie obsługuje nazwy: "+name);
        }
        return u;
    }
    synchronized static Uri base(Documents docs,boolean documents) throws IOException {
        Uri parent=docs.doc(DocumentsContract.getTreeDocumentId(docs.tree));
        if(documents){
            Node git=docs.children(parent).get("GitClone");
            if(git!=null && !git.dir) throw new IOException("Nazwa GitClone jest zajęta przez plik.");
            parent=git==null?docs.create(parent,"GitClone",true):git.uri;
        }
        return parent;
    }
}
