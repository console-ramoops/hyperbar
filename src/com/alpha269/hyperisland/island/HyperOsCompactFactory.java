package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Outline;
import android.graphics.RenderEffect;
import android.graphics.RuntimeShader;
import android.graphics.SurfaceTexture;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.media.MediaPlayer;
import android.net.Uri;
import android.text.format.DateUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.TextureView;
import android.view.Surface;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Source phone left/right/small module grammar; all geometry comes from donor XML. */
public final class HyperOsCompactFactory {
    private static final Map<String,Payload> PAYLOADS=new LinkedHashMap<>();
    private static final Map<String,int[]> PALETTES=new LinkedHashMap<String,int[]>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,int[]> e){return size()>24;}
    };
    private static final ExecutorService PALETTE_IO=Executors.newSingleThreadExecutor(task->{Thread t=new Thread(task,"HyperOS-compact-palette");t.setDaemon(true);return t;});
    private static long sequence;
    private HyperOsCompactFactory(){}

    /** Keep the full notification payload: param_island is independent of expanded param_v2/v3. */
    public static synchronized void register(String key,JSONObject fullPayload,Bundle pictures){
        if(key==null||fullPayload==null)return;
        String json=fullPayload.toString();if(json.length()>131072)return;
        try{long fingerprint=pictureFingerprint(pictures);Payload previous=PAYLOADS.get(key);if(previous!=null&&previous.encoded.equals(json)&&previous.pictureFingerprint==fingerprint)return;
            JSONObject copy=new JSONObject(json),nextIsland=island(copy);long version=++sequence;
            if(previous!=null&&previous.nativeIsland&&nextIsland!=null&&previous.pictureFingerprint==fingerprint&&island(previous.full).toString().equals(nextIsland.toString()))version=previous.version;
            PAYLOADS.remove(key);PAYLOADS.put(key,new Payload(copy,pictures==null?null:new Bundle(pictures),version,json,fingerprint));
            while(PAYLOADS.size()>64)PAYLOADS.remove(PAYLOADS.keySet().iterator().next());
        }catch(Exception invalid){throw new IllegalArgumentException("Invalid HyperOS capsule payload",invalid);}
    }
    public static synchronized void unregister(String key){PAYLOADS.remove(key);}
    private static synchronized Payload registered(String key){return PAYLOADS.get(key);}
    private static final class Payload {
        final JSONObject full;final Bundle pictures;final long version,pictureFingerprint;final String encoded;final boolean nativeIsland;
        Payload(JSONObject full,Bundle pictures,long version,String encoded,long pictureFingerprint){this.full=full;this.pictures=pictures;this.version=version;this.encoded=encoded;this.pictureFingerprint=pictureFingerprint;nativeIsland=island(full)!=null;}
    }
    public static Compact create(Context context,IslandItem item,boolean small){
        return create(context,item,small,HyperOsAssets.plugin(context),HyperOsAssets.fonts(context));
    }
    /** Explicit original carrier/font paths support isolated rendering without installing an update. */
    public static Compact create(Context context,IslandItem item,boolean small,HyperOsResources source,File fonts){
        return create(context,item,small,source,fonts,null);
    }
    public static Compact create(Context context,IslandItem item,boolean small,HyperOsResources source,File fonts,HyperOsResources call){
        Compact compact=new Compact(context,source,fonts,small,call);compact.update(item,SystemClock.elapsedRealtime());return compact;
    }

    public static final class Compact {
        public final View view;
        public String failure;
        private final Context context;private final HyperOsResources source,callSource;private final File fonts;
        private final FrameLayout body,smallBody;private final LayoutInflater inflater;private final boolean smallOnly;
        private final List<HyperOsLottieView> animations=new ArrayList<>();
        private final List<HyperOsLottieView> musicAnimations=new ArrayList<>();
        private final List<TextRow> rows=new ArrayList<>();
        private final List<ClockBinding> clocks=new ArrayList<>();
        private final List<IconModule> icons=new ArrayList<>();
        private final List<Effect> effects=new ArrayList<>();
        private final List<Video> videos=new ArrayList<>();
        private final Map<String,Drawable> localPictures=new LinkedHashMap<>();
        private final Handler main=new Handler(Looper.getMainLooper());
        private LinearLayout template;private FrameLayout leftArea,rightArea,cutoutArea;
        private Module left,right,smallModule;private JSONObject island;private Payload payload;
        private IslandItem item;private String structure;private boolean released,ownerVisible=true,bigVisible,smallVisible;
        private int leftNatural,rightNatural,leftAllocated,rightAllocated,cutoutAllocated,heightAllocated;
        private String paletteKey;private Drawable albumDrawable;private long generation;
        private int topColor=Color.WHITE,bottomColor=Color.WHITE;
        private Compact(Context context,HyperOsResources source,File fonts,boolean small,HyperOsResources call){
            this.context=context;this.source=source;this.callSource=call;this.fonts=fonts;this.smallOnly=small;bigVisible=!small;smallVisible=small;
            body=new FrameLayout(context);smallBody=new FrameLayout(context);body.setClipChildren(false);body.setClipToPadding(false);
            smallBody.setClipChildren(false);smallBody.setClipToPadding(false);view=small?smallBody:body;
            inflater=LayoutInflater.from(source.getBaseContext()).cloneInContext(source);
            inflater.setFactory2(new LayoutInflater.Factory2(){
                @Override public View onCreateView(String name,Context c,AttributeSet a){return onCreateView(null,name,c,a);}
                @Override public View onCreateView(View p,String name,Context c,AttributeSet a){
                    View result=null;
                    if(name.endsWith(".FadeLinearLayout"))result=new Fade(c,a,source);
                    else if(name.endsWith(".LottieView"))result=new HyperOsLottieView(c,a);
                    else if(name.endsWith(".TimerTextEffectView")||name.endsWith(".HyperChronometerPlugin"))result=new TextView(c,a);
                    else if(name.endsWith(".CircularProgressBar"))result=new Ring(c,a);
                    else if(name.endsWith(".ChargerView")||name.endsWith(".PowerSaveView"))result=new Device(c,a,Compact.this,name.endsWith(".PowerSaveView"));
                    else if(name.endsWith(".TextureVideoView"))result=new Video(c,a,Compact.this);
                    else if(name.endsWith(".FlashLightIcon")){ImageView icon=new ImageView(c,a);icon.setBackground(drawable("flash_light_icon"));icon.setScaleType(ImageView.ScaleType.CENTER);result=icon;}
                    if(result instanceof TextView){TypedArray attrs=c.obtainStyledAttributes(a,new int[]{android.R.attr.fontFamily});String family=attrs.getString(0);attrs.recycle();if(family!=null)HyperOsFonts.apply((TextView)result,fonts,family);}
                    return result;
                }
            });
        }
        public int leftMeasured(){return leftNatural;}
        public int rightMeasured(){return rightNatural;}
        public View smallView(){return smallBody;}
        public void setAreaTranslation(float left,float right){if(leftArea!=null)leftArea.setTranslationX(left);if(rightArea!=null)rightArea.setTranslationX(right);}
        public int leftFitStep(){return left==null||left.text==null?0:left.text.step;}
        public int rightFitStep(){return right==null||right.text==null?0:right.text.step;}
        public String leftModule(){return left==null?"":left.type;}
        public String rightModule(){return right==null?"":right.type;}
        public void layout(int leftWidth,int cutoutWidth,int rightWidth,int height){
            if(released||failure!=null)return;if(smallOnly){heightAllocated=Math.max(1,height);exact(smallBody,dim("small_island_width"),heightAllocated);for(Effect effect:effects)effect.size();return;}if(template==null)return;
            leftAllocated=Math.max(0,leftWidth);rightAllocated=Math.max(0,rightWidth);cutoutAllocated=Math.max(0,cutoutWidth);heightAllocated=Math.max(1,height);
            setArea(leftArea,leftAllocated);setArea(cutoutArea,cutoutAllocated);setArea(rightArea,rightAllocated);
            if(left!=null)left.fit(Math.max(0,leftAllocated-padding(leftArea)),heightAllocated);
            if(right!=null)right.fit(Math.max(0,rightAllocated-padding(rightArea)),heightAllocated);
            int width=leftAllocated+cutoutAllocated+rightAllocated;
            exact(body,width,heightAllocated);exact(smallBody,dim("small_island_width"),heightAllocated);
            for(Effect effect:effects)effect.size();
        }
        public void update(IslandItem next,long nowElapsed){
            if(released||next==null)return;
            Payload nextPayload=registered(next.key);String nextStructure=shape(next,nextPayload);
            boolean rebuild=!nextStructure.equals(structure);item=next;
            if(rebuild){structure=nextStructure;payload=nextPayload;rebuild(nowElapsed);}
            if(failure!=null)return;
            for(ClockBinding clock:clocks)clock.update(nowElapsed);
            for(HyperOsLottieView animation:animations){boolean visible=ownerVisible&&visibleOwner(animation);animation.setOwnerVisible(visible);if(visible)animation.step(nowElapsed);}
            for(Effect effect:effects)if(ownerVisible&&visibleOwner(effect.target))effect.frame(nowElapsed);
            measureNatural();
            if(heightAllocated>0)layout(leftAllocated,cutoutAllocated,rightAllocated,heightAllocated);
            requestPalette(next.art);
        }
        public boolean needsFrames(){
            if(released||failure!=null||!ownerVisible)return false;
            for(ClockBinding clock:clocks)if(clock.clock.running)return true;
            for(HyperOsLottieView animation:animations)if(visibleOwner(animation)&&animation.active())return true;
            for(Effect effect:effects)if(visibleOwner(effect.target)&&effect.animated)return true;
            return false;
        }
        private boolean visibleOwner(View child){for(View n=child;n!=null;n=n.getParent() instanceof View?(View)n.getParent():null){if(n==smallBody)return smallVisible&&(smallOnly||smallBody.getParent()!=null);if(n==body)return bigVisible&&!smallOnly;}return false;}
        /** Keep both donor modules alive while an owner crosses capsule roles. */
        public void setPresentationVisible(boolean big,boolean small){
            if(bigVisible==big&&smallVisible==small)return;bigVisible=big;smallVisible=small;
            for(HyperOsLottieView animation:animations)animation.setOwnerVisible(ownerVisible&&visibleOwner(animation));
            for(Video video:videos)video.setOwnerVisible(ownerVisible&&visibleOwner(video));
        }
        public void setOwnerVisible(boolean visible){
            if(ownerVisible==visible)return;ownerVisible=visible;
            for(HyperOsLottieView animation:animations)animation.setOwnerVisible(visible&&visibleOwner(animation));
            for(Video video:videos)video.setOwnerVisible(visible&&visibleOwner(video));
        }
        public void release(){released=true;generation++;dispose();body.removeAllViews();smallBody.removeAllViews();}
        private void dispose(){for(HyperOsLottieView a:animations)a.release();for(Video v:videos)v.release();for(Effect e:effects)e.target.setRenderEffect(null);effects.clear();videos.clear();animations.clear();musicAnimations.clear();rows.clear();clocks.clear();icons.clear();localPictures.clear();paletteKey=null;albumDrawable=null;body.setRenderEffect(null);smallBody.setRenderEffect(null);}
        private void rebuild(long now){
            generation++;dispose();body.removeAllViews();smallBody.removeAllViews();failure=null;leftNatural=rightNatural=0;
            try{
                if(item.blank)throw new IllegalArgumentException("No capsule data");
                island=payload==null||!payload.nativeIsland?canonical(now):island(payload.full);
                if(island==null)throw new IllegalArgumentException("No param_island in native focus payload");
                JSONObject big=island.optJSONObject("bigIslandArea"),small=island.optJSONObject("smallIslandArea");
                if(big==null)throw new IllegalArgumentException("Native capsule has no bigIslandArea");
                left=right=null;template=null;leftArea=rightArea=cutoutArea=null;
                if(!smallOnly){template=(LinearLayout)inflate("dynamic_big_island_template_standard",body);body.addView(template,new FrameLayout.LayoutParams(-1,-1));leftArea=(FrameLayout)find(template,"area_left");rightArea=(FrameLayout)find(template,"area_right");cutoutArea=(FrameLayout)find(template,"area_cutout");left=module("left",big,small,leftArea);right=module("right",big,small,rightArea);if(left!=null)leftArea.addView(left.root,left.params(Gravity.START|Gravity.CENTER_VERTICAL));if(right!=null)rightArea.addView(right.root,right.params(Gravity.END|Gravity.CENTER_VERTICAL));}
                smallModule=module("small",big,small,smallBody);
                if(smallModule!=null)smallBody.addView(smallModule.root,smallModule.params(Gravity.CENTER));
                body.setVisibility(View.VISIBLE);smallBody.setVisibility(View.VISIBLE);
                for(ClockBinding clock:clocks)clock.update(now);measureNatural();
                if(smallOnly)exact(smallBody,dim("small_island_width"),dim("island_height"));
            }catch(Throwable error){failure=error.getClass().getSimpleName()+": "+error.getMessage();dispose();body.removeAllViews();smallBody.removeAllViews();body.setVisibility(View.GONE);smallBody.setVisibility(View.GONE);}
        }
        private void measureNatural(){leftNatural=left==null?0:left.natural()+padding(leftArea);rightNatural=right==null?0:right.natural()+padding(rightArea);}
        private int padding(View v){return v==null?0:v.getPaddingLeft()+v.getPaddingRight();}
        private void setArea(FrameLayout area,int width){LinearLayout.LayoutParams p=(LinearLayout.LayoutParams)area.getLayoutParams();p.width=width;p.height=-1;p.weight=0;area.setLayoutParams(p);}
        private View inflate(String name,ViewGroup parent){View v=inflater.inflate(source.getResources().getLayout(source.id("layout",name)),parent,false);prepareStubs(v);return v;}
        private void prepareStubs(View v){if(v instanceof ViewStub)((ViewStub)v).setLayoutInflater(inflater);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)prepareStubs(((ViewGroup)v).getChildAt(i));}
        private View find(View root,String id){int value=source.getResources().getIdentifier(id,"id",source.getPackageName());return value==0?null:root.findViewById(value);}
        private View stub(View root,String id){View v=find(root,id);if(v instanceof ViewStub){v=((ViewStub)v).inflate();prepareStubs(v);}return v;}
        private int dim(String name){return source.getResources().getDimensionPixelSize(source.id("dimen",name));}
        private int color(String name,int fallback){int id=source.getResources().getIdentifier(name,"color",source.getPackageName());return id==0?fallback:source.getResources().getColor(id,source.getTheme());}
        private Drawable drawable(String name){int id=source.getResources().getIdentifier(name,"drawable",source.getPackageName());return id==0?null:source.getResources().getDrawable(id,source.getTheme());}
        private Bitmap bitmap(String name){int id=source.id("drawable",name);Bitmap b=BitmapFactory.decodeResource(source.getResources(),id);if(b==null)throw new IllegalArgumentException("Missing original bitmap "+name);return b;}
        private String shader(String name)throws Exception{try(InputStream in=source.getResources().openRawResource(source.id("raw",name));ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);return bytes.toString("UTF-8");}}
        private void callingEffect(View target,int width)throws Exception{
            Effect effect=new Effect(target,new RuntimeShader(shader("calling_light_wave")),true,"call",width,dim("island_area_padding"));effect.shader.setFloatUniform("uAlpha",1f);effect.shader.setFloatUniform("ds",3.5f);effects.add(effect);
        }
        private Drawable picture(String name){
            Drawable local=localPictures.get(name);if(local!=null)return copy(local);
            if(payload!=null&&payload.pictures!=null&&name!=null){Object value=payload.pictures.get(name);if(value instanceof Icon)try{return ((Icon)value).loadDrawable(context);}catch(Exception unavailable){return null;}if(value instanceof Bitmap)return new BitmapDrawable(source.getResources(),(Bitmap)value);}
            return name==null?null:drawable(name);
        }
        private int textColor(JSONObject text,boolean content){int plain=color(content?"content_color":"title_color",content?0xccffffff:Color.WHITE);if(!text.optBoolean("showHighlightColor",false))return plain;int c=parseColor(island.optString("highlightColor",null),plain);return content?(c&0x00ffffff)|((int)(Color.alpha(c)*.8d)<<24):c;}

        private Module module(String side,JSONObject big,JSONObject small,FrameLayout parent)throws Exception{
            String type=selectModule(side,big,small);if(type.length()==0)return null;
            JSONObject data;
                if(side.equals("left"))data=big.optJSONObject("imageTextInfoLeft");
            else if(side.equals("small")){data=small==null?null:small.optJSONObject("combinePicInfo");if(data==null)data=small==null?null:small.optJSONObject("picInfo");if(data==null){JSONObject l=big.optJSONObject("imageTextInfoLeft");data=l==null?null:l.optJSONObject("picInfo");}if(type.equals("moduleSmallTextOverIcon"))data=big.optJSONObject("imageTextInfoRight");}
            else {data=big.optJSONObject("imageTextInfoRight");if(type.equals("moduleText"))data=big.optJSONObject("textInfo");else if(type.equals("modulePic"))data=big.optJSONObject("picInfo");else if(type.equals("moduleSameWidthDigit"))data=big.optJSONObject("sameWidthDigitInfo");else if(type.equals("moduleFixedWidthDigit"))data=big.optJSONObject("fixedWidthDigitInfo");else if(type.equals("moduleImageText"))data=big.optJSONObject("progressTextInfo");}
            return new Module(type,data==null?new JSONObject():data,parent,side);
        }
        private final class Module {
            final View root;final String type;final JSONObject data;final String side;TextRow text;IconModule icon;Device device;Ring progress;View extraPic;int iconWidth;
            Module(String type,JSONObject data,FrameLayout parent,String side)throws Exception{
                this.type=type;this.data=data;this.side=side;root=inflate(layoutFor(type),parent);
                if(type.equals("moduleImageText_4")){
                    JSONObject pic=data.optJSONObject("picInfo"),info=data.optJSONObject("textInfo");String name=pic==null?"":pic.optString("pic");boolean power=name.equals("power_save_mode");
                    device=(Device)find(root,power?"power_save_view":"charge_view");if(!power&&!name.equals("charger_mode")&&!name.equals("quick_charger_mode"))throw new IllegalArgumentException("Unknown source device module "+name);
                    device.setVisibility(View.VISIBLE);device.bind(info==null?new JSONObject():info,name);return;
                }
                if(type.equals("moduleCombinePic")){
                    View iconOne=find(root,"island_icon_1"),iconTwo=find(root,"island_icon_2");icon=new IconModule(iconOne,data.optJSONObject("picInfo"));new IconModule(iconTwo,data.optJSONObject("smallPicInfo"));
                    progress=(Ring)find(root,"island_progress");bindProgress(progress,data.optJSONObject("progressInfo"));return;
                }
                if(type.equals("modulePic")||type.equals("modulePicSmallIsland")){icon=new IconModule(root,data,side.equals("small")?smallBody:body);return;}
                if(type.equals("moduleTextOverIcon")||type.equals("moduleSmallTextOverIcon")){
                    icon=new IconModule(find(root,"island_container_module_icon"),data.optJSONObject("picInfo"),side.equals("small")?smallBody:body);TextView title=(TextView)find(root,"island_title");JSONObject info=data.optJSONObject("textInfo");if(info!=null){String value=info.optString("title","");boolean valid=value.length()>0&&value.length()<=3&&value.matches("[0-9]+");title.setText(valid?value:"");if(info.has("narrowFont"))HyperOsFonts.apply(title,fonts,info.optBoolean("narrowFont")?"misans-condensed":"mipro-demibold");title.setTextColor(textColor(info,false));title.setVisibility(valid?View.VISIBLE:View.GONE);}return;
                }
                if(type.equals("moduleIconFixedWidthText")){
                    icon=new IconModule(find(root,"island_container_module_icon"),data.optJSONObject("picInfo"));JSONObject info=data.optJSONObject("textInfo");TextView title=(TextView)find(root,"fixed_width_text"),content=(TextView)find(root,"unit_text");HyperOsFonts.applyFixedDigits(title,fonts);if(info!=null){title.setText(info.optString("title",""));content.setText(info.optString("content",""));title.setVisibility(title.length()>0?0:8);content.setVisibility(content.length()>0?0:8);title.setTextColor(textColor(info,false));content.setTextColor(textColor(info,true));}return;
                }
                if(type.equals("moduleSameWidthDigit")||type.equals("moduleFixedWidthDigit")){
                    if(type.equals("moduleFixedWidthDigit")&&!data.has("digit")||type.equals("moduleSameWidthDigit")&&!data.has("digit")&&data.optJSONObject("timerInfo")==null)throw new IllegalArgumentException("Missing source digit or timerInfo");
                    TextView title=(TextView)find(root,type.equals("moduleSameWidthDigit")&&data.optJSONObject("timerInfo")!=null?"island_chronometer":type.equals("moduleSameWidthDigit")?"island_title":"island_digit");
                    if(type.equals("moduleSameWidthDigit")){View other=find(root,title==find(root,"island_chronometer")?"island_title":"island_chronometer");if(other!=null)other.setVisibility(View.GONE);}
                    title.setVisibility(View.VISIBLE);if(type.equals("moduleSameWidthDigit")&&data.optJSONObject("timerInfo")!=null)HyperOsFonts.applyFixedDigits(title,fonts);else if(type.equals("moduleFixedWidthDigit")){title.setTypeface(HyperOsFonts.fixedDigits(fonts));title.getPaint().setFakeBoldText(false);}
                    String digit=data.optString("digit","");if(type.equals("moduleFixedWidthDigit")&&!validFixedDigit(digit))digit="---";
                    JSONObject info=new JSONObject().put("title",digit).put("content",data.optString("content","")).put("showHighlightColor",data.optBoolean("showHighlightColor",false));
                    text=new TextRow(root,info,false,title);if(data.optJSONObject("timerInfo")!=null){ClockBinding c=new ClockBinding(new Clock(data.getJSONObject("timerInfo"),SystemClock.elapsedRealtime()),text);clocks.add(c);}
                    if(type.equals("moduleFixedWidthDigit")){extraPic=find(root,"island_pic");Drawable pic=picture(data.optString("pic",null));if(extraPic instanceof ImageView){((ImageView)extraPic).setImageDrawable(pic);extraPic.setVisibility(pic==null?8:0);}}return;
                }
                if(type.equals("moduleText")){text=new TextRow(root,data,false,null);return;}
                JSONObject info=data.optJSONObject("textInfo");boolean left=type.equals("moduleImageText_1");
                View textRoot=find(root,left?"island_container_module_text":"island_container_module_right_text");
                if(textRoot==null)textRoot=find(root,"island_container_module_text");text=new TextRow(textRoot==null?root:textRoot,info==null?new JSONObject():info,left,null);
                if(type.equals("moduleImageText")){progress=(Ring)find(root,"island_progress");bindProgress(progress,data.optJSONObject("progressInfo"));}
                else {View iconRoot=find(root,"island_container_module_icon");icon=new IconModule(iconRoot,data.optJSONObject("picInfo"));}
                position();
            }
            FrameLayout.LayoutParams params(int gravity){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-2,-1);p.gravity=gravity;return p;}
            int natural(){position();if(device!=null)return device.natural();if(text!=null){int suffix=extraPic!=null&&extraPic.getVisibility()==View.VISIBLE?dim("island_fix_icon_size")+dp(source,1):0;return iconWidth+text.natural()+suffix;}root.measure(unspecified(),exactSpec(dim("island_height")));return root.getMeasuredWidth();}
            void position(){
                iconWidth=icon==null?progress==null?0:dp(source,20):icon.width();if(text==null)return;
                ViewGroup.LayoutParams p=text.root.getLayoutParams();if(p instanceof FrameLayout.LayoutParams){FrameLayout.LayoutParams f=(FrameLayout.LayoutParams)p;
                    if(type.equals("moduleImageText_2")){f.gravity=Gravity.START|Gravity.CENTER_VERTICAL;f.setMarginEnd(iconWidth);}
                    else if(type.equals("moduleImageText_1")||type.equals("moduleImageText_3")){f.gravity=Gravity.END|Gravity.CENTER_VERTICAL;f.setMarginStart(iconWidth);}
                    text.root.setLayoutParams(f);
                }
            }
            void fit(int width,int height){position();if(text!=null)text.fit(Math.max(0,width-iconWidth-(extraPic!=null&&extraPic.getVisibility()==0?dim("island_fix_icon_size"):0)));ViewGroup.LayoutParams p=root.getLayoutParams();if(p!=null){p.width=width;p.height=height;root.setLayoutParams(p);}}
        }

        private final class TextRow {
            final View root;final JSONObject data;final boolean left,fixed;final TextView title,front,content;final Fade fade;int step=1;
            TextRow(View root,JSONObject data,boolean left,TextView supplied)throws Exception{
                this.root=root;this.data=data;this.left=left;fixed=supplied!=null&&supplied==find(root,"island_digit");
                title=supplied!=null?supplied:(TextView)stub(root,"island_title_stub");front=(TextView)stub(root,"island_front_title_stub");content=(TextView)(find(root,"island_content")!=null?find(root,"island_content"):stub(root,"island_content_stub"));
                View f=find(root,left?"island_text_fade_Layout":"right_text_fade_layout");if(f==null)f=find(root,"island_container_module_same_width_digit");if(f==null)f=find(root,"fixed_width_fade_layout");fade=f instanceof Fade?(Fade)f:null;
                String family=data.optBoolean("narrowFont",false)?"misans-condensed":"mipro-demibold";
                for(TextView t:new TextView[]{title,front,content})if(t!=null){t.setSingleLine(true);if(t!=supplied)HyperOsFonts.apply(t,fonts,family);}
                if(title!=null){title.setText(data.optString("title",""));title.setTextColor(textColor(data,false));}
                if(front!=null){front.setText(data.optString("frontTitle",""));front.setTextColor(textColor(data,true));}
                if(content!=null){content.setText(data.optString("content",""));content.setTextColor(textColor(data,true));}
                rows.add(this);fit(natural());
            }
            int natural(){int f=width(front),t=fixed?dim("island_fixed_width"):width(title),c=width(content);return f+t+c+space(f,c,dim("island_text_padding_inner"))+(fade==null?0:fade.getPaddingLeft()+fade.getPaddingRight());}
            void setTitle(String value){if(title!=null&&!value.contentEquals(title.getText()))title.setText(value);}
            void fit(int width){
                if(title==null)return;if(fixed){step=0;show(title,true);show(content,width(content)>0);title.setScaleX(1);title.setScaleY(1);ViewGroup.LayoutParams t=title.getLayoutParams();t.width=dim("island_fixed_width");title.setLayoutParams(t);if(fade!=null){fade.fading=natural()>width;ViewGroup.LayoutParams p=fade.getLayoutParams();p.width=width;fade.setLayoutParams(p);}return;}
                int padding=fade==null?0:fade.getPaddingLeft()+fade.getPaddingRight();int available=Math.max(0,width-padding),fw=width(front),tw=width(title),cw=width(content),gap=dim("island_text_padding_inner");step=fitStep(fw,tw,cw,gap,available);
                boolean full=step<=2,hasFront=step<=4;show(front,hasFront&&fw>0&&tw>0);show(title,tw>0);show(content,full&&cw>0&&tw>0);
                float scale=step==2||step==4||step>=6?.79f:1f,shift=(1f-scale)*tw,vertical=dim("island_text_content_margin");boolean rtl=root.getLayoutDirection()==View.LAYOUT_DIRECTION_RTL;
                title.setPivotX(left?(rtl?tw:0):(rtl?0:tw));title.setPivotY(dim("island_title_height")*.5f);title.setScaleX(scale);title.setScaleY(scale);
                title.setTranslationX(step==7?(left?(rtl?tw*scale-available:0):(rtl?0:tw*scale-available)):0);
                if(front!=null){front.setTranslationY(scale==1?vertical:0);front.setTranslationX(!left&&scale!=1?(rtl?-shift:shift):0);}
                if(content!=null){content.setTranslationY(scale==1?vertical:0);content.setTranslationX(left&&scale!=1?(rtl?shift:-shift):0);}
                for(TextView t:new TextView[]{front,title,content})if(t!=null){ViewGroup.LayoutParams p=t.getLayoutParams();p.width=width(t);t.setLayoutParams(p);}
                if(fade!=null){fade.fading=step==7;fade.fadeGravity=sameDirection(title.getText(),rtl)?1:0;ViewGroup.LayoutParams p=fade.getLayoutParams();p.width=width;fade.setLayoutParams(p);fade.invalidate();}
            }
        }
        private final class ClockBinding {
            final Clock clock;final TextRow text;ClockBinding(Clock clock,TextRow text){this.clock=clock;this.text=text;}
            void update(long now){text.setTitle(clock.text(now));}
        }
        private final class IconModule {
            final View root;final JSONObject data;View child;
            IconModule(View root,JSONObject data)throws Exception{this(root,data,body);}
            IconModule(View root,JSONObject data,View effectTarget)throws Exception{
                if(root==null)throw new IllegalArgumentException("Missing source icon container");this.root=root;this.data=data;icons.add(this);if(data==null){root.setVisibility(View.GONE);return;}
                int type=data.optInt("type",0);String pic=data.optString("pic",null),slot;
                if(type==3&&"flash_light_icon".equals(pic))slot="island_flash_icon_stub";
                else if(type==2||type==7)slot=type==7?"island_lottie_bigger_stub":"island_lottie_stub";
                else if(type==1)slot="island_fix_icon_stub";else if(type==3||type==4)slot="island_icon_stub";else if(type==5)slot="island_small_icon_stub";else if(type==6)slot="island_video_stub";else slot="island_app_icon_stub";
                child=stub(root,slot);if(child==null)throw new IllegalArgumentException("Missing source icon slot "+slot);child.setVisibility(View.VISIBLE);
                child.setContentDescription(data.optString("contentDescription",null));
                if(child instanceof HyperOsLottieView){HyperOsLottieView a=(HyperOsLottieView)child;String raw=rawName(pic);a.setOwnerVisible(ownerVisible&&visibleOwner(a));a.setAnimation(source,raw,data.optBoolean("autoplay",false),data.optBoolean("loop",false),data.optInt("number",-1));a.setCompactNumber(data.optInt("number",-1));if(raw.equals("music_wave")||raw.equals("music_pause")){a.setGradient(topColor,bottomColor);musicAnimations.add(a);}animations.add(a);}
                else if(child instanceof ImageView&&!slot.equals("island_flash_icon_stub")){Drawable icon=type==0?copy(item.appIcon!=null?item.appIcon:item.icon):picture(pic);if(icon==null)icon=copy(item.appIcon!=null?item.appIcon:item.icon);((ImageView)child).setImageDrawable(icon);if(icon==null)child.setVisibility(View.GONE);else {if("miui_media_album_icon".equals(pic))albumDrawable=icon;icon.setAutoMirrored(false);if(type==1||type==5){child.setOutlineProvider(new ViewOutlineProvider(){@Override public void getOutline(View v,Outline outline){outline.setRoundRect(0,0,v.getWidth(),v.getHeight(),dim("island_icon_radius"));}});child.setClipToOutline(true);}}}
                else if(child instanceof Video){((Video)child).bind(pic,data.optBoolean("loop",false));videos.add((Video)child);}
                if(type==3&&"call".equals(data.optString("effectSrc")))callingEffect(effectTarget,child.getLayoutParams().width);
            }
            int width(){if(child==null||child.getVisibility()==View.GONE)return 0;root.measure(unspecified(),exactSpec(dim("island_height")));return root.getMeasuredWidth();}
        }
        private JSONObject canonical(long now)throws Exception{
            JSONObject big=new JSONObject(),small=new JSONObject(),result=new JSONObject().put("bigIslandArea",big).put("smallIslandArea",small);
            String pic="hyperos-owned-icon";Drawable icon=item.icon!=null?item.icon:item.appIcon;localPictures.put(pic,icon);
            JSONObject leftPic=new JSONObject().put("type",1).put("pic",pic),smallPic=leftPic;
            if(item.isMedia){pic="miui_media_album_icon";Drawable album=item.art==null?item.appIcon:new BitmapDrawable(source.getResources(),item.art);if(album==null)album=drawable("ic_music_note");localPictures.put(pic,album);leftPic=new JSONObject().put("type",1).put("pic",pic);smallPic=leftPic;big.put("imageTextInfoRight",imageText(2,new JSONObject().put("type",2).put("pic",item.playing?"musicWave":"musicPause").put("autoplay",item.playing),null));}
            else if(item.kind==IslandItem.Kind.CALL){
                HyperOsResources call=callSource==null?HyperOsAssets.call(context):callSource;int id=call.id("drawable","ic_focus_small_island_call_logo");localPictures.put(pic,call.getResources().getDrawable(id,call.getTheme()));
                leftPic.put("type",HyperOsIncomingCalls.isIncoming(item)?3:1).put("effectSrc","call");smallPic=new JSONObject().put("type",1).put("pic",pic);
                if(item.hasChronometer()&&!HyperOsIncomingCalls.isIncoming(item))big.put("sameWidthDigitInfo",new JSONObject().put("timerInfo",itemTimer(item,now)));
                else big.put("textInfo",new JSONObject().put("title",HyperOsIncomingCalls.isIncoming(item)?"Incoming call":item.subtitle==null?item.title:item.subtitle));
            }else if(item.kind==IslandItem.Kind.RECORDING){
                if(item.recorder&&!item.recorderSaved){leftPic=new JSONObject().put("type",2).put("pic","voiceWaveSmall").put("autoplay",item.pausedMs<0);smallPic=leftPic;}
                if(item.hasChronometer()&&!item.recorderSaved)big.put("sameWidthDigitInfo",new JSONObject().put("timerInfo",itemTimer(item,now)));else big.put("textInfo",new JSONObject().put("title",item.title));
            }else if(item.kind==IslandItem.Kind.TIMER){
                HyperOsNative.ClockCardPlan plan=HyperOsNative.clockCardPlan(item);
                leftPic=new JSONObject().put("type",2).put("pic",plan.left).put("autoplay",plan.autoplay);smallPic=new JSONObject().put("type",2).put("pic",plan.small).put("autoplay",plan.autoplay);
                if(plan.grammar==0)big.put("sameWidthDigitInfo",new JSONObject().put("timerInfo",itemTimer(item,now)));
                else if(plan.grammar==1)big.put("sameWidthDigitInfo",new JSONObject().put("digit",item.clockText));
                else big.put("textInfo",new JSONObject().put("title",item.trailingText==null?item.title:item.trailingText));
            }else if(item.kind==IslandItem.Kind.CHARGING){
                String title=item.ringPercent>=0?item.ringPercent+"%":item.trailingText==null?String.valueOf(item.title):item.trailingText.toString();String state=String.valueOf(item.title).toLowerCase(Locale.ROOT);
                big.put("imageTextInfoRight",imageText(4,new JSONObject().put("type",3).put("pic",state.contains("power sav")?"power_save_mode":state.contains("fast")||state.contains("quick")?"quick_charger_mode":"charger_mode"),new JSONObject().put("title",title)));
            }else if(item.torch){leftPic=new JSONObject().put("type",3).put("pic","flash_light_icon");smallPic=leftPic;}
            else if(item.trailing==IslandItem.Trailing.CHRONOMETER)big.put("sameWidthDigitInfo",new JSONObject().put("timerInfo",itemTimer(item,now)));
            else {JSONObject text=new JSONObject().put("title",item.trailingText==null?item.title:item.trailingText);if(item.subtitle!=null)text.put("content",item.subtitle);
                if(item.progress>=0)big.put("progressTextInfo",new JSONObject().put("textInfo",text).put("progressInfo",new JSONObject().put("progress",Math.round(item.progress*100)).put("colorReach",String.format(Locale.ROOT,"#%08X",item.accent))));else big.put("textInfo",text);}
            big.put("imageTextInfoLeft",imageText(1,leftPic,null));small.put("picInfo",smallPic);return result;
        }
        private void requestPalette(Bitmap art){
            if(musicAnimations.isEmpty())return;
            final Drawable drawable=albumDrawable;final Bitmap bitmap=drawable instanceof BitmapDrawable?((BitmapDrawable)drawable).getBitmap():drawable==null?art:null;
            if(bitmap!=null&&bitmap.isRecycled()||bitmap==null&&drawable==null)return;
            final String key=bitmap!=null?System.identityHashCode(bitmap)+":"+bitmap.getGenerationId()+":"+bitmap.getWidth()+":"+bitmap.getHeight():"drawable:"+System.identityHashCode(drawable)+':'+drawable.getIntrinsicWidth()+':'+drawable.getIntrinsicHeight();
            if(key.equals(paletteKey))return;paletteKey=key;final long token=generation;
            int[] cached;synchronized(PALETTES){cached=PALETTES.get(key);}if(cached!=null){applyPalette(cached);return;}
            PALETTE_IO.execute(()->{Bitmap temporary=null;try{
                Bitmap input=bitmap;
                if(input==null){Drawable d=copy(drawable);if(d==drawable&&drawable.getConstantState()==null)return;int w=Math.max(1,d.getIntrinsicWidth()),h=Math.max(1,d.getIntrinsicHeight());if((long)w*h>16*1024*1024)return;temporary=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);d.setBounds(0,0,w,h);d.draw(new Canvas(temporary));input=temporary;}
                else if(input.getConfig()==Bitmap.Config.HARDWARE){temporary=input.copy(Bitmap.Config.ARGB_8888,false);input=temporary;}
                int w=input.getWidth(),h=input.getHeight();if((long)w*h>16*1024*1024)return;
                float ratio=Math.min(1f,64f/Math.max(w,h));
                if(ratio<1f){Bitmap scaled=Bitmap.createScaledBitmap(input,Math.max(1,Math.round(w*ratio)),Math.max(1,Math.round(h*ratio)),true);
                    if(temporary!=null&&temporary!=scaled)temporary.recycle();temporary=scaled;input=scaled;w=input.getWidth();h=input.getHeight();}
                int[] pixels=new int[w*h];input.getPixels(pixels,0,w,0,0,w,h);int[] result=new int[4];HyperOsNative.nativeArtworkPalette(pixels,w,h,result);synchronized(PALETTES){PALETTES.put(key,result);}main.post(()->{if(!released&&token==generation&&key.equals(paletteKey))applyPalette(result);});
            }catch(Throwable unavailable){/* Original default gradient stays white while no artwork palette can be produced. */}finally{if(temporary!=null)temporary.recycle();}});
        }
        private void applyPalette(int[] palette){topColor=palette[1];bottomColor=palette[2];for(HyperOsLottieView a:musicAnimations)a.setGradient(topColor,bottomColor);}
    }

    /** Exact seven source width states; title is scaled uniformly rather than squeezed horizontally. */
    public static int fitStep(int front,int title,int content,int gap,int available){
        int space=space(front,content,gap),a=front+title,b=a+content;float scaled=title*.79f,partial=front+scaled;
        if(available>=b+space)return 1;if((float)available>=content+partial+space)return 2;if(available>=a+space)return 3;if((float)available>=partial+space)return 4;if(available>=title)return 5;if((float)available>=scaled)return 6;return 7;
    }
    public static String selectModule(String side,JSONObject big,JSONObject small){
        JSONObject l=big.optJSONObject("imageTextInfoLeft"),r=big.optJSONObject("imageTextInfoRight");
        if(side.equals("left")){if(l==null)return "";int t=l.optInt("type",0);if(t==1)return "moduleImageText_1";if(t==5)return "moduleIconFixedWidthText";throw new IllegalArgumentException("Source left type must be1or5");}
        if(side.equals("small")){if(small!=null&&small.optJSONObject("combinePicInfo")!=null)return "moduleCombinePic";return r!=null&&r.optInt("type")==6?"moduleSmallTextOverIcon":"modulePicSmallIsland";}
        if(r!=null){switch(r.optInt("type")){case 2:return "moduleImageText_2";case 3:return "moduleImageText_3";case 4:return "moduleImageText_4";case 6:return "moduleTextOverIcon";}}
        String[] fields={"fixedWidthDigitInfo","sameWidthDigitInfo","progressTextInfo","textInfo","picInfo"},types={"moduleFixedWidthDigit","moduleSameWidthDigit","moduleImageText","moduleText","modulePic"};for(int i=0;i<fields.length;i++)if(big.optJSONObject(fields[i])!=null)return types[i];return "";
    }
    private static String layoutFor(String type){switch(type){case "modulePic":case "modulePicSmallIsland":return "dynamic_island_module_icon_1";case "moduleText":return "dynamic_island_module_right_text";case "moduleImageText":return "dynamic_island_module_progress_text";case "moduleCombinePic":return "dynamic_island_module_combine_image";case "moduleSameWidthDigit":return "dynamic_island_module_same_width_digit";case "moduleFixedWidthDigit":return "dynamic_island_module_fixed_width_digit";case "moduleIconFixedWidthText":return "dynamic_island_module_icon_fixed_width_text";case "moduleTextOverIcon":case "moduleSmallTextOverIcon":return "dynamic_island_module_text_over_icon";default:return "dynamic_island_module_image_text_"+type.substring(type.lastIndexOf('_')+1);}}
    private static JSONObject island(JSONObject full){JSONObject value=full.optJSONObject("param_island");if(value!=null)return value;if(full.optJSONObject("bigIslandArea")!=null)return full;for(String key:new String[]{"param_v3","param_v2"}){JSONObject p=full.optJSONObject(key);if(p!=null){value=island(p);if(value!=null)return value;}}return null;}
    private static JSONObject imageText(int type,JSONObject pic,JSONObject text)throws Exception{JSONObject j=new JSONObject().put("type",type);if(pic!=null)j.put("picInfo",pic);if(text!=null)j.put("textInfo",text);return j;}
    private static JSONObject itemTimer(IslandItem item,long now)throws Exception{long epoch=System.currentTimeMillis(),elapsed=item.pausedMs>=0?item.pausedMs:item.chronoCountDown?item.chronoBase-now:now-item.chronoBase;return new JSONObject().put("timerType",(item.chronoCountDown?-1:1)*(item.pausedMs>=0?2:1)).put("timerWhen",epoch+(item.chronoCountDown?elapsed:-elapsed)).put("timerSystemCurrent",epoch);}
    private static String shape(IslandItem i,Payload p){
        if(p!=null&&p.nativeIsland)return i.key+':'+p.version;
        // The media compact actually contains artwork and a waveform. Title,
        // route and position updates belong to its expanded card and must not
        // restart that waveform or replace its otherwise unchanged views.
        if(i.isMedia)return i.key+':'+(p==null?0:p.version)+":media:"+i.playing+':'+bitmapIdentity(i.art)+':'+drawableIdentity(i.icon);
        return i.key+':'+(p==null?0:p.version)+':'+i.kind+':'+i.playing+':'+i.pausedMs+':'+i.chronoBase+':'+i.clockStyle+':'+String.valueOf(i.clockText)+':'+i.recorderSaved+':'+i.recorder+':'+i.torch+':'+i.ringPercent+':'+i.progress+':'+String.valueOf(i.title)+':'+String.valueOf(i.subtitle)+':'+String.valueOf(i.trailingText)+':'+bitmapIdentity(i.art)+':'+drawableIdentity(i.icon);
    }
    private static String bitmapIdentity(Bitmap bitmap){return bitmap==null?"none":System.identityHashCode(bitmap)+":"+bitmap.getGenerationId();}
    private static String drawableIdentity(Drawable drawable){
        if(drawable==null)return "none";
        if(drawable instanceof BitmapDrawable)return bitmapIdentity(((BitmapDrawable)drawable).getBitmap());
        Drawable.ConstantState state=drawable.getConstantState();return "drawable:"+System.identityHashCode(state==null?drawable:state);
    }
    private static long pictureFingerprint(Bundle pictures){if(pictures==null)return 0;long hash=1;for(String key:new TreeSet<>(pictures.keySet())){Object v=pictures.get(key);hash=hash*31+key.hashCode();hash=hash*31+(v==null?0:v.hashCode());if(v instanceof Bitmap)hash=hash*31+((Bitmap)v).getGenerationId();}return hash;}
    private static int width(TextView t){return t==null?0:(int)t.getPaint().measureText(t.getText().toString());}
    private static int space(int front,int content,int gap){return front>0&&content>0?gap*2:front>0||content>0?gap:0;}
    private static boolean validFixedDigit(String text){int digits=0,dots=0;for(int i=0;i<text.length();i++){char c=text.charAt(i);if(Character.isDigit(c))digits++;else if(c=='.')dots++;else return false;}return digits<=3&&dots<=1;}
    private static void show(View v,boolean visible){if(v!=null)v.setVisibility(visible?View.VISIBLE:View.GONE);}
    private static int exactSpec(int value){return View.MeasureSpec.makeMeasureSpec(Math.max(0,value),View.MeasureSpec.EXACTLY);}
    private static int unspecified(){return View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED);}
    private static void exact(View v,int w,int h){v.measure(exactSpec(w),exactSpec(h));v.layout(0,0,w,h);}
    private static int dp(Context c,float v){return Math.round(c.getResources().getDisplayMetrics().density*v);}
    private static Drawable copy(Drawable d){return d==null?null:d.getConstantState()==null?d:d.getConstantState().newDrawable().mutate();}
    private static int parseColor(String s,int fallback){if(s==null||s.length()==0)return fallback;try{return Color.parseColor(s);}catch(IllegalArgumentException e){return fallback;}}
    private static boolean sameDirection(CharSequence text,boolean rtl){for(int i=0;i<text.length();i++){byte d=Character.getDirectionality(text.charAt(i));if(d==0)return !rtl;if(d==1||d==2)return rtl;}return true;}
    private static String rawName(String value){if(value==null)return "";switch(value){case "musicWave":return "music_wave";case "musicPause":return "music_pause";case "voiceWaveSmall":return "voice_wave_small";case "voiceWaveBig":return "voice_wave_big";case "alarmSmall":return "alarm_small";case "alarmBig":return "alarm_big";case "stopwatch":return "mark_timing";case "stopwatch_big":return "mark_timing_big";default:return value;}}
    private static final class Clock {
        final boolean running,countDown,minute;final long base,frozen;
        Clock(JSONObject json,long now){int type=json.optInt("timerType");running=type==1||type==-1||type==3||type==-3;countDown=type<0;minute=Math.abs(type)==3||Math.abs(type)==4;long when=json.optLong("timerWhen",System.currentTimeMillis()),current=running?System.currentTimeMillis():json.optLong("timerSystemCurrent",System.currentTimeMillis());base=now+when-current;frozen=countDown?when-current:current-when;}
        String text(long now){long seconds=Math.max(0,running?countDown?base-now:now-base:frozen)/1000;return minute?String.format(Locale.getDefault(),"%02d:%02d",seconds/60,seconds%60):DateUtils.formatElapsedTime(seconds);}
    }
    private static final class Fade extends LinearLayout {
        final float length;final Paint mask=new Paint(Paint.ANTI_ALIAS_FLAG);boolean fading;int fadeGravity=1;
        Fade(Context c,AttributeSet a,HyperOsResources r){super(c,a);setOrientation(HORIZONTAL);length=r.getResources().getDimension(r.id("dimen","text_shade_width"));}
        @Override protected void dispatchDraw(Canvas canvas){if(!fading||getWidth()<=0){super.dispatchDraw(canvas);return;}boolean rtl=getLayoutDirection()==View.LAYOUT_DIRECTION_RTL,right=rtl?fadeGravity==0:fadeGravity==1;float offset=right?getPaddingRight():getPaddingLeft(),edge=Math.min(getWidth(),length+offset);RectF rect=right?new RectF(getWidth()-edge,0,getWidth(),getHeight()):new RectF(0,0,edge,getHeight());int save=canvas.saveLayer(0,0,getWidth(),getHeight(),null);super.dispatchDraw(canvas);float from=right?rect.left:rect.right,to=right?rect.right:rect.left;mask.setShader(new LinearGradient(from,rect.centerY(),to,rect.centerY(),offset>0?new int[]{Color.BLACK,0,0}:new int[]{Color.BLACK,0},offset>0?new float[]{0,length/(offset+length),1}:new float[]{0,1},Shader.TileMode.CLAMP));mask.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));canvas.drawRect(rect,mask);mask.setXfermode(null);canvas.restoreToCount(save);}
    }
    private static final class Ring extends View {
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);float progress;int reach=0xff4788ff,unreach=0x33ffffff;boolean ccw;
        Ring(Context c,AttributeSet a){super(c,a);}
        @Override protected void onDraw(Canvas canvas){float stroke=dp(getContext(),2),diameter=(getWidth()/2)*2;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(stroke);paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(unreach);canvas.drawArc(stroke*.5f,stroke*.5f,diameter-stroke*.5f,diameter-stroke*.5f,0,360,false,paint);paint.setColor(reach);canvas.drawArc(stroke*.5f,stroke*.5f,diameter-stroke*.5f,diameter-stroke*.5f,ccw?270:0,Math.max(0,Math.min(100,progress))*3.6f,false,paint);}
    }
    private static void bindProgress(Ring ring,JSONObject data){if(ring==null)return;ring.setVisibility(data==null?View.GONE:View.VISIBLE);if(data!=null){ring.progress=data.optInt("progress");ring.reach=parseColor(data.optString("colorReach",null),0xff4788ff);ring.unreach=parseColor(data.optString("colorUnReach",null),0x33ffffff);ring.ccw=data.optBoolean("isCCW",false);ring.invalidate();}}
    /** Original constructors, shader programs and driver parameters, behind Android-only views. */
    private static final class Device extends FrameLayout {
        final Compact compact;final boolean power;final TextView text;View icon;View textFrame;Bitmap glow;
        Device(Context c,AttributeSet a,Compact compact,boolean power){
            super(c,a);this.compact=compact;this.power=power;text=new TextView(c);text.setSingleLine(true);text.setGravity(Gravity.CENTER_VERTICAL);text.setTextSize(14);HyperOsFonts.apply(text,compact.fonts,"mipro-demibold");
            if(power){LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams t=new LinearLayout.LayoutParams(-2,-2);t.gravity=Gravity.END;t.setMarginEnd(compact.dim("power_save_text_icon_gap"));row.addView(text,t);FrameLayout iconFrame=new FrameLayout(c);icon=new View(c);iconFrame.addView(icon,new FrameLayout.LayoutParams(-1,-1));Bitmap b=compact.bitmap("power_save");LinearLayout.LayoutParams i=new LinearLayout.LayoutParams(b.getWidth(),b.getHeight());i.gravity=Gravity.END;i.setMarginEnd(compact.dim("power_save_icon_margin_end"));row.addView(iconFrame,i);addView(row,new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL));textFrame=text;}
            else {glow=compact.bitmap("charger_glow_icon");FrameLayout frame=new FrameLayout(c);textFrame=frame;text.setFontFeatureSettings("tnum");FrameLayout.LayoutParams t=new FrameLayout.LayoutParams(-2,-2,Gravity.END|Gravity.CENTER_VERTICAL);t.setMarginStart(compact.dim("charger_view_text_start_offset"));t.setMarginEnd(glow.getWidth()-(int)Math.floor(.2f*glow.getWidth()));frame.addView(text,t);addView(frame,new FrameLayout.LayoutParams(-2,glow.getHeight()));}
        }
        void bind(JSONObject data,String mode)throws Exception{
            text.setText(data.optString("title",""));text.setTextSize(0,Math.round(14*getResources().getDisplayMetrics().density));text.setTextColor(power?compact.color("power_save_text_color",0xffff9f05):Color.GREEN);if(data.optBoolean("showHighlightColor",false))text.setTextColor(compact.textColor(data,false));
            if(power){Bitmap b=compact.bitmap("power_save");RuntimeShader s=new RuntimeShader(compact.shader("power_save_battery_icon"));s.setInputShader("uTexBattery",new BitmapShader(b,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));s.setFloatUniform("uBatterySize",b.getWidth(),b.getHeight());Effect e=new Effect(icon,s,false,"power-icon",0,0);int percent=compact.item.ringPercent;if(percent<0){android.os.BatteryManager manager=(android.os.BatteryManager)compact.context.getSystemService(Context.BATTERY_SERVICE);if(manager!=null)percent=manager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);}s.setFloatUniform("uBatteryPercentage",Math.max(0,Math.min(100,percent))/100f);compact.effects.add(e);RuntimeShader ts=new RuntimeShader(compact.shader("power_save_battery_text"));ts.setFloatUniform("uRippleTime",0f);compact.effects.add(new Effect(text,ts,false,"power-text",0,0));}
            else {HyperOsLottieView lottie=new HyperOsLottieView(getContext());icon=lottie;boolean fast=mode.equals("quick_charger_mode");lottie.setAnimation(compact.source,"charger_icon_lottie",fast,false,1);lottie.setCompactNumber(1);addView(lottie,new FrameLayout.LayoutParams(glow.getWidth(),glow.getHeight(),Gravity.END));compact.animations.add(lottie);
                RuntimeShader is=new RuntimeShader(compact.shader("charger_icon"));is.setInputShader("uChargingIconGlow",new BitmapShader(glow,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP));is.setFloatUniform("uGlowSize",glow.getWidth(),glow.getHeight());is.setFloatUniform("uGlowOffset",.92f);is.setFloatUniform("uP11",0f);is.setFloatUniform("uP12",.25f);is.setFloatUniform("uP21",.5f);is.setFloatUniform("uP22",1f);compact.effects.add(new Effect(lottie,is,true,"charger-icon",glow.getWidth(),0));RuntimeShader ts=new RuntimeShader(compact.shader("charger_light_text_wave"));ts.setFloatUniform("uScale",1f);compact.effects.add(new Effect(textFrame,ts,true,"charger-text",glow.getWidth(),0));
                text.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{String prefix="charger_view_text_gradient_color_";int start=compact.color(prefix+"start",Color.WHITE),middle=compact.color(prefix+"middle",start),end=compact.color(prefix+"end",middle);text.getPaint().setShader(new LinearGradient(text.getLayoutDirection()==1?text.getWidth():0,0,text.getLayoutDirection()==1?0:text.getWidth(),0,new int[]{start,middle,end},new float[]{0,.6f,1},Shader.TileMode.CLAMP));});
            }
        }
        int natural(){measure(unspecified(),unspecified());return getMeasuredWidth();}
    }
    private static final class Effect {
        final View target;final RuntimeShader shader;final boolean animated;final String kind;final float iconWidth,offset;final long start=SystemClock.elapsedRealtime();long last=-1;float glowTime;
        Effect(View target,RuntimeShader shader,boolean animated,String kind,float iconWidth,float offset){this.target=target;this.shader=shader;this.animated=animated;this.kind=kind;this.iconWidth=iconWidth;this.offset=offset;}
        void size(){int w=target.getWidth(),h=target.getHeight();if(w<=0||h<=0)return;shader.setFloatUniform("uResolution",w,h);boolean rtl=target.getLayoutDirection()==1;
            if(kind.equals("call"))shader.setFloatUniform("uRippleOrigin",rtl?Math.max(0,w-offset-iconWidth*.5f):iconWidth*.5f+offset,h*.5f);
            else if(kind.equals("charger-text"))shader.setFloatUniform("uRippleOrigin",rtl?iconWidth*.5f:Math.max(0,w-iconWidth*.5f),h*.5f);
            else if(kind.equals("power-text")){shader.setFloatUniform("uRippleOrigin",w*.86f,h*.5f);shader.setIntUniform("uRTL",rtl?1:0);}
            else if(kind.equals("power-icon"))shader.setIntUniform("uRTL",rtl?1:0);
            target.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader,"uTex"));
        }
        void frame(long now){if(animated){if(last>=0)glowTime+=((now-last)/1000f)/2f;last=now;shader.setFloatUniform("uTime",(now-start)/1000f);if(kind.equals("charger-icon"))shader.setFloatUniform("uGlowTime",glowTime);else if(kind.equals("charger-text"))shader.setFloatUniform("uRippleTime",glowTime);}size();}
    }
    private static final class Video extends TextureView implements TextureView.SurfaceTextureListener {
        final Compact compact;MediaPlayer player;Surface surface;String uri;boolean loop,prepared,ownerVisible=true;
        Video(Context c,AttributeSet a,Compact compact){super(c,a);this.compact=compact;setSurfaceTextureListener(this);setOpaque(false);}
        void bind(String uri,boolean loop){this.uri=uri;this.loop=loop;if(isAvailable())open(getSurfaceTexture());}
        void open(SurfaceTexture texture){release();ownerVisible=compact.ownerVisible;if(uri==null||texture==null)return;try{surface=new Surface(texture);player=new MediaPlayer();player.setSurface(surface);if(uri.equals("doubleCharge")){try(android.content.res.AssetFileDescriptor f=compact.source.getResources().openRawResourceFd(compact.source.id("raw","double_charge"))){player.setDataSource(f.getFileDescriptor(),f.getStartOffset(),f.getLength());}}else player.setDataSource(compact.context,Uri.parse(uri));player.setOnPreparedListener(p->{prepared=true;p.setLooping(loop);if(ownerVisible)p.start();});player.prepareAsync();}catch(Exception failure){release();compact.failure="Original video could not be opened: "+failure.getMessage();}}
        void setOwnerVisible(boolean visible){ownerVisible=visible;if(player==null||!prepared)return;try{if(visible&&!player.isPlaying())player.start();else if(!visible&&player.isPlaying())player.pause();}catch(IllegalStateException failure){compact.failure="Original video visibility failed: "+failure.getMessage();}}
        void release(){prepared=false;if(player!=null){player.release();player=null;}if(surface!=null){surface.release();surface=null;}}
        @Override public void onSurfaceTextureAvailable(SurfaceTexture t,int w,int h){open(t);}
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture t,int w,int h){}
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture t){release();return true;}
        @Override public void onSurfaceTextureUpdated(SurfaceTexture t){}
    }
}
