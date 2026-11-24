package com.alpha269.hyperisland.island;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Arrays;
import java.util.Random;

/** JNI checks in an owned app_process; no SystemUI attachment or app launch. */
public final class NativeProbe {
    private static int assertions;
    public static void main(String[] args) throws Exception {
        HyperOsNative.loadPath(args[0]);
        int[] capsule = new int[9];
        HyperOsNative.nativeCapsule(new int[]{60,40,30,393,852,34,4,108,30},
            new float[]{250,64,54}, false, false, capsule);
        require(Arrays.equals(capsule, new int[]{150,60,60,121,30,133,60,43,121}),
            "Native capsule output order");
        HyperOsNative.nativeCapsule(new int[]{60,40,30,393,852,34,4,108,30},
            new float[]{250,64,54}, true, false, capsule);
        require(capsule[8] == 138, "Native RTL camera anchoring");
        int[] untouched = capsule.clone();
        invalid(() -> HyperOsNative.nativeCapsule(new int[8], new float[3],
            false, false, capsule));
        require(Arrays.equals(capsule, untouched), "Invalid geometry preserves output");

        int vectors = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader(args[1]))) {
            reader.readLine();
            String line;
            while ((line = reader.readLine()) != null) {
                String[] columns = line.split(",");
                double[] v = new double[9];
                for (int i=0;i<v.length;i++) v[i] = Double.parseDouble(columns[i]);
                // JNI accepts the visible-frame timestep range. Larger donor
                // steps are independently covered by the native solver test.
                if (v[6] > 1) continue;
                double[] out = new double[3];
                HyperOsNative.nativeSpringBatch(new double[]{v[3],v[4],v[5],
                    v[0],v[1],v[2],0,0}, 1, v[6], out);
                close(out[0],v[7]); close(out[1],v[8]);
                vectors++;
            }
        }
        require(vectors == 1050, "Donor ART spring vector count through JNI");
        double[] untouchedSpring = {4,5,6,7,8,9};
        invalid(() -> HyperOsNative.nativeSpringBatch(new double[]{0,1,0,.82,.4,1,.01,0,
            0,1,0,.82,0,1,.01,0}, 2, 1.0/60, untouchedSpring));
        require(Arrays.equals(untouchedSpring,new double[]{4,5,6,7,8,9}),
            "Batch validates every channel before committing output");

        Random random = new Random(17092026);
        int cases = 0;
        for (int[] size : new int[][]{{1,1},{1,29},{33,1},{2,3},{7,11},{37,18},{270,600}}) {
            for (int radius : new int[]{0,1,4,16,32,99}) {
                int[] source = new int[size[0]*size[1]];
                for (int i=0;i<source.length;i++) source[i] = random.nextInt();
                int[] actual = source.clone();
                int[] expected = SnapshotPixels.blur(source,size[0],size[1],radius);
                HyperOsNative.nativeBlurArgb(actual,size[0],size[1],radius);
                require(Arrays.equals(actual,expected), "Preserved Rust blur pixels");
                cases++;
            }
        }
        int[] preserved = {0x12345678};
        invalid(() -> HyperOsNative.nativeBlurArgb(preserved,0,1,4));
        require(preserved[0] == 0x12345678, "Invalid blur preserves source");
        if (args.length > 2) {
            HyperOsNative.nativeInitializePalette(args[2]);
            int[] palette = new int[4];
            int[] gray = new int[16]; Arrays.fill(gray,0xffffffff);
            HyperOsNative.nativeArtworkPalette(gray,4,4,palette);
            require(Arrays.equals(palette,new int[]{0xff407ebf,0x99e6cbf2,0x9966afff,0}),
                "JNI achromatic fallback and original HyperOS tones");
            int[] red = new int[16]; Arrays.fill(red,0xffff0000);
            HyperOsNative.nativeArtworkPalette(red,4,4,palette);
            require(palette[0]==0xfff80000 && (palette[1]>>>24)==153
                && (palette[2]>>>24)==153 && palette[3]==0,"JNI quantized seed/gradient alpha");
            HyperOsNative.nativeArtworkPalette(new int[]{0xffff0000,0xff0000ff},2,1,palette);
            require(palette[3]==1,"JNI exact native tie fallback");
            int[] savedPalette=palette.clone();
            invalid(() -> HyperOsNative.nativeArtworkPalette(new int[2],1,1,palette));
            require(Arrays.equals(palette,savedPalette),"Invalid palette preserves outputs");
        }
        System.out.println("JNI passed: " + vectors + " donor spring vectors, "
            + cases + " blur comparisons, capsule/RTL/failure atomicity; "
            + assertions + " assertions");
    }
    private static void close(double actual,double expected) {
        require(Math.abs(actual-expected) <= Math.max(1e-10,Math.abs(expected)*1e-12),
            "Donor spring mismatch: " + actual + " / " + expected);
    }
    private static void invalid(Runnable action) {
        boolean threw = false;
        try { action.run(); } catch (IllegalArgumentException expected) { threw = true; }
        require(threw,"Invalid JNI arguments rejected");
    }
    private static void require(boolean okay,String message) {
        assertions++;
        if (!okay) throw new AssertionError(message);
    }
}
