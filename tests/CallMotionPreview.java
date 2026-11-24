import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.HardwareRenderer;
import android.graphics.Movie;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.alpha269.hyperisland.island.HyperOsNative;
import com.alpha269.hyperisland.island.HyperOsResources;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Original call XML/vectors rendered with the real Rust animator; owned surfaces only. */
public final class CallMotionPreview {
    static final int WIDTH=432,HEIGHT=230,BACKGROUND=0xff202126;
    static final long PERIOD=16_666_666L;
    static int screenWidth,sceneHeight,bigWidth,bigX,islandHeight,islandY,expandedWidth,expandedHeight,expandedMargin;
    static float density,sceneScale;
    static View incoming,big;
    static FrameLayout leftArea,rightArea;
    static RenderNode compactNode,expandedNode,containerNode,sceneNode;
    static Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG),secondaryPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    static ImageReader reader;
    static HardwareRenderer renderer;
    static double[] metrics,frame=new double[84];

    public static void main(String[] args){
        try{
            if(args.length>0&&args[0].equals("verify"))verify(new File(args[1]));else render(args);
            System.exit(0);
        }catch(Throwable error){error.printStackTrace(System.err);System.exit(1);}
    }
    static void render(String[] args)throws Exception {
        Looper.prepareMainLooper();
        if(Typeface.DEFAULT==null){Method fonts=Typeface.class.getDeclaredMethod("loadPreinstalledSystemFontMap");fonts.setAccessible(true);fonts.invoke(null);}
        Class<?> activity=Class.forName("android.app.ActivityThread");
        Object thread=activity.getMethod("systemMain").invoke(null);
        Context base=(Context)activity.getMethod("getSystemContext").invoke(thread);
        HyperOsResources call=new HyperOsResources(base,args[0],"com.android.incallui",new File(args[2]),true);
        HyperOsResources plugin=new HyperOsResources(base,args[1],"miui.systemui.plugin",new File(args[2]),true);
        HyperOsNative.loadPath(args[3]);
        File destination=new File(args[4]);if(!destination.isDirectory()&&!destination.mkdirs())throw new Exception("mkdir");
        File frames=new File(destination,"call-motion-frames");if(!frames.isDirectory()&&!frames.mkdirs())throw new Exception("frames mkdir");
        density=call.getResources().getDisplayMetrics().density;
        screenWidth=call.getResources().getDisplayMetrics().widthPixels;
        sceneScale=WIDTH/(float)screenWidth;sceneHeight=(int)Math.ceil(HEIGHT/sceneScale);
        expandedWidth=Math.round(364*density);expandedMargin=(screenWidth-expandedWidth)/2;
        FrameLayout callParent=new FrameLayout(call);
        incoming=call.inflate("call_focus_incoming_notification_view",callParent);
        String ringing=call.getString(call.id("string","focus_notification_on_ringing"));
        bindText(call,incoming,"caller_name","Alex",18,0xffffffff);
        bindText(call,incoming,"caller_info",ringing,14,0x80ffffff);
        bindIcon(call,incoming,"reject_call","ic_focus_reject_dark");
        bindIcon(call,incoming,"answer_call","ic_focus_answer_dark");
        margin(call,incoming,"caller_layout",true,14*density);
        margin(call,incoming,"caller_layout",false,10*density);
        margin(call,incoming,"answer_layout",false,14*density);
        incoming.measure(View.MeasureSpec.makeMeasureSpec(expandedWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        expandedHeight=incoming.getMeasuredHeight();incoming.layout(0,0,expandedWidth,expandedHeight);

        FrameLayout compactParent=new FrameLayout(plugin);
        big=plugin.inflate("dynamic_big_island_template_standard",compactParent);
        leftArea=big.findViewById(plugin.id("id","area_left"));rightArea=big.findViewById(plugin.id("id","area_right"));
        ImageView logo=new ImageView(plugin);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setImageDrawable(call.getResources().getDrawable(call.id("drawable","ic_focus_small_island_call_logo"),call.getTheme()));
        FrameLayout.LayoutParams logoParams=new FrameLayout.LayoutParams(px(20),px(20),Gravity.START|Gravity.CENTER_VERTICAL);
        logoParams.setMarginStart(px(1));logoParams.setMarginEnd(px(1));leftArea.addView(logo,logoParams);
        TextView label=(TextView)plugin.inflate("dynamic_island_module_right_title",rightArea);label.setText(ringing);
        label.measure(View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        FrameLayout.LayoutParams labelParams=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,px(19),Gravity.END|Gravity.CENTER_VERTICAL);
        labelParams.setMarginEnd(px(3));rightArea.addView(label,labelParams);
        int leftMeasured=px(8)+px(22)+px(1),rightMeasured=px(8)+px(3)+label.getMeasuredWidth()+px(1);
        int cutoutWidth=px(20),smallWidth=px(34),clockWidth=px(64),batteryWidth=px(54);
        float maximum=2*Math.min(screenWidth/2f-clockWidth,screenWidth/2f-batteryWidth);
        int[] sizes=new int[9];HyperOsNative.nativeCapsule(new int[]{leftMeasured,rightMeasured,cutoutWidth,screenWidth,2400,smallWidth,px(4),px(108),px(30)},new float[]{maximum,clockWidth,batteryWidth},false,false,sizes);
        bigWidth=sizes[0];bigX=sizes[3];islandHeight=px(34);
        float cutoutY=px(21);islandY=Math.max(px(3),(int)(cutoutY-islandHeight/2));
        View cutout=big.findViewById(plugin.id("id","area_cutout"));ViewGroup.LayoutParams cp=cutout.getLayoutParams();cp.width=cutoutWidth;cutout.setLayoutParams(cp);
        big.measure(View.MeasureSpec.makeMeasureSpec(bigWidth,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(islandHeight,View.MeasureSpec.EXACTLY));big.layout(0,0,bigWidth,islandHeight);
        int statusBarHeight=px(36);int expandedY=statusBarHeight>islandY+islandHeight?statusBarHeight+px(4):islandY+islandHeight+px(8);
        metrics=new double[]{screenWidth,islandHeight,islandY,bigWidth,bigWidth,bigX,expandedWidth,expandedWidth,expandedHeight,expandedY,expandedMargin,smallWidth,screenWidth-smallWidth-px(8),px(20),cutoutY,cutoutWidth,sizes[1],sizes[2],density,0,0};
        compactNode=new RenderNode("Original compact call content");compactNode.setPosition(bigX,islandY,bigX+bigWidth,islandY+islandHeight);
        expandedNode=new RenderNode("Original incoming call XML");expandedNode.setPosition(expandedMargin,islandY,expandedMargin+expandedWidth,islandY+expandedHeight);
        RecordingCanvas cardCanvas=expandedNode.beginRecording(expandedWidth,expandedHeight);try{incoming.draw(cardCanvas);}finally{expandedNode.endRecording();}
        containerNode=new RenderNode("HyperOS clipping and container motion");containerNode.setPosition(0,0,screenWidth,sceneHeight);containerNode.setClipToBounds(false);containerNode.setClipToOutline(true);
        sceneNode=new RenderNode("Owned call motion preview surface");sceneNode.setPosition(0,0,WIDTH,HEIGHT);
        textPaint.setColor(0xffeeeeee);textPaint.setTextSize(15);secondaryPaint.setColor(0xffb8b8b8);secondaryPaint.setTextSize(11);
        reader=ImageReader.newInstance(WIDTH,HEIGHT,PixelFormat.RGBA_8888,2);
        renderer=new HardwareRenderer();renderer.setSurface(reader.getSurface());renderer.setContentRoot(sceneNode);
        long owner=HyperOsNative.nativeCreateAnimator();JSONArray timeline=new JSONArray();int outputIndex=0;
        try{
            HyperOsNative.nativeTransition(owner,0,metrics,0xff000000,1f,false);
            for(int tick=0;tick<216;tick++){
                if(tick==70)HyperOsNative.nativeTransition(owner,1,metrics,0xff000000,1f,false);
                if(tick==148)HyperOsNative.nativeTransition(owner,2,metrics,0xff000000,1f,false);
                long active=HyperOsNative.nativeAnimatorFrame(owner,PERIOD,PERIOD,1f,frame);
                if((tick&1)!=0)continue;
                String phase=tick<46?"Appear":tick<70?"Compact":tick<114?"Expand":tick<148?"Expanded":tick<194?"Collapse":"Compact";
                updateScene(phase,cutoutY,cutoutWidth);
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                Bitmap bitmap=readFrame(frames,outputIndex);
                if(outputIndex==60)save(bitmap,new File(destination,"call-motion-poster.png"));
                bitmap.recycle();
                JSONArray channels=new JSONArray();for(int i=0;i<28;i++)channels.put(frame[i]);
                timeline.put(new JSONObject().put("frame",outputIndex).put("callback",tick).put("time_ms",tick*PERIOD/1_000_000.0).put("phase",phase).put("active_mask",active).put("channels",channels));
                outputIndex++;
            }
        }finally{
            HyperOsNative.nativeDestroyAnimator(owner);renderer.destroy();reader.close();
            compactNode.discardDisplayList();expandedNode.discardDisplayList();containerNode.discardDisplayList();sceneNode.discardDisplayList();
        }
        JSONArray geometry=new JSONArray();for(double value:metrics)geometry.put(value);
        JSONObject evidence=new JSONObject().put("frames",outputIndex).put("render_fps",30).put("native_callback_fps",60).put("native_period_ns",PERIOD)
            .put("source_data","Sample caller Alex; ringing label and logo from original FocusIsland/InCallUI resources")
            .put("metrics",geometry).put("incoming_height_px",expandedHeight).put("width",WIDTH).put("height",HEIGHT)
            .put("limitations","Original XML/vectors/fonts with exact native motion channels; opaque preview material and Android RenderEffect replace Xiaomi compositor material. Compact calling glow shader is not shown. Geometry is an explicit sample, not a capture of a live call.")
            .put("timeline",timeline);
        try(FileOutputStream out=new FileOutputStream(new File(destination,"call-motion-native-timeline.json"))){out.write(evidence.toString(2).getBytes("UTF-8"));}
        System.out.println("Rendered "+outputIndex+" original-call/native-motion hardware frames, incomingHeight="+expandedHeight+", compactWidth="+bigWidth+", density="+density);
    }
    static void updateScene(String phase,float cutoutY,int cutoutWidth){
        leftArea.setTranslationX((float)frame[15]);rightArea.setTranslationX((float)frame[16]);
        RecordingCanvas compact=compactNode.beginRecording(bigWidth,islandHeight);try{big.draw(compact);}finally{compactNode.endRecording();}
        compactNode.setScaleX((float)frame[10]);compactNode.setScaleY((float)frame[10]);compactNode.setAlpha((float)frame[9]);compactNode.setTranslationY((float)frame[13]);blur(compactNode,frame[14]);
        expandedNode.setScaleX((float)frame[23]);expandedNode.setScaleY((float)frame[24]);expandedNode.setAlpha((float)frame[22]);expandedNode.setTranslationY((float)frame[25]);expandedNode.setTranslationX((float)frame[26]);blur(expandedNode,frame[27]);
        containerNode.setTranslationX((float)frame[1]);containerNode.setTranslationY((float)frame[2]);containerNode.setAlpha((float)frame[3]);
        Outline outline=new Outline();float radius=Math.min(((float)frame[7]-(float)frame[6])/2f,30*density);
        outline.setRoundRect((int)frame[4],(int)frame[6],(int)frame[5],(int)frame[7],Math.max(0,radius));containerNode.setOutline(outline);
        RecordingCanvas container=containerNode.beginRecording(screenWidth,sceneHeight);
        try{container.drawColor(Color.BLACK);container.drawRenderNode(compactNode);container.drawRenderNode(expandedNode);}finally{containerNode.endRecording();}
        RecordingCanvas canvas=sceneNode.beginRecording(WIDTH,HEIGHT);
        try{
            canvas.drawColor(BACKGROUND);int saved=canvas.save();canvas.scale(sceneScale,sceneScale);canvas.drawRenderNode(containerNode);
            Paint camera=new Paint(Paint.ANTI_ALIAS_FLAG);camera.setColor(Color.BLACK);canvas.drawCircle(screenWidth/2f,cutoutY,cutoutWidth/2f,camera);canvas.restoreToCount(saved);
            canvas.drawText("HyperOS call · "+phase,14,HEIGHT-43,textPaint);
            canvas.drawText("Original resources + Rust motion · sample caller",14,HEIGHT-25,secondaryPaint);
            canvas.drawText("Opaque material preview; Xiaomi calling glow omitted",14,HEIGHT-10,secondaryPaint);
        }finally{sceneNode.endRecording();}
    }
    static void blur(RenderNode node,double ratio){int radius=Math.max(0,(int)(40*(float)ratio));node.setRenderEffect(radius>0?RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP):null);}
    static Bitmap readFrame(File frames,int index)throws Exception{
        try(Image image=reader.acquireNextImage()){
            if(image==null)throw new AssertionError("No hardware frame");Image.Plane plane=image.getPlanes()[0];ByteBuffer buffer=plane.getBuffer();
            int[] colors=new int[WIDTH*HEIGHT];byte[] rgba=new byte[WIDTH*HEIGHT*4];int rowStride=plane.getRowStride(),pixelStride=plane.getPixelStride();
            for(int y=0;y<HEIGHT;y++)for(int x=0;x<WIDTH;x++){
                int offset=y*rowStride+x*pixelStride,indexPixel=y*WIDTH+x;int red=buffer.get(offset)&255,green=buffer.get(offset+1)&255,blue=buffer.get(offset+2)&255,alpha=buffer.get(offset+3)&255;
                colors[indexPixel]=Color.argb(alpha,red,green,blue);rgba[indexPixel*4]=(byte)red;rgba[indexPixel*4+1]=(byte)green;rgba[indexPixel*4+2]=(byte)blue;rgba[indexPixel*4+3]=(byte)alpha;
            }
            try(FileOutputStream raw=new FileOutputStream(new File(frames,String.format("frame-%03d.rgba",index)))){raw.write(rgba);}
            Bitmap bitmap=Bitmap.createBitmap(colors,WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);save(bitmap,new File(frames,String.format("frame-%03d.png",index)));return bitmap;
        }
    }
    static void verify(File folder)throws Exception{
        Movie movie=Movie.decodeFile(new File(folder,"call-motion-HyperOS.gif").toString());if(movie==null||movie.width()!=WIDTH||movie.height()!=HEIGHT||movie.duration()!=3600)throw new AssertionError("GIF metadata");
        JSONArray results=new JSONArray();
        for(int frameIndex:new int[]{0,20,40,60,80,100}){
            movie.setTime((int)Math.round(frameIndex*100.0/30.0)*10+1);
            Bitmap bitmap=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888);movie.draw(new Canvas(bitmap),0,0);
            int[] colors=new int[WIDTH*HEIGHT];bitmap.getPixels(colors,0,WIDTH,0,0,WIDTH,HEIGHT);
            byte[] expected=new byte[WIDTH*HEIGHT*4];try(FileInputStream in=new FileInputStream(new File(folder,"call-motion-frames/"+String.format("mapped-%03d.rgba",frameIndex)))){int read=0,n;while(read<expected.length&&(n=in.read(expected,read,expected.length-read))>0)read+=n;if(read!=expected.length)throw new AssertionError("Short mapped frame");}
            int difference=0;for(int i=0;i<colors.length;i++){int argb=Color.argb(expected[i*4+3]&255,expected[i*4]&255,expected[i*4+1]&255,expected[i*4+2]&255);if(colors[i]!=argb)difference++;}
            if(difference!=0)throw new AssertionError("Decoded GIF frame differs "+frameIndex+": "+difference);
            results.put(new JSONObject().put("frame",frameIndex).put("different_palette_pixels",difference));bitmap.recycle();
        }
        JSONObject report=new JSONObject().put("decoder","Android Movie in isolated ART").put("duration_ms",movie.duration()).put("frames",results);
        try(FileOutputStream out=new FileOutputStream(new File(folder,"call-motion-gif-validation.json"))){out.write(report.toString(2).getBytes("UTF-8"));}
        System.out.println("Android GIF decode validated; six frames match the encoded source palette pixels.");
    }
    static int px(float dp){return Math.round(dp*density);}
    static void bindText(HyperOsResources source,View card,String name,String value,float size,int color){TextView view=card.findViewById(source.id("id",name));view.setText(value);view.setTextSize(TypedValue.COMPLEX_UNIT_DIP,size);view.setTextColor(color);view.setVisibility(View.VISIBLE);}
    static void bindIcon(HyperOsResources source,View card,String name,String icon){ImageView view=card.findViewById(source.id("id",name));view.setImageDrawable(source.getResources().getDrawable(source.id("drawable",icon),source.getTheme()));view.setVisibility(View.VISIBLE);}
    static void margin(HyperOsResources source,View card,String name,boolean start,float amount){View view=card.findViewById(source.id("id",name));ViewGroup.MarginLayoutParams params=(ViewGroup.MarginLayoutParams)view.getLayoutParams();if(start)params.setMarginStart(Math.round(amount));else params.setMarginEnd(Math.round(amount));view.setLayoutParams(params);}
    static void save(Bitmap bitmap,File path)throws Exception{try(FileOutputStream out=new FileOutputStream(path)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new AssertionError("PNG save");}}
}
