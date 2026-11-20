package com.ndspack.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity implements SurfaceHolder.Callback, GamepadView.Listener {

    private FrameLayout root;
    private SurfaceView surfaceView;
    private GamepadView pad;
    private TextView status;

    private volatile boolean ready = false;
    private boolean surfaceOk = false;
    private boolean started = false;
    private boolean menuOpen = false;
    private boolean fastForward = false;

    private int virtualMask = 0;
    private int keyMask = 0;
    private int stickMask = 0;

    private File stateDir;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wlp);
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        surfaceView.getHolder().setFormat(PixelFormat.RGBX_8888);
        surfaceView.getHolder().addCallback(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(1, 1));

        pad = new GamepadView(this);
        pad.setListener(this);
        root.addView(pad, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(16f);
        status.setGravity(Gravity.CENTER);
        status.setText("Preparando o jogo…");
        root.addView(status, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        setContentView(root);
        stateDir = new File(getFilesDir(), "states");
        stateDir.mkdirs();

        new Thread(this::prepareAndInit, "prepare").start();
    }

    // ------------------------------------------------------------------ preparo
    private void prepareAndInit() {
        try {
            File rom = new File(getFilesDir(), "game.nds");
            File marker = new File(getFilesDir(), "game.nds.len");
            long len = assetLength("game.nds");
            if (!rom.exists() || len < 0 || !marker.exists() || !readText(marker).equals(String.valueOf(len))) {
                runOnUiThread(() -> status.setText("Preparando o jogo pela primeira vez…\nIsso pode levar alguns segundos."));
                File tmp = new File(getFilesDir(), "game.nds.tmp");
                try (InputStream in = getAssets().open("game.nds");
                        OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    long total = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                    }
                    if (len < 0) len = total;
                }
                if (!tmp.renameTo(rom)) throw new IOException("não consegui gravar a ROM");
                try (OutputStream o = new FileOutputStream(marker)) {
                    o.write(String.valueOf(len).getBytes("UTF-8"));
                }
            }

            File core = new File(getApplicationInfo().nativeLibraryDir, "libretro_core.so");
            if (!core.exists()) throw new IOException("Núcleo do emulador ausente no APK.");

            File saves = new File(getFilesDir(), "saves");
            saves.mkdirs();
            File sys = getExternalFilesDir("system");
            if (sys == null) sys = new File(getFilesDir(), "system");
            sys.mkdirs();
            File ext = getExternalFilesDir(null);
            File options = new File(ext != null ? ext : getFilesDir(), "core_options.txt");
            if (!options.exists()) {
                String tpl = "# Opcoes do nucleo: uma por linha, chave = valor.\n"
                        + "# As chaves que o nucleo pede aparecem em log.txt (linhas 'opcao pedida').\n";
                try (OutputStream o = new FileOutputStream(options)) {
                    o.write(tpl.getBytes("UTF-8"));
                } catch (IOException ignored) {
                    // sem arquivo de opcoes: valem os padroes do nucleo
                }
            }

            boolean ok = Native.init(core.getAbsolutePath(), rom.getAbsolutePath(), sys.getAbsolutePath(),
                    saves.getAbsolutePath(), options.getAbsolutePath());
            if (!ok) throw new IOException("O núcleo não conseguiu abrir a ROM (veja o logcat: NdsPlayer).");
            final int[] size = Native.getVideoSize();
            runOnUiThread(() -> {
                ready = true;
                status.setVisibility(View.GONE);
                pad.setVideoSize(size[0], size[1]);
                maybeStart();
            });
        } catch (Throwable t) {
            final String msg = t.getMessage() == null ? t.toString() : t.getMessage();
            runOnUiThread(() -> status.setText("Erro: " + msg));
        }
    }

    private long assetLength(String name) {
        try (AssetFileDescriptor fd = getAssets().openFd(name)) {
            return fd.getLength();
        } catch (IOException e) {
            return -1;  // asset comprimido: sem tamanho conhecido
        }
    }

    private static String readText(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[64];
            int n = in.read(b);
            return n > 0 ? new String(b, 0, n, "UTF-8").trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    // ------------------------------------------------------------------ emulacao
    private void maybeStart() {
        if (!ready) return;
        if (surfaceOk) Native.setSurface(surfaceView.getHolder().getSurface());
        if (!started) {
            started = true;
            Native.start();
        }
        if (!menuOpen) Native.setPaused(false);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        surfaceOk = true;
        if (ready) Native.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        surfaceOk = false;
        if (ready) Native.setSurface(null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        maybeStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ready) {
            Native.setInput(0);
            Native.setTouch(0, 0, false);
            Native.setPaused(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ready) {
            ready = false;
            Native.setSurface(null);
            Native.stop();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ------------------------------------------------------------------ GamepadView.Listener
    @Override
    public void onMask(int mask) {
        virtualMask = mask;
        pushMask();
    }

    @Override
    public void onTouch(int x, int y, boolean down) {
        if (ready) Native.setTouch(x, y, down);
    }

    @Override
    public void onScreenRect(Rect r) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.leftMargin != r.left || lp.topMargin != r.top || lp.width != r.width() || lp.height != r.height()) {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = r.left;
            lp.topMargin = r.top;
            lp.width = Math.max(1, r.width());
            lp.height = Math.max(1, r.height());
            surfaceView.post(() -> surfaceView.setLayoutParams(lp));
        }
    }

    @Override
    public void onMenu() {
        openMenu();
    }

    private void pushMask() {
        if (ready) Native.setInput(virtualMask | keyMask | stickMask);
    }

    // ------------------------------------------------------------------ controle fisico
    private static int mapKey(int keyCode) {
        switch (keyCode) {
            // posicao fisica: Android A(sul)->B, B(leste)->A, X(oeste)->Y, Y(norte)->X
            case KeyEvent.KEYCODE_BUTTON_A: return GamepadView.B;
            case KeyEvent.KEYCODE_BUTTON_B: return GamepadView.A;
            case KeyEvent.KEYCODE_BUTTON_X: return GamepadView.Y;
            case KeyEvent.KEYCODE_BUTTON_Y: return GamepadView.X;
            case KeyEvent.KEYCODE_BUTTON_L1: return GamepadView.L;
            case KeyEvent.KEYCODE_BUTTON_R1: return GamepadView.R;
            case KeyEvent.KEYCODE_BUTTON_START: return GamepadView.START;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return GamepadView.SELECT;
            case KeyEvent.KEYCODE_DPAD_UP: return GamepadView.UP;
            case KeyEvent.KEYCODE_DPAD_DOWN: return GamepadView.DOWN;
            case KeyEvent.KEYCODE_DPAD_LEFT: return GamepadView.LEFT;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return GamepadView.RIGHT;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_MODE || code == KeyEvent.KEYCODE_BUTTON_THUMBL) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) openMenu();
            return true;
        }
        int bit = mapKey(code);
        if (bit != 0 && (e.getSource() & (InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_DPAD | InputDevice.SOURCE_JOYSTICK)) != 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) keyMask |= bit;
            else if (e.getAction() == KeyEvent.ACTION_UP) keyMask &= ~bit;
            pushMask();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X), hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float lx = e.getAxisValue(MotionEvent.AXIS_X), ly = e.getAxisValue(MotionEvent.AXIS_Y);
            float x = Math.abs(hx) > 0.5f ? hx : lx, y = Math.abs(hy) > 0.5f ? hy : ly;
            int m = 0;
            if (x < -0.5f) m |= GamepadView.LEFT;
            if (x > 0.5f) m |= GamepadView.RIGHT;
            if (y < -0.5f) m |= GamepadView.UP;
            if (y > 0.5f) m |= GamepadView.DOWN;
            // gatilhos analogicos como L/R
            if (e.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f) m |= GamepadView.L;
            if (e.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f) m |= GamepadView.R;
            if (m != stickMask) {
                stickMask = m;
                pushMask();
            }
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    // ------------------------------------------------------------------ menu
    @Override
    public void onBackPressed() {
        openMenu();
    }

    private void openMenu() {
        if (menuOpen || !ready) return;
        menuOpen = true;
        Native.setPaused(true);
        Native.setInput(0);
        Native.setTouch(0, 0, false);
        String[] items = {
            "Continuar",
            "Salvar estado",
            "Carregar estado",
            "Reiniciar o jogo",
            fastForward ? "Avanço rápido: ligado" : "Avanço rápido: desligado",
            "Sair do jogo"
        };
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 1: pickSlot(true); return;
                        case 2: pickSlot(false); return;
                        case 3:
                            Native.reset();
                            break;
                        case 4:
                            fastForward = !fastForward;
                            Native.setFastForward(fastForward);
                            break;
                        case 5:
                            finish();
                            return;
                        default:
                            break;
                    }
                    closeMenu();
                })
                .setOnCancelListener(dialog -> closeMenu())
                .create();
        d.show();
    }

    private void closeMenu() {
        menuOpen = false;
        hideSystemBars();
        if (ready) Native.setPaused(false);
    }

    private void pickSlot(boolean save) {
        final int slots = 3;
        String[] labels = new String[slots];
        DateFormat df = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (int i = 0; i < slots; i++) {
            File f = stateFile(i + 1);
            labels[i] = "Slot " + (i + 1) + (f.exists() ? " — " + df.format(new Date(f.lastModified())) : " — vazio");
        }
        new AlertDialog.Builder(this)
                .setTitle(save ? "Salvar estado" : "Carregar estado")
                .setItems(labels, (dialog, which) -> {
                    File f = stateFile(which + 1);
                    if (save) {
                        boolean ok = Native.saveState(f.getAbsolutePath());
                        Toast.makeText(this, ok ? "Estado salvo" : "Falha ao salvar", Toast.LENGTH_SHORT).show();
                    } else if (!f.exists()) {
                        Toast.makeText(this, "Slot vazio", Toast.LENGTH_SHORT).show();
                    } else {
                        boolean ok = Native.loadState(f.getAbsolutePath());
                        Toast.makeText(this, ok ? "Estado carregado" : "Falha ao carregar", Toast.LENGTH_SHORT).show();
                    }
                    closeMenu();
                })
                .setOnCancelListener(dialog -> closeMenu())
                .show();
    }

    private File stateFile(int slot) {
        return new File(stateDir, "slot" + slot + ".state");
    }
}
