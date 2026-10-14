/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package pramaan.harness.factory;

/**
 * Deterministic, seedable noise for the synthetic attack factory (Bible Part 45; Build Map #89). Nothing here is
 * secret: a simulated attack must be reproducible from its parameters and seed so a result can be re-run. It is an
 * integer hash, not a random generator, so the same pixel and seed always give the same value.
 */
final class SyntheticNoise {

    private SyntheticNoise() {
    }

    /** A value in [0, 1) that depends only on the lattice point and the seed. */
    static double unit(int x, int y, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth value noise in [0, 1): bilinear interpolation of {@link #unit} on a lattice of the given scale. */
    static double smooth(double x, double y, double scale, long seed) {
        double fx = x / scale;
        double fy = y / scale;
        int ix = (int) Math.floor(fx);
        int iy = (int) Math.floor(fy);
        double tx = fx - ix;
        double ty = fy - iy;
        tx = tx * tx * (3 - 2 * tx);
        ty = ty * ty * (3 - 2 * ty);
        double a = unit(ix, iy, seed);
        double b = unit(ix + 1, iy, seed);
        double c = unit(ix, iy + 1, seed);
        double d = unit(ix + 1, iy + 1, seed);
        return (a * (1 - tx) + b * tx) * (1 - ty) + (c * (1 - tx) + d * tx) * ty;
    }
}
