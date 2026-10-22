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
    public static final int SIZE = 160;
    private static final int SAMPLES = 10;
    private PoseLandmarker pose;
    public final boolean openCvReady;
    public final boolean poseReady;
    public String initError = "";

    public static final class Signature {
        public double[] geometry = new double[232];
        public double[] motion = new double[12];
        public boolean motionValid;
        public boolean gifVerified;
        public int uniqueFrames;
        public int poses;
        public int frames;
        public String method = "native-subject-pose-flow-v2";
        public final List<SubjectGeometry.Subject> subjects=new ArrayList<>();
        public final List<double[]> normalizedPoses=new ArrayList<>();
        public int poseFrames=0;
        public double subjectConfidence=0;
        public JSONObject json() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("geometry", arr(geometry)); obj.put("motion", arr(motion));
                obj.put("motionValid", motionValid); obj.put("gifVerified", gifVerified);
                obj.put("uniqueFrames", uniqueFrames); obj.put("poses", poses);
                obj.put("frames", frames); obj.put("method", method);
                obj.put("poseFrames",poseFrames);
                obj.put("subjectConfidence",subjectConfidence);
                JSONArray parts=new JSONArray(), posesOut=new JSONArray();
                for(SubjectGeometry.Subject sub:subjects)parts.put(sub.json());
                for(double[] ps:normalizedPoses)posesOut.put(arr(ps));
                obj.put("subjects",parts);
                obj.put("normalizedPoses",posesOut);
            } catch(Exception ignored) {}
            return obj;
        }
        static JSONArray arr(double[] vals) {
            JSONArray a = new JSONArray(); for(double v: vals) { try { a.put(Double.isFinite(v) ? v : 0); } catch (Exception ignored) {} } return a;
        }
        public static Signature fromJson(JSONObject obj) {
            if (obj == null || !"native-subject-pose-flow-v2".equals(obj.optString("method"))) return null;
            Signature s = new Signature();
            JSONArray g=obj.optJSONArray("geometry"), m=obj.optJSONArray("motion");
            if(g==null || m==null || g.length()!=232 || m.length()!=12) return null;
            for(int i=0;i<232;i++) s.geometry[i]=g.optDouble(i);
            for(int i=0;i<12;i++) s.motion[i]=m.optDouble(i);
            s.motionValid=obj.optBoolean("motionValid");
            s.gifVerified=obj.optBoolean("gifVerified");
            s.uniqueFrames=obj.optInt("uniqueFrames");
            s.poses=obj.optInt("poses");
            s.frames=obj.optInt("frames");
            s.poseFrames=obj.optInt("poseFrames");
            s.subjectConfidence=obj.optDouble("subjectConfidence");
            JSONArray ps=obj.optJSONArray("subjects");
            if(ps!=null)for(int i=0;i<Math.min(9,ps.length());i++){
                JSONObject v=ps.optJSONObject(i);
                JSONArray f=v==null?null:v.optJSONArray("features");
                if(f==null||f.length()!=70)continue;
                SubjectGeometry.Subject sub=new SubjectGeometry.Subject();
                for(int j=0;j<70;j++)sub.features[j]=f.optDouble(j);
                sub.confidence=v.optDouble("confidence");
                sub.source=v.optString("source");
                s.subjects.add(sub);
            }
            JSONArray poses=obj.optJSONArray("normalizedPoses");
            if(poses!=null)for(int i=0;i<Math.min(12,poses.length());i++){
                JSONArray f=poses.optJSONArray(i);
                if(f==null||f.length()!=16)continue;
                double[] p=new double[16];
                for(int j=0;j<16;j++)p[j]=f.optDouble(j);
                s.normalizedPoses.add(p);
            }
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
                    .setBaseOptions(options).setNumPoses(3)
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

    /** Stage-one thumbnail-only geometry, inexpensive and deterministic. */
    public Signature analyzeFast(Bitmap bitmap) {
        Signature sig=new Signature();
        if(bitmap==null)return sig;
        Bitmap frame=Bitmap.createScaledBitmap(bitmap,SIZE,SIZE,true);
        sig.geometry=geometry(frame);
        addSubjects(sig,frame);
        addPoses(sig,frame);
        sig.frames=1;
        sig.uniqueFrames=1;
        sig.gifVerified=false;
        sig.motionValid=false;
        if(frame!=bitmap)frame.recycle();
        return sig;
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
            if(i==0 || i==frames.size()/2 || i==frames.size()-1) {
                addSubjects(s,frames.get(i));
            }
            if(i==0 || i==frames.size()/3 || i==2*frames.size()/3 || i==frames.size()-1){
                addPoses(s,frames.get(i));
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


    private void addPoses(Signature sig,Bitmap bitmap){
        if(!poseReady||pose==null)return;
        try {
            PoseLandmarkerResult result=pose.detect(new BitmapImageBuilder(bitmap).build());
            if(result.landmarks()==null)return;
            sig.poseFrames++;
            sig.poses=Math.max(sig.poses,result.landmarks().size());
            for(List<NormalizedLandmark> human:result.landmarks()){
                if(human.size()<29)continue;
                double[] v=normalizedPose(human);
                if(v!=null && sig.normalizedPoses.size()<12)sig.normalizedPoses.add(v);
            }
        }catch(Throwable ignored){}
    }
    private static double[] normalizedPose(List<NormalizedLandmark> pose){
        int[] joints={11,12,23,24,25,26,27,28};
        double xmin=1,ymin=1,xmax=0,ymax=0;
        for(int k:joints){
            if(pose.size()<=k)return null;
            NormalizedLandmark p=pose.get(k);
            if(!Float.isFinite(p.x())||!Float.isFinite(p.y()))return null;
            xmin=Math.min(xmin,p.x());xmax=Math.max(xmax,p.x());
            ymin=Math.min(ymin,p.y());ymax=Math.max(ymax,p.y());
        }
        double scale=Math.max(.10,Math.max(xmax-xmin,ymax-ymin));
        double cx=(xmin+xmax)/2.,cy=(ymin+ymax)/2.;
        double[] out=new double[16];
        for(int i=0;i<joints.length;i++){
            NormalizedLandmark p=pose.get(joints[i]);
            out[2*i]=Math.max(-1,Math.min(1,(p.x()-cx)/scale));
            out[2*i+1]=Math.max(-1,Math.min(1,(p.y()-cy)/scale));
        }
        return out;
    }
    private static void addSubjects(Signature sig,Bitmap bitmap){
        if(sig.subjects.size()>=9)return;
        for(SubjectGeometry.Subject part:SubjectGeometry.analyze(bitmap)){
            if(sig.subjects.size()>=9)break;
            sig.subjects.add(part);
            sig.subjectConfidence=Math.max(sig.subjectConfidence,part.confidence);
        }
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
        double[] f=new double[232];
        int[] pix=new int[SIZE*SIZE]; b.getPixels(pix,0,SIZE,0,0,SIZE,SIZE);
        for(int y=0;y<SIZE;y++){
            for(int x=0;x<SIZE;x++){
                int argb=pix[y*SIZE+x];
                double lum=(.2126*Color.red(argb)+.7152*Color.green(argb)+.0722*Color.blue(argb))/255.0;
                int bin=(y*10/SIZE)*10+(x*10/SIZE);
                f[bin]+=lum/(SIZE*SIZE/100.0);
                if(x>0) {
                    int o=pix[y*SIZE+x-1];
                    double ol=(.2126*Color.red(o)+.7152*Color.green(o)+.0722*Color.blue(o))/255.;
                    f[100+bin]+=Math.abs(lum-ol)/(SIZE*SIZE/100.0);
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
            f[200+(y*4/SIZE)*4+(x*4/SIZE)]+=delta/(SIZE*SIZE/16.0);
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
            features[216+i*2]+=Math.max(0,Math.min(1,l.x()))*factor;
            features[216+i*2+1]+=Math.max(0,Math.min(1,l.y()))*factor;
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
        Mat first=new Mat(),second=new Mat(),ga=new Mat(),gb=new Mat(),flow=new Mat(),
            smallA=new Mat(),smallB=new Mat();
        try {
            Utils.bitmapToMat(a,first); Utils.bitmapToMat(b,second);
            Imgproc.cvtColor(first,ga,Imgproc.COLOR_RGBA2GRAY);
            Imgproc.cvtColor(second,gb,Imgproc.COLOR_RGBA2GRAY);
            Imgproc.resize(ga,smallA,new Size(80,80));
            Imgproc.resize(gb,smallB,new Size(80,80));
            Video.calcOpticalFlowFarneback(smallA,smallB,flow,0.5,2,12,2,5,1.1,0);
            double[] vx=new double[100], vy=new double[100];int n=0;
            for(int y=4;y<80;y+=8) for(int x=4;x<80;x+=8){
                double[] v=flow.get(y,x); if(v!=null){vx[n]=v[0];vy[n]=v[1];n++;}
            }
            if(n==0) return 0;
            Arrays.sort(vx,0,n); Arrays.sort(vy,0,n);
            double cx=vx[n/2], cy=vy[n/2];
            double magnitude=0;
            // Optical flow is camera-corrected by subtracting median global flow.
            for(int y=4;y<80;y+=8) for(int x=4;x<80;x+=8){
                double[] v=flow.get(y,x); if(v!=null) magnitude+=Math.hypot(v[0]-cx,v[1]-cy);
            }
            return Math.min(1.0,magnitude/(n*8));
        } catch(Throwable ignored){return 0;}
        finally {first.release();second.release();ga.release();gb.release();flow.release();smallA.release();smallB.release();}
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
            for(int i=216;i<232;i++){double x=a.geometry[i]-b.geometry[i]; d+=x*x*.75;}
        }
        double geom=Math.exp(-3*Math.sqrt(d/200));
        boolean motion=a.motionValid && b.motionValid;
        double md=0;
        for(int i=0;i<12;i++){double x=a.motion[i]-b.motion[i];md+=x*x;}
        double mot=Math.exp(-4*Math.sqrt(md/12));
        return new Similarity(geom,mot,motion);
    }

    @Override public void close(){if(pose!=null){pose.close();pose=null;}}
}
