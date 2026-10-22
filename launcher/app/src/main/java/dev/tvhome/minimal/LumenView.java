package dev.tvhome.minimal;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import java.util.Calendar;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Peisajul animat „Lumen". Straturile (munți, păduri, lac) se redesenează la ora curentă pe un fir
 * din fundal, aproximativ la fiecare 2-3 minute; restul animației (nori, stele, licurici, aurora,
 * paralaxă, reflexie) e doar desenare de bitmap-uri, ușoară pentru GPU-ul unui stick/cube.
 * Coordonatele sunt în spațiul virtual 1280x720, scalat „cover" pe ecran.
 */
public class LumenView extends View {

    private static final int RW = LumenScene.RW;
    private static final int RH = LumenScene.RH;
    private static final int LAKE_Y = LumenScene.LAKE_Y;
    private static final int LH = LumenScene.LH;
    private static final int MARGIN = LumenScene.MARGIN;
    private static final int STRIPS = 26;

    private static final class Cloud { int s; float x, y, sc, v, a; }
    private static final class Star { float x, y, a, f, p, s; }
    private static final class Fly { float x, y, p, f1, f2, b, ax, ay; }
    private static final class Glit { float u, r, p; }

    private final Cloud[] clouds = new Cloud[7];
    private final Star[] stars = new Star[230];
    private final Fly[] flies = new Fly[42];
    private final Glit[] glits = new Glit[46];

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Random rnd = new Random();

    // sprite-uri (create pe firul din fundal, publicate prin spritesReady)
    private Bitmap[] cloudSprites;
    private Bitmap glowSprite, auroraSprite, noiseSprite;
    private Paint noisePaint;
    private volatile boolean spritesReady;

    private volatile LumenScene.Layers layers;
    private boolean building;
    private PorterDuffColorFilter cloudFilter, fireflyFilter;

    // timp și stare
    private Palette.Pal pal = Palette.at(12f);
    private float curH = 12f;
    private float lastGenH = -99f;
    private float fixedHour = -1f;
    private boolean active;
    private long lastNs;
    private float time;
    private float sinceTick;
    private boolean skip;
    private float par, parTarget, idle;
    private float nextShoot = 6f;
    private boolean shootOn;
    private float shootT0, shootX, shootY;
    private Runnable onLayersChanged;

    // orbite
    private boolean sunOn, moonOn;
    private float sunX, sunY, sunAlt, sunLow, moonX, moonY, moonAlt;

    // vopsele refolosite
    private final Paint skyPaint = new Paint();
    private final Paint lakePaint = new Paint();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint addP = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint moonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint vigPaint = new Paint();
    private final RectF rf = new RectF();
    private final Rect src = new Rect();

    public LumenView(Context ctx) {
        super(ctx);
        setWillNotDraw(false);
        addP.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        line.setStrokeCap(Paint.Cap.ROUND);
        moonPaint.setShader(new RadialGradient(-6f, -6f, 34f, 0xFFFFFFFF, 0xFFCFD8EE, Shader.TileMode.CLAMP));
        vigPaint.setShader(new RadialGradient(RW / 2f, RH * 0.485f, RW * 0.78f,
                new int[]{0x00000000, 0x00000000, 0x75000000}, new float[]{0f, 0.33f, 1f}, Shader.TileMode.CLAMP));
        fireflyFilter = new PorterDuffColorFilter(0xFFD8FF7A, PorterDuff.Mode.SRC_IN);

        LumenScene.Rng r = new LumenScene.Rng(4);
        for (int i = 0; i < clouds.length; i++) {
            Cloud c = new Cloud();
            c.s = (int) Math.floor(r.f() * 3);
            c.x = r.f() * (RW + 600) - 300;
            c.y = RH * (0.07f + r.f() * 0.27f);
            c.sc = 0.8f + r.f() * 1.1f;
            c.v = 4 + r.f() * 9;
            c.a = 0.5f + r.f() * 0.4f;
            clouds[i] = c;
        }
        r = new LumenScene.Rng(6);
        for (int i = 0; i < stars.length; i++) {
            Star s = new Star();
            s.x = r.f() * RW;
            s.y = (float) Math.pow(r.f(), 1.3) * RH * 0.5f;
            s.a = 0.35f + r.f() * 0.65f;
            s.f = 0.6f + r.f() * 2.4f;
            s.p = r.f() * 6.28f;
            s.s = 0.5f + r.f() * 1.3f;
            stars[i] = s;
        }
        r = new LumenScene.Rng(8);
        for (int i = 0; i < flies.length; i++) {
            Fly f = new Fly();
            f.x = r.f() * RW;
            f.y = RH * (0.55f + r.f() * 0.33f);
            f.p = r.f() * 6.28f;
            f.f1 = 0.15f + r.f() * 0.25f;
            f.f2 = 0.2f + r.f() * 0.3f;
            f.b = 0.8f + r.f() * 1.6f;
            f.ax = 20 + r.f() * 50;
            f.ay = 8 + r.f() * 20;
            flies[i] = f;
        }
        r = new LumenScene.Rng(9);
        for (int i = 0; i < glits.length; i++) {
            Glit g = new Glit();
            g.u = i / (float) glits.length;
            g.r = r.f();
            g.p = r.f() * 6.28f;
            glits[i] = g;
        }
        applyPalette();
    }

    // ------------------------------------------------------------------ API public

    /** Ora fixă pentru teste (ex. `adb shell am start ... --ef hour 18.9`); negativ = ora reală. */
    public void setFixedHour(float h) {
        fixedHour = h;
        updateTime(true);
    }

    /** Paralaxă: −1 (stânga) .. +1 (dreapta), după poziția aplicației focalizate din dock. */
    public void setParallaxTarget(float v) {
        parTarget = Palette.cl(v, -1f, 1f);
    }

    public void setOnLayersChanged(Runnable r) {
        onLayersChanged = r;
    }

    public void setActive(boolean on) {
        if (on == active) return;
        active = on;
        if (on) {
            lastNs = System.nanoTime();
            updateTime(false);
            postOnAnimation(frame);
        } else {
            removeCallbacks(frame);
        }
    }

    /** Desenează în `c` (coordonatele lui `target`) versiunea micșorată/neclară a peisajului, pentru „sticla" mată. */
    void drawBlurFor(Canvas c, View target, Paint p) {
        LumenScene.Layers L = layers;
        if (L == null || L.blur == null || L.blur.isRecycled() || getWidth() == 0) return;
        int[] a = new int[2];
        int[] t = new int[2];
        getLocationInWindow(a);
        target.getLocationInWindow(t);
        float s = Math.max(getWidth() / (float) RW, getHeight() / (float) RH);
        float ox = (getWidth() - RW * s) / 2f + a[0] - t[0];
        float oy = (getHeight() - RH * s) / 2f + a[1] - t[1];
        c.save();
        c.translate(ox, oy);
        c.scale(s * RW / L.blur.getWidth(), s * RH / L.blur.getHeight());
        c.drawBitmap(L.blur, 0f, 0f, p);
        c.restore();
    }

    boolean hasBlur() {
        LumenScene.Layers L = layers;
        return L != null && L.blur != null && !L.blur.isRecycled();
    }

    // ------------------------------------------------------------------ timp

    private static float realHour() {
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f + c.get(Calendar.SECOND) / 3600f;
    }

    private void updateTime(boolean force) {
        float h = fixedHour >= 0f ? fixedHour : realHour();
        if (force || Math.abs(h - curH) > 0.003f) {
            curH = h;
            pal = Palette.at(h);
            applyPalette();
        }
        float d = Math.abs(h - lastGenH);
        d = Math.min(d, 24f - d);
        if (lastGenH < 0f || d > 0.04f) rebuild(); // lastGenH < 0: încă nu am randat nimic
        invalidate();
    }

    private void applyPalette() {
        Palette.Pal P = pal;
        skyPaint.setShader(new LinearGradient(0f, 0f, 0f, LAKE_Y,
                new int[]{Palette.opaque(P.top), Palette.opaque(Palette.lerpC(P.top, P.hor, 0.55f)), Palette.opaque(P.hor)},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        lakePaint.setShader(new LinearGradient(0f, LAKE_Y, 0f, RH,
                Palette.opaque(Palette.lerpC(P.hor, P.top, 0.30f)),
                Palette.opaque(Palette.scaleC(Palette.lerpC(P.top, 0x000000, 0.35f), 0.5f + 0.5f * P.light)),
                Shader.TileMode.CLAMP));
    }

    private void rebuild() {
        if (building) return;
        building = true;
        final float h = curH;
        try {
            worker.submit(new Runnable() {
            @Override public void run() {
                try {
                    if (!spritesReady) makeSprites();
                    final LumenScene.Layers n = LumenScene.build(Palette.at(h));
                    post(new Runnable() {
                        @Override public void run() {
                            LumenScene.Layers old = layers;
                            layers = n;
                            lastGenH = h;
                            building = false;
                            cloudFilter = new PorterDuffColorFilter(Palette.opaque(n.cloudTint), PorterDuff.Mode.SRC_IN);
                            if (old != null) old.recycle();
                            if (onLayersChanged != null) onLayersChanged.run();
                            invalidate();
                        }
                    });
                } catch (Throwable t) {
                    post(new Runnable() {
                        @Override public void run() { building = false; }
                    });
                }
            }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            building = false; // view-ul a fost detașat între timp
        }
    }

    private void makeSprites() {
        Bitmap[] cs = {LumenScene.cloudSprite(5), LumenScene.cloudSprite(9), LumenScene.cloudSprite(14)};
        Bitmap g = LumenScene.glowSprite();
        Bitmap a = LumenScene.auroraSprite();
        Bitmap n = LumenScene.noiseSprite();
        Paint np = new Paint();
        np.setShader(new BitmapShader(n, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        cloudSprites = cs;
        glowSprite = g;
        auroraSprite = a;
        noiseSprite = n;
        noisePaint = np;
        spritesReady = true;
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!active) return;
            long now = System.nanoTime();
            float dt = Math.min(0.1f, (now - lastNs) / 1e9f);
            lastNs = now;
            time += dt;
            par += (parTarget - par) * (1f - (float) Math.exp(-dt * 3.2f));
            sinceTick += dt;
            if (sinceTick > 1f) {
                sinceTick = 0f;
                updateTime(false);
            }
            skip = !skip; // ~30 fps: desenăm la fiecare al doilea vsync
            if (!skip) invalidate();
            postOnAnimation(this);
        }
    };

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        active = false;
        removeCallbacks(frame);
        worker.shutdownNow();
        LumenScene.Layers L = layers;
        layers = null;
        if (L != null) L.recycle();
        spritesReady = false;
    }

    // ------------------------------------------------------------------ desen

    private float off(float m) {
        return -par * m * 30f + idle * m * 5f;
    }

    private void orbit() {
        float sp = (curH - 6f) / 13.5f;
        sunOn = sp > 0f && sp < 1f;
        if (sunOn) {
            sunAlt = (float) Math.sin(Math.PI * sp);
            sunX = Palette.lerp(0.10f, 0.90f, sp) * RW;
            sunY = RH * 0.60f - sunAlt * RH * 0.50f;
            sunLow = Palette.cl(1f - sunAlt * 1.6f, 0f, 1f);
        }
        float hh = curH < 12f ? curH + 24f : curH;
        float mp = (hh - 18.6f) / 11.6f;
        moonOn = mp > 0f && mp < 1f;
        if (moonOn) {
            moonAlt = (float) Math.sin(Math.PI * mp);
            moonX = Palette.lerp(0.12f, 0.88f, mp) * RW;
            moonY = RH * 0.60f - moonAlt * RH * 0.46f;
        }
    }

    private void bitmapAt(Canvas c, Bitmap b, float x, float y, Paint p) {
        c.drawBitmap(b, x, y, p);
    }

    private void spriteTo(Canvas c, Bitmap b, float l, float t, float r, float bt, Paint p) {
        rf.set(l, t, r, bt);
        c.drawBitmap(b, null, rf, p);
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        c.drawColor(Color.BLACK);
        float s = Math.max(w / (float) RW, h / (float) RH);
        c.save();
        c.translate((w - RW * s) / 2f, (h - RH * s) / 2f);
        c.scale(s, s);
        c.clipRect(0, 0, RW, RH);
        try {
            drawScene(c);
        } finally {
            c.restore();
        }
    }

    private void drawScene(Canvas c) {
        final Palette.Pal P = pal;
        final LumenScene.Layers L = layers;
        final float T = time;
        idle = (float) Math.sin(T * 0.11f);

        c.drawRect(0f, 0f, RW, LAKE_Y + 1f, skyPaint);
        if (L == null || !spritesReady) {
            c.drawRect(0f, LAKE_Y, RW, RH, lakePaint);
            return;
        }
        orbit();

        // stele + stea căzătoare
        if (P.star > 0.02f) {
            for (Star st : stars) {
                float a = P.star * st.a * (0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(T * st.f + st.p)));
                fill.setColor(Palette.argb(0xFFFFFF, a));
                c.drawCircle(st.x + off(0.1f), st.y, st.s, fill);
            }
            if (T > nextShoot && !shootOn && P.star > 0.5f) {
                shootOn = true;
                shootT0 = T;
                shootX = RW * (0.45f + rnd.nextFloat() * 0.5f);
                shootY = RH * (0.05f + rnd.nextFloat() * 0.18f);
            }
            if (shootOn) {
                float u = (T - shootT0) / 0.9f;
                if (u > 1f) {
                    shootOn = false;
                    nextShoot = T + 22f + rnd.nextFloat() * 25f;
                } else {
                    for (int k = 0; k < 9; k++) {
                        float uu = Math.max(0f, u - k * 0.018f);
                        float al = (1f - u) * (1f - k / 9f) * 0.9f;
                        fill.setColor(Palette.argb(0xFFFFFF, al));
                        c.drawCircle(shootX - uu * 420f, shootY + uu * 190f, 2.4f - k * 0.2f, fill);
                    }
                }
            }
        }

        // aurora
        float au = Palette.cl((P.star - 0.7f) / 0.3f, 0f, 1f);
        if (au > 0.01f && (curH >= 21.5f || curH <= 4.2f)) {
            c.save();
            c.translate(-110f + (float) Math.sin(T * 0.05f) * 60f + off(0.2f), RH * 0.02f);
            c.scale(1f, 1f + 0.07f * (float) Math.sin(T * 0.33f));
            bmp.setAlpha(Math.round(255f * 0.95f * au * (0.72f + 0.28f * (float) Math.sin(T * 0.5f))));
            c.drawBitmap(auroraSprite, 0f, 0f, bmp);
            bmp.setAlpha(255);
            c.restore();
        }

        // soare
        int sunCol = 0;
        float glowR = 0f;
        if (sunOn) {
            sunCol = Palette.lerpC(0xFFF4D6, 0xFF8A4A, sunLow);
            glowR = RH * (0.50f + 0.30f * sunLow);
            bmp.setColorFilter(new PorterDuffColorFilter(Palette.opaque(sunCol), PorterDuff.Mode.SRC_IN));
            bmp.setAlpha(217);
            spriteTo(c, glowSprite, sunX - glowR, sunY - glowR, sunX + glowR, sunY + glowR, bmp);
            bmp.setAlpha(255);
            bmp.setColorFilter(null);
            fill.setColor(Palette.opaque(Palette.lerpC(0xFFFFFF, sunCol, 0.35f)));
            c.drawCircle(sunX, sunY, 24f + 8f * sunLow, fill);
        }
        // lună
        if (moonOn) {
            float mv = Palette.cl((0.62f - P.light) * 2.6f, 0f, 1f) * Palette.cl(moonAlt * 5f, 0f, 1f);
            float mr = RH * 0.28f;
            bmp.setColorFilter(new PorterDuffColorFilter(0xFFBFD4FF, PorterDuff.Mode.SRC_IN));
            bmp.setAlpha(Math.round(255f * 0.55f * mv));
            spriteTo(c, glowSprite, moonX - mr, moonY - mr, moonX + mr, moonY + mr, bmp);
            bmp.setAlpha(255);
            bmp.setColorFilter(null);
            c.save();
            c.translate(moonX, moonY);
            moonPaint.setAlpha(Math.round(255f * mv));
            c.drawCircle(0f, 0f, 27f, moonPaint);
            fill.setColor(Palette.argb(0x7887AA, 0.18f * mv));
            c.drawCircle(8f, -6f, 7f, fill);
            c.drawCircle(-9f, 8f, 5f, fill);
            c.drawCircle(4f, 12f, 4f, fill);
            c.restore();
        }

        // nori
        float ca = 0.35f + 0.65f * P.light;
        bmp.setColorFilter(cloudFilter);
        float span = RW + 720f;
        for (Cloud cd : clouds) {
            float v = cd.x + T * cd.v + off(0.4f) + 300f;
            v = v % span;
            if (v < 0f) v += span;
            float cx = v - 360f;
            bmp.setAlpha(Math.round(255f * cd.a * ca));
            spriteTo(c, cloudSprites[cd.s], cx, cd.y, cx + 560f * cd.sc, cd.y + 170f * cd.sc, bmp);
        }

        // munți depărtați
        bmp.setAlpha(255);
        bmp.setColorFilter(null);
        bitmapAt(c, L.l0, -MARGIN + off(0.6f), L.y0, bmp);
        if (sunOn) backlit(c, 0.40f, sunCol, glowR);

        float mist = Palette.cl(1f - Math.abs(P.light - 0.45f) * 1.5f, 0.18f, 1f);
        bmp.setColorFilter(cloudFilter);
        bmp.setAlpha(Math.round(255f * 0.20f * mist));
        spriteTo(c, cloudSprites[1], -200f + (T * 3f) % 400f + off(0.8f), RH * 0.42f,
                -200f + (T * 3f) % 400f + off(0.8f) + 1900f, RH * 0.42f + 120f, bmp);
        bmp.setAlpha(255);
        bmp.setColorFilter(null);

        // munți medii
        bitmapAt(c, L.l1, -MARGIN + off(1.0f), L.y1, bmp);
        if (sunOn) backlit(c, 0.22f, sunCol, glowR);

        bmp.setColorFilter(cloudFilter);
        bmp.setAlpha(Math.round(255f * 0.26f * mist));
        spriteTo(c, cloudSprites[2], -300f + (T * 4.5f) % 500f + off(1.2f), RH * 0.52f,
                -300f + (T * 4.5f) % 500f + off(1.2f) + 1900f, RH * 0.52f + 130f, bmp);
        bmp.setAlpha(255);
        bmp.setColorFilter(null);

        // dealuri cu păduri
        bitmapAt(c, L.l2, -MARGIN + off(1.5f), L.y2, bmp);

        // lac + reflexie în 26 de fâșii care unduiesc
        c.drawRect(0f, LAKE_Y, RW, RH, lakePaint);
        float sh = LH / (float) STRIPS;
        for (int i = 0; i < STRIPS; i++) {
            float dx = (float) Math.sin(T * 1.15f + i * 0.9f) * (0.8f + i * 0.22f) + off(1.3f);
            bmp.setAlpha(Math.round(255f * 0.82f * (1f - i / (float) STRIPS * 0.78f)));
            src.set(0, Math.round(i * sh), RW, Math.min(LH, Math.round(i * sh + sh + 0.6f)));
            rf.set(dx, LAKE_Y + i * sh, dx + RW, LAKE_Y + i * sh + src.height());
            c.drawBitmap(L.refl, src, rf, bmp);
        }
        bmp.setAlpha(255);

        // sclipiri pe apă (soare sau lună)
        float gx = 0f, gv = 0f;
        int gc = 0;
        boolean glit = false;
        if (sunOn && sunAlt > 0.04f) {
            glit = true;
            gx = sunX;
            gv = Palette.cl(sunAlt * 3f, 0f, 1f);
            gc = Palette.lerpC(0xFFF4D6, 0xFF9A55, sunLow);
        } else if (moonOn && moonAlt > 0.04f) {
            glit = true;
            gx = moonX;
            gv = 0.6f * Palette.cl(moonAlt * 3f, 0f, 1f);
            gc = 0xDDE7FF;
        }
        if (glit) {
            for (Glit g : glits) {
                float y = LAKE_Y + 6f + (float) Math.pow(g.u, 1.5f) * (LH - 14f);
                float spread = 6f + (y - LAKE_Y) * 0.5f;
                float x = gx + (float) Math.sin(T * 1.3f + g.p) * spread * 0.7f + (g.r - 0.5f) * spread;
                float len = 10f + (y - LAKE_Y) * 0.32f * (0.4f + g.r);
                float al = gv * (0.14f + 0.62f * Math.max(0f, (float) Math.sin(T * 2.2f + g.p * 3f)));
                line.setColor(Palette.argb(gc, al));
                line.setStrokeWidth(1.2f + (y - LAKE_Y) / LH * 2.6f);
                c.drawLine(x - len / 2f, y, x + len / 2f, y, line);
            }
        }

        // licurici
        float ff = Palette.cl((0.5f - P.light) * 3f, 0f, 1f);
        if (ff > 0.02f) {
            for (Fly f : flies) {
                float fx = f.x + (float) Math.sin(T * f.f1 + f.p) * f.ax + off(1.8f);
                float fy = f.y + (float) Math.cos(T * f.f2 + f.p * 1.7f) * f.ay;
                float bl = ff * (0.25f + 0.75f * Math.max(0f, (float) Math.sin(T * f.b + f.p)));
                bmp.setColorFilter(fireflyFilter);
                bmp.setAlpha(Math.round(255f * bl * 0.8f));
                spriteTo(c, glowSprite, fx - 15f, fy - 15f, fx + 15f, fy + 15f, bmp);
                fill.setColor(Palette.argb(0xF3FFB0, Math.min(1f, bl * 1.3f)));
                c.drawCircle(fx, fy, 1.7f, fill);
            }
            bmp.setAlpha(255);
            bmp.setColorFilter(null);
        }

        // pini din prim-plan, vignetă, grain
        bitmapAt(c, L.l3, -MARGIN + off(2.6f), L.y3, bmp);
        c.drawRect(0f, 0f, RW, RH, vigPaint);
        c.drawRect(0f, 0f, RW, RH, noisePaint);
    }

    private void backlit(Canvas c, float k, int sunCol, float glowR) {
        float bl = k * sunLow * Palette.cl(sunAlt * 6f, 0f, 1f);
        if (bl <= 0.01f) return;
        addP.setColorFilter(new PorterDuffColorFilter(Palette.opaque(sunCol), PorterDuff.Mode.SRC_IN));
        addP.setAlpha(Math.round(255f * bl));
        spriteTo(c, glowSprite, sunX - glowR, sunY - glowR, sunX + glowR, sunY + glowR, addP);
    }
}
