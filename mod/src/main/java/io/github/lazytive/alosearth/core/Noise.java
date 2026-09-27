package io.github.lazytive.alosearth.core;

import java.util.Random;

/**
 * Improved Perlin noise (3-D) with a fixed seed. All terrain noise is sampled
 * at points on the globe (unit vector x radius), never at block coordinates,
 * so it is identical on both sides of every seam and in the seam margins.
 */
public final class Noise {
    private final int[] p = new int[512];

    public Noise(long seed) {
        int[] perm = new int[256];
        for (int i = 0; i < 256; i++) perm[i] = i;
        Random r = new Random(seed);
        for (int i = 255; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = perm[i];
            perm[i] = perm[j];
            perm[j] = t;
        }
        for (int i = 0; i < 512; i++) p[i] = perm[i & 255];
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double t, double a, double b) {
        return a + t * (b - a);
    }

    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    /** Noise in roughly [-1, 1]. */
    public double noise(double x, double y, double z) {
        double fx = Math.floor(x), fy = Math.floor(y), fz = Math.floor(z);
        int X = (int) fx & 255, Y = (int) fy & 255, Z = (int) fz & 255;
        x -= fx;
        y -= fy;
        z -= fz;
        double u = fade(x), v = fade(y), w = fade(z);
        int A = p[X] + Y, AA = p[A] + Z, AB = p[A + 1] + Z;
        int B = p[X + 1] + Y, BA = p[B] + Z, BB = p[B + 1] + Z;
        return lerp(w,
            lerp(v, lerp(u, grad(p[AA], x, y, z), grad(p[BA], x - 1, y, z)),
                    lerp(u, grad(p[AB], x, y - 1, z), grad(p[BB], x - 1, y - 1, z))),
            lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1), grad(p[BA + 1], x - 1, y, z - 1)),
                    lerp(u, grad(p[AB + 1], x, y - 1, z - 1), grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }

    /** Fractal sum of {@code octaves} octaves at base wavelength {@code scale}. */
    public double fbm(double x, double y, double z, double scale, int octaves) {
        double sum = 0, amp = 1, norm = 0, f = 1 / scale;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x * f + i * 31.7, y * f - i * 17.3, z * f + i * 11.1);
            norm += amp;
            amp *= 0.5;
            f *= 2;
        }
        return sum / norm;
    }
}
