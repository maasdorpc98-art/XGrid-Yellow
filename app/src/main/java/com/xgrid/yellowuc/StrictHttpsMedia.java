package com.xgrid.yellowuc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.util.Locale;

/** Bounded, HTTPS-only media fetch. Not a general URL/proxy interface. */
public final class StrictHttpsMedia {
    private StrictHttpsMedia() {}
    public static final int GIF_CAP = 15_000_000, PREVIEW_CAP = 1_800_000;
    public static boolean permitted(String s) {
        if(s==null || s.length()<12 || s.length()>1600)return false;
        try {
            URI u=new URI(s);
            if(!"https".equalsIgnoreCase(u.getScheme()) || u.getUserInfo()!=null || u.getFragment()!=null || u.getRawAuthority()==null)return false;
            if(u.getPort()!=-1 && u.getPort()!=443)return false;
            String host=u.getHost(); if(host==null)return false;
            host=host.toLowerCase(Locale.ROOT);
            if(host.length()<4 || host.endsWith(".") || host.contains("%") || host.indexOf(':')>=0 || host.matches("[0-9.]+"))return false;
            if(host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal") || host.endsWith(".test") || host.endsWith(".invalid"))return false;
            return true;
        } catch(Exception e){return false;}
    }
    private static boolean publicAddress(InetAddress address) {
        byte[] ip=address.getAddress();
        if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())return false;
        if(ip.length==4){int a=ip[0]&255,b=ip[1]&255;
            return a!=0 && a!=10 && a!=127 && a<224 && !(a==100&&b>=64&&b<=127) && !(a==169&&b==254) && !(a==172&&b>=16&&b<=31) && !(a==192&&b==168) && !(a==192&&b==0) && !(a==198&&(b==18||b==19)) && !(a==203&&b==0) && !(a==192&&b==0) && !(a==192&&b==88) && !(a==198&&b==51);
        }
        // Block ULA fc00::/7 and IPv4 mapped addresses; reject unspecified loopback above.
        if(ip.length==16){int a=ip[0]&255;return (a&0xfe)!=0xfc && !(ip[0]==0 && ip[1]==0 && ip[10]==-1 && ip[11]==-1);}
        return false;
    }
    public static byte[] fetch(String rawUrl,int cap) throws Exception {
        if(cap<100 || cap>GIF_CAP)throw new IllegalArgumentException("invalid media cap");
        String url=rawUrl;
        for(int redirect=0;redirect<4;redirect++){
            if(!permitted(url))throw new SecurityException("blocked URL");
            URI u=new URI(url);
            for(InetAddress ip:InetAddress.getAllByName(u.getHost()))if(!publicAddress(ip))throw new SecurityException("private/reserved host IP");
            HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();
            c.setConnectTimeout(3800);c.setReadTimeout(5600);c.setInstanceFollowRedirects(false);
            c.setUseCaches(true);c.setRequestProperty("Accept","image/gif,image/webp,image/jpeg,image/png,image/*;q=.8");
            c.setRequestProperty("User-Agent","Mozilla/5.0 (Android; XGrid Yellow UC)");
            try {
                int status=c.getResponseCode();
                if(status==301||status==302||status==303||status==307||status==308){
                    String next=c.getHeaderField("Location");
                    if(next==null)throw new IllegalStateException("redirect without location");
                    url=new URL(new URL(url),next).toString();continue;
                }
                if(status<200 || status>=300)throw new IllegalStateException("HTTP "+status);
                if(c.getContentLengthLong()>cap)throw new IllegalStateException("oversized content");
                try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    byte[] buf=new byte[8192];int n,total=0;
                    while((n=in.read(buf))!=-1){total+=n;if(total>cap)throw new IllegalStateException("media exceeded byte limit");out.write(buf,0,n);}
                    return out.toByteArray();
                }
            } finally {c.disconnect();}
        }
        throw new SecurityException("redirect depth exceeded");
    }
    public static boolean isGif(byte[] bytes){return bytes!=null&&bytes.length>=10&&bytes[0]=='G'&&bytes[1]=='I'&&bytes[2]=='F'&&bytes[3]=='8'&&(bytes[4]=='7'||bytes[4]=='9')&&bytes[5]=='a';}
    public static Bitmap stillImage(String url)throws Exception{
        byte[] bytes=fetch(url,PREVIEW_CAP);
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
        if(bounds.outWidth<1 || bounds.outHeight<1 || (long)bounds.outWidth*bounds.outHeight>16_000_000L)throw new IllegalArgumentException("invalid/oversized bitmap");
        BitmapFactory.Options opts=new BitmapFactory.Options();opts.inSampleSize=Math.max(1,Math.max(bounds.outWidth,bounds.outHeight)/640);
        return BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
    }
}