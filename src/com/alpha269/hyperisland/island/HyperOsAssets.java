package com.alpha269.hyperisland.island;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Materializes the module's resource-only carriers in the host's private cache. */
public final class HyperOsAssets {
    private static final String MODULE = "com.alpha269.hyperisland";
    private static final String PREFIX = "assets/hyperos/";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(job -> {
        Thread thread = new Thread(job,"HyperOS-resource-preparation");
        thread.setDaemon(true); return thread;
    });
    private static final Map<String,File> FILES = new LinkedHashMap<>();
    private static final Map<String,HyperOsResources> RESOURCES =
        new LinkedHashMap<String,HyperOsResources>(8,.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String,HyperOsResources> entry) {
                return size()>6;
            }
        };
    private HyperOsAssets() {}

    public interface Prepared {
        void ready();
        void failed(Throwable failure);
    }

    /** Large font/resource copies happen before the replacement is advertised. */
    public static void prepare(Context context, Prepared completion) {
        final Handler main = new Handler(Looper.getMainLooper());
        IO.execute(() -> {
            try {
                java.util.Set<String> keepFiles = new java.util.HashSet<>();
                for (String name : new String[]{
                    "plugin-res.apk", "call-res.apk", "systemui-res.apk", "lottie-runtime.dex",
                    "fonts/MiSansVF.ttf", "fonts/MiSansLatinVF.ttf", "fonts/MiSansCondensed2T.ttf"
                }) {
                    keepFiles.add(asset(context, name).getName());
                }
                for (String opt : new String[]{"glass_material.sksl", "glass_normal.sksl", "smooth_corner_mips.bin"}) {
                    try { keepFiles.add(asset(context, opt).getName()); } catch (Throwable ignored) {}
                }
                File fontDir = fonts(context);
                pruneStaleCache(context.getCodeCacheDir(), keepFiles, fontDir == null ? null : fontDir.getName());
                main.post(completion::ready);
            } catch(Throwable failure) {
                main.post(() -> completion.failed(failure));
            }
        });
    }

    private static void pruneStaleCache(File cacheRoot, java.util.Set<String> keepAssets, String keepFontDir) {
        if (cacheRoot == null || !cacheRoot.isDirectory()) return;
        try {
            File assetsDir = new File(cacheRoot, "hyperos-assets");
            File[] assetEntries = assetsDir.listFiles();
            if (assetEntries != null) {
                for (File f : assetEntries) {
                    String n = f.getName();
                    if ("lottie-opt".equals(n) || n.endsWith(".tmp") || keepAssets.contains(n)) continue;
                    if (f.isFile()) f.delete();
                }
            }
            File[] rootEntries = cacheRoot.listFiles();
            if (rootEntries != null && keepFontDir != null) {
                for (File f : rootEntries) {
                    String n = f.getName();
                    if (f.isDirectory() && n.startsWith("hyperos-fonts-") && !n.equals(keepFontDir)) {
                        File[] children = f.listFiles();
                        if (children != null) for (File c : children) c.delete();
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public static HyperOsResources plugin(Context context) {
        return resources(context,"plugin-res.apk","miui.systemui.plugin");
    }
    public static HyperOsResources call(Context context) {
        return resources(context,"call-res.apk","com.android.incallui");
    }
    public static HyperOsResources systemUi(Context context) {
        return resources(context,"systemui-res.apk","com.android.systemui");
    }

    private static synchronized HyperOsResources resources(Context context,String carrier,String name) {
        try {
            File path = asset(context,carrier);
            String key = path.getPath() + ':' + context.getResources().getConfiguration().toString()
                + ':' + context.getResources().getDisplayMetrics().densityDpi;
            HyperOsResources existing = RESOURCES.get(key);
            if (existing != null) return existing;
            HyperOsResources result = new HyperOsResources(context,path.getPath(),name,fonts(context),true);
            RESOURCES.put(key,result); return result;
        } catch(Exception failure) {
            throw new IllegalStateException("Could not open HyperOS " + carrier,failure);
        }
    }

    public static File fonts(Context context) {
        try {
            File regular = asset(context,"fonts/MiSansVF.ttf");
            File latin = asset(context,"fonts/MiSansLatinVF.ttf");
            File condensed = asset(context,"fonts/MiSansCondensed2T.ttf");
            // Their content-addressed names differ, but a Typeface reads the exact
            // original filename under this immutable carrier-version directory.
            File directory = new File(context.getCodeCacheDir(),"hyperos-fonts-"
                + regular.getName().substring(0,regular.getName().indexOf('-')) + '-'
                + latin.getName().substring(0,latin.getName().indexOf('-')) + '-'
                + condensed.getName().substring(0,condensed.getName().indexOf('-')));
            ensureDirectory(directory);
            copyVerified(regular,new File(directory,"MiSansVF.ttf"));
            copyVerified(latin,new File(directory,"MiSansLatinVF.ttf"));
            copyVerified(condensed,new File(directory,"MiSansCondensed2T.ttf"));
            return directory;
        } catch(IOException failure) {
            throw new IllegalStateException("Could not prepare HyperOS fonts",failure);
        }
    }

    public static File asset(Context context,String relative) throws IOException {
        try {
            ApplicationInfo module = context.getPackageManager().getApplicationInfo(MODULE,0);
            return asset(new File(module.sourceDir),context.getCodeCacheDir(),relative);
        } catch(android.content.pm.PackageManager.NameNotFoundException failure) {
            throw new IOException("Dynamic Island package is unavailable",failure);
        }
    }

    /** Explicit paths support isolated carrier verification without an installed update. */
    public static synchronized File asset(File module,File privateCache,String relative) throws IOException {
        if (relative==null || relative.isEmpty() || relative.startsWith("/")
            || relative.contains("..") || relative.contains("\\"))
            throw new IOException("Invalid HyperOS asset name");
        String key = module.getCanonicalPath() + ':' + module.lastModified() + ':' + relative;
        File prepared = FILES.get(key);
        if (prepared!=null && prepared.isFile()) return prepared;
        try(ZipFile zip = new ZipFile(module)) {
            ZipEntry entry = zip.getEntry(PREFIX + relative);
            if (entry==null || entry.isDirectory() || entry.getSize()<0 || entry.getSize()>128L*1024*1024)
                throw new IOException("Missing or invalid HyperOS asset " + relative);
            File directory = new File(privateCache,"hyperos-assets"); ensureDirectory(directory);
            String basename = relative.substring(relative.lastIndexOf('/')+1);
            File result = new File(directory,Long.toHexString(entry.getCrc()) + '-' + basename);
            if (!matches(result,entry.getSize(),entry.getCrc())) {
                File temporary = File.createTempFile("hyperos-", ".tmp",directory);
                boolean committed = false;
                try {
                    CRC32 crc = new CRC32(); long bytes = 0;
                    try(InputStream input = zip.getInputStream(entry);
                        FileOutputStream output = new FileOutputStream(temporary)) {
                        byte[] buffer = new byte[32768]; int count;
                        while((count=input.read(buffer))!=-1) {
                            output.write(buffer,0,count); crc.update(buffer,0,count); bytes += count;
                        }
                        output.getFD().sync();
                    }
                    if (bytes!=entry.getSize() || crc.getValue()!=entry.getCrc())
                        throw new IOException("HyperOS asset integrity mismatch: " + relative);
                    if (!temporary.renameTo(result)) throw new IOException("Could not commit " + relative);
                    committed = true;
                } finally {
                    if (!committed) temporary.delete();
                }
            }
            FILES.put(key,result); return result;
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IOException("Could not create private HyperOS resource directory");
    }
    private static boolean matches(File file,long size,long crc) throws IOException {
        if (!file.isFile() || file.length()!=size) return false;
        CRC32 actual = new CRC32();
        try(InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int count;
            while((count=input.read(buffer))!=-1) actual.update(buffer,0,count);
        }
        return actual.getValue()==crc;
    }
    private static void copyVerified(File source,File destination) throws IOException {
        if (destination.isFile() && destination.length()==source.length()) return;
        File temporary = File.createTempFile("hyperos-font-", ".tmp",destination.getParentFile());
        boolean committed = false;
        try {
            try(InputStream input = new FileInputStream(source);FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[32768]; int count;
                while((count=input.read(buffer))!=-1) output.write(buffer,0,count);
                output.getFD().sync();
            }
            if (temporary.length()!=source.length() || !temporary.renameTo(destination))
                throw new IOException("Could not commit donor font");
            committed = true;
        } finally { if (!committed) temporary.delete(); }
    }
}
