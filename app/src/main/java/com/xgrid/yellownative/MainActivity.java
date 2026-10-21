package com.xgrid.yellownative;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** GIF-only app; Google retrieval is separate from on-device visual ranking. */
public final class MainActivity extends Activity {
    private static final int BG=0xff101210, PANEL=0xff1c211c, YELLOW=0xffffd137,
            TEXT=0xfff5f4ed, MUTED=0xffa8b0a3;
    private EditText queryBox;
    private TextView status, stats, anchorsView;
    private GridView grid;
    private FrameLayout webHost;
    private boolean browserOpen;
    private SearchAdapter adapter;
    private boolean thumbRefreshPending;
    private GifStore store;
    private VisualEngine visual;
    private SearchBridge search;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService inference=Executors.newSingleThreadExecutor();
    private final AtomicBoolean ranking=new AtomicBoolean(false);
    private final List<GifStore.Item> cards=new ArrayList<>();
    private final LinkedHashMap<String,GifStore.Item> targets=new LinkedHashMap<>();
    private final LinkedHashMap<String,GifStore.Item> negative=new LinkedHashMap<>();
    private String currentQuery="", lastStatus="Ready", googleHost="", lastOutcome="Not run";
    private int googleImages, googleCandidates, searches, research, findMore, analyzed, anchorAnalyses,
            candidateReady, candidateFailed, geometryFallbacks, uniqueSearchCards,
            rankMoves, rankTransactions, animatedLoaded, thumbReady, domScans;
    private long lastFindMs;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        store=new GifStore(this);
        render();
        search=new SearchBridge(this,new SearchBridge.Listener(){
            @Override public void onCandidates(Map<String,GifStore.Item> found,int raw,String host) {
                runOnUiThread(()->ingest(found,raw,host));
            }
            @Override public void onStatus(String message){runOnUiThread(()->setStatus(message));}
        });
        webHost=new FrameLayout(this);
        webHost.setClipChildren(true);
        webHost.setClipToPadding(true);
        webHost.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(1)));
        search.webView.setAlpha(.01f);
        webHost.addView(search.webView,new FrameLayout.LayoutParams(dp(420),dp(850)));
        ((LinearLayout)grid.getParent()).addView(webHost);
        inference.execute(()->{
            visual=new VisualEngine(getApplicationContext());
            runOnUiThread(()->setStatus("Visual engine: OpenCV "+(visual.openCvReady?"READY":"fallback")
                +" • MediaPipe "+(visual.poseReady?"READY":"fallback")));
        });
    }

    private void render(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);
        root.setPadding(dp(12),dp(8),dp(12),dp(4));
        TextView title=text("XSEARCH QUALITY   ·   YELLOW",18,YELLOW,true);
        root.addView(title);
        TextView note=text("GIF ONLY  •  Geometry 70%  /  Motion 30%  •  Manual ranking",11,MUTED,false);
        root.addView(note);

        LinearLayout searchLine=new LinearLayout(this);
        searchLine.setOrientation(LinearLayout.HORIZONTAL);
        queryBox=new EditText(this);
        queryBox.setSingleLine(true);queryBox.setTextColor(TEXT);queryBox.setHintTextColor(MUTED);
        queryBox.setHint("Search animated GIFs");queryBox.setTextSize(14);
        queryBox.setBackgroundColor(PANEL);queryBox.setPadding(dp(8),dp(4),dp(8),dp(4));
        LinearLayout.LayoutParams input=new LinearLayout.LayoutParams(0,dp(44),1);
        searchLine.addView(queryBox,input);
        Button go=button("SEARCH");go.setOnClickListener(v->startSearch(false));
        searchLine.addView(go,new LinearLayout.LayoutParams(dp(90),dp(44)));
        root.addView(searchLine,margin(0,10,0,5));

        LinearLayout actions=new LinearLayout(this);actions.setOrientation(LinearLayout.HORIZONTAL);
        Button find=button("FIND MORE"), researchButton=button("RE-SEARCH"), load=button("LOAD MORE");
        find.setOnClickListener(v->findMore());
        researchButton.setOnClickListener(v->startSearch(true));
        load.setOnClickListener(v->{ if(search!=null)search.continueLoad();});
        actions.addView(find,new LinearLayout.LayoutParams(0,dp(45),1));
        actions.addView(researchButton,new LinearLayout.LayoutParams(0,dp(45),1));
        actions.addView(load,new LinearLayout.LayoutParams(0,dp(45),1));
        root.addView(actions,margin(0,1,0,5));

        anchorsView=text("Image anchors: +0 / −0  •  Tap a GIF to choose",12,YELLOW,false);
        root.addView(anchorsView,margin(0,3,0,1));
        stats=text("Results: 0   |   Source: Google Images animated",11,MUTED,false);
        root.addView(stats,margin(0,2,0,2));
        status=text("Ready • enter a query",12,MUTED,false);
        root.addView(status,margin(0,3,0,5));

        LinearLayout tools=new LinearLayout(this);tools.setOrientation(LinearLayout.HORIZONTAL);
        Button diagnostic=button("DIAGNOSTIC"),clear=button("CLEAR"),browser=button("GOOGLE VIEW");
        diagnostic.setOnClickListener(v->diagnosticDialog());
        clear.setOnClickListener(v->{ targets.clear();negative.clear();updateStats();setStatus("Anchors cleared • page unchanged");});
        browser.setOnClickListener(v->toggleBrowser());
        tools.addView(diagnostic,new LinearLayout.LayoutParams(0,dp(40),1));
        tools.addView(clear,new LinearLayout.LayoutParams(0,dp(40),1));
        tools.addView(browser,new LinearLayout.LayoutParams(0,dp(40),1));
        root.addView(tools,margin(0,1,0,4));

        grid=new GridView(this);
        grid.setNumColumns(3);grid.setHorizontalSpacing(dp(5));grid.setVerticalSpacing(dp(7));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setBackgroundColor(BG);
        grid.setVerticalScrollBarEnabled(false);
        adapter=new SearchAdapter();grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent,view,pos,id)->openCandidate(cards.get(pos)));
        root.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
    }
    private static LinearLayout.LayoutParams margin(int l,int t,int r,int b){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);
        p.setMargins(l,t,r,b);return p;
    }
    private TextView text(String value,int sp,int color,boolean bold){
        TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private Button button(String label){
        Button b=new Button(this);b.setText(label);b.setTextColor(BG);b.setAllCaps(false);
        b.setTextSize(11);b.setPadding(1,1,1,1);b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(YELLOW));
        return b;
    }
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private void setStatus(String message){lastStatus=message;status.setText(message);}
    private void updateStats(){
        anchorsView.setText("Image anchors: +"+targets.size()+" / −"+negative.size()+"  •  Tap a GIF to choose");
        stats.setText("Results: "+cards.size()+"  •  GIFs verified: "+store.gifSuccess
            +"  •  visual matches ready: "+candidateReady);
        adapter.notifyDataSetChanged();
    }
    private void toggleBrowser() {
        if(webHost==null || search==null)return;
        browserOpen=!browserOpen;
        grid.setVisibility(browserOpen?View.GONE:View.VISIBLE);
        webHost.setLayoutParams(browserOpen
            ? new LinearLayout.LayoutParams(-1,0,1)
            : new LinearLayout.LayoutParams(-1,dp(1)));
        search.webView.setAlpha(browserOpen?1f:.01f);
        search.webView.setLayoutParams(browserOpen
            ? new FrameLayout.LayoutParams(-1,-1)
            : new FrameLayout.LayoutParams(dp(420),dp(850)));
        if(browserOpen)setStatus("Google view • allow consent or scroll results • tap GOOGLE VIEW again to return");
        else {search.scan();setStatus("Back to Yellow grid • refreshing extracted GIFs");}
    }

    private void startSearch(boolean existing) {
        if(search==null)return;
        String q=(existing?currentQuery:queryBox.getText().toString()).trim();
        if(q.isEmpty()){Toast.makeText(this,"Enter a search query",Toast.LENGTH_SHORT).show();return;}
        if(!existing){targets.clear();negative.clear();searches++;}else{research++;}
        currentQuery=q;
        if(!existing)queryBox.setText(q);
        cards.clear();adapter.notifyDataSetChanged();
        googleImages=0; googleCandidates=0;
        setStatus(existing?"RE-SEARCH • fetching current query":"SEARCH • fetching GIF results");
        search.search(q);updateStats();
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(queryBox.getWindowToken(),0);
    }
    private void ingest(Map<String,GifStore.Item> found,int raw,String host){
        googleImages=raw;googleHost=host;domScans++;
        LinkedHashMap<String,GifStore.Item> have=new LinkedHashMap<>();
        for(GifStore.Item item:cards)have.put(item.key,item);
        int growth=0;
        for(GifStore.Item item:found.values()) if(!have.containsKey(item.key)){
            have.put(item.key,item);growth++;
        }
        googleCandidates=found.size();uniqueSearchCards+=growth;
        if(growth>0){
            cards.clear();cards.addAll(have.values());updateStats();
            setStatus("Google GIF pool: "+cards.size()+" candidates • +"+growth+" • select anchors");
        } else if(found.isEmpty())setStatus("Google returned no usable images • try LOAD MORE");
    }
    private void openCandidate(GifStore.Item item) {
        String[] choices={"MORE LIKE THIS","LESS LIKE THIS","PLAY ORIGINAL GIF","REMOVE THIS ANCHOR"};
        new AlertDialog.Builder(this).setTitle(item.label==null?"GIF":item.label)
          .setItems(choices,(dialog,which)->{
            if(which==0 || which==1){
                if(which==0){negative.remove(item.key);targets.put(item.key,item);}
                else {targets.remove(item.key);negative.put(item.key,item);}
                setStatus("Anchor saved • grid stays still until FIND MORE");
                updateStats();
                fetchAndAnalyze(item,true,null);
            }else if(which==2)playGif(item);
            else {targets.remove(item.key);negative.remove(item.key);updateStats();}
          }).show();
    }
    private void fetchAndAnalyze(GifStore.Item item,boolean anchor,Runnable done){
        store.workers.submit(()->{
            try{
                analyzeItem(item,anchor);
                if(anchor)anchorAnalyses++;else candidateReady++;
            }catch(Exception ex){
                item.failure=ex.getMessage();candidateFailed++;
            }
            if(done!=null)runOnUiThread(done);
            else runOnUiThread(this::updateStats);
        });
    }
    private void analyzeItem(GifStore.Item item,boolean anchor)throws Exception{
        if(item.signature!=null&&item.signature.frames>0)return;
        if(!item.gifUrl.isEmpty()) {
            VisualEngine.Signature old=store.remembered(item.gifUrl);
            if(old!=null&&old.frames>0){item.signature=old;item.verifiedGif=old.gifVerified;return;}
        }
        // Fast candidate pass: no original-GIF download and no pose inference.
        // Anchors retain rich original-GIF analysis when the source is reachable.
        byte[] bytes=null;
        if(anchor && !item.gifUrl.isEmpty()){
            try{bytes=store.loadGif(item.gifUrl);item.verifiedGif=true;}
            catch(Exception e){item.failure=e.getMessage();}
        }
        Bitmap thumbnail=item.thumb!=null ? item.thumb : store.loadThumbnail(item.previewUrl);
        if(bytes==null && thumbnail==null)throw new Exception("GIF and thumbnail unavailable");
        if(visual==null)throw new Exception("Visual engine initializing");
        synchronized(visual){
            item.signature=anchor ? visual.analyze(bytes,thumbnail) : visual.analyzeFast(thumbnail);
        }
        if(item.signature.frames==0)throw new Exception("No decodable frames");
        if(!item.signature.gifVerified)geometryFallbacks++;
        analyzed++;
        if(!item.gifUrl.isEmpty()&&item.verifiedGif)store.remember(item.gifUrl,item.signature);
    }

    private void findMore() {
        if(targets.isEmpty()){setStatus("Choose MORE LIKE THIS on at least one GIF first");return;}
        if(!ranking.compareAndSet(false,true)){setStatus("FIND MORE already running");return;}
        findMore++;lastOutcome="Analyzing current GIF pool";
        setStatus("FIND MORE • visual analysis in progress, page stable");
        final long began=System.currentTimeMillis();
        inference.execute(()->{
            try {
                // Score spread candidates from across entire loaded page, not only the top.
                List<GifStore.Item> positives=new ArrayList<>(targets.values());
                List<GifStore.Item> negatives=new ArrayList<>(negative.values());
                for(GifStore.Item anchor:positives){
                    try{analyzeItem(anchor,true);}catch(Exception e){anchor.failure=e.getMessage();}
                }
                for(GifStore.Item anchor:negatives){
                    try{analyzeItem(anchor,true);}catch(Exception e){anchor.failure=e.getMessage();}
                }
                int target=positives.size()>=2?36:24;
                List<GifStore.Item> work=new ArrayList<>();
                synchronized(cards){
                    int size=cards.size();
                    for(int i=0;i<target&&size>0;i++){
                        int ix=Math.min(size-1,(int)Math.floor((i+.5)*size/target));
                        GifStore.Item item=cards.get(ix);
                        if(!work.contains(item))work.add(item);
                    }
                }
                CompletionService<GifStore.Item> queue=new ExecutorCompletionService<>(store.workers);
                for(GifStore.Item item:work)queue.submit(()->{
                    try{analyzeItem(item,false);}catch(Exception ex){item.failure=ex.getMessage();}
                    return item;
                });
                int ready=0,done=0;
                long deadline=System.currentTimeMillis()+15000;
                while(done<work.size()){
                    long left=deadline-System.currentTimeMillis();
                    if(left<=0)break;
                    Future<GifStore.Item> f=queue.poll(left,TimeUnit.MILLISECONDS);
                    if(f==null)break;
                    GifStore.Item item=f.get();done++;
                    if(item.signature!=null&&item.signature.frames>0)ready++;
                    if(done>=12&&ready>=8)break;
                }
                candidateReady=ready;
                if(ready>0) {
                    for(GifStore.Item item:cards){
                        double positive=0;int p=0;double negative=0;int n=0;
                        for(GifStore.Item anchor:positives){
                            if(anchor.signature!=null&&item.signature!=null){
                                positive+=VisualEngine.match(anchor.signature,item.signature).score;p++;
                            }
                        }
                        for(GifStore.Item anchor:negatives){
                            if(anchor.signature!=null&&item.signature!=null){
                                negative+=VisualEngine.match(anchor.signature,item.signature).score;n++;
                            }
                        }
                        item.rank=p>0?Math.max(0,positive/p-(n>0?.35*negative/n:0)):-1;
                    }
                    final int scoredReady=ready;
                    runOnUiThread(()->{
                        List<String> before=new ArrayList<>();
                        for(GifStore.Item i:cards)before.add(i.key);
                        cards.sort(Comparator.comparingDouble((GifStore.Item x)->x.rank).reversed());
                        int moves=0;
                        for(int i=0;i<cards.size();i++)if(!cards.get(i).key.equals(before.get(i)))moves++;
                        rankMoves=moves;rankTransactions++;
                        lastFindMs=System.currentTimeMillis()-began;
                        lastOutcome=moves>0?"Reshuffled "+moves+" cards":"Scored; order unchanged";
                        setStatus("FIND MORE • "+lastOutcome+" • "+scoredReady+" analyzed • "+lastFindMs+"ms");
                        ranking.set(false);adapter.notifyDataSetChanged();updateStats();
                    });
                }else runOnUiThread(()->{
                    lastFindMs=System.currentTimeMillis()-began;
                    lastOutcome="No usable candidates • unchanged";ranking.set(false);
                    setStatus(lastOutcome);updateStats();
                });
            }catch(Exception e){runOnUiThread(()->{
                ranking.set(false);lastOutcome="Rank failure: "+e.getClass().getSimpleName();
                setStatus(lastOutcome);
            });}
        });
    }

    private void playGif(GifStore.Item item){
        if(item.gifUrl.isEmpty()){setStatus("No original GIF URL • preview thumbnail only");return;}
        setStatus("Opening original GIF…");
        store.workers.submit(()->{
            try {
                byte[] bytes=store.loadGif(item.gifUrl);
                Drawable drawable=store.drawableFromGif(bytes);
                runOnUiThread(()->{
                    ImageView image=new ImageView(this);image.setImageDrawable(drawable);
                    image.setAdjustViewBounds(true);
                    image.setMinimumHeight(dp(180));image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    if(drawable instanceof AnimatedImageDrawable)((AnimatedImageDrawable)drawable).start();
                    new AlertDialog.Builder(this).setTitle("Original animated GIF")
                        .setView(image).setPositiveButton("Close",(d,w)->{
                            if(drawable instanceof AnimatedImageDrawable)((AnimatedImageDrawable)drawable).stop();
                        }).setOnCancelListener(d->{
                            if(drawable instanceof AnimatedImageDrawable)((AnimatedImageDrawable)drawable).stop();
                        }).show();
                    animatedLoaded++;
                });
            }catch(Exception e){runOnUiThread(()->setStatus("Original GIF unavailable: "+e.getMessage()));}
        });
    }

    private final class SearchAdapter extends BaseAdapter {
        private final class Tile {
            ImageView image;
            TextView caption;
            String boundKey;
        }
        @Override public int getCount(){return cards.size();}
        @Override public Object getItem(int pos){return cards.get(pos);}
        @Override public long getItemId(int pos){return pos;}
        @Override public View getView(int pos, View reuse, ViewGroup parent){
            GifStore.Item item=cards.get(pos);
            final LinearLayout box;
            final Tile tile;
            if(reuse instanceof LinearLayout && reuse.getTag() instanceof Tile){
                box=(LinearLayout)reuse;
                tile=(Tile)box.getTag();
            } else {
                box=new LinearLayout(MainActivity.this);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setBackgroundColor(PANEL);
                tile=new Tile();
                tile.image=new ImageView(MainActivity.this);
                tile.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                box.addView(tile.image,new LinearLayout.LayoutParams(-1,dp(118)));
                tile.caption=text("",10,YELLOW,false);
                tile.caption.setPadding(dp(4),dp(2),0,dp(2));
                box.addView(tile.caption,new LinearLayout.LayoutParams(-1,dp(27)));
                box.setTag(tile);
            }
            tile.boundKey=item.key;
            String flag=targets.containsKey(item.key)?"★ MORE ":(negative.containsKey(item.key)?"− LESS ":"");
            String type=item.verifiedGif?"GIF":(item.gifUrl.isEmpty()?"? GIF":"GIF link");
            tile.caption.setText(flag+type+(item.rank>0?String.format(Locale.ROOT,"  %.0f%%",item.rank*100):""));
            Bitmap cached=item.thumb!=null ? item.thumb : store.cachedThumbnail(item.previewUrl);
            if(cached!=null){
                tile.image.setImageBitmap(cached);
                item.thumb=cached;
            }else{
                tile.image.setImageResource(android.R.drawable.ic_menu_gallery);
                if(!item.loading && store.mayLoadThumbnail(item.previewUrl)){
                    item.loading=true;
                    store.thumbWorkers.execute(()->{
                        Bitmap loaded=store.loadThumbnail(item.previewUrl);
                        runOnUiThread(()->{
                            item.loading=false;
                            if(loaded!=null){
                                item.thumb=loaded;
                                thumbReady++;
                                if(item.key.equals(tile.boundKey))tile.image.setImageBitmap(loaded);
                                // Rebind any cells that were recycled while their bitmap was loading.
                                if(!thumbRefreshPending){
                                    thumbRefreshPending=true;
                                    ui.postDelayed(()->{
                                        thumbRefreshPending=false;
                                        adapter.notifyDataSetChanged();
                                    },110);
                                }
                            }
                        });
                    });
                }
            }
            return box;
        }
    }

    private String diagnostics(){
        StringBuilder s=new StringBuilder();
        s.append("XGrid Yellow Native v0.2.1 Fast Grid diagnostic\n");
        s.append("Time: ").append(new java.util.Date()).append("\n");
        s.append("Search query: ").append(currentQuery).append("\n");
        s.append("Google host: ").append(googleHost).append("\n");
        s.append("Google images / extracted / scans: ").append(googleImages).append(" / ")
          .append(googleCandidates).append(" / ").append(domScans).append("\n");
        s.append("Searches / re-search / find-more: ").append(searches).append(" / ")
          .append(research).append(" / ").append(findMore).append("\n");
        s.append("Current cards / lifetime new: ").append(cards.size()).append(" / ").append(uniqueSearchCards).append("\n");
        s.append("MORE / LESS anchors: ").append(targets.size()).append(" / ").append(negative.size()).append("\n");
        s.append("Media HTTP / GIF success / rejected / failures: ").append(store.httpRequests).append(" / ")
          .append(store.gifSuccess).append(" / ").append(store.gifRejected).append(" / ").append(store.failures).append("\n");
        s.append("Media downloaded bytes / cache hits: ").append(store.bytesDownloaded)
          .append(" / ").append(store.cacheHits).append("\n");
        s.append("Thumb attempts/success/fail/disk hits/cooldown skips: ")
            .append(store.thumbAttempts).append(" / ").append(store.thumbSuccess)
            .append(" / ").append(store.thumbFailures).append(" / ")
            .append(store.thumbDiskHits).append(" / ").append(store.thumbSkipped).append("\\n");
        s.append("Thumb cumulative network ms / memory hits: ")
            .append(store.thumbNetworkMs).append(" / ").append(store.thumbMemoryHits).append("\\n");
        s.append("Grid thumbnails ready / GIF viewer loads: ").append(thumbReady).append(" / ").append(animatedLoaded).append("\n");
        s.append("Visual OpenCV / pose: ").append(visual!=null&&visual.openCvReady).append(" / ")
          .append(visual!=null&&visual.poseReady).append("\n");
        s.append("Visual initialization note: ").append(visual==null?"still initializing":visual.initError).append("\n");
        s.append("Visual sampled / geometry fallback / anchors sampled: ").append(analyzed).append(" / ")
          .append(geometryFallbacks).append(" / ").append(anchorAnalyses).append("\n");
        s.append("FIND MORE candidates ready/fail: ").append(candidateReady).append(" / ").append(candidateFailed).append("\n");
        s.append("Rank transactions / nodes moved / time ms: ").append(rankTransactions).append(" / ")
          .append(rankMoves).append(" / ").append(lastFindMs).append("\n");
        s.append("FIND MORE outcome: ").append(lastOutcome).append("\n");
        s.append("Ranking 70% Geometry + 30% Motion if both valid; 100% geometry fallback\n");
        s.append("Motion uses 8 deterministic Movie frame times + OpenCV camera-corrected optical flow\n");
        s.append("Last status: ").append(lastStatus).append("\n");
        for(int i=0;i<Math.min(6,cards.size());i++){
            GifStore.Item item=cards.get(i);
            s.append("Result ").append(i+1).append(": ")
              .append(String.format(Locale.ROOT,"%.3f",item.rank)).append(" • ")
              .append(item.signature==null?"unanalyzed":
                 ("frames="+item.signature.frames+" unique="+item.signature.uniqueFrames+
                  " pose="+item.signature.poses+" motion="+item.signature.motionValid))
              .append("\n");
        }
        return s.toString();
    }
    private void diagnosticDialog(){
        String d=diagnostics();
        new AlertDialog.Builder(this).setTitle("Yellow diagnostic").setMessage(d)
            .setPositiveButton("COPY",(dialog,i)->{
                ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Yellow diagnostic",d));
                Toast.makeText(this,"Diagnostic copied",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("Close",null).show();
    }
    @Override protected void onDestroy(){
        if(search!=null)search.shutdown();
        if(store!=null)store.shutdown();
        inference.shutdownNow();
        if(visual!=null)visual.close();
        super.onDestroy();
    }
}
