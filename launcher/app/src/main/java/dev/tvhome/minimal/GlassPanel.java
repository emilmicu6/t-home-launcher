package dev.tvhome.minimal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * „Sticlă mată" în stil Apple: peisajul neclar din spate (instantaneu micșorat al scenei),
 * o peliculă albă translucidă, lumină de contur sus și umbră moale dedesubt.
 * Compatibil cu Android 5+ (nu folosește RenderEffect/blur în timp real).
 */
final class GlassPanel extends View {
    private final LumenView scene;
    private final float radius;
    private final boolean flat;      // true = ecran întreg (fără umbră/contur), cu peliculă mai închisă

    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    private final float density;

    GlassPanel(Context ctx, LumenView scene, float radiusPx, boolean flat) {
        super(ctx);
        this.scene = scene;
        this.radius = radiusPx;
        this.flat = flat;
        this.density = ctx.getResources().getDisplayMetrics().density;
        rimPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setStyle(Paint.Style.STROKE);
        innerPaint.setColor(0x24FFFFFF);
        shadowPaint.setColor(0x0B000000);
    }

    /** Nu influențează dimensiunea părintelui (wrap_content): se întinde cât îi dă părintele. */
    @Override protected void onMeasure(int ws, int hs) {
        setMeasuredDimension(resolveSize(0, ws), resolveSize(0, hs));
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (flat) {
            tintPaint.setShader(new LinearGradient(0f, 0f, 0f, h, 0xA6060810, 0xA6060810, Shader.TileMode.CLAMP));
        } else {
            tintPaint.setShader(new LinearGradient(0f, 0f, 0f, h, 0x38FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
            rimPaint.setStrokeWidth(1.5f * density);
            rimPaint.setShader(new LinearGradient(0f, 0f, 0f, h * 0.6f, 0x8CFFFFFF, 0x24FFFFFF, Shader.TileMode.CLAMP));
            innerPaint.setStrokeWidth(density);
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        if (!flat) {
            // umbră moale: straturi de dreptunghiuri rotunjite tot mai mari, decalate în jos
            for (int k = 1; k <= 9; k++) {
                float grow = k * 3.2f * density;
                r.set(-grow, -grow + 18f * density, w + grow, h + grow + 18f * density);
                c.drawRoundRect(r, radius + grow, radius + grow, shadowPaint);
            }
        }

        r.set(0f, 0f, w, h);
        clip.reset();
        clip.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.save();
        c.clipPath(clip);
        if (scene.hasBlur()) {
            scene.drawBlurFor(c, this, blurPaint);
        } else {
            c.drawColor(0x55101830);
        }
        if (flat) {
            c.drawColor(0x33000000); // „brightness .82" din preview
        }
        c.drawRect(r, tintPaint);
        c.restore();

        if (!flat) {
            float half = 0.75f * density;
            r.set(half, half, w - half, h - half);
            c.drawRoundRect(r, radius - half, radius - half, rimPaint);
            float in = 1.5f * density;
            r.set(in, in, w - in, h - in);
            c.drawRoundRect(r, radius - in, radius - in, innerPaint);
        }
    }
}
