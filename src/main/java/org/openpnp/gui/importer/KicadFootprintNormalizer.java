package org.openpnp.gui.importer;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.gui.importer.KicadFootprintParser.ParsedPad;
import org.openpnp.model.Footprint;
import org.openpnp.model.Footprint.Pad;
import org.openpnp.model.LengthUnit;

/** Converts KiCad coordinates once (Y inverted), then rotates a known pin 1 into OpenPnP lower-left. */
public final class KicadFootprintNormalizer {
    public static final class Result {
        public final Footprint footprint;
        public final double rotation;
        public final String pinOneName;
        public final String reason;
        public final boolean needsReview;
        public final List<String> warnings;
        Result(Footprint footprint, double rotation, String pinOneName, String reason,
                boolean needsReview, List<String> warnings) {
            this.footprint=footprint;
            this.rotation=rotation;
            this.pinOneName=pinOneName;
            this.reason=reason;
            this.needsReview=needsReview;
            this.warnings=warnings;
        }
    }
    private KicadFootprintNormalizer() {}

    public static Result normalize(KicadFootprintParser.Result parsed) {
        return normalize(parsed, 0);
    }
    public static Result normalize(KicadFootprintParser.Result parsed, double manualAdjustment) {
        ParsedPad pin = null;
        for (ParsedPad p : parsed.pads) { if ("1".equalsIgnoreCase(p.name)) { pin=p;
        break; } }
        if (pin == null) { for (ParsedPad p : parsed.pads) { if ("A1".equalsIgnoreCase(p.name)) { pin=p;
        break; } } }
        double centerX=0, centerY=0;
        if (parsed.hasBodyBounds) {
            centerX=(parsed.bodyMinX+parsed.bodyMaxX)/2;
            centerY=-(parsed.bodyMinY+parsed.bodyMaxY)/2;
        }
        else { if (!parsed.pads.isEmpty()) {
            for (ParsedPad p:parsed.pads){centerX+=p.x;
            centerY+=-p.y;}
            centerX/=parsed.pads.size();
            centerY/=parsed.pads.size();
        } }
        double automatic=0;
        boolean review=false;
        String reason;
        if (pin == null) { review=true;
        reason="未找到 1/A1 焊盘，未自动判断方向"; }
        else {
            double px=pin.x, py=-pin.y;
            double[] candidates={0,90,180,270};
            boolean found=false;
            for(double a:candidates){double[] rp=rotate(px,py,a), rc=rotate(centerX,centerY,a);
                if(rp[0]<rc[0]-1e-6&&rp[1]<rc[1]-1e-6){automatic=a;
                found=true;
                break;}}
            if(!found){review=true;
            reason="1 脚位于中心轴或几何完全对称，方向需人工确认";}
            else {reason="焊盘 "+pin.name+" 经 "+format(automatic)+"° 调整到 OpenPnP 左下象限";
                if("A1".equalsIgnoreCase(pin.name)){review=true;
                reason+="；阵列封装请复核 A1 方向";}}
        }
        double rotation=angle(automatic+manualAdjustment);
        Footprint fp=new Footprint();
        fp.setUnits(LengthUnit.Millimeters);
        for(ParsedPad source:parsed.pads){double[] xy=rotate(source.x,-source.y,rotation);
        Pad p=new Pad();
            p.setName(source.name);
            p.setX(xy[0]);
            p.setY(xy[1]);
            p.setWidth(source.width);
            p.setHeight(source.height);
            p.setRotation(angle(source.rotation+rotation));
            p.setRoundness(source.roundness);
            fp.addPad(p);}
        if(parsed.hasBodyBounds){double w=Math.abs(parsed.bodyMaxX-parsed.bodyMinX),h=Math.abs(parsed.bodyMaxY-parsed.bodyMinY);
            if(((int)Math.round(rotation/90))%2!=0){double t=w;
            w=h;
            h=t;}
            fp.setBodyWidth(w);
            fp.setBodyHeight(h);}
        List<String>warnings=new ArrayList<>(parsed.warnings);
        if(manualAdjustment!=0) { reason+="；用户另行调整 "+format(manualAdjustment)+"°"; }
        return new Result(fp,rotation,pin==null?null:pin.name,reason,review,warnings);
    }
    private static double[] rotate(double x,double y,double degrees){double r=Math.toRadians(degrees),c=Math.cos(r),s=Math.sin(r);
    return new double[]{c*x-s*y,s*x+c*y};}
    private static double angle(double a){a%=360;
    return a<0?a+360:a;}
    private static String format(double d){return String.format(java.util.Locale.ROOT,"%.0f",d);}
}
