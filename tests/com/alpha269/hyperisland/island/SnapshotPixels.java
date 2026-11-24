package com.alpha269.hyperisland.island;

/** A separable, clamped box blur for small screenshot bitmaps. */
final class SnapshotPixels {
    static int[] blur(int[] pixels, int width, int height, int radius) {
        if (width < 1 || height < 1 || pixels.length != width * height) {
            throw new IllegalArgumentException("Invalid screenshot dimensions");
        }
        int r = Math.max(1, Math.min(32, radius));
        int[] scratch = new int[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            sweep(pixels, scratch, width, height, r, true);
            sweep(scratch, pixels, width, height, r, false);
        }
        return pixels;
    }

    private static void sweep(int[] source, int[] output, int width, int height,
                              int radius, boolean horizontal) {
        int lines = horizontal ? height : width;
        int length = horizontal ? width : height;
        int stride = horizontal ? 1 : width;
        int divisor = radius * 2 + 1;
        for (int line = 0; line < lines; line++) {
            int base = horizontal ? line * width : line;
            int red = 0, green = 0, blue = 0;
            for (int x = -radius; x <= radius; x++) {
                int color = source[base + clamp(x, length) * stride];
                red += (color >>> 16) & 255;
                green += (color >>> 8) & 255;
                blue += color & 255;
            }
            for (int x = 0; x < length; x++) {
                output[base + x * stride] = 0xff000000
                    | (red / divisor << 16) | (green / divisor << 8) | blue / divisor;
                int leaving = source[base + clamp(x - radius, length) * stride];
                int entering = source[base + clamp(x + radius + 1, length) * stride];
                red += ((entering >>> 16) & 255) - ((leaving >>> 16) & 255);
                green += ((entering >>> 8) & 255) - ((leaving >>> 8) & 255);
                blue += (entering & 255) - (leaving & 255);
            }
        }
    }

    private static int clamp(int position, int length) {
        return Math.max(0, Math.min(length - 1, position));
    }
}
