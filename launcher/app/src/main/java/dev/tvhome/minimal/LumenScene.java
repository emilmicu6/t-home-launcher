package dev.tvhome.minimal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * Peisajul „Lumen": munți, păduri, lac. Se desenează în straturi (bitmap-uri) la ora curentă;
 * animația (nori, stele, licurici, paralaxă) se face apoi în LumenView, fără costuri mari.
 * Algoritmii sunt aceiași cu cei din preview/lumen_preview.html.
 */
final class LumenScene {
    private LumenScene() {}

    static final int RW = 1280;
    static final int RH = 720;
    static final int LAKE_Y = 479;          // Math.round(RH * 0.665)
    static final int LH = RH - LAKE_Y;
    static final int LAYER_W = 1440;
    static final int MARGIN = (LAYER_W - RW) / 2;

    /** Rezultatul unei randări la o anumită oră. */
    static final class Layers {
        Bitmap l0, l1, l2, l3;   // munți depărtați, munți medii, dealuri cu pădure, pini din prim-plan
        Bitmap refl;             // reflexia în lac (RW x LH)
        Bitmap blur;             // instantaneu mic, pentru sticla mată
        int y0, y1, y2, y3;
        int cloudTint;           // RGB

        void recycle() {
            Bitmap[] all = {l0, l1, l2, l3, refl, blur};
            for (Bitmap b : all) if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /** Generator pseudo-aleator determinist (mulberry32), identic cu cel din preview. */
    static final class Rng {
        private int a;

        Rng(int seed) {
            a = seed;
        }

        double next() {
            a += 0x6D2B79F5;
            int t = (a ^ (a >>> 15)) * (1 | a);
            t = (t + ((t ^ (t >>> 7)) * (61 | t))) ^ t;
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }

        float f() {
            return (float) next();
        }
    }

    private static final float[] FAR = mdisp(new Rng(11), 8, 0.56);
    private static final float[] MID = mdisp(new Rng(23), 8, 0.52);
    private static final float[] NEAR = mdisp(new Rng(37), 7, 0.50);

    private static float[] mdisp(Rng r, int levels, double rough) {
        int n = (1 << levels) + 1;
        float[] a = new float[n];
        a[0] = r.f();
        a[n - 1] = r.f();
        int step = n - 1;
        double amp = 1.0;
        while (step > 1) {
            int half = step >> 1;
            for (int i = half; i < n; i += step) {
                a[i] = (float) ((a[i - half] + a[i + half]) / 2.0 + (r.next() - 0.5) * amp);
            }
            amp *= rough;
            step = half;
        }
        float mn = 1e9f, mx = -1e9f;
        for (int i = 0; i < n; i++) {
            mn = Math.min(mn, a[i]);
            mx = Math.max(mx, a[i]);
        }
        for (int i = 0; i < n; i++) a[i] = (a[i] - mn) / (mx - mn);
        return a;
    }

    private static float sampleH(float[] a, float x) {
        float t = x / LAYER_W * (a.length - 1);
        int i = (int) Math.floor(t);
        float f = t - i;
        if (i >= a.length - 1) return a[a.length - 1];
        return a[i] * (1f - f) + a[i + 1] * f;
    }

    private static float ridgeY(float[] arr, float x, float base, float amp, float pw) {
        return (float) (base - amp * Math.pow(sampleH(arr, x), pw));
    }

    private static Paint paint() {
        return new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    }

    private static Path ridgePath(float[] arr, float base, float amp, float pw, float bottom) {
        Path p = new Path();
        p.moveTo(0f, bottom);
        for (int x = 0; x <= LAYER_W; x += 3) p.lineTo(x, ridgeY(arr, x, base, amp, pw));
        p.lineTo(LAYER_W, bottom);
        p.close();
        return p;
    }

    private static void rim(Canvas g, float[] arr, float base, float amp, float pw, Palette.Pal P, float w, float a) {
        Path p = new Path();
        for (int x = 0; x <= LAYER_W; x += 3) {
            float y = ridgeY(arr, x, base, amp, pw);
            if (x == 0) p.moveTo(x, y); else p.lineTo(x, y);
        }
        Paint s = paint();
        s.setStyle(Paint.Style.STROKE);
        s.setStrokeWidth(w);
        s.setStrokeJoin(Paint.Join.ROUND);
        s.setColor(Palette.argb(Palette.lerpC(P.hor, 0xFFFFFF, 0.38f), a));
        g.drawPath(p, s);
    }

    private static void smallTrees(Canvas g, float[] arr, float base, float amp, float size, int rgb, Rng r, float yoff) {
        Paint p = paint();
        p.setColor(Palette.opaque(rgb));
        float x = -10f;
        while (x < LAYER_W + 10) {
            float ry = ridgeY(arr, x, base, amp, 1f) + yoff;
            float s = size * (0.65f + r.f() * 0.75f);
            float tw = s * 0.36f;
            for (int l = 0; l < 3; l++) {
                float ly = ry - s * 0.27f * l + s * 0.2f;
                float lw = tw * (1f - l * 0.24f);
                Path t = new Path();
                t.moveTo(x, ly - s * 0.48f);
                t.lineTo(x - lw, ly);
                t.lineTo(x + lw, ly);
                t.close();
                g.drawPath(t, p);
            }
            x += tw * (0.85f + r.f() * 0.7f);
        }
    }

    private static void bigPine(Canvas g, float cx, float baseY, float h, float w, int rgb, Rng r) {
        Paint p = paint();
        p.setColor(Palette.opaque(rgb));
        int tiers = 9;
        float y = baseY - h;
        g.drawRect(cx - w * 0.05f, baseY - h * 0.12f, cx + w * 0.05f, baseY + h * 0.02f, p);
        for (int i = 0; i < tiers; i++) {
            float t = i / (float) (tiers - 1);
            float ty = y + h * (0.06f + t * 0.84f);
            float tw = w * (0.18f + t * 0.82f) * 0.5f;
            float th = h * 0.22f;
            float lean = (r.f() - 0.5f) * w * 0.05f;
            Path q = new Path();
            q.moveTo(cx + lean * 0.3f, ty - th * 0.55f);
            q.lineTo(cx - tw * 0.55f + lean, ty + th * 0.2f);
            q.lineTo(cx - tw, ty + th * 0.5f);
            q.lineTo(cx - tw * 0.6f, ty + th * 0.42f);
            q.lineTo(cx, ty + th * 0.6f);
            q.lineTo(cx + tw * 0.6f, ty + th * 0.42f);
            q.lineTo(cx + tw, ty + th * 0.5f);
            q.lineTo(cx + tw * 0.55f + lean, ty + th * 0.2f);
            q.close();
            g.drawPath(q, p);
        }
        Path top = new Path();
        top.moveTo(cx, y - h * 0.03f);
        top.lineTo(cx - w * 0.03f, y + h * 0.1f);
        top.lineTo(cx + w * 0.03f, y + h * 0.1f);
        top.close();
        g.drawPath(top, p);
    }

    /** Micșorează în pași de câte 2x (media corectă a pixelilor) până la ~minW pixeli lățime: un „blur" ieftin. */
    private static Bitmap halveDown(Bitmap src, int minW) {
        Bitmap cur = src;
        while (cur.getWidth() / 2 >= minW) {
            Bitmap next = Bitmap.createScaledBitmap(cur, cur.getWidth() / 2, cur.getHeight() / 2, true);
            if (cur != src) cur.recycle();
            cur = next;
        }
        if (cur == src) cur = Bitmap.createBitmap(src); // copie, ca să putem recicla originalul
        return cur;
    }

    static int cloudTintColor(Palette.Pal P) {
        int base = Palette.lerpC(0x3A4766, 0xFFFFFF, P.light);
        return Palette.lerpC(base, P.hor, 0.38f);
    }

    // ------------------------------------------------------------------ randarea straturilor

    static Layers build(Palette.Pal P) {
        Layers L = new Layers();
        final Bitmap.Config cfg = Bitmap.Config.ARGB_8888;

        // L0 - munți depărtați, cu zăpadă
        int y0 = Math.round(RH * 0.18f);
        int h0 = LAKE_Y - y0 + 10;
        Bitmap b0 = Bitmap.createBitmap(LAYER_W, h0, cfg);
        Canvas g0 = new Canvas(b0);
        float base0 = RH * 0.545f - y0, amp0 = RH * 0.34f;
        int col0 = Palette.shade(0x6F83A8, P, 0.62f);
        int snow = Palette.lerpC(col0, 0xFFFFFF, 0.55f * P.light + 0.08f);
        Paint f0 = paint();
        f0.setShader(new LinearGradient(0f, 0f, 0f, h0,
                new int[]{Palette.opaque(snow), Palette.opaque(Palette.lerpC(snow, col0, 0.6f)),
                        Palette.opaque(col0), Palette.opaque(Palette.lerpC(col0, P.hor, 0.5f))},
                new float[]{0f, 0.22f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        g0.drawPath(ridgePath(FAR, base0, amp0, 1.35f, h0), f0);
        rim(g0, FAR, base0, amp0, 1.35f, P, 2.2f, Palette.rimA(P, 0.9f));
        L.l0 = b0;
        L.y0 = y0;

        // L1 - munți medii
        int y1 = Math.round(RH * 0.36f);
        int h1 = LAKE_Y - y1 + 10;
        Bitmap b1 = Bitmap.createBitmap(LAYER_W, h1, cfg);
        Canvas g1 = new Canvas(b1);
        float base1 = RH * 0.60f - y1, amp1 = RH * 0.215f;
        int col1 = Palette.shade(0x4C5F85, P, 0.40f);
        Paint f1 = paint();
        f1.setShader(new LinearGradient(0f, 0f, 0f, h1,
                Palette.opaque(Palette.lerpC(col1, 0xFFFFFF, 0.10f * P.light)),
                Palette.opaque(Palette.lerpC(col1, P.hor, 0.4f)), Shader.TileMode.CLAMP));
        g1.drawPath(ridgePath(MID, base1, amp1, 1.1f, h1), f1);
        rim(g1, MID, base1, amp1, 1.1f, P, 1.8f, Palette.rimA(P, 0.7f));
        L.l1 = b1;
        L.y1 = y1;

        // L2 - dealuri cu pădure de conifere
        int y2 = Math.round(RH * 0.50f);
        int h2 = LAKE_Y - y2 + 10;
        Bitmap b2 = Bitmap.createBitmap(LAYER_W, h2, cfg);
        Canvas g2 = new Canvas(b2);
        float base2 = RH * 0.655f - y2, amp2 = RH * 0.085f;
        Paint f2 = paint();
        f2.setColor(Palette.opaque(Palette.shade(0x2C4056, P, 0.20f)));
        g2.drawPath(ridgePath(NEAR, base2, amp2, 1f, h2), f2);
        Rng rt = new Rng(101);
        smallTrees(g2, NEAR, base2, amp2, RH * 0.052f, Palette.shade(0x223648, P, 0.17f), rt, 0f);
        smallTrees(g2, NEAR, base2, amp2, RH * 0.044f, Palette.shade(0x182A3A, P, 0.10f), rt, RH * 0.018f);
        Paint gs = paint();
        gs.setShader(new LinearGradient(0f, h2 - 40, 0f, h2, Palette.argb(P.hor, 0f), Palette.argb(P.hor, 0.28f),
                Shader.TileMode.CLAMP));
        g2.drawRect(0f, h2 - 40, LAYER_W, h2, gs);
        L.l2 = b2;
        L.y2 = y2;

        // L3 - pini mari în prim-plan, pe margini
        int y3 = Math.round(RH * 0.30f);
        int h3 = RH - y3;
        Bitmap b3 = Bitmap.createBitmap(LAYER_W, h3, cfg);
        Canvas g3 = new Canvas(b3);
        Rng r3 = new Rng(303);
        int colf = Palette.shade(0x0C161D, P, 0.04f);
        float[][] pines = {{70f, 0.98f, 150f}, {190f, 0.72f, 120f}, {300f, 0.50f, 96f}, {1370f, 0.96f, 160f},
                {1250f, 0.70f, 124f}, {1150f, 0.46f, 92f}, {14f, 0.80f, 120f}, {1426f, 0.82f, 128f}};
        for (int k = 0; k < pines.length; k++) {
            float[] pn = pines[k];
            bigPine(g3, pn[0], h3 + 14, h3 * pn[1], pn[2], Palette.lerpC(colf, P.hor, (0.05f * k) % 0.15f), r3);
        }
        Paint fg = paint();
        fg.setColor(Palette.opaque(colf));
        Path gl = new Path();
        gl.moveTo(0f, h3);
        gl.lineTo(0f, h3 - 34);
        gl.cubicTo(160f, h3 - 52, 300f, h3 - 8, 430f, h3);
        gl.close();
        g3.drawPath(gl, fg);
        Path gr = new Path();
        gr.moveTo(LAYER_W, h3);
        gr.lineTo(LAYER_W, h3 - 40);
        gr.cubicTo(1290f, h3 - 60, 1150f, h3 - 8, 1000f, h3);
        gr.close();
        g3.drawPath(gr, fg);
        L.l3 = b3;
        L.y3 = y3;

        // compoziție statică (cer + straturi) pentru reflexie și pentru sticla mată
        Bitmap comp = Bitmap.createBitmap(RW, RH, cfg);
        Canvas gc = new Canvas(comp);
        Paint sky = paint();
        sky.setShader(new LinearGradient(0f, 0f, 0f, LAKE_Y,
                new int[]{Palette.opaque(P.top), Palette.opaque(Palette.lerpC(P.top, P.hor, 0.55f)), Palette.opaque(P.hor)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        gc.drawRect(0f, 0f, RW, RH, sky);
        gc.drawBitmap(b0, -MARGIN, y0, null);
        gc.drawBitmap(b1, -MARGIN, y1, null);
        gc.drawBitmap(b2, -MARGIN, y2, null);

        Bitmap refl = Bitmap.createBitmap(RW, LH, cfg);
        Canvas gr2 = new Canvas(refl);
        gr2.save();
        gr2.translate(0f, LH);
        gr2.scale(1f, -1f);
        gr2.drawBitmap(comp, new Rect(0, LAKE_Y - LH, RW, LAKE_Y), new RectF(0f, 0f, RW, LH), null);
        gr2.restore();
        L.refl = refl;

        // lac + prim-plan, apoi micșorare pentru „blur"
        Paint lake = paint();
        lake.setShader(new LinearGradient(0f, LAKE_Y, 0f, RH,
                Palette.opaque(Palette.lerpC(P.hor, P.top, 0.30f)),
                Palette.opaque(Palette.scaleC(Palette.lerpC(P.top, 0x000000, 0.35f), 0.5f + 0.5f * P.light)),
                Shader.TileMode.CLAMP));
        gc.drawRect(0f, LAKE_Y, RW, RH, lake);
        Paint rp = paint();
        rp.setAlpha(190);
        gc.drawBitmap(refl, 0f, LAKE_Y, rp);
        gc.drawBitmap(b3, -MARGIN, y3, null);
        L.blur = halveDown(comp, 40);
        comp.recycle();

        L.cloudTint = cloudTintColor(P);
        return L;
    }

    // ------------------------------------------------------------------ sprite-uri statice

    static Bitmap cloudSprite(int seed) {
        Bitmap e = Bitmap.createBitmap(560, 170, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(e);
        Rng r = new Rng(seed);
        for (int k = 0; k < 11; k++) {
            float cx = 90f + r.f() * 380f;
            float cy = 88f + (r.f() - 0.5f) * 40f;
            float rad = 38f + r.f() * 52f;
            Paint p = paint();
            p.setShader(new RadialGradient(cx, cy, rad, new int[]{0x9EFFFFFF, 0x38FFFFFF, 0x00FFFFFF},
                    new float[]{0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
            g.drawCircle(cx, cy, rad, p);
        }
        return e;
    }

    static Bitmap glowSprite() {
        Bitmap e = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(e);
        Paint p = paint();
        p.setShader(new RadialGradient(128f, 128f, 128f,
                new int[]{0xFFFFFFFF, 0x8CFFFFFF, 0x24FFFFFF, 0x00FFFFFF},
                new float[]{0f, 0.18f, 0.5f, 1f}, Shader.TileMode.CLAMP));
        g.drawRect(0f, 0f, 256f, 256f, p);
        return e;
    }

    /** Perdele de aurora: coloane verticale subțiri, adunate aditiv. */
    static Bitmap auroraSprite() {
        Bitmap e = Bitmap.createBitmap(1500, 340, Bitmap.Config.ARGB_8888);
        Canvas g = new Canvas(e);
        Rng r = new Rng(77);
        int[] cols = {Palette.mute(0x3CFFB0), Palette.mute(0x5AD1FF), Palette.mute(0xA77BFF)};
        Paint p = paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        for (int k = 0; k < 3; k++) {
            float top = 70f + k * 30f;
            float amp = 22f + r.f() * 18f;
            float f = 1.1f + r.f() * 1.2f;
            float ph = r.f() * 6.28f;
            float len0 = 120f + r.f() * 60f;
            for (int x = 0; x < 1500; x += 3) {
                float y = top + amp * (float) Math.sin(x / 1500f * f * 6.283f + ph);
                float len = len0 * (0.55f + 0.3f * (float) Math.sin(x * 0.011f + k * 2) + 0.25f * (float) Math.sin(x * 0.037f + k));
                float a = (0.20f + 0.12f * (float) Math.sin(x * 0.02f + k)) * (0.55f + 0.9f * r.f());
                p.setShader(new LinearGradient(0f, y, 0f, y + len,
                        new int[]{Palette.argb(cols[k], 0f), Palette.argb(cols[k], a * (k == 2 ? 0.7f : 1f)),
                                Palette.argb(cols[k], 0f)},
                        new float[]{0f, 0.12f, 1f}, Shader.TileMode.CLAMP));
                g.drawRect(x, y, x + 3, y + len, p);
            }
        }
        return e;
    }

    /** Grain fin, ca să nu apară benzi în gradientele de cer. */
    static Bitmap noiseSprite() {
        int n = 192;
        int[] px = new int[n * n];
        Rng r = new Rng(1234);
        for (int i = 0; i < px.length; i++) {
            int v = r.next() < 0.5 ? 0 : 255;
            px[i] = (6 << 24) | (v << 16) | (v << 8) | v;
        }
        Bitmap b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
        b.setPixels(px, 0, n, 0, 0, n, n);
        return b;
    }
}
