package com.xgrid.yellownative;

import android.app.Activity;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-backed discovery adapter. Google owns its content and can change DOM
 * or decline retrieval. Only candidates exposed by the loaded page are used.
 */
public final class SearchBridge {
    public interface Listener {
        void onCandidates(Map<String,GifStore.Item> cards, int rawCount, String host);
        void onStatus(String message);
    }
    public final WebView webView;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Listener listener;
    private String query="";
    private int epoch;
    private int pass;

    public SearchBridge(Activity context, Listener listener){
        this.listener=listener;
        webView=new WebView(context);
        WebSettings w=webView.getSettings();
        w.setJavaScriptEnabled(true);
        w.setDomStorageEnabled(true);
        w.setLoadsImagesAutomatically(true);
        w.setBlockNetworkImage(false);
        w.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                if(!url.contains("google.")) return;
                int at=epoch;
                listener.onStatus("Google page loaded • extracting GIF candidates");
                for(int d:new int[]{600,2200,4300,7500})
                    handler.postDelayed(()->{if(epoch==at)scan();},d);
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,android.webkit.WebResourceError err){
                if(req.isForMainFrame()) listener.onStatus("Google retrieval error: "+err.getDescription());
            }
        });
    }
    public void search(String q) {
        query=q.trim();epoch++;pass=0;
        String url="https://www.google.com/search?udm=2&tbs=itp%3Aanimated&q="
                +Uri.encode(query)+"&zx="+System.currentTimeMillis();
        listener.onStatus("Searching Google GIF results…");
        webView.loadUrl(url);
    }
    public void continueLoad(){
        int at=epoch;
        webView.evaluateJavascript("(function(){window.scrollBy(0,Math.max(900,innerHeight*2));return true})()",v->{});
        handler.postDelayed(()->{if(at==epoch)scan();},950);
    }
    public void scan() {
        int at=epoch;
        // Avoid network JS injection. Inspect only the current Google result DOM.
        String js="(function(){"
          +"var o=[],seen={},imgs=document.querySelectorAll('img');"
          +"function dec(s){try{return decodeURIComponent(s)}catch(e){return s}}"
          +"function source(h){if(!h)return '';"
          +"try{var u=new URL(h,location.href);"
          +"var v=u.searchParams.get('imgurl')||u.searchParams.get('mediaurl')||u.searchParams.get('iurl')||'';"
          +"if(v)return dec(v);"
          +"if(/\\.gif(?:[?#]|$)/i.test(h)&&u.hostname.indexOf('google.')<0)return h;"
          +"}catch(e){}return ''}"
          +"for(var i=0;i<imgs.length&&o.length<220;i++){var el=imgs[i],a=el.closest('a');"
          +"var t=el.currentSrc||el.getAttribute('data-src')||el.src||'';"
          +"if(!/^https?:/i.test(t))continue;"
          +"if((el.naturalWidth||el.width)<70||(el.naturalHeight||el.height)<55)continue;"
          +"if(/google\\.com\\/images\\/branding|gstatic\\.com\\/images\\/branding/i.test(t))continue;"
          +"var h=a?a.href:'',g=source(h)||source(t)||source(el.getAttribute('data-iurl')||'');"
          +"var title=(el.alt||(a&&a.getAttribute('aria-label'))||'GIF result').slice(0,90);"
          +"var k=(g||t).split('#')[0];if(seen[k])continue;seen[k]=1;"
          +"o.push({thumb:t,gif:g,title:title})}"
          +"var html=document.documentElement.innerHTML;var rx=/(https?:\\/\\/[^\\s\\\"'<>]{8,500}\\.gif(?:\\?[^\\s\\\"'<>]{0,150})?)/gi,m;"
          +"while(o.length<180&&(m=rx.exec(html))!==null){var g=dec(m[1]).replace(/\\\\u0026/g,'&');if(seen[g])continue;seen[g]=1;"
          +"o.push({thumb:g,gif:g,title:'Original GIF link'});}"
          +"return JSON.stringify({host:location.host,count:imgs.length,items:o})"
          +"})()";
        webView.evaluateJavascript(js,result->{
            if(epoch!=at)return;
            try{
                // evaluateJavascript returns a JSON-quoted string.
                String raw=new JSONArray("["+result+"]").optString(0,"{}");
                JSONObject obj=new JSONObject(raw);
                JSONArray arr=obj.optJSONArray("items");
                LinkedHashMap<String,GifStore.Item> cards=new LinkedHashMap<>();
                if(arr!=null) for(int i=0;i<arr.length();i++){
                    JSONObject item=arr.optJSONObject(i); if(item==null)continue;
                    String thumb=item.optString("thumb"),gif=item.optString("gif");
                    if(!(thumb.startsWith("https://")||thumb.startsWith("http://")))continue;
                    String identity=GifStore.key(gif.isEmpty()?thumb:gif);
                    cards.putIfAbsent(identity,new GifStore.Item(identity,thumb,gif,item.optString("title")));
                }
                listener.onCandidates(cards,obj.optInt("count"),obj.optString("host"));
                if(cards.isEmpty())listener.onStatus("No extractable GIF results • Google may have changed its page");
            }catch(Exception e){listener.onStatus("DOM parse failed: "+e.getClass().getSimpleName());}
        });
    }
    public void shutdown(){handler.removeCallbacksAndMessages(null); webView.stopLoading();webView.destroy();}
}
