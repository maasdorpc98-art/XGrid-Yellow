package com.xgrid.yellownative;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Movie;
import android.graphics.Paint;

import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker;
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Deterministic frame-time sampler with geometry, pose and camera-corrected
 * optical flow. Not a classification model. Only bona fide moving frames
 * contribute to Motion; still images cannot fabricate motion confidence.
 */
public final class VisualEngine implements AutoCloseable {
    public static final int SIZE = 80;
    private static final int SAMPLES = 8;
    private PoseLandmarker pose;
    public final boolean openCvReady;
    public final boolean poseReady;
    public String initError = "";

    public static final class Signature {
        public double[] geometry = new double[216];
        public double[] motion = new double[8];
        public boolean motionValid;
        public boolean gifVerified;
        public int uniqueFrames;
        public int poses;
        public int frames;
        public String method = "native-geometry-pose-flow-v1";
        public JSONObject json() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("geometry", arr(geometry)); obj.put("motion", arr(motion));
                obj.put("motionValid", motionValid); obj.put("gifVerified", gifVerified);
                obj.put("uniqueFrames", uniqueFrames); obj.put("poses", poses);
                obj.put("frames", frames); obj.put("method", method);
            } catch(Exception ignored) {}
            return obj;
        }
        static JSONArray arr(double[] vals) {
            JSONArray a = new JSONArray(); for(double v: vals) a.put(v); return a;
        }
        public static Signature fromJson(JSONObject obj) {
            if (obj == null || !"native-geometry-pose-flow-v1".equals(obj.optString("method"))) return null;
            Signature s = new Signature();
            JSONArray g=obj.optJSONArray("geometry"), m=obj.optJSONArray("motion");
            if(g==null || m==null || g.length()!=216 || m.length()!=8) return null;
            for(int i=0;i<216;i++) s.geometry[i]=g.optDouble(i);
            for(int i=0;i<8;i++) s.motion[i]=m.optDouble(i);
            s.motionValid=obj.optBoolean("motionValid");
            s.gifVerified=obj.optBoolean("gifVerified");
            s.uniqueFrames=obj.optInt("uniqueFrames");
            s.poses=obj.optInt("poses");
            s.frames=obj.optInt("frames");
            return s;
        }
    }

    public static final class Similarity {
        public final double geometry, motion, score;
        public final boolean motionUsed;
        Similarity(double g,double m,boolean use){
            geometry=g;motion=m;motionUsed=use;score=use?(.7*g+.3*m):g;
        }
    }

    public VisualEngine(Context context) {
        boolean cv=false;
        try { cv=OpenCVLoader.initDebug(); }
        catch(Throwable t) { initError="OpenCV: "+t.getClass().getSimpleName(); }
        openCvReady=cv;
        PoseLandmarker p=null;
        try {
            BaseOptions options=BaseOptions.builder().setModelAssetPath("pose_landmarker_full.task").build();
            PoseLandmarker.PoseLandmarkerOptions config = PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(options).setNumPoses(2)
                    .setRunningMode(RunningMode.IMAGE)
                    .setMinPoseDetectionConfidence(.5f)
                    .setMinPosePresenceConfidence(.5f)
                    .build();
            p=PoseLandmarker.createFromOptions(context, config);
        } catch(Throwable t){ initError+=" Pose: "+t.getClass().getSimpleName()+": "+t.getMessage(); }
        pose=p; poseReady=p!=null;
    }

    public static boolean isGif(byte[] bytes) {
        return bytes!=null && bytes.length>=6 && bytes[0]=='G' && bytes[1]=='I'
            && bytes[2]=='F' && bytes[3]=='8' && (bytes[4]=='7'||bytes[4]=='9')
            && bytes[5]=='a';
    }

    public Signature analyze(byte[] data, Bitmap fallback) {
        final Signature s=new Signature();
        List<Bitmap> frames=new ArrayList<>();
        if(isGif(data)) {
            Movie movie=Movie.decodeByteArray(data,0,data.length);
            if(movie!=null && movie.width()>0 && movie.height()>0) {
                s.gifVerified=true;
                int dur=movie.duration();
                if(dur<=0) dur=1000;
                for(int i=0;i<SAMPLES;i++){
                    Bitmap b=Bitmap.createBitmap(SIZE,SIZE,Bitmap.Config.ARGB_8888);
                    Canvas canvas=new Canvas(b);
                    canvas.drawColor(Color.BLACK);
                    movie.setTime((int)((long)dur*i/SAMPLES));
                    float scale=Math.min((float)SIZE/movie.width(), (float)SIZE/movie.height());
                    float w=scale*movie.width(), h=scale*movie.height();
                    canvas.translate((SIZE-w)/2f,(SIZE-h)/2f);
                    canvas.scale(scale,scale);
                    movie.draw(canvas,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));
                    frames.add(b);
                }
            }
        }
        if(frames.isEmpty() && fallback!=null) {
            frames.add(Bitmap.createScaledBitmap(fallback,SIZE,SIZE,true));
        }
        if(frames.isEmpty()) return s;
        s.frames=frames.size();
        long prev=-1;
        for(Bitmap b:frames){
            long hash=frameHash(b);
            if(prev!=hash) s.uniqueFrames++;
            prev=hash;
        }
        for(int i=0;i<frames.size();i++) {
            double[] a=geometry(frames.get(i));
            for(int j=0;j<s.geometry.length;j++) s.geometry[j]+=a[j]/frames.size();
            if(poseReady && (i==0 || i==frames.size()/2)) {
                try {
                    PoseLandmarkerResult result=pose.detect(new BitmapImageBuilder(frames.get(i)).build());
                    if(result.landmarks()!=null && !result.landmarks().isEmpty()){
                        s.poses=Math.max(s.poses,result.landmarks().size());
                        addPose(s.geometry, result.landmarks().get(0), 1.0/2.0);
                    }
                } catch(Throwable ignored){}
            }
        }
        if(s.gifVerified && s.uniqueFrames>=2 && frames.size()>1) {
            int n=0;double total=0;
            for(int i=1;i<frames.size();i++){
                double v = motion(frames.get(i-1),frames.get(i));
                s.motion[i]=v; total+=v; n++;
            }
            s.motion[0] = total/Math.max(1,n);
            s.motionValid=total > .02;  // genuine measurable motion after correction
        }
        for(Bitmap b:frames) b.recycle();
        return s;
    }

    private static long frameHash(Bitmap b){
        int[] p=new int[SIZE*SIZE]; b.getPixels(p,0,SIZE,0,0,SIZE,SIZE);
        long hash=1469598103934665603L;
        for(int y=0;y<SIZE;y+=4) for(int x=0;x<SIZE;x+=4) {
            int v=p[y*SIZE+x];
            hash=(hash ^ ((v>>16)&0xf8)) * 1099511628211L;
            hash=(hash ^ ((v>>8)&0xf8)) * 1099511628211L;
            hash=(hash ^ (v&0xf8)) * 1099511628211L;
        }
        return hash;
    }

    private double[] geometry(Bitmap b) {
        double[] f=new double[216];
        int[] pix=new int[SIZE*SIZE]; b.getPixels(pix,0,SIZE,0,0,SIZE,SIZE);
        for(int y=0;y<SIZE;y++){
            for(int x=0;x<SIZE;x++){
                int argb=pix[y*SIZE+x];
                double lum=(.2126*Color.red(argb)+.7152*Color.green(argb)+.0722*Color.blue(argb))/255.0;
                int bin=(y/8)*10+x/8;
                f[bin]+=lum/64.0;
                if(x>0) {
                    int o=pix[y*SIZE+x-1];
                    double ol=(.2126*Color.red(o)+.7152*Color.green(o)+.0722*Color.blue(o))/255.;
                    f[100+bin]+=Math.abs(lum-ol)/64.0;
                }
            }
        }
        // Foreground proxy: local contrast to border-derived background.
        double border=0;int count=0;
        for(int x=0;x<SIZE;x+=4){ border+=gray(pix[x]);border+=gray(pix[(SIZE-1)*SIZE+x]);count+=2;}
        for(int y=0;y<SIZE;y+=4){ border+=gray(pix[y*SIZE]);border+=gray(pix[y*SIZE+SIZE-1]);count+=2;}
        border/=Math.max(1,count);
        for(int y=0;y<SIZE;y++) for(int x=0;x<SIZE;x++){
            double delta=Math.min(1, Math.abs(gray(pix[y*SIZE+x])-border)*1.7);
            f[200+(y/20)*4+(x/20)]+=delta/400.0;
        }
        return f;
    }
    private static double gray(int c){return (.2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c))/255.;}
    private static void addPose(double[] features,List<NormalizedLandmark> p,double factor){
        // Pose-region coarse summary fits the existing geometry rather than
        // introducing a third ranking signal. The last 16 slots get landmark XY.
        int[] idx={11,12,23,24,25,26,27,28};
        for(int i=0;i<idx.length;i++) if(p.size()>idx[i]){
            NormalizedLandmark l=p.get(idx[i]);
            features[200+i*2]+=Math.max(0,Math.min(1,l.x()))*factor;
            features[200+i*2+1]+=Math.max(0,Math.min(1,l.y()))*factor;
        }
    }

    private double motion(Bitmap a,Bitmap b) {
        if(!openCvReady) {
            int[] p=new int[SIZE*SIZE],q=new int[SIZE*SIZE];
            a.getPixels(p,0,SIZE,0,0,SIZE,SIZE); b.getPixels(q,0,SIZE,0,0,SIZE,SIZE);
            double diff=0;
            for(int i=0;i<p.length;i+=4) diff+=Math.abs(gray(p[i])-gray(q[i]));
            return diff/(p.length/4);
        }
        Mat first=new Mat(),second=new Mat(),ga=new Mat(),gb=new Mat(),flow=new Mat();
        try {
            Utils.bitmapToMat(a,first); Utils.bitmapToMat(b,second);
            Imgproc.cvtColor(first,ga,Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(second,gb,Imgproc.COLOR_RGBA2GRAY);
            Video.calcOpticalFlowFarneback(ga,gb,flow,0.5,2,12,2,5,1.1,0);
            double[] vx=new double[100], vy=new double[100];int n=0;
            for(int y=4;y<SIZE;y+=8) for(int x=4;x<SIZE;x+=8){
                double[] v=flow.get(y,x); if(v!=null){vx[n]=v[0];vy[n]=v[1];n++;}
            }
            if(n==0) return 0;
            Arrays.sort(vx,0,n); Arrays.sort(vy,0,n);
            double cx=vx[n/2], cy=vy[n/2];
            double magnitude=0;
            // Optical flow is camera-corrected by subtracting median global flow.
            for(int y=4;y<SIZE;y+=8) for(int x=4;x<SIZE;x+=8){
                double[] v=flow.get(y,x); if(v!=null) magnitude+=Math.hypot(v[0]-cx,v[1]-cy);
            }
            return Math.min(1.0,magnitude/(n*8));
        } catch(Throwable ignored){return 0;}
        finally {first.release();second.release();ga.release();gb.release();flow.release();}
    }

    public static Similarity match(Signature a,Signature b){
        if(a==null || b==null || a.frames==0 || b.frames==0) return new Similarity(0,0,false);
        double d=0;
        for(int i=0;i<200;i++) {
            double x=a.geometry[i]-b.geometry[i];
            d+=(i<100?1:0.4)*x*x;
        }
        // Pose features should only influence geometry when both have poses.
        if(a.poses>0 && b.poses>0){
            for(int i=200;i<216;i++){double x=a.geometry[i]-b.geometry[i]; d+=x*x*.75;}
        }
        double geom=Math.exp(-3*Math.sqrt(d/200));
        boolean motion=a.motionValid && b.motionValid;
        double md=0;
        for(int i=0;i<8;i++){double x=a.motion[i]-b.motion[i];md+=x*x;}
        double mot=Math.exp(-4*Math.sqrt(md/8));
        return new Similarity(geom,mot,motion);
    }

    @Override public void close(){if(pose!=null){pose.close();pose=null;}}
}
