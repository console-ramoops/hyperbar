package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.widget.TextView;
import java.io.File;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/** Donor FontWght/MultiLangHelper policy; Android retains shaping and text spacing. */
public final class HyperOsFonts {
    private static final String[] GRADES={"thin","extralight","light","normal","regular","medium","demibold","semibold","bold","heavy"};
    private static final int[] LIGHT={150,200,250,305,330,380,450,520,630,700};
    private static final int[] DARK={150,175,225,285,310,360,430,500,600,650};
    private static final String LATIN_LANGUAGES="az ms bs ca cs da de et en es eu fr gl ha hr id in it lv lt hu mt nl nb uz pl pt ro sq sk sl fi sv vi tr el be bg kk mk ru sr uk";
    private static final Map<String,Typeface> FACES=new LinkedHashMap<String,Typeface>(16,.75f,true){
        @Override protected boolean removeEldestEntry(Map.Entry<String,Typeface> entry){return size()>48;}
    };
    private static final Map<TextView,AppliedStyle> STYLES=new WeakHashMap<>();
    private static final class AppliedStyle {
        final int requested;final Typeface applied;
        AppliedStyle(int requested,Typeface applied){this.requested=requested;this.applied=applied;}
    }
    private HyperOsFonts(){}

    /** Source manager reads the application UI config, not a forced-dark resource carrier. */
    private static Context configurationContext(Context context){
        while(context instanceof HyperOsResources)context=((HyperOsResources)context).getBaseContext();
        return context;
    }
    public static boolean night(Context context){
        return (configurationContext(context).getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
    }
    public static Locale locale(Context context){
        Configuration c=configurationContext(context).getResources().getConfiguration();
        return c.getLocales().isEmpty()?Locale.getDefault():c.getLocales().get(0);
    }
    public static String namedFilename(Context context){
        String language=locale(context).getLanguage();
        // Exact donor locales include en-GB/en-IN/en-US; its global family also
        // prepends the Latin VF for the corresponding language group.
        for(String allowed:LATIN_LANGUAGES.split(" "))if(allowed.equals(language))return "MiSansLatinVF.ttf";
        return "MiSansVF.ttf";
    }
    private static int grade(String family){
        if("mipro".equals(family)||"miui".equals(family))return 4;
        if("miui-bold".equals(family))return 5;
        if(family==null)return -1;
        for(int i=0;i<GRADES.length;i++)if(family.equals("mipro-"+GRADES[i]))return i;
        return -1;
    }
    /** Default donor slider50 returns its exact middle weight, for every size grade. */
    public static int namedAxis(Context context,String family,int requestedStyle){
        int index=grade(family);if(index<0)return -1;
        if((requestedStyle&Typeface.BOLD)!=0&&index<GRADES.length-1)index++;
        return (night(context)?DARK:LIGHT)[index];
    }
    public static Typeface face(Context context,String family){
        return face(context,HyperOsAssets.fonts(context),family,Typeface.NORMAL);
    }
    public static Typeface face(Context context,File directory,String family,int requestedStyle){
        if("misans-condensed".equals(family))return build(directory,"MiSansCondensed2T.ttf",450,30,false,
            "condensed:"+locale(context).toLanguageTag()+':'+night(context));
        int axis=namedAxis(context,family,requestedStyle);if(axis<0)return null;
        return build(directory,namedFilename(context),axis,0,(requestedStyle&Typeface.ITALIC)!=0,
            family+':'+locale(context).toLanguageTag()+':'+night(context)+':'+requestedStyle);
    }
    /** Source BaseIslandModuleViewHolder uses this explicit file/axis for compact digits. */
    public static Typeface fixedDigits(Context context){return fixedDigits(HyperOsAssets.fonts(context));}
    public static Typeface fixedDigits(File directory){return build(directory,"MiSansVF.ttf",450,0,false,"island-fixed-digits");}

    private static synchronized Typeface build(File directory,String name,int axis,int width,boolean italic,String profile){
        File font=new File(directory,name);
        if(!font.isFile())throw new IllegalStateException("Missing exact HyperOS font "+font);
        String[] fallbacks=width>0?new String[]{"MiSansLatinVF.ttf","MiSansVF.ttf"}:
            "MiSansLatinVF.ttf".equals(name)?new String[]{"MiSansVF.ttf"}:new String[]{"MiSansLatinVF.ttf"};
        String key=identity(font)+':'+axis+':'+width+':'+italic+':'+profile;
        for(String fallback:fallbacks)key+=':'+identity(new File(directory,fallback));
        Typeface cached=FACES.get(key);if(cached!=null)return cached;
        try {
            Font primary=font(font,axis,width,italic);
            Typeface.CustomFallbackBuilder builder=new Typeface.CustomFallbackBuilder(new FontFamily.Builder(primary).build());
            // Donor global English family puts Latin first, then MiSans CJK.
            // Condensed has only132 characters and needs the same donor fallback.
            // Explicit families prevent a Samsung FlipFont DEFAULT change from
            // replacing missing punctuation/CJK in an otherwise identical face.
            for(String fallback:fallbacks)builder.addCustomFallback(new FontFamily.Builder(font(new File(directory,fallback),axis,0,italic)).build());
            Typeface face=builder.setStyle(primary.getStyle()).setSystemFallback("sans-serif").build();
            if(face==null)throw new IllegalStateException("Could not decode HyperOS font "+font);
            FACES.put(key,face);return face;
        } catch(java.io.IOException failure){throw new IllegalStateException("Could not decode HyperOS font "+font,failure);}
    }
    private static String identity(File font){return font.getAbsolutePath()+':'+font.length()+':'+font.lastModified();}
    private static Font font(File file,int axis,int width,boolean italic)throws java.io.IOException{
        if(!file.isFile())throw new IllegalStateException("Missing exact HyperOS font "+file);
        return new Font.Builder(file).setFontVariationSettings("'wght' "+axis+(width>0?", 'wdth' "+width:"")).setSlant(italic?FontStyle.FONT_SLANT_ITALIC:FontStyle.FONT_SLANT_UPRIGHT).build();
    }
    public static void apply(TextView view,File directory,String family){
        // Samsung TextView.getTypeface() can return the default NORMAL face
        // when FlipFont is enabled, even though its Paint uses our exact VF.
        // Read the face that actually renders the glyphs.
        Typeface current=view.getPaint().getTypeface();int style;
        synchronized(STYLES){
            AppliedStyle previous=STYLES.get(view);
            // Custom VF faces may expose NORMAL on the host even for a bold
            // donor grade. Retain the original textStyle across config rebinds.
            style=previous!=null&&current==previous.applied?previous.requested:
                current==null?Typeface.NORMAL:current.getStyle();
        }
        Typeface face=face(view.getContext(),directory,family,style);if(face==null)return;
        view.setTypeface(face);
        synchronized(STYLES){STYLES.put(view,new AppliedStyle(style,view.getPaint().getTypeface()));}
        // Donor named-font boldness is already encoded by its grade/axis.
        // Plain TextView.setTypeface retains stale fakeBold, as the ART probe
        // confirms; do not add a second synthetic stroke on top of that grade.
        view.getPaint().setFakeBoldText(false);
        view.getPaint().setTextSkewX((style&Typeface.ITALIC)!=0&&(face.getStyle()&Typeface.ITALIC)==0?-.25f:0f);
    }
    public static void applyFixedDigits(TextView view,File directory){
        view.setTypeface(fixedDigits(directory));view.getPaint().setFakeBoldText(false);view.getPaint().setTextSkewX(0f);
        synchronized(STYLES){STYLES.remove(view);}
        // Original IslandDigitStyle declares tabular digits; no letter-spacing change.
        view.setFontFeatureSettings("tnum");
    }
    public static synchronized void clear(){FACES.clear();}
}
