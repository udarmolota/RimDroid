package com.rimdroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import com.rimdroid.input.Binding;
import com.rimdroid.input.ButtonElement;
import com.rimdroid.input.ControlElement;
import com.rimdroid.input.ControlElementDescription;
import com.rimdroid.input.InputControlsView;
import com.rimdroid.input.MouseStickElement;
import com.rimdroid.input.WasdStickElement;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * On-screen controls layout editor (Zomdroid-style). Tap an element to select it, drag
 * to move, use the side panel to resize (scale), set opacity, change bindings, and add /
 * delete elements. Changes are saved to prefs on Done / pause.
 */
public class ControlsEditorActivity extends Activity implements InputControlsView.EditorListener {

    private InputControlsView controls;
    private ScrollView panel;
    private LinearLayout panelContainer;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        // Immersive fullscreen (hide status + nav bars) so the editing area matches the
        // game's fullscreen surface — otherwise element positions would be off on devices
        // with a visible navigation bar.
        getWindow().setDecorFitsSystemWindows(false);
        setContentView(R.layout.activity_controls_editor);
        hideSystemBars();   // after setContentView — the decor view / insets controller now exist
        density = getResources().getDisplayMetrics().density;

        float renderScale = 0.72f;
        LauncherPreferences lp = LauncherPreferences.getSingleton();
        if (lp != null) {
            // Match the game's effective scale (stored value raised to the per-device
            // floor) so edited element positions line up with the running game.
            android.graphics.Rect b = getWindowManager().getCurrentWindowMetrics().getBounds();
            int sLong  = Math.max(b.width(), b.height());
            int sShort = Math.min(b.width(), b.height());
            renderScale = lp.getEffectiveRenderScale(sLong, sShort);
        }

        FrameLayout host = findViewById(R.id.controls_host);
        controls = new InputControlsView(this, renderScale);
        host.addView(controls, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        controls.setEditMode(true, this);

        panel = findViewById(R.id.panel);
        panelContainer = findViewById(R.id.panel_container);

        ((Button) findViewById(R.id.btn_add)).setOnClickListener(v -> showAddDialog());
        ((Button) findViewById(R.id.btn_reset)).setOnClickListener(v -> showResetDialog());
        ((Button) findViewById(R.id.btn_done)).setOnClickListener(v -> { controls.saveToPrefs(); finish(); });
    }

    private void hideSystemBars() {
        android.view.WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(android.view.WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    @Override protected void onPause() { super.onPause(); if (controls != null) controls.saveToPrefs(); }

    // ===== EditorListener =====
    @Override public void onElementSelected(ControlElement el) {
        if (el == null) { panel.setVisibility(View.GONE); return; }
        buildPanel(el);
        panel.setVisibility(View.VISIBLE);
    }

    // ===== panel construction =====

    private void buildPanel(ControlElement el) {
        panelContainer.removeAllViews();
        addTitle(el.editorLabel());

        addSlider("Size", 50, 200, Math.round(el.getScale() * 100), "%",
                v -> { el.setScale(v / 100f); controls.invalidate(); });
        addSlider("Opacity", 5, 100, Math.round(el.getAlpha() / 2.55f), "%",
                v -> { el.setAlpha(Math.round(v * 2.55f)); controls.invalidate(); });
        // "Opacity → all": copy THIS element's opacity to every element at once
        // (Zomdroid-style), instead of adjusting each one separately.
        Button toAll = new Button(this);
        toAll.setText("Opacity → all elements");
        toAll.setOnClickListener(v -> {
            controls.applyAlphaToAll(el.getAlpha());
            android.widget.Toast.makeText(this, "Opacity applied to all elements",
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        panelContainer.addView(toAll, rowParams());

        if (el instanceof ButtonElement) {
            ButtonElement b = (ButtonElement) el;
            addBindingSpinner("Action", b.getBinding(), b::setBinding);
            addText("Label", b.getText(), b::setText);
            addCheckbox("Round (circle)", b.getShape() == ButtonElement.Shape.CIRCLE, round -> {
                b.setShape(round ? ButtonElement.Shape.CIRCLE : ButtonElement.Shape.RECT);
                controls.invalidate();
            });
            addCheckbox("Toggle (latch on/off)", b.isToggle(), b::setToggle);
        } else if (el instanceof MouseStickElement) {
            MouseStickElement m = (MouseStickElement) el;
            addSlider("Sensitivity", 25, 800, Math.round(m.getSensitivity() * 100), "%",
                    v -> m.setSensitivity(v / 100f));
        } else if (el instanceof WasdStickElement) {
            WasdStickElement w = (WasdStickElement) el;
            final String[] names = {"Up", "Right", "Down", "Left"};
            for (int i = 0; i < 4; i++) {
                final int idx = i;
                addBindingSpinner(names[i], w.getDir(i), bnd -> w.setDir(idx, bnd));
            }
        }

        Button del = new Button(this);
        del.setText(R.string.editor_delete);
        del.setOnClickListener(v -> { controls.removeSelected(); panel.setVisibility(View.GONE); });
        LinearLayout.LayoutParams lp = rowParams();
        lp.topMargin = (int) (16 * density);
        panelContainer.addView(del, lp);
    }

    private void addTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(18);
        t.setPadding(0, 0, 0, (int) (8 * density));
        panelContainer.addView(t, rowParams());
    }

    private void addSlider(String label, int min, int max, int value, String unit, IntConsumer onChange) {
        final TextView lbl = new TextView(this);
        lbl.setTextColor(0xFFDDE3EA);
        lbl.setText(label + ": " + value + unit);
        panelContainer.addView(lbl, rowParams());

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, value - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = progress + min;
                lbl.setText(label + ": " + v + unit);
                onChange.accept(v);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = (int) (10 * density);
        panelContainer.addView(sb, lp);
    }

    private void addBindingSpinner(String label, Binding selected, Consumer<Binding> onSel) {
        TextView lbl = new TextView(this);
        lbl.setTextColor(0xFFDDE3EA);
        lbl.setText(label);
        panelContainer.addView(lbl, rowParams());

        Spinner sp = new Spinner(this);
        final Binding[] values = Binding.values();
        ArrayAdapter<Binding> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(ad);
        for (int i = 0; i < values.length; i++) if (values[i] == selected) { sp.setSelection(i); break; }
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { onSel.accept(values[pos]); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = (int) (10 * density);
        panelContainer.addView(sp, lp);
    }

    private void addText(String label, String value, Consumer<String> onChange) {
        TextView lbl = new TextView(this);
        lbl.setTextColor(0xFFDDE3EA);
        lbl.setText(label);
        panelContainer.addView(lbl, rowParams());

        EditText et = new EditText(this);
        et.setText(value);
        et.setTextColor(0xFFFFFFFF);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { onChange.accept(s.toString()); controls.invalidate(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = (int) (10 * density);
        panelContainer.addView(et, lp);
    }

    private void addCheckbox(String label, boolean checked, Consumer<Boolean> onChange) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(0xFFDDE3EA);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener((b, isChecked) -> onChange.accept(isChecked));
        LinearLayout.LayoutParams lp = rowParams();
        lp.bottomMargin = (int) (10 * density);
        panelContainer.addView(cb, lp);
    }

    private LinearLayout.LayoutParams rowParams() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    // ===== toolbar actions =====

    private void showAddDialog() {
        final String[] items = { "Button", "Mouse-stick (cursor)", "Camera-stick (keys)" };
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_add)
                .setItems(items, (d, which) -> {
                    ControlElementDescription desc;
                    switch (which) {
                        case 1:  desc = ControlElementDescription.mouseStick(0.5f, 0.5f); break;
                        case 2:  desc = ControlElementDescription.wasdStick(
                                Binding.KEY_UP, Binding.KEY_RIGHT, Binding.KEY_DOWN, Binding.KEY_LEFT, 0.5f, 0.5f); break;
                        default: desc = ControlElementDescription.button("BTN", Binding.MOUSE_LEFT, "CIRCLE", 0.5f, 0.5f); break;
                    }
                    controls.addElement(desc);
                })
                .show();
    }

    private void showResetDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.editor_reset)
                .setMessage(R.string.editor_reset_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    controls.loadDefault();
                    panel.setVisibility(View.GONE);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
