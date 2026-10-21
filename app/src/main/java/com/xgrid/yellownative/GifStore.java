package com.xgrid.yellownative;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.LruCache;

import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Capped independent media store. Google search supplies candidate URLs;
 * each original is independently checked for GIF87a/GIF89a magic.
 */
public final class GifStore {
    public static class Item {
        public final String key;
        public String previewUrl;
        public String gifUrl;
        public String label;
        public Bitmap thumb;
        public volatile boolean loading;
        public volatile boolean verifiedGif;
        public volatile boolean mediaBlocked;
        public volatile VisualEngine.Signature signature;
        public volatile double rank;
        public volatile String failure = "";
        Item(String key,String preview,String gif,String title){
            this.key=key;previewUrl=preview;gifUrl=gif;label=title;
        }
    }
    private final File disk;
    private final SharedPreferences signatures;
    private final LruCache<String,Bitmap> thumbnails = new LruCache<String,Bitmap>(48_000) {
        @Override protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
    };
    public final ExecutorService workers = Executors.newFixedThreadPool(5);
    public volatile int httpRequests, gifSuccess, gifRejected, failures, cacheHits, bytesDownloaded;

    public GifStore(Context context){
        disk=new File(context.getCacheDir(),"yellow_gifs");
        disk.mkdirs();
        signatures=context.getSharedPreferences("yellow_signatures_v1",Context.MODE_PRIVATE);
    }
    public static String key(String value){
        try {
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            byte[] b=md.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder out=new StringBuilder();
            for(int i=0;i<16;i++)out.append(String.format(Locale.ROOT,"%02x",b[i]&255));
            return out.toString();
        }catch(Exception e){return Integer.toHexString(value.hashCode());}
    }
    public Bitmap cachedThumbnail(String url){synchronized(thumbnails){return thumbnails.get(key(url));}}
    public Bitmap loadThumbnail(String url) {
        if(url==null || url.isEmpty())return null;
        String k=key(url);
        synchronized(thumbnails){Bitmap in=thumbnails.get(k);if(in!=null)return in;}
        try {
            byte[] raw=download(url,2_000_000);
            BitmapFactory.Options option=new BitmapFactory.Options();
            option.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(raw,0,raw.length,option);
            option.inSampleSize=1;
            while(option.outWidth/option.inSampleSize>350 || option.outHeight/option.inSampleSize>350)option.inSampleSize*=2;
            option.inJustDecodeBounds=false;
            Bitmap bitmap=BitmapFactory.decodeByteArray(raw,0,raw.length,option);
            if(bitmap!=null) synchronized(thumbnails){thumbnails.put(k,bitmap);}
            return bitmap;
        }catch(Exception ex){return null;}
    }
    public byte[] loadGif(String url) throws Exception {
        if(url==null || url.isEmpty())throw new Exception("No original GIF URL from retrieval");
        File f=new File(disk,key(url)+".gif");
        if(f.exists() && f.length()>=6 && f.length()<=15_000_000){
            try(FileInputStream in=new FileInputStream(f)){
                byte[] cached=read(in,15_000_000);
                if(VisualEngine.isGif(cached)){cacheHits++;f.setLastModified(System.currentTimeMillis());return cached;}
            }
            f.delete();
        }
        byte[] data=download(url,15_000_000);
        if(!VisualEngine.isGif(data)){gifRejected++;throw new Exception("Original is not GIF data");}
        try(FileOutputStream out=new FileOutputStream(f)){out.write(data);}
        gifSuccess++;evict();
        return data;
    }
    private byte[] download(String url,int maxBytes)throws Exception{
        if(!(url.startsWith("https://") || url.startsWith("http://")))throw new Exception("Unsupported URL scheme");
        URL u=new URL(url);
        String protocol=u.getProtocol();
        if(!protocol.equals("https") && !protocol.equals("http"))throw new Exception("Invalid protocol");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(5000);c.setReadTimeout(7000);c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36");
        c.setRequestProperty("Accept","image/gif,image/webp,image/avif,image/*;q=0.8,*/*;q=0.5");
        httpRequests++;
        try{
            if(c.getResponseCode()<200 || c.getResponseCode()>=300)throw new Exception("HTTP "+c.getResponseCode());
            if(c.getContentLengthLong()>maxBytes)throw new Exception("Media over size limit");
            byte[] result=read(c.getInputStream(),maxBytes);
            bytesDownloaded+=result.length;
            return result;
        }catch(Exception err){failures++;throw err;}
        finally{c.disconnect();}
    }
    private static byte[] read(java.io.InputStream input,int cap)throws Exception{
        try(java.io.InputStream in=input;ByteArrayOutputStream b=new ByteArrayOutputStream()){
            byte[] tmp=new byte[8192];int count,total=0;
            while((count=in.read(tmp))>=0){
                total+=count;
                if(total>cap)throw new Exception("Media exceeds max bytes");
                b.write(tmp,0,count);
            }
            return b.toByteArray();
        }
    }
    private void evict(){
        File[] files=disk.listFiles();if(files==null)return;
        long total=0;for(File f:files)total+=f.length();
        if(total<85_000_000)return;
        java.util.Arrays.sort(files,(a,b)->Long.compare(a.lastModified(),b.lastModified()));
        for(File f:files){if(total<60_000_000)break;total-=f.length();f.delete();}
    }
    public Drawable drawableFromGif(byte[] data) throws Exception {
        if(!VisualEngine.isGif(data))throw new Exception("Not GIF");
        ImageDecoder.Source source=ImageDecoder.createSource(ByteBuffer.wrap(data));
        Drawable d=ImageDecoder.decodeDrawable(source);
        if(d instanceof AnimatedImageDrawable)((AnimatedImageDrawable)d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
        return d;
    }
    public VisualEngine.Signature remembered(String url){
        try{return VisualEngine.Signature.fromJson(new JSONObject(signatures.getString(key(url),""))); }
        catch(Exception err){return null;}
    }
    public void remember(String url,VisualEngine.Signature signature){
        if(url!=null && signature!=null && signature.frames>0)
            signatures.edit().putString(key(url),signature.json().toString()).apply();
    }
    public void shutdown(){workers.shutdownNow();}
}
