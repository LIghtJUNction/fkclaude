package io.github.lightjunction.fkclaude;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MainActivity extends Activity {
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private Switch enabled;
    private record Row(Schema.Field field, Spinner mode, EditText input) {}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Profile profile;
        try {
            if (!ProfileStore.exists(this)) ProfileStore.save(this, Profile.defaults());
            profile = ProfileStore.load(this);
        } catch (RuntimeException e) {
            profile = new Profile(Map.of("enabled", "false"));
            toast(R.string.load_error);
        }
        if (state != null && state.containsKey("draft")) {
            try { profile = Profile.parse(state.getString("draft")); }
            catch (IllegalArgumentException ignored) { /* Retain the last valid saved profile. */ }
        }
        render(profile);
    }

    private void render(Profile profile) {
        rows.clear();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(dp(18), dp(28), dp(18), dp(24));
        TextView title = text(getString(R.string.app_name));
        title.setTextSize(28);
        root.addView(title);
        root.addView(text(getString(R.string.intro)));
        enabled = new Switch(this);
        enabled.setText(R.string.enabled);
        enabled.setChecked(profile.enabled());
        root.addView(enabled);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        button(actions, R.string.save, v -> save());
        button(actions, R.string.randomize, v -> {
            Map<String, String> generated = new HashMap<>();
            Profile.randomize(generated);
            for (var entry : generated.entrySet()) {
                Row row = rows.get(entry.getKey());
                row.input.setText(entry.getValue());
                row.mode.setSelection(2);
            }
            toast(R.string.edited);
        });
        button(actions, R.string.preset, v -> { render(Profile.defaults(profile)); toast(R.string.edited); });
        root.addView(actions);
        root.addView(text(getString(R.string.limits)));
        root.addView(text(getString(R.string.build_note)));
        root.addView(text(getString(R.string.ip_guard_note)));
        int group = -1;
        LinearLayout section = null;
        for (Schema.Field field : Schema.FIELDS) {
            if (field.group() != group) {
                group = field.group();
                TextView heading = text(getString(Schema.GROUP_LABELS[group]));
                heading.setTextSize(21);
                heading.setPadding(0, dp(24), 0, dp(4));
                root.addView(heading);
                section = new LinearLayout(this);
                section.setOrientation(LinearLayout.VERTICAL);
                section.setVisibility(group == 0 ? View.VISIBLE : View.GONE);
                root.addView(section);
                LinearLayout content = section;
                heading.setCompoundDrawablesWithIntrinsicBounds(0, 0, group == 0
                        ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
                heading.setOnClickListener(v -> {
                    boolean expand = content.getVisibility() != View.VISIBLE;
                    content.setVisibility(expand ? View.VISIBLE : View.GONE);
                    heading.setCompoundDrawablesWithIntrinsicBounds(0, 0, expand
                            ? android.R.drawable.arrow_up_float : android.R.drawable.arrow_down_float, 0);
                });
                heading.setFocusable(true);
            }
            section.addView(text(getString(field.label())));
            Spinner mode = new Spinner(this);
            mode.setAdapter(ArrayAdapter.createFromResource(this, R.array.modes, android.R.layout.simple_spinner_dropdown_item));
            section.addView(mode);
            EditText input = new EditText(this);
            boolean multiline = field.key().endsWith("properties") || field.key().equals("native_env") || field.key().equals("native_mount") || field.key().equals("ip_guard_cidrs");
            input.setSingleLine(!multiline);
            if (field.type().equals("long")) input.setInputType(InputType.TYPE_CLASS_NUMBER);
            else if (field.type().equals("float")) input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            else input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            input.setText(profile.get(field.key(), ""));
            input.setTextSize(15);
            if (field.key().endsWith("_id")) input.setTypeface(android.graphics.Typeface.MONOSPACE);
            input.setContentDescription(getString(field.label()));
            section.addView(input);
            Row row = new Row(field, mode, input);
            rows.put(field.key(), row);
            int selection = !profile.has(field.key()) ? 0 : profile.get(field.key()).equals(field.blocked()) ? 1 : 2;
            input.setEnabled(selection == 2);
            mode.setSelection(selection);
            mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { input.setEnabled(pos == 2); }
                @Override public void onNothingSelected(AdapterView<?> a) {}
            });
        }
        button(root, R.string.save, v -> save());
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
        root.requestFocus();
        if (getWindow().getInsetsController() != null) getWindow().getInsetsController().setSystemBarsAppearance(
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        // Keep controls clear of gesture/status bars on edge-to-edge Android versions.
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
    }

    private Profile collect() {
        Map<String, String> values = new HashMap<>();
        values.put("enabled", String.valueOf(enabled.isChecked()));
        for (Row row : rows.values()) {
            int mode = row.mode.getSelectedItemPosition();
            if (mode != 0) {
                String value = mode == 1 ? row.field.blocked() : row.input.getText().toString();
                try { new Profile(Map.of(row.field.key(), value)); }
                catch (RuntimeException invalid) {
                    ((View) row.input.getParent()).setVisibility(View.VISIBLE);
                    row.input.setError(getString(R.string.invalid)); row.input.requestFocus(); throw invalid;
                }
                values.put(row.field.key(), value);
            }
        }
        return new Profile(values);
    }
    private void save() {
        try { ProfileStore.save(this, collect()); toast(R.string.saved); }
        catch (RuntimeException e) { toast(R.string.invalid); }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        try { state.putString("draft", collect().encode()); }
        catch (RuntimeException ignored) { /* Invalid draft is not persisted. */ }
    }
    private void button(LinearLayout parent, int text, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(action);
        parent.addView(button);
    }
    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value); view.setTextSize(15); view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(int id) { Toast.makeText(this, id, Toast.LENGTH_LONG).show(); }
}
