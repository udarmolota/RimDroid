package com.rimdroid;

import android.graphics.Insets;
import android.os.Bundle;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.rimdroid.input.GamepadMapping;

/**
 * Step-by-step gamepad button remapper (ported from Zomdroid's GamepadMapperFragment, adapted to
 * RimDroid's Activity-based editors). The user presses the physical button they want to act as each
 * LOGICAL button (A/B/X/Y/…); {@link GamepadMapping} stores physical→logical so a controller with
 * swapped/“inverted” buttons works. The logical→action layer (A=left click, …) lives in
 * GamepadHandler and is unchanged. Triggers/sticks are not remapped here (analog, handled directly).
 */
public class GamepadMapperActivity extends AppCompatActivity {

    private int currentStep = 0;
    private boolean mappingActive = false;
    private int[] mapping;              // logical index → physical keycode (full length COUNT)
    private String[] labels;           // per wizard step

    private TextView stepLabel;
    private Button startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_gamepad_mapper);

        // Edge-to-edge (Android 15+ draws under the system bars): pad the root by the status-bar
        // (top) and navigation-bar (bottom) insets so the toolbar/back isn't under the notification
        // bar and the buttons aren't eaten by the nav bar. Same approach as LauncherActivity.
        findViewById(R.id.mapper_root).setOnApplyWindowInsetsListener((v, wi) -> {
            Insets bars = wi.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return wi;
        });

        MaterialToolbar toolbar = findViewById(R.id.mapper_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());   // top-left back arrow → return to Settings

        stepLabel = findViewById(R.id.gamepad_mapper_step_label);
        startButton = findViewById(R.id.gamepad_mapper_start_button);
        Button resetButton = findViewById(R.id.gamepad_mapper_reset_button);

        labels = getResources().getStringArray(R.array.gamepad_mapper_labels);

        startButton.setOnClickListener(v -> beginMapping());
        resetButton.setOnClickListener(v -> {
            GamepadMapping.reset(this);
            mappingActive = false;
            startButton.setVisibility(Button.VISIBLE);
            stepLabel.setText(R.string.gamepad_mapper_instructions);
            Toast.makeText(this, R.string.gamepad_mapper_reset_done, Toast.LENGTH_SHORT).show();
        });

        stepLabel.setText(R.string.gamepad_mapper_instructions);
    }

    private void beginMapping() {
        mapping = GamepadMapping.get().clone();   // keep GUIDE etc.; overwrite wizard slots as we go
        currentStep = 0;
        mappingActive = true;
        startButton.setVisibility(Button.GONE);
        showStep();
    }

    private void showStep() {
        if (currentStep < GamepadMapping.WIZARD_ORDER.length) {
            stepLabel.setText(getString(R.string.gamepad_mapper_step, labels[currentStep]));
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (mappingActive && isFromGamepad(event.getSource())
                && event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            handlePress(event.getKeyCode());
            return true;   // consume so it isn't treated as navigation/back
        }
        return super.dispatchKeyEvent(event);
    }

    private void handlePress(int keyCode) {
        // reject a keycode already assigned to an earlier step this run
        for (int i = 0; i < currentStep; i++) {
            if (mapping[GamepadMapping.WIZARD_ORDER[i]] == keyCode) {
                Toast.makeText(this, R.string.gamepad_mapper_dup, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        mapping[GamepadMapping.WIZARD_ORDER[currentStep]] = keyCode;
        currentStep++;
        if (currentStep >= GamepadMapping.WIZARD_ORDER.length) {
            GamepadMapping.save(this, mapping);
            mappingActive = false;
            startButton.setVisibility(Button.VISIBLE);
            stepLabel.setText(R.string.gamepad_mapper_done);
            Toast.makeText(this, R.string.gamepad_mapper_done, Toast.LENGTH_SHORT).show();
        } else {
            showStep();
        }
    }

    private static boolean isFromGamepad(int source) {
        return (source & InputDevice.SOURCE_GAMEPAD)  == InputDevice.SOURCE_GAMEPAD
            || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
            || (source & InputDevice.SOURCE_DPAD)     == InputDevice.SOURCE_DPAD;
    }
}
