package com.gathin.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The cinema screen's dock: an endless ring of every installed app, turning with a smooth animation, the
 * focused app stopped in the middle of a frosted pill and named above it. D-pad left/right turns the ring
 * one app at a time (it wraps around) with a soft interface click, OK opens the app with a deeper confirm
 * sound, holding OK opens the app's TV settings page, Up goes back to the header buttons.
 *
 * <p>All apps live on one ring: the seven tiles around the focused one are the only views, rebound as the
 * ring turns, so a TV with hundreds of apps still draws seven tiles. The geometry follows the reference
 * video: small square tiles on a compact band, the middle tile enlarged about 1.6x under a white ring.
 *
 * <p>Built for smoothness on a TV GPU: the turn moves the seven already-laid-out tiles with translations and
 * scales (no measure/layout pass per frame), and every app's artwork (tile plate, pill colours, pill icon) is
 * sampled once on a background thread, so turning the ring never decodes a bitmap on the UI thread.
 *
 * <p>Only the cinema profile uses this view; the classic screen keeps the RecyclerView dock in
 * {@link MainActivity}.
 */
final class CinemaCarousel extends FrameLayout {

    /** Up on the ring goes back to the header buttons, which only the activity can focus. */
    interface Listener {
        void onNavigateUp();
    }

    private static final String TAG = "CinemaCarousel";
    private static final int SLOTS = 7;
    /** One tile at rest: the reference's 103 px on a 1920-wide screen (density 2 there, so 51.5dp). */
    private static final float TILE_DP = 51.5f;
    /** The focused tile's scale under the white ring. */
    private static final float FOCUS_SCALE = 1.62f;
    /** Centre of the neighbour tile / of the second neighbour, from the middle, in dp (152 px / 273 px). */
    private static final float NEAR_DP = 76f, FAR_DP = 136.5f;
    /** The frosted band: height, distance from the screen's bottom edge, side padding, corner radius (145 px,
        52 px, 19 px, 72 px in the reference). */
    private static final float BAND_H_DP = 72.5f, BAND_BOTTOM_DP = 26f, BAND_PAD_DP = 9.5f, BAND_RADIUS_DP = 36f;
    /** The focused app's name chip: text size, padding, gap above the focused tile, corner radius. */
    private static final float CHIP_TEXT_SP = 17f, CHIP_PAD_H_DP = 14f, CHIP_PAD_V_DP = 6f,
            CHIP_GAP_DP = 30f, CHIP_RADIUS_DP = 12f;
    /** The ring's own height: the band, the enlarged tile and the chip above it all fit. */
    static final float VIEW_H_DP = 180f;
    /** Tile corner radius, as the reference's 28 px on a 103 px tile. */
    private static final float TILE_RADIUS_DP = 14f;
    /** Turn length: short enough that a second press lands while the first is still easing out. */
    private static final int TURN_MS = 280;

    /**
     * The name badge above the focused tile: two curated looks, switched from the launcher's own menu.
     * The colour-of-the-app pills and the busy alternatives were dropped after review.
     */
    static final int CHIP_GLASS = 0;      // dark frosted glass, white text (the reference video's chip)
    static final int CHIP_NONE = 1;       // the name alone, a soft shadow instead of a chip
    static final int CHIP_STYLES = 2;
    private static final String CHIP_STYLE_KEY = "cinema_chip_style";

    static int chipStyle(Context context) {
        android.content.SharedPreferences prefs =
                context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        int style = prefs.contains(CHIP_STYLE_KEY)
                ? prefs.getInt(CHIP_STYLE_KEY, CHIP_GLASS)
                : StudioDefaults.integer(context, "chipStyle", CHIP_GLASS);
        return style >= 0 && style < CHIP_STYLES ? style : CHIP_GLASS;
    }

    static void setChipStyle(Context context, int style) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).edit()
                .putInt(CHIP_STYLE_KEY, Math.max(0, Math.min(CHIP_STYLES - 1, style))).apply();
    }

    private final float density;
    private final Listener listener;
    private final List<String> favourites;
    private final List<String> hidden;
    private final String firePkg;
    private final boolean animations;
    private final boolean reducedMotion;
    private final Sounds sounds;
    private final List<App> apps = new ArrayList<>();
    private final Slot[] slots = new Slot[SLOTS];
    private final TextView chip;
    private final int chipStyle;
    /** The frozen band: a plain View with a glass drawable, so a turning frame never repaints it. */
    private final View bandView;
    private final RectF band = new RectF();
    private final GradientDrawable fallbackChip = new GradientDrawable();
    private GradientDrawable glassChip;
    private FireBannerDrawable fire;
    private ValueAnimator animator;
    private float bandTop, bandBottom, tileCenterY;
    private int lastWidth, lastHeight;
    private int tilePx;
    private float nearPx, farPx;
    private float chipWidth, chipHeight;
    private boolean chipDirty = true;
    private int selection;
    private int startIndex;
    private int direction = 1;
    private float frac;
    private boolean fireRunning;

    /** One installed app on the ring; its artwork is sampled once, off the UI thread. */
    private static final class App {
        final String pkg;
        final String label;
        final ComponentName component;
        final boolean leanback;
        final Drawable icon;
        volatile boolean artReady;
        volatile int[] plate;
        volatile GradientDrawable tileBackground;

        App(String pkg, String label, ComponentName component, boolean leanback, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.component = component;
            this.leanback = leanback;
            this.icon = icon;
        }
    }

    CinemaCarousel(Context context, Config cfg, Listener listener) {
        super(context);
        this.density = getResources().getDisplayMetrics().density;
        this.listener = listener;
        this.favourites = cfg.dockApps;
        this.hidden = cfg.hiddenApps;
        this.firePkg = context.getString(R.string.fire_app);
        this.animations = cfg.animations;
        this.reducedMotion = cfg.reducedMotion;
        this.sounds = new Sounds(context);
        this.chipStyle = chipStyle(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setClickable(true);
        setLongClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setClipChildren(false);
        setClipToPadding(false);

        tilePx = Math.round(TILE_DP * density);
        nearPx = NEAR_DP * density;
        farPx = FAR_DP * density;
        GradientDrawable glass = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x4AFFFFFF, 0x1FFFFFFF});
        glass.setCornerRadius(BAND_RADIUS_DP * density);
        glass.setStroke(Math.max(2, Math.round(density)), 0x66FFFFFF);
        bandView = new View(context);
        bandView.setBackground(glass);
        bandView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(bandView, new LayoutParams(0, 0));
        for (int i = 0; i < SLOTS; i++) {
            Slot slot = new Slot(context, tilePx);
            slots[i] = slot;
            addView(slot, new LayoutParams(tilePx, tilePx));
        }
        chip = new TextView(context);
        chip.setTextColor(Color.WHITE);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP);
        chip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        chip.setSingleLine(true);
        chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) chip.setElevation(9f * density);
        chip.setPadding(Math.round(CHIP_PAD_H_DP * density), Math.round(CHIP_PAD_V_DP * density),
                Math.round(CHIP_PAD_H_DP * density), Math.round(CHIP_PAD_V_DP * density));
        fallbackChip.setColor(0xFF33363D);
        fallbackChip.setCornerRadius(CHIP_RADIUS_DP * density);
        chip.setBackground(fallbackChip);
        chip.setAlpha(0f);
        addView(chip, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setOnKeyListener((v, keyCode, event) -> {
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) step(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (event.getAction() == KeyEvent.ACTION_DOWN) step(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (event.getAction() == KeyEvent.ACTION_DOWN && listener != null) listener.onNavigateUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;      // nothing is below the ring
                default:
                    return false;     // OK and long OK: the view's own click handling
            }
        });
        setOnClickListener(v -> openSelected());
        setOnLongClickListener(v -> {
            App app = selected();
            if (app == null) return false;
            sounds.confirm();
            try {
                Intent info = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + app.pkg)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(info);
            } catch (RuntimeException ignored) {
                // an app without a settings page: nothing to open
            }
            return true;
        });
        setOnFocusChangeListener((v, focused) -> {
            if (!focused) finishAnimation();
        });
        loadApps();
    }

    // ---- the ring ------------------------------------------------------------------------------

    /** Every launchable app, the cinema dock's favourites first, then the rest by name. */
    private void loadApps() {
        PackageManager pm = getContext().getPackageManager();
        Map<String, App> byPkg = new LinkedHashMap<>();
        Intent tv = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(tv, 0)) addApp(byPkg, pm, ri, true);
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) addApp(byPkg, pm, ri, false);

        List<App> ordered = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (String favourite : favourites) {
            App app = byPkg.get(favourite);
            if (app != null && used.add(favourite)) ordered.add(app);
        }
        List<App> rest = new ArrayList<>();
        for (App app : byPkg.values()) if (!used.contains(app.pkg)) rest.add(app);
        Collections.sort(rest, Comparator.comparing(app -> app.label.toLowerCase(Locale.ROOT)));
        ordered.addAll(rest);

        String keep = selection < apps.size() ? apps.get(selection).pkg : null;
        apps.clear();
        apps.addAll(ordered);
        selection = 0;
        if (keep != null) {
            for (int i = 0; i < apps.size(); i++) {
                if (apps.get(i).pkg.equals(keep)) {
                    selection = i;
                    break;
                }
            }
        }
        startIndex = selection;
        frac = 0f;
        // The first tiles are ready before the first frame; the rest follow on a background thread so a
        // turn never samples an icon on the UI thread.
        int ready = Math.min(apps.size(), SLOTS);
        for (int i = 0; i < ready; i++) prepareArtwork(apps.get(i));
        prepareArtworkAsync(new ArrayList<>(apps.subList(ready, apps.size())));
        // Bind the ring before the first layout pass: setting a background, an image or padding from inside
        // onLayout would call requestLayout() during layout (a second pass, and a warning every start).
        rebindSlots();
        chipDirty = true;
        updateChip();
        requestLayout();
        invalidate();
    }

    @SuppressWarnings("deprecation")
    private void addApp(Map<String, App> byPkg, PackageManager pm, ResolveInfo ri, boolean leanback) {
        String pkg = ri.activityInfo.packageName;
        if (pkg.equals(getContext().getPackageName()) || hidden.contains(pkg) || byPkg.containsKey(pkg)) return;
        CharSequence label = ri.loadLabel(pm);
        if (label == null || label.toString().trim().isEmpty()) return;
        Drawable icon;
        try {
            icon = ri.loadIcon(pm);
        } catch (RuntimeException e) {
            icon = null;
        }
        if (icon == null) return;
        ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
        byPkg.put(pkg, new App(pkg, label.toString().trim(), component, leanback, icon));
    }

    /** Samples one app's tile plate, pill colour and pill icon; safe to call off the UI thread. */
    private void prepareArtwork(App app) {
        if (app.artReady) return;
        try {
            // Only the tile's plate is drawn: the name badge is neutral now, so its per-app colours and the
            // icon copy are no longer sampled (that work used to run for every app at startup).
            int[] plate = AppAdapter.plateFor(app.icon);
            GradientDrawable tile = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    plate != null ? plate : new int[]{0xFF23262B, 0xFF141619});
            tile.setCornerRadius(TILE_RADIUS_DP * density);
            app.plate = plate;
            app.tileBackground = tile;
            app.artReady = true;
        } catch (RuntimeException e) {
            // a broken icon: the tile keeps its neutral fallback
            Log.w(TAG, "artwork failed for " + app.pkg, e);
            app.artReady = true;
        }
    }

    private void prepareArtworkAsync(List<App> remaining) {
        if (remaining.isEmpty()) return;
        Thread worker = new Thread(() -> {
            for (App app : remaining) prepareArtwork(app);
            post(() -> {
                if (isAttachedToWindow()) {
                    chipDirty = true;
                    updateChip();
                }
            });
        }, "CinemaArtwork");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Re-reads the app list (an app was installed, removed or renamed). */
    void reloadApps() {
        loadApps();
    }

    private App selected() {
        return selection >= 0 && selection < apps.size() ? apps.get(selection) : null;
    }

    /** Focused app's index on the ring. */
    int selectedIndex() {
        return selection;
    }

    private void openSelected() {
        App app = selected();
        if (app == null) return;
        sounds.confirm();
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (app.leanback) launch.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        try {
            getContext().startActivity(launch);
            if (animations && !reducedMotion && getContext() instanceof Activity) {
                ((Activity) getContext()).overridePendingTransition(R.anim.app_open_enter, R.anim.app_open_exit);
            }
        } catch (RuntimeException e) {
            if (app.component != null) {
                try {
                    getContext().startActivity(new Intent(Intent.ACTION_MAIN).setComponent(app.component)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException ignored) {
                    // the app cannot be opened from Home
                }
            }
        }
    }

    /** Turns the ring by one app; dir is +1 for right and -1 for left. */
    private void step(int dir) {
        if (apps.size() < 2) return;
        finishAnimation();
        sounds.focusTick();
        boolean fireWasBurning = fire != null && fire.isRunning();
        if (fireWasBurning) fire.stop();
        startIndex = selection;
        direction = dir;
        frac = 0f;
        if (!animations || reducedMotion) {
            selection = mod(startIndex + dir);
            startIndex = selection;
            rebindSlots();
            updateChip();
            applyPositions();
            invalidate();
            if (fireRunning && fire != null) fire.start();
            return;
        }
        ValueAnimator turn = ValueAnimator.ofFloat(0f, 1f);
        turn.setDuration(TURN_MS);
        turn.setInterpolator(new DecelerateInterpolator(1.9f));
        turn.addUpdateListener(a -> {
            frac = (float) a.getAnimatedValue();
            // No measure/layout: the seven tiles are already laid out, so the whole turn is just their
            // translations and scales, which the render thread picks up without touching the view tree.
            applyPositions();
            invalidate();
        });
        turn.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (animator != turn) return;
                animator = null;
                frac = 0f;
                selection = mod(startIndex + direction);
                startIndex = selection;
                rebindSlots();
                updateChip();
                applyPositions();
                invalidate();
                if (fireRunning && fire != null) fire.start();
            }
        });
        animator = turn;
        turn.start();
    }

    /** Stops a running turn at once, committing the step it reached: used before a new turn or on pause. */
    private void finishAnimation() {
        ValueAnimator running = animator;
        if (running != null) {
            // onAnimationEnd sees animator == running and lands the ring on the new app, so nothing jumps.
            running.end();
            animator = null;
        }
        frac = 0f;
    }

    /** Jumps the ring to an app (the activity's focus-return). */
    void select(int index) {
        if (apps.isEmpty()) return;
        finishAnimation();
        selection = mod(index);
        startIndex = selection;
        rebindSlots();
        updateChip();
        applyPositions();
        invalidate();
    }

    private void rebindSlots() {
        if (apps.isEmpty()) return;
        for (int s = 0; s < SLOTS; s++) bind(slots[s], apps.get(mod(startIndex + s - 3)));
    }

    private void updateChip() {
        App app = selected();
        if (app == null) {
            chip.setAlpha(0f);
            return;
        }
        boolean changed = !app.label.contentEquals(chip.getText());
        chip.setText(app.label);
        setContentDescription(app.label);
        styleChip(app);
        chip.setAlpha(1f);
        if (changed && animations && !reducedMotion) {
            chip.animate().cancel();
            chip.setScaleX(0.92f);
            chip.setScaleY(0.92f);
            chip.animate().scaleX(1f).scaleY(1f).setDuration(170)
                    .setInterpolator(new DecelerateInterpolator(1.5f)).start();
        }
        chipDirty = true;
        if (lastWidth > 0) {
            measureChip();
            applyPositions();
            invalidate();
        } else {
            requestLayout();
        }
    }

    /**
     * The name badge above the focused tile, in one of the two curated looks:
     * <ul>
     *   <li>{@link #CHIP_GLASS} — the reference video's dark glass chip, nearly opaque, white text;</li>
     *   <li>{@link #CHIP_NONE} — the name alone, a soft shadow keeping it readable on the wallpaper.</li>
     * </ul>
     * Both are neutral; the per-app colours show only on the tiles behind the icons.
     */
    private void styleChip(App app) {
        if (chipStyle == CHIP_NONE) {
            chip.setBackground(null);
            chip.setShadowLayer(8f * density, 0f, 2f * density, 0xE0000000);
            chip.setElevation(0f);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            chip.setLetterSpacing(0.02f);
            chip.setCompoundDrawablesRelative(null, null, null, null);
        } else {
            chip.setBackground(glassChip());
            chip.setShadowLayer(0f, 0f, 0f, 0);
            chip.setElevation(8f * density);
            chip.setTextColor(0xFFFFFFFF);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP);
            chip.setLetterSpacing(0.01f);
            chip.setCompoundDrawablesRelative(null, null, null, null);
        }
    }

    /** The neutral dark glass chip of the reference video, built once per carousel and almost opaque. */
    private GradientDrawable glassChip() {
        if (glassChip == null) {
            glassChip = new GradientDrawable();
            glassChip.setColor(0xF50B0E13);
            glassChip.setCornerRadius(CHIP_RADIUS_DP * density);
            glassChip.setStroke(Math.max(2, Math.round(1.5f * density)), 0x3DFFFFFF);
        }
        return glassChip;
    }

    void startFire() {
        fireRunning = true;
        if (fire != null) fire.start();
    }

    void stopFire() {
        fireRunning = false;
        if (fire != null) fire.stop();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        finishAnimation();
        sounds.release();
    }

    private int mod(int value) {
        int n = apps.size();
        if (n == 0) return 0;
        int m = value % n;
        return m < 0 ? m + n : m;
    }

    // ---- layout and paint -----------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l;
        int height = b - t;
        if (width <= 0 || height <= 0 || apps.isEmpty()) return;
        if (width != lastWidth || height != lastHeight) {
            lastWidth = width;
            lastHeight = height;
            chipDirty = true;
            float centerX = width / 2f;
            bandBottom = height - BAND_BOTTOM_DP * density;
            bandTop = bandBottom - BAND_H_DP * density;
            tileCenterY = (bandTop + bandBottom) / 2f;
            float halfBand = farPx + tilePx / 2f + BAND_PAD_DP * density;
            band.set(centerX - halfBand, bandTop, centerX + halfBand, bandBottom);
            bandView.layout(Math.round(band.left), Math.round(band.top),
                    Math.round(band.right), Math.round(band.bottom));
        }
        for (Slot slot : slots) {
            // Laid out once at the origin; every turn only translates them (see applyPositions).
            if (slot.getWidth() != tilePx || slot.getHeight() != tilePx) {
                slot.layout(0, 0, tilePx, tilePx);
            }
        }
        if (chipDirty) measureChip();
        applyPositions();
    }

    /** Measures the pill only when its text or the view size changed, never per animation frame. */
    private void measureChip() {
        if (lastWidth <= 0) return;
        chip.setMaxWidth(Math.round(lastWidth * 0.62f));
        chip.measure(MeasureSpec.makeMeasureSpec(lastWidth, MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(lastHeight, MeasureSpec.AT_MOST));
        chipWidth = chip.getMeasuredWidth();
        chipHeight = chip.getMeasuredHeight();
        if (chip.getWidth() != Math.round(chipWidth) || chip.getHeight() != Math.round(chipHeight)) {
            chip.layout(0, 0, Math.round(chipWidth), Math.round(chipHeight));
        }
        chipDirty = false;
    }

    /** Places the seven tiles and the pill from the current {@link #frac}: pure translation, no layout pass. */
    private void applyPositions() {
        if (lastWidth <= 0 || apps.isEmpty()) return;
        float centerX = lastWidth / 2f;
        for (int s = 0; s < SLOTS; s++) {
            Slot slot = slots[s];
            float d = (s - 3) - direction * frac;
            float ad = Math.abs(d);
            float sign = d == 0f ? 0f : Math.signum(d);
            float x = centerX + sign * distance(ad);
            float scale = 1f + (FOCUS_SCALE - 1f) * Math.max(0f, 1f - ad);
            slot.setX(x - tilePx / 2f);
            slot.setY(tileCenterY - tilePx / 2f);
            slot.setScaleX(scale);
            slot.setScaleY(scale);
            slot.setAlpha(ad > 2.35f ? Math.max(0f, 1f - (ad - 2.35f) / 0.65f) : 1f);
            // Visibility stays put: toggling it here called requestLayout() during layout (a second layout
            // pass every frame). A fully transparent tile costs almost nothing instead.
            slot.ring.setAlpha(Math.max(0f, 1f - ad));
        }
        if (chipWidth > 0f) {
            float chipX = Math.max(8 * density, Math.min(centerX - chipWidth / 2f,
                    lastWidth - chipWidth - 8 * density));
            chip.setX(chipX);
            chip.setY(bandTop - CHIP_GAP_DP * density - chipHeight);
        }
    }

    private float distance(float ad) {
        if (ad <= 1f) return ad * nearPx;
        return nearPx + (ad - 1f) * (farPx - nearPx);
    }

    private void bind(Slot slot, App app) {
        if (slot.bound == app) return;
        slot.bound = app;
        if (app == null) {
            slot.setVisibility(GONE);
            return;
        }
        if (app != null) slot.setVisibility(VISIBLE);
        if (firePkg.equals(app.pkg)) {
            if (fire == null) fire = new FireBannerDrawable();
            slot.icon.setScaleType(ImageView.ScaleType.FIT_XY);
            slot.icon.setPadding(0, 0, 0, 0);
            slot.icon.setImageDrawable(fire);
            slot.setBackground(null);
            if (fireRunning) fire.start();
        } else {
            slot.icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int pad = Math.round(TILE_DP * density * 0.21f);
            slot.icon.setPadding(pad, pad, pad, pad);
            slot.icon.setImageDrawable(app.icon);
            slot.setBackground(app.tileBackground != null ? app.tileBackground : plate(app));
        }
    }

    /** The tile fill for an app whose background is still being prepared. */
    private GradientDrawable plate(App app) {
        int[] colours = app.plate;
        if (colours == null) colours = AppAdapter.plateFor(app.icon);
        GradientDrawable fill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colours);
        fill.setCornerRadius(TILE_RADIUS_DP * density);
        return fill;
    }

    /** One tile on the ring: the app's mark on its plate, a white ring while it is the focused one. */
    private final class Slot extends FrameLayout {
        final ImageView icon;
        final View ring;
        App bound;

        Slot(Context context, int size) {
            super(context);
            setClipChildren(true);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), TILE_RADIUS_DP * density);
                }
            });
            // Each tile is a small cached texture: moving and scaling it during a turn is then pure
            // compositing on the TV's GPU instead of re-recording vector paths every frame.
            setLayerType(View.LAYER_TYPE_HARDWARE, null);
            icon = new ImageView(context);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            ring = new View(context);
            GradientDrawable stroke = new GradientDrawable();
            stroke.setCornerRadius(TILE_RADIUS_DP * density);
            stroke.setStroke(Math.max(2, Math.round(2f * density)), 0xFFFFFFFF);
            stroke.setColor(Color.TRANSPARENT);
            ring.setBackground(stroke);
            ring.setAlpha(0f);
            ring.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(ring, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }
}
