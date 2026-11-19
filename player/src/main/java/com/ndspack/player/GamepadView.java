package com.ndspack.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

/**
 * Camada transparente por cima do video: desenha o controle virtual e converte toques em
 * botoes do RetroPad ou em toque na tela inferior do DS.
 */
final class GamepadView extends View {

    interface Listener {
        void onMask(int mask);

        void onTouch(int x, int y, boolean down);

        void onMenu();

        void onScreenRect(Rect r);
    }

    static final int B = 1 << 0, Y = 1 << 1, SELECT = 1 << 2, START = 1 << 3, UP = 1 << 4, DOWN = 1 << 5,
            LEFT = 1 << 6, RIGHT = 1 << 7, A = 1 << 8, X = 1 << 9, L = 1 << 10, R = 1 << 11;

    private Listener listener;
    private int frameW = 256, frameH = 384;
    private int insetL, insetT, insetR, insetB;

    private final Rect screenRect = new Rect();
    private final RectF lRect = new RectF(), rRect = new RectF(), selRect = new RectF(), startRect = new RectF();
    private final RectF menuRect = new RectF();
    private float dpadX, dpadY, dpadR;
    private float faceX, faceY, faceR, faceR2;

    private int mask = 0;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

    GamepadView(Context c) {
        super(c);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(0xB0FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(0xD0FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        setOnApplyWindowInsetsListener(
                (v, insets) -> {
                    applyInsets(insets);
                    return insets;
                });
    }

    void setListener(Listener l) {
        listener = l;
    }

    void setVideoSize(int w, int h) {
        if (w > 0 && h > 0) {
            frameW = w;
            frameH = h;
            computeLayout();
            invalidate();
        }
    }

    Rect getScreenRect() {
        return screenRect;
    }

    private void applyInsets(WindowInsets insets) {
        int l = 0, t = 0, r = 0, b = 0;
        if (Build.VERSION.SDK_INT >= 28) {
            DisplayCutout dc = insets.getDisplayCutout();
            if (dc != null) {
                l = dc.getSafeInsetLeft();
                t = dc.getSafeInsetTop();
                r = dc.getSafeInsetRight();
                b = dc.getSafeInsetBottom();
            }
        }
        insetL = l;
        insetT = t;
        insetR = r;
        insetB = b;
        computeLayout();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        computeLayout();
    }

    private void computeLayout() {
        final int W = getWidth(), H = getHeight();
        if (W == 0 || H == 0) return;
        final float aspect = (float) frameW / (float) frameH;
        final float availH = H - insetT - insetB;
        final boolean portrait = H >= W;
        float sw, sh, sx, sy;
        if (portrait) {
            float availW = W - insetL - insetR;
            sh = Math.min(availW / aspect, availH * 0.68f);
            sw = sh * aspect;
            sx = insetL + (availW - sw) / 2f;
            sy = insetT;
        } else {
            sh = availH;
            sw = Math.min(sh * aspect, (W - insetL - insetR) * 0.55f);
            sh = sw / aspect;
            sx = (W - sw) / 2f;
            sy = insetT + (availH - sh) / 2f;
        }
        screenRect.set(Math.round(sx), Math.round(sy), Math.round(sx + sw), Math.round(sy + sh));

        float zx0, zx1, zy0, zy1;  // zona de controles (portrait: faixa inferior)
        if (portrait) {
            zx0 = insetL;
            zx1 = W - insetR;
            zy0 = screenRect.bottom;
            zy1 = H - insetB;
            float zh = zy1 - zy0, zw = zx1 - zx0;
            float shoulderW = zw * 0.28f, shoulderH = Math.min(zh * 0.15f, zw * 0.12f);
            lRect.set(zx0 + zw * 0.04f, zy0 + zh * 0.03f, zx0 + zw * 0.04f + shoulderW, zy0 + zh * 0.03f + shoulderH);
            rRect.set(zx1 - zw * 0.04f - shoulderW, lRect.top, zx1 - zw * 0.04f, lRect.bottom);
            float cy = zy0 + zh * 0.56f;
            dpadR = Math.min(zw * 0.20f, zh * 0.30f);
            dpadX = zx0 + zw * 0.25f;
            dpadY = cy;
            faceR = Math.min(zw * 0.075f, zh * 0.115f);
            faceR2 = faceR * 2.1f;
            faceX = zx1 - zw * 0.26f;
            faceY = cy;
            float pillW = zw * 0.15f, pillH = Math.min(zh * 0.07f, zw * 0.05f);
            float py = zy1 - zh * 0.08f - pillH;
            selRect.set(zx0 + zw * 0.5f - pillW - zw * 0.03f, py, zx0 + zw * 0.5f - zw * 0.03f, py + pillH);
            startRect.set(zx0 + zw * 0.5f + zw * 0.03f, py, zx0 + zw * 0.5f + zw * 0.03f + pillW, py + pillH);
            float mr = Math.min(zw * 0.045f, zh * 0.07f);
            menuRect.set(zx0 + zw * 0.5f - mr, zy0 + zh * 0.06f, zx0 + zw * 0.5f + mr, zy0 + zh * 0.06f + 2 * mr);
        } else {
            float lw = screenRect.left - insetL, rw = (W - insetR) - screenRect.right;
            float lx0 = insetL, rx0 = screenRect.right;
            float top = insetT, ah = availH;
            float shoulderH = Math.min(ah * 0.14f, lw * 0.3f);
            lRect.set(lx0 + lw * 0.06f, top + ah * 0.04f, lx0 + lw * 0.52f, top + ah * 0.04f + shoulderH);
            rRect.set(rx0 + rw * 0.48f, lRect.top, rx0 + rw * 0.94f, lRect.bottom);
            dpadR = Math.min(lw * 0.36f, ah * 0.22f);
            dpadX = lx0 + lw * 0.5f;
            dpadY = top + ah * 0.55f;
            faceR = Math.min(rw * 0.12f, ah * 0.10f);
            faceR2 = faceR * 2.1f;
            faceX = rx0 + rw * 0.5f;
            faceY = top + ah * 0.55f;
            float pillW = Math.min(lw * 0.40f, ah * 0.26f), pillH = Math.min(ah * 0.07f, pillW * 0.35f);
            float py = top + ah * 0.90f - pillH;
            selRect.set(lx0 + lw * 0.5f - pillW / 2f, py, lx0 + lw * 0.5f + pillW / 2f, py + pillH);
            startRect.set(rx0 + rw * 0.5f - pillW / 2f, py, rx0 + rw * 0.5f + pillW / 2f, py + pillH);
            float mr = Math.min(lw * 0.09f, ah * 0.06f);
            menuRect.set(lx0 + lw * 0.74f, lRect.top, lx0 + lw * 0.74f + 2 * mr, lRect.top + 2 * mr);
        }
        if (listener != null) listener.onScreenRect(screenRect);
    }

    // ------------------------------------------------------------------ desenho
    @Override
    protected void onDraw(Canvas c) {
        stroke.setStrokeWidth(Math.max(2f, getWidth() * 0.004f));
        // D-pad
        drawDpad(c);
        // botoes de acao (layout Nintendo: A direita, B baixo, X cima, Y esquerda)
        drawRound(c, faceX + faceR2, faceY, faceR, "A", (mask & A) != 0);
        drawRound(c, faceX, faceY + faceR2, faceR, "B", (mask & B) != 0);
        drawRound(c, faceX, faceY - faceR2, faceR, "X", (mask & X) != 0);
        drawRound(c, faceX - faceR2, faceY, faceR, "Y", (mask & Y) != 0);
        drawPill(c, lRect, "L", (mask & L) != 0);
        drawPill(c, rRect, "R", (mask & R) != 0);
        drawPill(c, selRect, "SELECT", (mask & SELECT) != 0);
        drawPill(c, startRect, "START", (mask & START) != 0);
        // menu
        float cx = menuRect.centerX(), cy = menuRect.centerY(), r = menuRect.width() / 2f;
        c.drawCircle(cx, cy, r, stroke);
        for (int i = -1; i <= 1; i++) {
            c.drawLine(cx - r * 0.5f, cy + i * r * 0.32f, cx + r * 0.5f, cy + i * r * 0.32f, stroke);
        }
    }

    private void drawDpad(Canvas c) {
        float a = dpadR * 0.36f;  // meia largura dos bracos
        fill.setColor(0x22FFFFFF);
        c.drawCircle(dpadX, dpadY, dpadR, fill);
        c.drawCircle(dpadX, dpadY, dpadR, stroke);
        fill.setColor(0x66FFFFFF);
        if ((mask & UP) != 0) c.drawRect(dpadX - a, dpadY - dpadR, dpadX + a, dpadY - a * 0.2f, fill);
        if ((mask & DOWN) != 0) c.drawRect(dpadX - a, dpadY + a * 0.2f, dpadX + a, dpadY + dpadR, fill);
        if ((mask & LEFT) != 0) c.drawRect(dpadX - dpadR, dpadY - a, dpadX - a * 0.2f, dpadY + a, fill);
        if ((mask & RIGHT) != 0) c.drawRect(dpadX + a * 0.2f, dpadY - a, dpadX + dpadR, dpadY + a, fill);
        // cruz
        c.drawLine(dpadX - dpadR * 0.7f, dpadY, dpadX + dpadR * 0.7f, dpadY, stroke);
        c.drawLine(dpadX, dpadY - dpadR * 0.7f, dpadX, dpadY + dpadR * 0.7f, stroke);
    }

    private void drawRound(Canvas c, float x, float y, float r, String label, boolean pressed) {
        fill.setColor(pressed ? 0x77FFFFFF : 0x22FFFFFF);
        c.drawCircle(x, y, r, fill);
        c.drawCircle(x, y, r, stroke);
        text.setTextSize(r * 0.9f);
        c.drawText(label, x, y + r * 0.32f, text);
    }

    private void drawPill(Canvas c, RectF rect, String label, boolean pressed) {
        fill.setColor(pressed ? 0x77FFFFFF : 0x22FFFFFF);
        float rad = rect.height() / 2f;
        c.drawRoundRect(rect, rad, rad, fill);
        c.drawRoundRect(rect, rad, rad, stroke);
        text.setTextSize(rect.height() * 0.5f);
        c.drawText(label, rect.centerX(), rect.centerY() + rect.height() * 0.18f, text);
    }

    // ------------------------------------------------------------------ toque
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            update(0, false, 0, 0);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int idx = e.getActionIndex();
            if (menuRect.contains(e.getX(idx), e.getY(idx)) || insideExpanded(menuRect, e.getX(idx), e.getY(idx))) {
                if (listener != null) listener.onMenu();
                return true;
            }
        }
        int newMask = 0;
        boolean touching = false;
        int tx = 0, ty = 0;
        for (int i = 0; i < e.getPointerCount(); i++) {
            if (action == MotionEvent.ACTION_UP) break;
            if (action == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex()) continue;
            final float x = e.getX(i), y = e.getY(i);
            if (screenRect.contains((int) x, (int) y)) {
                if (!touching) {
                    touching = true;
                    float nx = (x - screenRect.left) / Math.max(1, screenRect.width());
                    float ny = (y - screenRect.top) / Math.max(1, screenRect.height());
                    tx = Math.round((nx * 2f - 1f) * 32767f);
                    ty = Math.round((ny * 2f - 1f) * 32767f);
                }
                continue;
            }
            newMask |= hit(x, y);
        }
        update(newMask, touching, tx, ty);
        return true;
    }

    private static boolean insideExpanded(RectF r, float x, float y) {
        float m = r.width() * 0.4f;
        return x >= r.left - m && x <= r.right + m && y >= r.top - m && y <= r.bottom + m;
    }

    private int hit(float x, float y) {
        int m = 0;
        float dx = x - dpadX, dy = y - dpadY;
        if (dx * dx + dy * dy <= dpadR * dpadR * 1.9f && dx * dx + dy * dy > dpadR * dpadR * 0.02f) {
            float t = dpadR * 0.30f;
            if (dx < -t) m |= LEFT;
            if (dx > t) m |= RIGHT;
            if (dy < -t) m |= UP;
            if (dy > t) m |= DOWN;
        }
        m |= hitRound(x, y, faceX + faceR2, faceY, A);
        m |= hitRound(x, y, faceX, faceY + faceR2, B);
        m |= hitRound(x, y, faceX, faceY - faceR2, X);
        m |= hitRound(x, y, faceX - faceR2, faceY, Y);
        if (insideExpanded(lRect, x, y)) m |= L;
        if (insideExpanded(rRect, x, y)) m |= R;
        if (insideExpanded(selRect, x, y)) m |= SELECT;
        if (insideExpanded(startRect, x, y)) m |= START;
        return m;
    }

    private int hitRound(float x, float y, float cx, float cy, int bit) {
        float dx = x - cx, dy = y - cy;
        float rr = faceR * 1.45f;
        return (dx * dx + dy * dy <= rr * rr) ? bit : 0;
    }

    private void update(int newMask, boolean touching, int tx, int ty) {
        if ((newMask & ~mask) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (newMask != mask) {
            mask = newMask;
            invalidate();
            if (listener != null) listener.onMask(mask);
        }
        if (listener != null) listener.onTouch(tx, ty, touching);
    }
}
