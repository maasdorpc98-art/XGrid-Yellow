package com.xgrid.yellowblue;

import android.app.Activity;
import android.app.Application;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.mmbox.xbrowser.Browser;
import com.xgrid.yellownative.GifStore;
import com.xgrid.yellownative.VisualEngine;

import org.json.JSONObject;

import java.net.URI;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs INSIDE the preserved Blue-based browser. It extends Blue's original
 * Application; it does not replace BrowserActivity or its WebView pipeline.
 *
 * The method only accepts a public HTTPS image URL, and posts results to the
 * WebView as a named JS event. It never performs unsolicited ranking.
 */
public final class BridgeApplication extends Browser implements Application.ActivityLifecycleCallbacks {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<WebView> attached = Collections.newSetFromMap(new WeakHashMap<WebView, Boolean>());
    private final ExecutorService jobs = Executors.newFixedThreadPool(2);
    private final AtomicInteger inflight = new AtomicInteger();
    private volatile VisualEngine engine;
    private volatile GifStore store;
    private volatile String error = "";
    private volatile int finished, failed, rejected, attachedViews;
    private volatile int originalGifSuccess, previewGeometrySuccess, badUrls;
    private volatile long totalAnalysisMs;
    private Activity current;
    private long lastSweep;

    @Override public void onCreate() {
        super.onCreate(); // Mandatory: retain the entire original Blue initialization.
        store = new GifStore(getApplicationContext());
        registerActivityLifecycleCallbacks(this);
        jobs.execute(() -> {
            try { engine = new VisualEngine(getApplicationContext()); }
            catch(Throwable t){error = "engine-init: " + t.getClass().getSimpleName();}
        });
    }

    @Override public void onActivityResumed(Activity activity) {
        current = activity;
        sweep(activity);
        activity.getWindow().getDecorView().getViewTreeObserver()
            .addOnGlobalLayoutListener(() -> {
                if (current == activity && SystemClock.elapsedRealtime() - lastSweep > 950L) {
                    sweep(activity);
                }
            });
    }

    private void sweep(Activity activity) {
        lastSweep = SystemClock.elapsedRealtime();
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        findWebViews(decor);
    }

    private void findWebViews(View root) {
        if(root==null)return;
        if(root instanceof WebView){
            WebView view = (WebView)root;
            if(!attached.contains(view)){
                try{
                    view.addJavascriptInterface(new VisualBridge(view), "yellowVision");
                    attached.add(view);
                    attachedViews++;
                }catch(Throwable t){error = "attach: " + t.getClass().getSimpleName();}
            }
        }
        if(root instanceof ViewGroup){
            ViewGroup group = (ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++)findWebViews(group.getChildAt(i));
        }
    }

    public final class VisualBridge {
        private final WebView host;
        VisualBridge(WebView v){host=v;}

        @JavascriptInterface public String status() {
            JSONObject o=new JSONObject();
            try{
                VisualEngine v=engine;
                o.put("ready",v!=null);
                o.put("openCv",v!=null&&v.openCvReady);
                o.put("pose",v!=null&&v.poseReady);
                o.put("nativeAttached",attachedViews);
                o.put("analyzed",finished);
                o.put("failures",failed);
                o.put("rejected",rejected);
                o.put("inflight",inflight.get());
                o.put("analysisMs",totalAnalysisMs);
                o.put("originalGifSuccess",originalGifSuccess);
                o.put("previewGeometrySuccess",previewGeometrySuccess);
                o.put("badUrls",badUrls);
                o.put("error",error+(v!=null?v.initError:""));
            }catch(Exception ignored){}
            return o.toString();
        }

        /**
         * GIF source is preferred for an anchor. Search result previews can be
         * analyzed as geometry-only when original GIFs are inaccessible.
         */
        @JavascriptInterface public boolean analyzeVisual(String original, String preview, String token) {
            if(token==null||token.length()<2||token.length()>96)return false;
            final String gifUrl=isSafePublicUrl(original)?original:"";
            final String previewUrl=isSafePublicUrl(preview)?preview:"";
            if(gifUrl.isEmpty() && previewUrl.isEmpty()){badUrls++;rejected++;return false;}
            if(inflight.incrementAndGet()>3){inflight.decrementAndGet();return false;}
            main.post(() -> {
                String page=host.getUrl();
                if(page==null || !page.matches("(?i)^https://(?:www\\\\.)?google\\\\.[^/]+/(?:search|imgres).*")){
                    inflight.decrementAndGet();rejected++;
                    return;
                }
                jobs.execute(()->analyze(host,gifUrl,previewUrl,token));
            });
            return true;
        }

        /** Backwards-compatible native hook for earlier XSearch scripts. */
        @JavascriptInterface public boolean analyzeGif(String url, String token) {
            return analyzeVisual(url,url,token);
        }

        private boolean isSafePublicUrl(String url) {
            if(url==null || url.length()>1600 || !url.startsWith("https://"))return false;
            try {
                URI uri=new URI(url);
                String hostName=uri.getHost();
                if(hostName==null || hostName.length()<4 || uri.getUserInfo()!=null)return false;
                String name=hostName.toLowerCase(java.util.Locale.ROOT);
                if(name.equals("localhost") || name.endsWith(".localhost") ||
                    name.endsWith(".local") || name.endsWith(".internal") ||
                    name.endsWith(".test") || name.equals("metadata.google.internal") ||
                    name.startsWith("127.") || name.startsWith("10.") ||
                    name.startsWith("192.168.") || name.startsWith("169.254.") ||
                    name.equals("::1") || name.startsWith("[") ||
                    name.matches("^172\\\\.(?:1[6-9]|2[0-9]|3[01])\\\\..*") ||
                    name.matches("^[0-9.]+$"))return false;
                int port=uri.getPort(); if(port!=-1 && port!=443)return false;
                return true;
            }catch(Exception ignored){return false;}
        }

        private void analyze(WebView web,String gifUrl,String previewUrl,String token) {
            long start=SystemClock.elapsedRealtime();
            JSONObject output=new JSONObject();
            try {
                output.put("token",token);
                output.put("url",gifUrl.isEmpty()?previewUrl:gifUrl);
                VisualEngine local=engine;
                if(local==null)throw new IllegalStateException("Native visual engine starting");
                VisualEngine.Signature signature=null;
                String method="preview-geometry";
                String originalError="";
                if(!gifUrl.isEmpty() && gifUrl.matches("(?i)^https://.*\\\\.gif(?:[?#].*)?$")) {
                    try {
                        signature=store.remembered(gifUrl);
                        if(signature==null || !signature.gifVerified){
                            byte[] bytes=store.loadGif(gifUrl);
                            synchronized(local){signature=local.analyze(bytes,null);}
                            if(signature==null || !signature.gifVerified || signature.frames<1)
                                throw new IllegalArgumentException("Original GIF not decodable");
                            store.remember(gifUrl,signature);
                        }
                        originalGifSuccess++;
                        method="original-gif-motion";
                    } catch(Exception e) {
                        originalError=e.getClass().getSimpleName();
                        signature=null;
                    }
                }
                if(signature==null){
                    Bitmap image=null;
                    if(!previewUrl.isEmpty())image=store.loadThumbnail(previewUrl);
                    if(image==null && !gifUrl.isEmpty() && !gifUrl.equals(previewUrl))
                        image=store.loadThumbnail(gifUrl);
                    if(image==null)throw new IllegalStateException("Preview unavailable"+(originalError.isEmpty()?"":"; original="+originalError));
                    synchronized(local){signature=local.analyzeFast(image);}
                    if(signature.frames<1)throw new IllegalStateException("Preview geometry unavailable");
                    previewGeometrySuccess++;
                }
                output.put("ok",true);
                output.put("signature",signature.json());
                output.put("mode",method);
                output.put("originalError",originalError);
                finished++;
            }catch(Throwable ex) {
                failed++;
                try {
                    output.put("ok",false);
                    output.put("error",ex.getClass().getSimpleName()+": "+ex.getMessage());
                }catch(Exception ignored){}
            }finally {
                totalAnalysisMs+=SystemClock.elapsedRealtime()-start;
                inflight.decrementAndGet();
            }
            final String payload=output.toString();
            main.post(()->{
                try { web.evaluateJavascript("window.dispatchEvent(new CustomEvent('__xgridNativeAnalysis',{detail:"+payload+"}));",null); }
                catch(Throwable t){error="callback: "+t.getClass().getSimpleName();}
            });
        }
    }

    @Override public void onActivityCreated(Activity a, Bundle b){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityPaused(Activity a){if(current==a)current=null;}
    @Override public void onActivityStopped(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a, Bundle b){}
    @Override public void onActivityDestroyed(Activity a){if(current==a)current=null;}
}
