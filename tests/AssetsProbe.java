import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import com.alpha269.hyperisland.island.HyperOsAssets;
import com.alpha269.hyperisland.island.HyperOsResources;
import java.io.File;
import java.lang.reflect.Method;

/** Reads the exact staged resource carriers in an owned Android process. */
public final class AssetsProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if (Typeface.DEFAULT==null) {
            Method fonts=Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
            fonts.setAccessible(true); fonts.invoke(null);
        }
        Class<?> activity=Class.forName("android.app.ActivityThread");
        Object thread=activity.getMethod("systemMain").invoke(null);
        Context base=(Context)activity.getMethod("getSystemContext").invoke(thread);
        File module=new File(args[0]),cache=new File(args[1]),fonts=new File(args[2]);
        int checks=0;
        for (String[] carrier : new String[][] {
            {"plugin-res.apk","miui.systemui.plugin","dynamic_big_island_template_standard","focus_notification_template_standard"},
            {"call-res.apk","com.android.incallui","call_focus_incoming_notification_view","call_focus_active_notification_view"},
            {"systemui-res.apk","com.android.systemui","miui_media_session_island","miui_media_session_island_dummy"}}) {
            File path=HyperOsAssets.asset(module,cache,carrier[0]);
            HyperOsResources resources=new HyperOsResources(base,path.getPath(),carrier[1],fonts,true);
            for(int i=2;i<carrier.length;i++) {
                int id=resources.id("layout",carrier[i]);
                try(android.content.res.XmlResourceParser parser=resources.getResources().getLayout(id)) {
                    while(parser.next()!=org.xmlpull.v1.XmlPullParser.START_TAG) {}
                    if(parser.getName()==null)throw new AssertionError("Empty donor layout");
                    checks++;
                }
            }
            if(!HyperOsAssets.asset(module,cache,carrier[0]).equals(path))throw new AssertionError("Cache reuse");
            checks++;
        }
        for(String relative:new String[]{"fonts/MiSansVF.ttf","fonts/MiSansCondensed2T.ttf",
            "material_blend.sksl","smooth_corner_mips.bin"}) {
            if(HyperOsAssets.asset(module,cache,relative).length()==0)throw new AssertionError("Empty asset");
            checks++;
        }
        boolean rejected=false;
        try { HyperOsAssets.asset(module,cache,"../outside"); } catch(java.io.IOException expected){rejected=true;}
        if(!rejected)throw new AssertionError("Asset traversal accepted");
        System.out.println("Android resource-carrier/cache checks passed: "+(checks+1));
        System.exit(0);
    }
}
