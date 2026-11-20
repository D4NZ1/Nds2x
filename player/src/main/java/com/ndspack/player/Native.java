package com.ndspack.player;

import android.view.Surface;

/** Ponte JNI com o frontend libretro (player.cpp). */
final class Native {
    static {
        System.loadLibrary("player");
    }

    private Native() {}

    static native boolean init(String corePath, String rom, String sysDir, String saveDir, String optionsFile);

    static native int[] getVideoSize();

    /** Log do frontend e do nucleo guardado em memoria. */
    static native String getLog();

    static native void setSurface(Surface surface);

    static native void start();

    static native void setPaused(boolean paused);

    static native void stop();

    /** Bits no formato RetroPad: B=0 Y=1 SELECT=2 START=3 UP=4 DOWN=5 LEFT=6 RIGHT=7 A=8 X=9 L=10 R=11. */
    static native void setInput(int mask);

    /** x e y normalizados de -32767 a 32767 sobre o quadro inteiro. */
    static native void setTouch(int x, int y, boolean down);

    static native void setFastForward(boolean ff);

    static native void reset();

    static native boolean saveState(String path);

    static native boolean loadState(String path);
}
