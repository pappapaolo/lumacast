package app.lumacast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Home screen of the app: shows the receiver name, how to connect, and one-time setup. */
public class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status, name, steps;
    private Button allowButton, renameButton;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        startForegroundService(new Intent(this, AirPlayService.class));
        float d = getResources().getDisplayMetrics().density;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding((int) (96 * d), (int) (48 * d), (int) (96 * d), (int) (48 * d));

        ImageView glyph = new ImageView(this);
        glyph.setImageResource(R.drawable.glyph);
        glyph.setAlpha(0.9f);
        root.addView(glyph, new LinearLayout.LayoutParams((int) (160 * d), (int) (160 * d)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) (64 * d), 0, 0, 0);
        root.addView(col, new LinearLayout.LayoutParams(0, -2, 1));

        status = text(16, 0xB3FFFFFF, false);
        name = text(44, Color.WHITE, true);
        steps = text(18, 0xCCFFFFFF, false);
        steps.setLineSpacing(8 * d, 1f);
        col.addView(status);
        col.addView(name);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = (int) (20 * d);
        col.addView(steps, sp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = (int) (32 * d);
        col.addView(buttons, bp);
        allowButton = button("Allow automatic pop-up", v -> openOverlaySettings());
        renameButton = button("Rename", v -> rename());
        buttons.addView(allowButton);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
        rp.leftMargin = (int) (16 * d);
        buttons.addView(renameButton, rp);

        setContentView(root);
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        float d = getResources().getDisplayMetrics().density;
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(getColorStateList(R.color.button_text));
        b.setBackgroundResource(R.drawable.button);
        b.setPadding((int) (28 * d), (int) (12 * d), (int) (28 * d), (int) (12 * d));
        b.setOnClickListener(l);
        return b;
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
        (Settings.canDrawOverlays(this) ? renameButton : allowButton).requestFocus();
    }

    @Override protected void onPause() { handler.removeCallbacksAndMessages(null); super.onPause(); }

    private void refresh() {
        boolean overlay = Settings.canDrawOverlays(this);
        status.setText(AirPlayService.status.equals("Ready") ? "Ready to mirror" : AirPlayService.status);
        name.setText(AirPlayService.deviceName);
        String s = "On your iPhone, iPad or Mac open Control Center \u2192 Screen Mirroring and pick \u201c" + AirPlayService.deviceName + "\u201d.\n"
                + "For music, tap the AirPlay icon in any audio app.\n\n";
        s += overlay ? "Mirroring opens by itself. Lumacast runs in the background and starts with your TV."
                : "One-time step: press \u201cAllow automatic pop-up\u201d, select Lumacast, press OK, then Back. Mirroring will then open by itself.";
        steps.setText(s);
        allowButton.setVisibility(overlay ? View.GONE : View.VISIBLE);
        handler.postDelayed(this::refresh, 1000);
    }

    private void openOverlaySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        input.setText(AirPlayService.deviceName);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("Name shown on your iPhone and Mac")
                .setView(input)
                .setPositiveButton("Save", (dlg, w) -> {
                    String n = input.getText().toString().trim();
                    if (!n.isEmpty()) AirPlayService.rename(this, n);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
