package app.lumacast;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Fullscreen black screen with the mirrored video, aspect-fit. */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback {
    static volatile MirrorActivity current;
    private FrameLayout root;
    private SurfaceView surfaceView;
    private int videoW = 16, videoH = 9;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        current = this;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, bo, ol, ot, or, ob) -> fit());
        setContentView(root);
        surfaceView.getHolder().addCallback(this);
        AirPlayService.video.sizeListener = (w, h) -> runOnUiThread(() -> { videoW = w; videoH = h; fit(); });
    }

    private void fit() {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0 || videoW == 0 || videoH == 0) return;
        int w = rw, h = (int) ((long) rw * videoH / videoW);
        if (h > rh) { h = rh; w = (int) ((long) rh * videoW / videoH); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surfaceView.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h;
            surfaceView.setLayoutParams(lp);
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) { AirPlayService.video.setSurface(h.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { AirPlayService.video.setSurface(null); }

    @Override public void onBackPressed() {
        AirPlayService.dismissedByUser(this);
        finish();
    }

    @Override protected void onDestroy() {
        if (current == this) current = null;
        AirPlayService.video.sizeListener = null;
        super.onDestroy();
    }
}
