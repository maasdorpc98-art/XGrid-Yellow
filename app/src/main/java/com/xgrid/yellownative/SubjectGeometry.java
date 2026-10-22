package com.xgrid.yellownative;

import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Foreground-normalized subject descriptors. This method is intentionally
 * conservative: segmentation is trusted only for near-uniform border
 * backgrounds (illustrations/studio GIFs). Complex backgrounds yield a
 * lower-confidence whole-image descriptor rather than fabricated anatomy.
 *
 * Independent horizontal components make two-pose exercise illustrations
 * comparable to an illustration containing only one of those subjects.
 */
public final class SubjectGeometry {
    private static final int W=160, H=160, DIM=70;
    private SubjectGeometry(){}

    public static final class Subject {
        public double[] features=new double[DIM];
        public double confidence;
        public String source="silhouette";
        public double left,top,right,bottom;
        public JSONObject json(){
            JSONObject o=new JSONObject();
            try{
                JSONArray values=new JSONArray();
                for(double v:features)values.put(Double.isFinite(v)?v:0);
                o.put("features",values);
                o.put("confidence",confidence);
                o.put("source",source);
                o.put("bbox",new JSONArray().put(left).put(top).put(right).put(bottom));
            }catch(Exception ignored){}
            return o;
        }
    }

    public static List<Subject> analyze(Bitmap original){
        ArrayList<Subject> results=new ArrayList<>();
        if(original==null)return results;
        Bitmap img=Bitmap.createScaledBitmap(original,W,H,true);
        try{
            int[] pixels=new int[W*H];
            img.getPixels(pixels,0,W,0,0,W,H);
            double[] bg=borderMean(pixels);
            double borderDeviation=borderVariation(pixels,bg);
            boolean uniform=borderDeviation<.115;
            boolean[] mask=new boolean[W*H];
            int fg=0;
            double threshold=Math.max(.13,2.5*borderDeviation);
            if(uniform){
                for(int i=0;i<pixels.length;i++){
                    double distance=colorDistance(pixels[i],bg);
                    boolean isFg=distance>threshold;
                    mask[i]=isFg;if(isFg)fg++;
                }
                double coverage=fg/(double)mask.length;
                // Reject full-frame masks and essentially blank backgrounds.
                uniform=coverage>.035 && coverage<.76;
            }
            if(!uniform){
                results.add(describeWholeFrame(pixels));
                return results;
            }

            int[] xs=new int[W];
            for(int y=0;y<H;y++)for(int x=0;x<W;x++)if(mask[y*W+x])xs[x]++;
            // A subject must persist across multiple rows. Isolated decoration
            // and labels are not treated as distinct people.
            int[] smooth=new int[W];
            for(int x=0;x<W;x++){
                int sum=0;
                for(int k=Math.max(0,x-2);k<=Math.min(W-1,x+2);k++)sum+=xs[k];
                smooth[x]=sum;
            }
            List<int[]> ranges=new ArrayList<>();
            int begin=-1,previous=-1;
            for(int x=0;x<W;x++){
                if(smooth[x]>=6){
                    if(begin<0)begin=x;
                    previous=x;
                }else if(begin>=0 && x-previous>5){
                    ranges.add(new int[]{begin,previous+1});
                    begin=-1;
                }
            }
            if(begin>=0)ranges.add(new int[]{begin,W});

            List<int[]> merged=new ArrayList<>();
            for(int[] r:ranges){
                if(!merged.isEmpty() && r[0]-merged.get(merged.size()-1)[1]<7)
                    merged.get(merged.size()-1)[1]=r[1];
                else merged.add(r);
            }
            List<int[]> substantial=new ArrayList<>();
            for(int[] r:merged){
                int amount=0;
                for(int y=0;y<H;y++)for(int x=r[0];x<r[1];x++)if(mask[y*W+x])amount++;
                if(amount>=Math.max(135,fg*.09) && r[1]-r[0]>=12) substantial.add(r);
            }
            // Fallback to one foreground subject when splitting is unreliable.
            if(substantial.isEmpty() || substantial.size()>4)
                substantial=Arrays.asList(new int[]{0,W});
            for(int[] range:substantial){
                Subject subject=describeMask(pixels,mask,range[0],range[1],bg,borderDeviation);
                if(subject!=null)results.add(subject);
            }
            if(results.isEmpty())results.add(describeWholeFrame(pixels));
            results.sort(Comparator.comparingDouble((Subject s)->s.confidence).reversed());
            return results.subList(0,Math.min(3,results.size()));
        }finally{
            if(img!=original)img.recycle();
        }
    }

    private static Subject describeWholeFrame(int[] pix){
        Subject s=new Subject();
        s.confidence=.12;s.source="whole-image-low-confidence";
        fill(pix,null,0,0,W,H,s);
        return s;
    }

    private static Subject describeMask(int[] pix,boolean[] mask,int x0,int x1,double[] bg,double deviation){
        int left=W,top=H,right=0,bottom=0,total=0;
        for(int y=0;y<H;y++)for(int x=x0;x<x1;x++)if(mask[y*W+x]){
            total++;left=Math.min(left,x);right=Math.max(right,x);
            top=Math.min(top,y);bottom=Math.max(bottom,y);
        }
        if(total<110||right-left<7||bottom-top<10)return null;
        Subject s=new Subject();
        int width=right-left+1,height=bottom-top+1;
        double fraction=total/(double)(width*height);
        double contrast=Math.max(.0,1-Math.min(1,deviation/.115));
        s.confidence=Math.max(.1,Math.min(.98,.4+.45*contrast+.13*Math.min(1,fraction/.30)));
        s.source="foreground-subject";
        fill(pix,mask,left,top,right+1,bottom+1,s);
        return s;
    }

    private static void fill(int[] pix,boolean[] mask,int x0,int y0,int x1,int y1,Subject out){
        int width=Math.max(1,x1-x0),height=Math.max(1,y1-y0);
        int[] pixelsIn=new int[48], totalIn=new int[48];
        int included=0;double rr=0,gg=0,bb=0,edge=0;
        double centerX=0,centerY=0;
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
            int id=y*W+x;
            int binY=Math.min(7,(y-y0)*8/height),binX=Math.min(5,(x-x0)*6/width);
            int bin=binY*6+binX;totalIn[bin]++;
            boolean inside=mask==null||mask[id];
            if(!inside)continue;
            pixelsIn[bin]++;included++;
            int c=pix[id];rr+=Color.red(c)/255.;gg+=Color.green(c)/255.;bb+=Color.blue(c)/255.;
            centerX+=(x-x0)/(double)width;
            centerY+=(y-y0)/(double)height;
            if(x+1<x1){
                int other=pix[id+1];
                edge+=Math.abs(gray(c)-gray(other));
            }
        }
        for(int i=0;i<48;i++)out.features[i]=pixelsIn[i]/(double)Math.max(1,totalIn[i]);
        for(int y=0;y<8;y++) for(int x=0;x<6;x++)out.features[48+y]+=out.features[y*6+x]/6;
        for(int x=0;x<6;x++)for(int y=0;y<8;y++)out.features[56+x]+=out.features[y*6+x]/8;
        out.features[62]=rr/Math.max(1,included);
        out.features[63]=gg/Math.max(1,included);
        out.features[64]=bb/Math.max(1,included);
        out.features[65]=Math.min(1,Math.max(0,Math.log(width/(double)height)+1)/2);
        out.features[66]=included/(double)(width*height);
        out.features[67]=centerX/Math.max(1,included);
        out.features[68]=centerY/Math.max(1,included);
        out.features[69]=edge/Math.max(1,included);
        out.left=x0/(double)W;out.top=y0/(double)H;
        out.right=x1/(double)W;out.bottom=y1/(double)H;
    }

    private static double[] borderMean(int[] p){
        double[] bg=new double[3];int n=0;
        for(int x=0;x<W;x+=5){
            add(bg,p[x]);add(bg,p[(H-1)*W+x]);n+=2;
        }
        for(int y=0;y<H;y+=5){
            add(bg,p[y*W]);add(bg,p[y*W+W-1]);n+=2;
        }
        for(int i=0;i<3;i++)bg[i]/=Math.max(1,n);
        return bg;
    }
    private static double borderVariation(int[] p,double[] bg){
        double total=0;int n=0;
        for(int x=0;x<W;x+=5){
            total+=sqDistance(p[x],bg)+sqDistance(p[(H-1)*W+x],bg);n+=2;
        }
        for(int y=0;y<H;y+=5){
            total+=sqDistance(p[y*W],bg)+sqDistance(p[y*W+W-1],bg);n+=2;
        }
        return Math.sqrt(total/(Math.max(1,n)*3));
    }
    private static double gray(int c){return (.2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c))/255.;}
    private static double sqDistance(int c,double[] bg){
        double r=Color.red(c)/255.-bg[0],g=Color.green(c)/255.-bg[1],b=Color.blue(c)/255.-bg[2];
        return r*r+g*g+b*b;
    }
    private static double colorDistance(int c,double[] bg){return Math.sqrt(sqDistance(c,bg)/3);}
    private static void add(double[] mean,int c){
        mean[0]+=Color.red(c)/255.;
        mean[1]+=Color.green(c)/255.;
        mean[2]+=Color.blue(c)/255.;
    }
}
