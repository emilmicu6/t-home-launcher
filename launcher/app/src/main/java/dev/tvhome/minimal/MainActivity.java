package dev.tvhome.minimal;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * „Lumen · Noir" - home minimal pentru TV: peisaj sobru care urmează ora reală, ceas mare și subțire,
 * un bloc „ultima aplicație" jos-stânga și o coloană de aplicații favorite pe marginea dreaptă.
 * Fără permisiuni, fără rețea, fără biblioteci.
 *
 * Telecomandă: ↑↓ navighezi, OK deschide, apăsare lungă OK pe o aplicație = mută/scoate,
 * apăsare lungă OK în „Toate aplicațiile" = adaugă/scoate din favorite. Meniu = Toate aplicațiile.
 * După 2 minute fără nicio apăsare, interfața se estompează („modul cinema"); orice buton o aduce înapoi.
 *
 * Test fără să aștepți ora: adb shell am start -n dev.tvhome.minimal/.MainActivity --ef hour 19.9
 * (revenire la ora reală: --ef hour -1; ecranul „Toate": --ez grid true).
 */
public class MainActivity extends Activity {

    private static final String PREFS = "tvhome";
    private static final String KEY_FAVS = "favs";
    private static final String KEY_TIME = "t_"; // + pachet = momentul ultimei deschideri
    private static final int MAX_FAVS = 6;
    private static final long IDLE_MS = 120_000L;

    /** Favorite implicite (primele găsite pe TV). Le poți schimba și direct de pe telecomandă. */
    private static final String[] DEFAULT_FAVS = {
            "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom", "com.stremio.one", "org.xbmc.kodi",
            "com.amazon.tv.settings.v2"
    };

    private static final int INK = 0xFF05070C;

    private static class App {
        String pkg, label;
        Intent intent;
        Drawable icon;
    }

    /** O pastilă din coloana de pe dreapta (aplicație sau butonul „Toate"). */
    private static class Tile {
        App app;                // null = butonul „Toate aplicațiile"
        String name;
        FrameLayout box;
        ImageView icon;
        TextView label;
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private List<App> apps = new ArrayList<>();
    private final Map<String, App> byPkg = new HashMap<>();
    private final List<String> favs = new ArrayList<>();
    private final List<Tile> tiles = new ArrayList<>();

    private LumenView scene;
    private FrameLayout uiLayer;
    private LinearLayout clockBox;
    private TextView clockView, dateView;
    private LinearLayout block;
    private TextView ovView, titleView, metaView;
    private LinearLayout rail;
    private FrameLayout gridOverlay;
    private TextView gridCount;
    private GridView grid;
    private AppsAdapter gridAdapter;
    private View lastSel;

    private float fixedHour = -1f;
    private boolean cinema;
    private boolean swallowUp;
    private boolean everResumed;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE, d MMMM", new Locale("ro", "RO"));
    private final SimpleDateFormat dayFmt = new SimpleDateFormat("d MMM", new Locale("ro", "RO"));
    private static final ColorFilter GRAY;

    static {
        ColorMatrix m = new ColorMatrix();
        m.setSaturation(0f);
        GRAY = new ColorMatrixColorFilter(m);
    }

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateHeader();
            ui.postDelayed(this, 1000);
        }
    };
    private final Runnable goCinema = new Runnable() {
        @Override public void run() {
            if (gridOverlay.getVisibility() == View.VISIBLE) {
                armIdle();
                return;
            }
            cinema = true;
            uiLayer.animate().alpha(0f).setDuration(1400).start();
        }
    };

    // ------------------------------------------------------------------ ciclu de viață

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        handleIntent(getIntent());
        loadApps();
    }

    @Override protected void onResume() {
        super.onResume();
        scene.setActive(true);
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        wake(false);
        playEntrance();
        everResumed = true;
        if (tiles.size() > 0) refreshInfo(); // „ultima oară" s-a schimbat cât timp erai în altă aplicație
    }

    @Override protected void onPause() {
        super.onPause();
        scene.setActive(false);
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(goCinema);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); // Home apăsat cât timp suntem deja în față
        setIntent(intent);
        handleIntent(intent);
        wake(false);
        if (!intent.getBooleanExtra("grid", false)) hideGrid();
        if (rail != null && rail.getChildCount() > 0 && gridOverlay.getVisibility() != View.VISIBLE) {
            rail.getChildAt(0).requestFocus();
        }
        playEntrance();
    }

    private void handleIntent(Intent i) {
        if (i == null) return;
        if (i.hasExtra("hour")) {
            fixedHour = i.getFloatExtra("hour", -1f);
            scene.setFixedHour(fixedHour);
            updateHeader();
        }
        if (i.getBooleanExtra("grid", false)) ui.post(this::showGrid);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        bg.shutdownNow();
    }

    @Override public void onBackPressed() {
        if (gridOverlay.getVisibility() == View.VISIBLE) hideGrid();
        // altfel: nu facem nimic - Home nu trebuie să se închidă
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (cinema) { // prima apăsare doar trezește ecranul, nu activează nimic
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                swallowUp = true;
                wake(true);
            }
            return true;
        }
        if (swallowUp && e.getAction() == KeyEvent.ACTION_UP) {
            swallowUp = false;
            return true;
        }
        armIdle();
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (gridOverlay.getVisibility() == View.VISIBLE) hideGrid(); else showGrid();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private void armIdle() {
        ui.removeCallbacks(goCinema);
        ui.postDelayed(goCinema, IDLE_MS);
    }

    private void wake(boolean animate) {
        boolean was = cinema;
        cinema = false;
        uiLayer.animate().cancel();
        if (animate && was) uiLayer.animate().alpha(1f).setDuration(700).start(); else uiLayer.setAlpha(1f);
        armIdle();
    }

    private void playEntrance() {
        if (rail == null) return;
        long delay = everResumed ? 0 : 150;
        rail.animate().cancel();
        rail.setAlpha(0f);
        rail.setTranslationX(dp(26));
        rail.animate().alpha(1f).translationX(0f).setStartDelay(delay).setDuration(650)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        block.animate().cancel();
        block.setAlpha(0f);
        block.setTranslationY(dp(18));
        block.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(700)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        clockBox.animate().cancel();
        clockBox.setAlpha(0f);
        clockBox.animate().alpha(1f).setStartDelay(delay / 2).setDuration(700).start();
    }

    // ------------------------------------------------------------------ UI

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(float dpSize, String family, float alpha, float letterSpacing) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dpSize);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setShadowLayer(dp(14), 0f, dp(3), 0x66000000);
        t.setIncludeFontPadding(false);
        t.setAlpha(alpha);
        if (letterSpacing != 0f) t.setLetterSpacing(letterSpacing);
        return t;
    }

    private GradientDrawable tileBg(boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(selected ? 0x2EFFFFFF : 0x12FFFFFF);
        g.setCornerRadius(dp(14));
        g.setStroke(selected ? dp(1.5f) : dp(1), selected ? 0xE6FFFFFF : 0x24FFFFFF);
        return g;
    }

    private GradientDrawable scrim(GradientDrawable.Orientation o, int from, int to) {
        return new GradientDrawable(o, new int[]{from, to});
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setClipChildren(false);

        scene = new LumenView(this);
        root.addView(scene, new FrameLayout.LayoutParams(-1, -1));

        uiLayer = new FrameLayout(this);
        uiLayer.setClipChildren(false);
        root.addView(uiLayer, new FrameLayout.LayoutParams(-1, -1));

        int sw = getResources().getDisplayMetrics().widthPixels;
        int sh = getResources().getDisplayMetrics().heightPixels;

        // vignete: stânga (pentru text) și jos
        View left = new View(this);
        left.setBackground(scrim(GradientDrawable.Orientation.LEFT_RIGHT, 0xC7030509, 0x00030509));
        uiLayer.addView(left, new FrameLayout.LayoutParams((int) (sw * 0.72f), -1, Gravity.START));
        View bottom = new View(this);
        bottom.setBackground(scrim(GradientDrawable.Orientation.TOP_BOTTOM, 0x00030509, 0xC7030509));
        uiLayer.addView(bottom, new FrameLayout.LayoutParams(-1, (int) (sh * 0.48f), Gravity.BOTTOM));

        // ceas + dată - stânga sus
        clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockView = text(66, "sans-serif-light", 1f, -0.04f);
        dateView = text(10.5f, "sans-serif-medium", 0.55f, 0.28f);
        clockBox.addView(clockView, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-2, -2);
        dl.topMargin = dp(8);
        clockBox.addView(dateView, dl);
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        cp.setMargins(dp(70), dp(48), 0, 0);
        uiLayer.addView(clockBox, cp);

        // bloc „aplicația selectată" - stânga jos
        block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        ovView = text(8, "sans-serif-medium", 0.6f, 0.34f);
        titleView = text(48, "sans-serif-medium", 1f, -0.04f);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        metaView = text(11, "sans-serif", 0.55f, 0.02f);
        block.addView(ovView, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(9);
        block.addView(titleView, tl);
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2);
        ml.topMargin = dp(10);
        block.addView(metaView, ml);
        TextView pill = new TextView(this);
        pill.setText("Deschide");
        pill.setTextColor(0xFF0A0C10);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10.5f);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(17), 0, dp(17), 0);
        GradientDrawable pb = new GradientDrawable();
        pb.setColor(Color.WHITE);
        pb.setCornerRadius(dp(15.5f));
        pill.setBackground(pb);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, dp(31));
        pl.topMargin = dp(18);
        block.addView(pill, pl);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(dp(520), -2, Gravity.BOTTOM | Gravity.START);
        bp.setMargins(dp(70), 0, 0, dp(66));
        uiLayer.addView(block, bp);

        // coloana de aplicații - dreapta
        rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.END);
        rail.setClipChildren(false);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        rp.setMargins(0, 0, dp(65), 0);
        uiLayer.addView(rail, rp);

        // „Toate aplicațiile" - panou întunecat pe tot ecranul
        gridOverlay = new FrameLayout(this);
        gridOverlay.setVisibility(View.GONE);
        gridOverlay.setClickable(true);
        gridOverlay.addView(new GlassPanel(this, scene, 0f, true), new FrameLayout.LayoutParams(-1, -1));
        TextView title = text(32, "sans-serif-light", 1f, -0.03f);
        title.setText("Aplicații");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        tp.setMargins(dp(70), dp(48), 0, 0);
        gridOverlay.addView(title, tp);
        gridCount = text(8.5f, "sans-serif-medium", 0.5f, 0.3f);
        FrameLayout.LayoutParams gcp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        gcp.setMargins(dp(71), dp(96), 0, 0);
        gridOverlay.addView(gridCount, gcp);
        grid = new GridView(this);
        grid.setNumColumns(8);
        grid.setVerticalSpacing(dp(14));
        grid.setSelector(new ColorDrawable(0));
        grid.setClipToPadding(false);
        grid.setPadding(dp(50), dp(12), dp(50), dp(24));
        grid.setFocusable(true);
        gridAdapter = new AppsAdapter();
        grid.setAdapter(gridAdapter);
        grid.setOnItemClickListener((p, v, pos, id) -> launch(apps.get(pos)));
        grid.setOnItemLongClickListener((p, v, pos, id) -> {
            toggleFav(apps.get(pos));
            return true;
        });
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                if (lastSel != null) gridHighlight(lastSel, false);
                lastSel = v;
                if (v != null) gridHighlight(v, true);
            }

            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1, -1);
        gp.setMargins(0, dp(150), 0, 0);
        gridOverlay.addView(grid, gp);
        root.addView(gridOverlay, new FrameLayout.LayoutParams(-1, -1));

        scene.setOnLayersChanged(() -> {
            View g = gridOverlay.getChildAt(0);
            if (g != null) g.invalidate();
        });

        setContentView(root);
        updateHeader();
    }

    // ------------------------------------------------------------------ ceas

    private float currentHour() {
        if (fixedHour >= 0f) return fixedHour;
        Calendar c = Calendar.getInstance();
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60f;
    }

    private void updateHeader() {
        float h = currentHour();
        int hr = (int) Math.floor(h);
        int mn = (int) Math.floor((h - hr) * 60f);
        clockView.setText(String.format(Locale.US, "%02d:%02d", hr, mn));
        dateView.setText(dateFmt.format(new Date()).toUpperCase(new Locale("ro", "RO")));
    }

    private String ago(long ts) {
        long d = System.currentTimeMillis() - ts;
        if (d < 0) d = 0;
        long min = d / 60000L;
        if (min < 2) return "chiar acum";
        if (min < 60) return "acum " + min + " min";
        long h = min / 60;
        if (h < 24) return "acum " + h + (h == 1 ? " oră" : " ore");
        if (h < 48) return "ieri";
        return dayFmt.format(new Date(ts));
    }

    // ------------------------------------------------------------------ coloana din dreapta

    private ImageView iconView(Drawable d, int padDp) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(d);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        iv.setColorFilter(GRAY);
        iv.setAlpha(0.82f);
        return iv;
    }

    private void buildRail() {
        rail.removeAllViews();
        tiles.clear();
        for (String pkg : favs) {
            App a = byPkg.get(pkg);
            if (a != null) addTile(a, a.label, a.icon);
        }
        addTile(null, "Toate aplicațiile", new DotsDrawable());
        if (rail.getChildCount() > 0) rail.getChildAt(0).requestFocus();
        refreshInfo();
    }

    private void addTile(final App a, String name, Drawable icon) {
        final Tile t = new Tile();
        t.app = a;
        t.name = name;

        LinearLayout rowL = new LinearLayout(this);
        rowL.setOrientation(LinearLayout.HORIZONTAL);
        rowL.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        rowL.setClipChildren(false);

        t.label = text(10.5f, "sans-serif-medium", 0f, 0.2f);
        t.label.setText(name.toUpperCase(new Locale("ro", "RO")));
        t.label.setSingleLine(true);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-2, -2);
        ll.rightMargin = dp(13);
        rowL.addView(t.label, ll);

        t.box = new FrameLayout(this);
        t.box.setBackground(tileBg(false));
        t.icon = iconView(icon, 11);
        t.box.addView(t.icon, new FrameLayout.LayoutParams(-1, -1));
        t.box.setFocusable(true);
        t.box.setClickable(true);
        t.box.setOnClickListener(v -> { if (a != null) launch(a); else showGrid(); });
        t.box.setOnLongClickListener(v -> {
            if (a != null) dockMenu(a);
            return true;
        });
        t.box.setOnFocusChangeListener((v, has) -> onTileFocus(t, has));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(48), dp(48));
        rowL.addView(t.box, bl);

        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, -2);
        rl.topMargin = tiles.isEmpty() ? 0 : dp(11);
        rail.addView(rowL, rl);
        tiles.add(t);
    }

    private void onTileFocus(Tile t, boolean has) {
        t.box.animate().cancel();
        t.label.animate().cancel();
        if (has) {
            t.box.setBackground(tileBg(true));
            t.icon.setColorFilter(null);
            t.icon.setAlpha(1f);
            t.box.animate().scaleX(1.08f).scaleY(1.08f).setDuration(260).setInterpolator(new DecelerateInterpolator(1.6f)).start();
            t.label.animate().alpha(1f).setDuration(260).start();
            showInfo(t);
            int n = tiles.size(), idx = tiles.indexOf(t);
            scene.setParallaxTarget(n <= 1 ? 0f : (idx - (n - 1) / 2f) / ((n - 1) / 2f));
        } else {
            t.box.setBackground(tileBg(false));
            t.icon.setColorFilter(GRAY);
            t.icon.setAlpha(0.82f);
            t.box.animate().scaleX(1f).scaleY(1f).setDuration(200).start();
            t.label.animate().alpha(0f).setDuration(200).start();
        }
    }

    private void refreshInfo() {
        for (Tile t : tiles) if (t.box.hasFocus()) { showInfo(t); return; }
        if (!tiles.isEmpty()) showInfo(tiles.get(0));
    }

    private void showInfo(Tile t) {
        String ov, title, meta;
        if (t.app != null) {
            long ts = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_TIME + t.app.pkg, 0L);
            ov = ts > 0 ? "Ultima oară · " + ago(ts) : "Aplicație";
            title = t.app.label;
            meta = "";
        } else {
            ov = "Bibliotecă";
            title = "Toate aplicațiile";
            meta = apps.size() + " instalate";
        }
        ovView.setText(ov.toUpperCase(new Locale("ro", "RO")));
        titleView.setText(title);
        metaView.setText(meta);
        metaView.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);
        block.setAlpha(0.35f);
        block.animate().alpha(1f).setStartDelay(0).setDuration(240).start();
    }

    /** Trei puncte desenate (butonul „Toate"). */
    private static final class DotsDrawable extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        DotsDrawable() { p.setColor(Color.WHITE); }

        @Override public void draw(Canvas c) {
            float w = getBounds().width(), h = getBounds().height(), cy = getBounds().centerY(), r = Math.min(w, h) * 0.075f;
            for (int i = -1; i <= 1; i++) c.drawCircle(getBounds().centerX() + i * w * 0.24f, cy, r, p);
        }

        @Override public void setAlpha(int a) { p.setAlpha(a); }

        @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    // ------------------------------------------------------------------ „Toate aplicațiile"

    private void showGrid() {
        gridCount.setText((apps.size() + " INSTALATE"));
        gridOverlay.setVisibility(View.VISIBLE);
        gridOverlay.setAlpha(0f);
        gridOverlay.setTranslationY(dp(18));
        gridOverlay.animate().alpha(1f).translationY(0f).setDuration(350).start();
        clockBox.setVisibility(View.INVISIBLE);
        block.setVisibility(View.INVISIBLE);
        rail.setVisibility(View.INVISIBLE);
        grid.requestFocus();
    }

    private void hideGrid() {
        if (gridOverlay.getVisibility() != View.VISIBLE) return;
        gridOverlay.animate().cancel();
        gridOverlay.setVisibility(View.GONE);
        clockBox.setVisibility(View.VISIBLE);
        block.setVisibility(View.VISIBLE);
        rail.setVisibility(View.VISIBLE);
        if (rail.getChildCount() > 0) rail.getChildAt(0).requestFocus();
    }

    private void gridHighlight(View cell, boolean on) {
        if (!(cell instanceof LinearLayout)) return;
        LinearLayout c = (LinearLayout) cell;
        FrameLayout box = (FrameLayout) c.getChildAt(0);
        ImageView icon = (ImageView) box.getChildAt(0);
        TextView label = (TextView) c.getChildAt(1);
        box.setBackground(tileBg(on));
        icon.setColorFilter(on ? null : GRAY);
        icon.setAlpha(on ? 1f : 0.82f);
        label.setAlpha(on ? 1f : 0.55f);
        c.animate().cancel();
        c.animate().scaleX(on ? 1.07f : 1f).scaleY(on ? 1.07f : 1f).setDuration(200).start();
    }

    private class AppsAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int i) { return apps.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override public View getView(int pos, View convert, ViewGroup parent) {
            App a = apps.get(pos);
            LinearLayout cell = new LinearLayout(MainActivity.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setGravity(Gravity.CENTER_HORIZONTAL);
            cell.setPadding(dp(4), dp(8), dp(4), dp(6));
            FrameLayout box = new FrameLayout(MainActivity.this);
            box.setBackground(tileBg(false));
            box.addView(iconView(a.icon, 15), new FrameLayout.LayoutParams(-1, -1));
            cell.addView(box, new LinearLayout.LayoutParams(dp(75), dp(75)));
            TextView label = text(10, "sans-serif", 0.55f, 0.04f);
            label.setText((favs.contains(a.pkg) ? "★ " : "") + a.label);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(10);
            cell.addView(label, lp);
            return cell;
        }
    }

    // ------------------------------------------------------------------ aplicații & favorite

    private void loadApps() {
        bg.submit(() -> {
            PackageManager pm = getPackageManager();
            Map<String, App> map = new LinkedHashMap<>();
            for (String cat : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
                Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
                    App a = new App();
                    a.pkg = pkg;
                    a.label = String.valueOf(ri.loadLabel(pm));
                    a.icon = ri.loadIcon(pm);
                    a.intent = new Intent(Intent.ACTION_MAIN).addCategory(cat)
                            .setClassName(pkg, ri.activityInfo.name)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    map.put(pkg, a);
                }
            }
            final List<App> list = new ArrayList<>(map.values());
            Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
            ui.post(() -> {
                apps = list;
                byPkg.clear();
                byPkg.putAll(map);
                loadFavs();
                buildRail();
                gridAdapter.notifyDataSetChanged();
            });
        });
    }

    private void loadFavs() {
        favs.clear();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_FAVS, null);
        if (saved != null) {
            for (String p : saved.split(",")) {
                if (favs.size() >= MAX_FAVS) break;
                if (byPkg.containsKey(p) && !favs.contains(p)) favs.add(p);
            }
        } else {
            for (String p : DEFAULT_FAVS) {
                if (favs.size() >= MAX_FAVS) break;
                if (byPkg.containsKey(p) && !favs.contains(p)) favs.add(p);
            }
            for (App a : apps) {
                if (favs.size() >= 5) break;
                if (!favs.contains(a.pkg)) favs.add(a.pkg);
            }
            saveFavs();
        }
    }

    private void saveFavs() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_FAVS, TextUtils.join(",", favs)).apply();
    }

    private void toggleFav(App a) {
        if (favs.contains(a.pkg)) {
            favs.remove(a.pkg);
            Toast.makeText(this, a.label + " scos din favorite", Toast.LENGTH_SHORT).show();
        } else if (favs.size() >= MAX_FAVS) {
            Toast.makeText(this, "Favorite pline (max " + MAX_FAVS + ")", Toast.LENGTH_SHORT).show();
            return;
        } else {
            favs.add(a.pkg);
            Toast.makeText(this, a.label + " adăugat în favorite", Toast.LENGTH_SHORT).show();
        }
        saveFavs();
        buildRail();
        gridAdapter.notifyDataSetChanged();
    }

    private void dockMenu(final App a) {
        final String[] items = {"Mută mai sus", "Mută mai jos", "Scoate din favorite"};
        new AlertDialog.Builder(this).setTitle(a.label).setItems(items, (d, which) -> {
            int i = favs.indexOf(a.pkg);
            if (which == 0 && i > 0) Collections.swap(favs, i, i - 1);
            else if (which == 1 && i >= 0 && i < favs.size() - 1) Collections.swap(favs, i, i + 1);
            else if (which == 2) favs.remove(a.pkg);
            saveFavs();
            buildRail();
            gridAdapter.notifyDataSetChanged();
        }).show();
    }

    private void launch(App a) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong(KEY_TIME + a.pkg, System.currentTimeMillis()).apply();
            startActivity(a.intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Nu pot deschide " + a.label, Toast.LENGTH_SHORT).show();
        }
    }
}
