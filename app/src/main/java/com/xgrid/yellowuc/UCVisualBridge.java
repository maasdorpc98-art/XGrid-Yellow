package com.xgrid.yellowuc;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import com.xgrid.yellownative.VisualEngine;
import org.json.JSONObject;
import java.net.URI;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Real on-device MediaPipe/OpenCV worker and authenticated page-response bridge.
 * Host must supply the actual UC page implementation, not android.webkit.WebView
 * assumed by name. No global interface installation or arbitrary-site attach.
 */
public final class UCVisualBridge implements AutoCloseable {
    public interface TrustedPageHost {
        /** Active top-level page, not an iframe or stale navigation. */
        String activeTopLevelUrl();
        /** Attaches an annotated Java object before a trusted page loads. */
        void attachInterface(Object bridge,String name);
        /** Removes the interface on leaving the trusted page. */
        void removeInterface(String name);
        /** Runs script in the main frame on Android's UI thread. */
        void evaluateMainFrame(String script);
    }
    private final TrustedPageHost host;
    private final ThreadPoolExecutor jobs=new ThreadPoolExecutor(1,2,20,TimeUnit.SECONDS,new ArrayBlockingQueue<>(4),r->{Thread t=new Thread(r,"yellow-vision");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final AtomicInteger requests=new AtomicInteger(),success=new AtomicInteger(),failure=new AtomicInteger(),cancelled=new AtomicInteger(),timeout=new AtomicInteger(),rejected=new AtomicInteger(),cacheHits=new AtomicInteger();
    private final SecureRandom rng=new SecureRandom();
    private final Map<String,VisualEngine.Signature> cache=new LinkedHashMap<String,VisualEngine.Signature>(96,.75f,true){@Override protected boolean removeEldestEntry(Map.Entry<String,VisualEngine.Signature> e){return size()>160;}};
    private volatile VisualEngine engine;
    private volatile String initError="",activePage="",session="";
    private volatile boolean attached,closed;
    private volatile long analysisMs;

    public UCVisualBridge(Context ctx,TrustedPageHost page){
        host=page;
        try{jobs.execute(()->{try{engine=new VisualEngine(ctx.getApplicationContext());}catch(Throwable t){initError="engine:"+t.getClass().getSimpleName();}});}catch(RuntimeException e){initError=e.getClass().getSimpleName();}
    }
    public static boolean googleSearch(String s){
        try{URI u=new URI(s);String h=u.getHost(),p=u.getPath();
            return "https".equalsIgnoreCase(u.getScheme())&&h!=null&&(h.equalsIgnoreCase("google.com")||h.toLowerCase(java.util.Locale.ROOT).matches("(?:www\\.)?google\\.(?:com|[a-z]{2,3}|co\\.[a-z]{2}|com\\.[a-z]{2})"))&&p!=null&&(p.equals("/search")||p.equals("/imgres")||p.equals("/imghp"));
        }catch(Exception e){return false;}
    }
    /** Called by a confirmed UC page-navigation callback BEFORE page script runs. */
    public void trustedNavigation(String url){
        if(closed)return;
        Runnable navigation=()->{
            String next=googleSearch(url)?url:"";
            if(attached){try{host.removeInterface("xgridUCVision");}catch(Throwable ignored){}attached=false;}
            session="";activePage="";
            if(next.isEmpty())return;
            byte[] random=new byte[24];rng.nextBytes(random);
            StringBuilder hex=new StringBuilder();for(byte v:random)hex.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
            session=hex.toString();activePage=next;
            try{host.attachInterface(this,"xgridUCVision");attached=true;}
            catch(Throwable t){initError="attach:"+t.getClass().getSimpleName();session="";activePage="";}
        };
        if(Looper.myLooper()==Looper.getMainLooper())navigation.run();else ui.post(navigation);
    }
    /** Called on an actual UC main-frame document-ready callback (after attach). */
    public void trustedDocumentReady(){
        ui.post(()->{
            if(closed||!attached||!googleSearch(host.activeTopLevelUrl())||activePage.isEmpty())return;
            host.evaluateMainFrame("(function(){if(window===top)Object.defineProperty(window,'__xgridNativeSession',{value:'"+session+"',configurable:true});})();");
        });
    }
    @JavascriptInterface public String status(){
        JSONObject j=new JSONObject();
        try{
            VisualEngine e=engine;
            j.put("ready",e!=null&&attached&&!closed&&!activePage.isEmpty());
            j.put("pose",e!=null&&e.poseReady);j.put("openCv",e!=null&&e.openCvReady);
            j.put("nativeAttached",attached?1:0);
            j.put("inflight",jobs.getActiveCount());j.put("queued",jobs.getQueue().size());j.put("cacheHits",cacheHits.get());
            j.put("analyzed",success.get());j.put("failures",failure.get());j.put("rejected",rejected.get());
            j.put("cancelled",cancelled.get());j.put("timeouts",timeout.get());j.put("analysisMs",analysisMs);
            j.put("error",initError);
        }catch(Exception ignored){}
        return j.toString();
    }
    /** Requires both per-request token and independently injected session capability. */
    @JavascriptInterface public boolean analyzeVisual(String original,String preview,String token,String capability){
        if(closed||!attached||engine==null||token==null||token.length()<2||token.length()>96||capability==null||!capability.equals(session)||activePage.isEmpty()){rejected.incrementAndGet();return false;}
        String gif=StrictHttpsMedia.permitted(original)?original:"";
        String still=StrictHttpsMedia.permitted(preview)?preview:"";
        if(gif.isEmpty()&&still.isEmpty()){rejected.incrementAndGet();return false;}
        final String page=activePage;
        try{jobs.execute(()->analyze(page,gif,still,token));requests.incrementAndGet();return true;}
        catch(RejectedExecutionException e){rejected.incrementAndGet();return false;}
    }
    private void analyze(String page,String gif,String still,String token){
        long start=SystemClock.elapsedRealtime();JSONObject payload=new JSONObject();
        try{
            if(!page.equals(activePage)||closed)throw new java.util.concurrent.CancellationException("navigation changed");
            payload.put("token",token);payload.put("url",gif.isEmpty()?still:gif);
            VisualEngine.Signature sig=null;String mode="preview-geometry";
            String cacheKey=gif.isEmpty()?still:gif;
            synchronized(cache){sig=cache.get(cacheKey);}
            if(sig!=null){cacheHits.incrementAndGet();mode=sig.gifVerified?"cached-original-gif":"cached-preview-geometry";}
            if(sig==null && !gif.isEmpty() && gif.toLowerCase(java.util.Locale.ROOT).matches("^https://.*\\.gif(?:[?].*)?$")){
                try{byte[] bytes=StrictHttpsMedia.fetch(gif,StrictHttpsMedia.GIF_CAP);
                    if(!StrictHttpsMedia.isGif(bytes))throw new IllegalArgumentException("not GIF87a/GIF89a");
                    synchronized(engine){sig=engine.analyze(bytes,null);}if(sig==null||sig.frames<1||!sig.gifVerified)throw new IllegalStateException("GIF decode failed");
                    mode="original-gif-motion";
                }catch(Exception ignored){sig=null;}
            }
            if(sig==null){String source=!still.isEmpty()?still:gif;Bitmap bitmap=StrictHttpsMedia.stillImage(source);
                if(bitmap==null)throw new IllegalStateException("no preview bitmap");
                try{synchronized(engine){sig=engine.analyzeFast(bitmap);}mode="preview-geometry";}
                finally{bitmap.recycle();}
            }
            if(sig==null||sig.frames<1)throw new IllegalStateException("signature unavailable");
            synchronized(cache){cache.put(cacheKey,sig);}
            payload.put("ok",true);payload.put("signature",sig.json());payload.put("mode",mode);success.incrementAndGet();
        }catch(java.util.concurrent.CancellationException t){cancelled.incrementAndGet();return;}
        catch(Throwable t){failure.incrementAndGet();try{payload.put("ok",false);payload.put("error",t.getClass().getSimpleName());}catch(Exception ignored){}}
        finally{analysisMs+=SystemClock.elapsedRealtime()-start;}
        final String message=payload.toString();
        ui.post(()->{if(!closed&&attached&&page.equals(activePage)&&page.equals(host.activeTopLevelUrl())){
            try{host.evaluateMainFrame("window.dispatchEvent(new CustomEvent('__xgridNativeAnalysis',{detail:"+message+"}));");}
            catch(Throwable t){initError="callback:"+t.getClass().getSimpleName();}
        }else cancelled.incrementAndGet();});
    }
    @Override public void close(){
        closed=true;ui.post(()->{if(attached)try{host.removeInterface("xgridUCVision");}catch(Throwable ignored){}attached=false;});
        jobs.shutdownNow();synchronized(cache){cache.clear();}
        VisualEngine v=engine;if(v!=null){synchronized(v){v.close();}}engine=null;
    }
}