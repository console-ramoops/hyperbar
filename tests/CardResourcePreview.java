import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperOsResources;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/** Renders exact call layout/vector resources with explicitly synthetic data. */
public final class CardResourcePreview {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        if (Typeface.DEFAULT == null) {
            Method fonts = Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");
            fonts.setAccessible(true); fonts.invoke(null);
        }
        Class<?> activity = Class.forName("android.app.ActivityThread");
        Object thread = activity.getMethod("systemMain").invoke(null);
        Context base = (Context)activity.getMethod("getSystemContext").invoke(thread);
        HyperOsResources source = new HyperOsResources(base,args[0],
            "com.android.incallui",new File(args[1]),true);
        float dp = source.getResources().getDisplayMetrics().density;
        int width = Math.round(364*dp);
        FrameLayout parent = new FrameLayout(source);
        View active = source.inflate("call_focus_active_notification_view",parent);
        View incoming = source.inflate("call_focus_incoming_notification_view",parent);
        bindText(source,active,"caller_active_name","Alex",18,0xffffffff);
        bindText(source,active,"caller_active_time","01:23",14,0x80ffffff);
        hide(source,active,"caller_active_time_layout_aod");
        bindIcon(source,active,"audio","ic_focus_speaker_dark");
        bindIcon(source,active,"reject_active_call","ic_focus_reject_dark");
        bindText(source,incoming,"caller_name","Alex",18,0xffffffff);
        bindText(source,incoming,"caller_info","Incoming call",14,0x80ffffff);
        bindIcon(source,incoming,"reject_call","ic_focus_reject_dark");
        bindIcon(source,incoming,"answer_call","ic_focus_answer_dark");
        for (View card : new View[]{active,incoming}) {
            margin(source,card,"caller_layout",true,14*dp);
            margin(source,card,"caller_layout",false,10*dp);
            margin(source,card,"answer_layout",false,14*dp);
            int height = card.getLayoutParams().height;
            card.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(0,height),height >= 0
                    ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED));
            card.layout(0,0,width,card.getMeasuredHeight());
        }
        int border = Math.round(14*dp), gap = Math.round(18*dp);
        Bitmap bitmap = Bitmap.createBitmap(width+2*border,
            active.getHeight()+incoming.getHeight()+2*border+gap,Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(0xff202126);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG); paint.setColor(0xff000000);
        int top = border;
        for (View card : new View[]{incoming,active}) {
            canvas.drawRoundRect(new RectF(border,top,border+width,top+card.getHeight()),
                Math.min(card.getHeight()/2f,30*dp),Math.min(card.getHeight()/2f,30*dp),paint);
            int save = canvas.save(); canvas.translate(border,top); card.draw(canvas);
            canvas.restoreToCount(save); top += card.getHeight()+gap;
        }
        File destination = new File(args[2]);
        destination.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
                throw new AssertionError("PNG encode failed");
        }
        System.out.println("Original call XML/vectors rendered with sample data, density="
            +source.getResources().getDisplayMetrics().densityDpi
            +", incomingHeight="+incoming.getHeight()+", activeHeight="+active.getHeight());
        bitmap.recycle();
        System.exit(0);
    }
    private static void bindText(HyperOsResources source,View card,String name,
        String text,float size,int color) {
        TextView view = card.findViewById(source.id("id",name));
        view.setText(text); view.setTextSize(TypedValue.COMPLEX_UNIT_DIP,size);
        view.setTextColor(color); view.setVisibility(View.VISIBLE);
    }
    private static void bindIcon(HyperOsResources source,View card,String name,String icon) {
        ImageView view = card.findViewById(source.id("id",name));
        view.setImageDrawable(source.getResources().getDrawable(source.id("drawable",icon),source.getTheme()));
        view.setVisibility(View.VISIBLE);
    }
    private static void hide(HyperOsResources source,View card,String name) {
        card.findViewById(source.id("id",name)).setVisibility(View.GONE);
    }
    private static void margin(HyperOsResources source,View card,String name,
        boolean start,float pixels) {
        View view = card.findViewById(source.id("id",name));
        ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams)view.getLayoutParams();
        if (start) params.setMarginStart(Math.round(pixels));
        else params.setMarginEnd(Math.round(pixels));
        view.setLayoutParams(params);
    }
}
