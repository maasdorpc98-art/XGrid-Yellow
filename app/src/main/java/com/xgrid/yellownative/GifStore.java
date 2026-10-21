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
import java.util.concurrent.ConcurrentHashMap;
import java.io.IOException;
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
    private final File thumbDisk;
    private final ConcurrentHashMap<String,Long> thumbnailCooldown = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Object> thumbnailLocks = new ConcurrentHashMap<>();
    public volatile int thumbAttempts, thumbSuccess, thumbFailures, thumbDiskHits, thumbSkipped, thumbMemoryHits;
    public volatile long thumbNetworkMs;
    private final SharedPreferences signatures;
    private final LruCache<String,Bitmap> thumbnails = new LruCache<String,Bitmap>(48_000) {
        @Override protected int sizeOf(String key,Bitmap value){return Math.max(1,value.getByteCount()/1024);}
    };
    public final ExecutorService workers = Executors.newFixedThreadPool(4);
    public final ExecutorService thumbWorkers = Executors.newFixedThreadPool(7);
    public volatile int httpRequests, gifSuccess, gifRejected, failures, cacheHits, bytesDownloaded;

    public GifStore(Context context){
        disk=new File(context.getCacheDir(),"yellow_gifs");
        disk.mkdirs();
        thumbDisk=new File(context.getCacheDir(),"yellow_thumbnails");
        thumbDisk.mkdirs();
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
    public Bitmap cachedThumbnail(String url) {
        if(url==null || url.isEmpty())return null;
        Bitmap bitmap;
        synchronized(thumbnails) { bitmap=thumbnails.get(key(url)); }
        if(bitmap!=null)thumbMemoryHits++;
        return bitmap;
    }
    public boolean mayLoadThumbnail(String url) {
        if(url==null||url.isEmpty())return false;
        long until=thumbnailCooldown.getOrDefault(key(url),0L);
        if(until>System.currentTimeMillis()){thumbSkipped++;return false;}
        return true;
    }
    public Bitmap loadThumbnail(String url) {
        if(url==null || url.isEmpty())return null;
        String k=key(url);
        Bitmap in=cachedThumbnail(url);
        if(in!=null)return in;
        if(!mayLoadThumbnail(url))return null;
        // Per-URL single flight across recycled cells and FIND MORE.
        Object lock=thumbnailLocks.computeIfAbsent(k,unused->new Object());
        synchronized(lock){
            try{
                in=cachedThumbnail(url);
                if(in!=null)return in;
                if(!mayLoadThumbnail(url))return null;
                File local=new File(thumbDisk,k+".img");
                if(local.exists() && local.length()>0 && local.length()<=1_800_000L){
                    Bitmap diskBitmap=decodeThumb(readFile(local,1_800_000));
                    if(diskBitmap!=null){
                        thumbDiskHits++;
                        local.setLastModified(System.currentTimeMillis());
                        synchronized(thumbnails){thumbnails.put(k,diskBitmap);}
                        return diskBitmap;
                    }
                    local.delete();
                }
                long start=System.currentTimeMillis();
                thumbAttempts++;
                byte[] raw=download(url,1_800_000,2200,3600);
                thumbNetworkMs+=System.currentTimeMillis()-start;
                Bitmap bitmap=decodeThumb(raw);
                if(bitmap==null)throw new IOException("Thumbnail decode returned null");
                thumbSuccess++;
                synchronized(thumbnails){thumbnails.put(k,bitmap);}
                try(FileOutputStream out=new FileOutputStream(local)){out.write(raw);}
                if((thumbSuccess%20)==0)pruneThumbDisk();
                return bitmap;
            }catch(Exception ex){
                thumbFailures++;
                // A bad URL should not monopolize all workers as cells recycle.
                thumbnailCooldown.put(k,System.currentTimeMillis()+60_000L);
                return null;
            }finally {
                thumbnailLocks.remove(k,lock);
            }
        }
    }
    private static Bitmap decodeThumb(byte[] bytes){
        if(bytes==null || bytes.length==0)return null;
        BitmapFactory.Options opt=new BitmapFactory.Options();
        opt.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
        if(opt.outWidth<=0||opt.outHeight<=0)return null;
        int sample=1;
        while(opt.outWidth/sample>230 || opt.outHeight/sample>230)sample*=2;
        opt.inSampleSize=sample;
        opt.inJustDecodeBounds=false;
        opt.inPreferredConfig=Bitmap.Config.RGB_565;
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,opt);
    }
    private static byte[] readFile(File file,int limit) throws Exception {
        try(FileInputStream in=new FileInputStream(file)){return read(in,limit);}
    }
    private void pruneThumbDisk(){
        File[] files=thumbDisk.listFiles(); if(files==null)return;
        long total=0;
        for(File file:files)total+=file.length();
        if(total<=45_000_000L)return;
        java.util.Arrays.sort(files,(x,y)->Long.compare(x.lastModified(),y.lastModified()));
        for(File file:files){if(total<=28_000_000L)break;total-=file.length();file.delete();}
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
    private byte[] download(String url,int maxBytes)throws Exception {
        return download(url,maxBytes,4400,6200);
    }
    private byte[] download(String url,int maxBytes,int connectMs,int readMs)throws Exception{
        if(!(url.startsWith("https://") || url.startsWith("http://")))throw new Exception("Unsupported URL scheme");
        URL u=new URL(url);
        String protocol=u.getProtocol();
        if(!protocol.equals("https") && !protocol.equals("http"))throw new Exception("Invalid protocol");
        HttpURLConnection c=(HttpURLConnection)u.openConnection();
        c.setConnectTimeout(connectMs);c.setReadTimeout(readMs);c.setInstanceFollowRedirects(true);
        c.setUseCaches(true);
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
    public void shutdown(){workers.shutdownNow();thumbWorkers.shutdownNow();}
}
