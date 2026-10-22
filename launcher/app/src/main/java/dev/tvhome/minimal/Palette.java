package dev.tvhome.minimal;

/** Paleta cerului în funcție de ora din zi + mici utilitare de culoare (RGB pe 24 de biți). */
final class Palette {
    private Palette() {}

    static final float[] KH = {0f, 4.5f, 6f, 7.5f, 12f, 16.5f, 18.5f, 19.5f, 21f, 24f};
    static final int[] KTOP = {0x050A1F, 0x0B1535, 0x2B3F78, 0x4A86C8, 0x2F7FD1,
            0x3B82C4, 0x3A5A9A, 0x1C2550, 0x080F2C, 0x050A1F};
    static final int[] KHOR = {0x0F2347, 0x2B3A67, 0xF08A6B, 0xFFD9A8, 0xBFE3FF,
            0xFFE2B0, 0xFF9A5A, 0xD9587A, 0x1D3160, 0x0F2347};
    static final float[] KLIGHT = {0.12f, 0.20f, 0.50f, 0.85f, 1f, 0.9f, 0.6f, 0.35f, 0.18f, 0.12f};
    static final float[] KSTAR = {1f, 0.8f, 0.15f, 0f, 0f, 0f, 0f, 0.4f, 0.95f, 1f};

    /** Starea cerului la o anumită oră. */
    static final class Pal {
        int top;      // culoarea de sus a cerului (RGB)
        int hor;      // culoarea de la orizont (RGB)
        float light;  // 0..1 cât de luminos e peisajul
        float star;   // 0..1 vizibilitatea stelelor
    }

    static Pal at(float h) {
        h = ((h % 24f) + 24f) % 24f;
        int i = 0;
        while (i < KH.length - 2 && h >= KH[i + 1]) i++;
        float f = (h - KH[i]) / (KH[i + 1] - KH[i]);
        Pal p = new Pal();
        p.top = mute(lerpC(KTOP[i], KTOP[i + 1], f));
        p.hor = mute(lerpC(KHOR[i], KHOR[i + 1], f));
        p.light = lerp(KLIGHT[i], KLIGHT[i + 1], f);
        p.star = lerp(KSTAR[i], KSTAR[i + 1], f);
        return p;
    }

    /** „Noir": cât din culoare rămâne (1 = original, 0 = alb-negru). Peisajul e intenționat sobru. */
    static final float SAT = 0.42f;
    static final float DIM = 0.86f;

    static int mute(int c) {
        float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        float y = 0.3f * r + 0.59f * g + 0.11f * b;
        r = (y + (r - y) * SAT) * DIM;
        g = (y + (g - y) * SAT) * DIM;
        b = (y + (b - y) * SAT) * DIM;
        return (Math.round(cl(r, 0f, 255f)) << 16) | (Math.round(cl(g, 0f, 255f)) << 8) | Math.round(cl(b, 0f, 255f));
    }

    static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    static float cl(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    static int lerpC(int a, int b, float f) {
        float r = lerp((a >> 16) & 255, (b >> 16) & 255, f);
        float g = lerp((a >> 8) & 255, (b >> 8) & 255, f);
        float bl = lerp(a & 255, b & 255, f);
        return (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(bl);
    }

    static int scaleC(int a, float s) {
        float r = cl(((a >> 16) & 255) * s, 0f, 255f);
        float g = cl(((a >> 8) & 255) * s, 0f, 255f);
        float b = cl((a & 255) * s, 0f, 255f);
        return (Math.round(r) << 16) | (Math.round(g) << 8) | Math.round(b);
    }

    /** Culoarea unui strat de relief la ora curentă: mai închis noaptea, estompat de ceață spre orizont. */
    static int shade(int base, Pal p, float haze) {
        int x = scaleC(mute(base), 0.20f + 0.80f * p.light);
        x = lerpC(x, p.hor, haze * (0.30f + 0.45f * p.light));
        return lerpC(x, p.top, (1f - p.light) * 0.18f);
    }

    static float lum(int c) {
        return (0.3f * ((c >> 16) & 255) + 0.59f * ((c >> 8) & 255) + 0.11f * (c & 255)) / 255f;
    }

    /** Opacitatea luminii de contur pe creste: puternică la răsărit/apus, aproape zero noaptea. */
    static float rimA(Pal p, float k) {
        return cl(k * lum(p.hor) * (1f - 0.6f * p.light) + 0.02f, 0f, 0.6f);
    }

    static int opaque(int rgb) {
        return 0xFF000000 | rgb;
    }

    static int argb(int rgb, float a) {
        int al = Math.round(cl(a, 0f, 1f) * 255f);
        return (al << 24) | (rgb & 0xFFFFFF);
    }
}
