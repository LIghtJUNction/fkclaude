package io.github.lightjunction.fkclaude;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A local, Activity-scoped audit view. No system overlay permission or external service. */
final class AuditPanel {
    static final String OPEN_EXTRA = "fkclaude.openAudit";
    static final int REQUEST_EXPORT = 0x5F4C;
    private static final int BG = 0xff141618, SURFACE = 0xff212427;
    private static final int FG = 0xfff0f2f4, MUTED = 0xffa5adb4, ACCENT = 0xffa9c7ff;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Activity, WeakReference<AuditPanel>> PANELS = new WeakHashMap<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "fkclaude-audit-ui");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
    private static boolean exportRequested;
    private static final String[] CATEGORIES = {"", "network", "telemetry", "conversation", "device",
            "identifier", "location", "clipboard", "contacts", "calendar", "packages", "sensor",
            "microphone", "camera", "webview", "integrity", "system"};
    private static final String[] LABELS = {"全部类别", "网络", "遥测", "对话", "设备与系统", "设备标识",
            "定位", "剪贴板", "联系人", "日历", "应用查询", "传感器", "麦克风", "相机", "网页", "完整性证明", "审计状态"};

    private final WeakReference<Activity> owner;
    private final TextView bubble;
    private Dialog dialog;
    private Dialog detailDialog;
    private Dialog clearDialog;
    private EventAdapter adapter;
    private ListView list;
    private TextView status;
    private TextView empty;
    private EditText search;
    private Spinner category;
    private Switch onlyNonConversation;
    private Switch recording;
    private Button pause;
    private boolean paused, loading, disposed, suspended, updatingToggle;
    private long shownRevision = -1;
    private int filterGeneration;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (disposed || suspended || dialog == null || !dialog.isShowing()) return;
            updateRecordState();
            if (!paused) refresh(false);
            MAIN.postDelayed(this, 1000);
        }
    };
    private final Runnable searchChanged = () -> refresh(true);

    static void attach(Activity activity) { attach(activity, false); }

    /** Returns true only after an explicitly requested panel is visible. */
    static boolean attach(Activity activity, boolean requested) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> attach(activity, requested));
            return false;
        }
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        try {
            WeakReference<AuditPanel> reference = PANELS.get(activity);
            AuditPanel panel = reference == null ? null : reference.get();
            if (panel == null || panel.disposed) {
                panel = new AuditPanel(activity);
                PANELS.put(activity, new WeakReference<>(panel));
            }
            panel.suspended = false;
            if (panel.dialog != null && panel.dialog.isShowing()) {
                MAIN.removeCallbacks(panel.tick);
                MAIN.post(panel.tick);
            }
            Intent intent = activity.getIntent();
            if (requested || (intent != null && intent.getBooleanExtra(OPEN_EXTRA, false))) {
                AuditLog.record("system", "audit_panel_request", "AuditPanel", "requested",
                        Map.of("phase", requested ? "preserved_intent" : "intent"));
                panel.bubble.setVisibility(View.VISIBLE);
                if (panel.show()) {
                    if (intent != null) intent.removeExtra(OPEN_EXTRA);
                    return true;
                }
            }
        } catch (Throwable error) {
            AuditHooks.uiFailure("attach", error);
        }
        return false;
    }

    static void pause(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> pause(activity));
            return;
        }
        WeakReference<AuditPanel> reference = PANELS.get(activity);
        AuditPanel panel = reference == null ? null : reference.get();
        if (panel != null) {
            panel.suspended = true;
            MAIN.removeCallbacks(panel.tick);
            MAIN.removeCallbacks(panel.searchChanged);
        }
    }

    static void detach(Activity activity) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN.post(() -> detach(activity));
            return;
        }
        WeakReference<AuditPanel> reference = PANELS.remove(activity);
        AuditPanel panel = reference == null ? null : reference.get();
        if (panel == null) return;
        panel.disposed = true;
        MAIN.removeCallbacks(panel.tick);
        MAIN.removeCallbacks(panel.searchChanged);
        if (panel.detailDialog != null) panel.detailDialog.dismiss();
        if (panel.clearDialog != null) { panel.clearDialog.dismiss(); panel.clearDialog = null; }
        if (panel.dialog != null) panel.dialog.dismiss();
        if (panel.bubble.getParent() instanceof ViewGroup parent) parent.removeView(panel.bubble);
    }

    /** Return true only for the document picker explicitly launched by this panel. */
    static boolean onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_EXPORT || !exportRequested) return false;
        exportRequested = false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return true;
        Uri uri = data.getData();
        if (!"content".equals(uri.getScheme())) {
            toast(activity, "未写入：请选择系统文件保存位置");
            return true;
        }
        Context context = activity.getApplicationContext();
        WeakReference<Activity> weak = new WeakReference<>(activity);
        submit(() -> {
            boolean success = false;
            try {
                try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("Document stream unavailable");
                    output.write(AuditLog.exportJsonl().getBytes(StandardCharsets.UTF_8));
                    output.flush();
                }
                success = true;
            } catch (Exception ignored) {
                // Never expose provider paths, document names or exception messages in logs.
            }
            final boolean saved = success;
            MAIN.post(() -> {
                Activity target = weak.get();
                if (target != null && !target.isDestroyed())
                    toast(target, saved ? "已导出脱敏 JSONL" : "导出失败，请重新选择保存位置");
            });
        }, activity);
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    private AuditPanel(Activity activity) {
        owner = new WeakReference<>(activity);
        bubble = text(activity, "审计", 13, ACCENT);
        bubble.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bubble.setGravity(Gravity.CENTER);
        bubble.setPadding(dp(activity, 15), 0, dp(activity, 15), 0);
        bubble.setBackground(shape(SURFACE, dp(activity, 24)));
        bubble.setElevation(dp(activity, 5));
        bubble.setContentDescription("打开 fkclaude 行为审计，可拖动位置");
        bubble.setFocusable(true);
        bubble.setOnClickListener(v -> show());
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout parent)) return;
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48), Gravity.TOP | Gravity.LEFT);
        position.leftMargin = Math.max(0, activity.getResources().getDisplayMetrics().widthPixels - dp(activity, 82));
        position.topMargin = dp(activity, 100);
        parent.addView(bubble, position);
        bubble.post(() -> constrainBubble(parent, position.leftMargin, position.topMargin));
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float startX, startY, originalX, originalY;
            boolean moved;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startX = event.getRawX(); startY = event.getRawY();
                        originalX = bubble.getX(); originalY = bubble.getY(); moved = false;
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                        if (Math.abs(dx) + Math.abs(dy) > dp(activity, 8)) moved = true;
                        if (moved) constrainBubble(parent, originalX + dx, originalY + dy);
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> {
                        if (!moved) view.performClick();
                        return true;
                    }
                    case MotionEvent.ACTION_CANCEL -> { return true; }
                    default -> { return false; }
                }
            }
        });
        parent.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            if (!disposed && (r - l != oldR - oldL || b - t != oldB - oldT))
                constrainBubble(parent, bubble.getX(), bubble.getY());
        });
    }

    private void constrainBubble(FrameLayout parent, float x, float y) {
        Activity activity = owner.get();
        if (activity == null || disposed) return;
        WindowInsets insets = parent.getRootWindowInsets();
        Insets bars = insets == null ? Insets.NONE : insets.getInsets(
                WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
        int pad = dp(activity, 8);
        float left = bars.left + pad, top = bars.top + pad;
        float right = Math.max(left, parent.getWidth() - bars.right - bubble.getWidth() - pad);
        float bottom = Math.max(top, parent.getHeight() - bars.bottom - bubble.getHeight() - pad);
        bubble.setX(Math.max(left, Math.min(x, right)));
        bubble.setY(Math.max(top, Math.min(y, bottom)));
    }

    private boolean show() {
        boolean alreadyVisible = dialog != null && dialog.isShowing();
        try {
            showInternal();
            boolean visible = dialog != null && dialog.isShowing();
            if (visible && !alreadyVisible)
                AuditLog.record("system", "audit_panel_open", "AuditPanel", "visible", Map.of("phase", "dialog"));
            return visible;
        } catch (Throwable error) {
            MAIN.removeCallbacks(tick);
            MAIN.removeCallbacks(searchChanged);
            try { if (dialog != null) dialog.dismiss(); }
            catch (Throwable cleanupError) { AuditHooks.uiFailure("show_cleanup", cleanupError); }
            dialog = null;
            try { bubble.setVisibility(View.VISIBLE); }
            catch (Throwable cleanupError) { AuditHooks.uiFailure("entry_restore", cleanupError); }
            AuditHooks.uiFailure("show", error);
            return false;
        }
    }

    private void showInternal() {
        Activity activity = owner.get();
        if (disposed || activity == null || activity.isDestroyed() || activity.isFinishing()) return;
        if (dialog != null && dialog.isShowing()) return;
        paused = false;
        shownRevision = -1;
        dialog = new Dialog(activity, android.R.style.Theme_Material_Dialog_NoActionBar);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column(activity);
        root.setPadding(dp(activity, 18), dp(activity, 14), dp(activity, 18), dp(activity, 12));
        root.setBackground(shape(BG, dp(activity, 24)));
        root.setFocusableInTouchMode(true);

        LinearLayout titleRow = row(activity);
        LinearLayout title = column(activity);
        TextView eyebrow = text(activity, "FKCLAUDE  /  LOCAL AUDIT", 10, MUTED);
        eyebrow.setLetterSpacing(0.12f);
        title.addView(eyebrow);
        title.addView(text(activity, "行为审计", 25, FG));
        titleRow.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        Button close = button(activity, "收起", v -> dialog.dismiss());
        close.setContentDescription("关闭弹窗，保留记录状态");
        titleRow.addView(close);
        root.addView(titleRow);

        ScrollView controlsScroll = new ScrollView(activity);
        controlsScroll.setFillViewport(false);
        controlsScroll.setVerticalScrollBarEnabled(false);
        LinearLayout controls = column(activity);
        controlsScroll.addView(controls);
        root.addView(controlsScroll, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout recordRow = row(activity);
        status = text(activity, "正在读取本地记录…", 11, MUTED);
        status.setMaxLines(3);
        recordRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        recording = new Switch(activity);
        recording.setText("记录");
        recording.setTextSize(13);
        recording.setTextColor(FG);
        recording.setContentDescription("开启或关闭事件记录");
        recording.setChecked(AuditLog.isEnabled());
        recording.setOnCheckedChangeListener((view, checked) -> {
            if (updatingToggle) return;
            AuditLog.setEnabled(checked);
            updateRecordState();
            refresh(true);
        });
        recordRow.addView(recording);
        controls.addView(recordRow);

        search = new EditText(activity);
        search.setTextColor(FG);
        search.setHintTextColor(MUTED);
        search.setTextSize(14);
        search.setSingleLine(true);
        search.setHint("搜索接口、域名或字段");
        search.setContentDescription("搜索审计事件");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setPadding(dp(activity, 14), dp(activity, 9), dp(activity, 14), dp(activity, 9));
        search.setBackground(shape(SURFACE, dp(activity, 12)));
        LinearLayout.LayoutParams searchLayout = new LinearLayout.LayoutParams(-1, dp(activity, 44));
        searchLayout.topMargin = dp(activity, 8);
        controls.addView(search, searchLayout);

        category = new Spinner(activity);
        ArrayAdapter<String> categories = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, LABELS) {
            @Override public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(FG); view.setTextSize(13); return view;
            }
            @Override public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(FG); view.setBackgroundColor(SURFACE); view.setTextSize(14);
                view.setPadding(dp(activity, 12), dp(activity, 12), dp(activity, 12), dp(activity, 12));
                return view;
            }
        };
        category.setAdapter(categories);
        category.setContentDescription("按事件类别筛选");
        controls.addView(category, new LinearLayout.LayoutParams(-1, dp(activity, 42)));
        onlyNonConversation = new Switch(activity);
        onlyNonConversation.setText("仅非对话网络");
        onlyNonConversation.setTextSize(13);
        onlyNonConversation.setTextColor(FG);
        onlyNonConversation.setContentDescription("仅显示非对话网络事件，包含未知用途");
        controls.addView(onlyNonConversation);

        LinearLayout actions = row(activity);
        pause = button(activity, "暂停刷新", v -> {
            paused = !paused;
            pause.setText(paused ? "继续刷新" : "暂停刷新");
            updateRecordState();
            if (!paused) refresh(true);
        });
        pause.setContentDescription("暂停或继续列表刷新，记录开关独立");
        actions.addView(pause, new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        actions.addView(button(activity, "导出", v -> chooseExport()),
                new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        actions.addView(button(activity, "清空", v -> confirmClear()),
                new LinearLayout.LayoutParams(0, dp(activity, 44), 1));
        controls.addView(actions);

        FrameLayout events = new FrameLayout(activity);
        list = new ListView(activity);
        list.setDivider(null);
        list.setDividerHeight(dp(activity, 6));
        list.setClipToPadding(false);
        list.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        list.setContentDescription("审计事件列表，最新在前，点击查看完整记录");
        adapter = new EventAdapter(activity);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> showDetails(adapter.items.get(position).json));
        events.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = text(activity, "尚无匹配事件\n使用 Claude 后在此查看；可调整筛选条件。", 14, MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(activity, 14), dp(activity, 16), dp(activity, 14), dp(activity, 16));
        events.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        root.addView(events, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView footnote = text(activity, "本地脱敏记录 · 尝试、响应、阻断分别记录", 10, MUTED);
        footnote.setPadding(0, dp(activity, 8), 0, 0);
        root.addView(footnote);

        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { filtersChanged(); }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        onlyNonConversation.setOnCheckedChangeListener((v, checked) -> filtersChanged());
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterGeneration++;
                MAIN.removeCallbacks(searchChanged);
                MAIN.postDelayed(searchChanged, 250);
            }
            @Override public void afterTextChanged(Editable editable) {}
        });
        dialog.setContentView(root);
        dialog.setOnDismissListener(d -> {
            MAIN.removeCallbacks(tick);
            MAIN.removeCallbacks(searchChanged);
            if (detailDialog != null) { detailDialog.dismiss(); detailDialog = null; }
            if (clearDialog != null) { clearDialog.dismiss(); clearDialog = null; }
            filterGeneration++;
            bubble.setVisibility(View.VISIBLE);
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            android.graphics.Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
            Insets safe = activity.getWindowManager().getCurrentWindowMetrics().getWindowInsets()
                    .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            int height = Math.max(dp(activity, 240), bounds.height() - safe.top - safe.bottom - dp(activity, 32));
            window.setLayout(Math.min(bounds.width() - dp(activity, 24), dp(activity, 620)), height);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams params = window.getAttributes();
            params.dimAmount = 0.55f;
            window.setAttributes(params);
        }
        root.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            int max = Math.min(dp(activity, 260), Math.max(dp(activity, 80), (b - t) / 2));
            ViewGroup.LayoutParams params = controlsScroll.getLayoutParams();
            if (params.height != max) { params.height = max; controlsScroll.setLayoutParams(params); }
        });
        bubble.setVisibility(View.INVISIBLE);
        root.requestFocus();
        refresh(true);
        MAIN.removeCallbacks(tick);
        MAIN.postDelayed(tick, 1000);
    }

    private void filtersChanged() {
        filterGeneration++;
        shownRevision = -1;
        refresh(true);
    }

    private void updateRecordState() {
        if (status == null || recording == null) return;
        updatingToggle = true;
        recording.setChecked(AuditLog.isEnabled());
        updatingToggle = false;
        status.setText((AuditLog.isEnabled() ? "记录中" : "已停止记录")
                + (paused ? " · 列表已暂停" : " · 实时刷新") + "\n" + summaryLabel(AuditLog.summary()));
        bubble.setText(AuditLog.isEnabled() ? "审计" : "审计 · 停");
    }

    private static String summaryLabel(String raw) {
        return raw.replaceFirst("^(Recording|Paused) \\| ", "")
                .replace("PID ", "进程 ")
                .replaceAll("([0-9]+/[0-9]+) retained", "保留 $1")
                .replace("evicted ", "淘汰 ")
                .replace("queue dropped ", "队列丢弃 ")
                .replace("disk errors ", "磁盘错误 ")
                .replace("invalid history ", "无效历史 ")
                .replace("loading history", "正在载入历史")
                .replace(" | ", " · ");
    }

    private void refresh(boolean force) {
        if (disposed || suspended || dialog == null || !dialog.isShowing() || search == null || category == null) return;
        if (loading) {
            if (force) shownRevision = -1;
            return;
        }
        long revision = AuditLog.revision();
        if (!force && (paused || revision == shownRevision)) return;
        loading = true;
        String query = search.getText().toString();
        String selected = CATEGORIES[category.getSelectedItemPosition()];
        boolean nonConversation = onlyNonConversation.isChecked();
        int generation = filterGeneration;
        Activity activity = owner.get();
        boolean scheduled = submit(() -> {
            List<Event> result = new ArrayList<>();
            String error = null;
            try {
                for (String line : AuditLog.snapshot(query, selected, nonConversation, 300)) result.add(Event.parse(line));
            } catch (RuntimeException ignored) {
                error = "读取失败；稍后重试";
            }
            String failure = error;
            MAIN.post(() -> {
                loading = false;
                if (disposed || dialog == null || !dialog.isShowing()) return;
                if (generation != filterGeneration) { refresh(true); return; }
                if (failure != null) { status.setText(failure); return; }
                int first = list.getFirstVisiblePosition();
                int top = list.getChildAt(0) == null ? 0 : list.getChildAt(0).getTop();
                String anchor = first > 0 && first < adapter.items.size() ? adapter.items.get(first).identity : null;
                adapter.items = result;
                adapter.notifyDataSetChanged();
                if (anchor != null) {
                    for (int i = 0; i < result.size(); i++) {
                        if (anchor.equals(result.get(i).identity)) { list.setSelectionFromTop(i, top); break; }
                    }
                }
                shownRevision = revision;
                updateRecordState();
            });
        }, activity);
        if (!scheduled) loading = false;
    }

    private void showDetails(String json) {
        Activity activity = owner.get();
        if (activity == null || disposed) return;
        String formatted;
        try { formatted = new JSONObject(json).toString(2); }
        catch (Exception ignored) { formatted = json; }
        TextView detail = text(activity, formatted, 12, FG);
        detail.setTypeface(Typeface.MONOSPACE);
        detail.setTextIsSelectable(true);
        detail.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 12));
        ScrollView scroll = new ScrollView(activity);
        scroll.setBackgroundColor(BG);
        scroll.addView(detail);
        detailDialog = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("事件详情").setView(scroll).setPositiveButton("完成", null).create();
        detailDialog.show();
    }

    private void confirmClear() {
        Activity activity = owner.get();
        if (activity == null || disposed || activity.isFinishing() || activity.isDestroyed()) return;
        if (clearDialog != null && clearDialog.isShowing()) return;
        AlertDialog confirmation = new AlertDialog.Builder(activity, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("清空本地审计记录？")
                .setMessage("删除当前保留的事件。记录开关保持现状；已导出的文件保留。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, which) -> submit(() -> {
                    AuditLog.clear();
                    MAIN.post(() -> { filterGeneration++; refresh(true); });
                }, activity)).create();
        clearDialog = confirmation;
        confirmation.setOnDismissListener(d -> {
            if (clearDialog == d) clearDialog = null;
        });
        confirmation.show();
    }

    @SuppressWarnings("deprecation")
    private void chooseExport() {
        Activity activity = owner.get();
        if (activity == null || disposed) return;
        if (exportRequested) { toast(activity, "请先完成当前保存操作"); return; }
        Intent picker = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/x-ndjson");
        picker.putExtra(Intent.EXTRA_TITLE, "fkclaude-audit-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new java.util.Date()) + ".jsonl");
        exportRequested = true;
        try { activity.startActivityForResult(picker, REQUEST_EXPORT); }
        catch (RuntimeException ignored) {
            exportRequested = false;
            toast(activity, "无法打开系统文件选择器");
        }
    }

    private static boolean submit(Runnable runnable, Context context) {
        Context application = context == null ? null : context.getApplicationContext();
        try {
            WORKER.execute(() -> {
                try { runnable.run(); }
                catch (RuntimeException ignored) {
                    if (application != null) toast(application, "审计操作未完成，请重试");
                }
            });
            return true;
        }
        catch (RejectedExecutionException ignored) {
            if (context != null) toast(context, "审计操作处理中，请稍后重试");
            return false;
        }
    }

    private static final class Event {
        final String json, identity, meta, title, subtitle;
        private Event(String json, String identity, String meta, String title, String subtitle) {
            this.json = json; this.identity = identity; this.meta = meta; this.title = title; this.subtitle = subtitle;
        }
        static Event parse(String line) {
            try {
                JSONObject json = new JSONObject(line);
                String time = json.optString("time", json.optString("timestamp", ""));
                int t = time.indexOf('T');
                if (t >= 0 && time.length() >= t + 9) time = time.substring(t + 1, t + 9) + "Z";
                String kind = json.optString("category", "system");
                String label = kind;
                for (int i = 0; i < CATEGORIES.length; i++) if (CATEGORIES[i].equals(kind)) { label = LABELS[i]; break; }
                JSONObject fields = json.optJSONObject("details");
                if (fields == null) fields = new JSONObject();
                String source = json.optString("source", "");
                String action = json.optString("action", "event");
                String outcome = json.optString("outcome", "");
                return new Event(line, json.optString("session_id", json.optString("session", ""))
                        + ":" + json.optLong("seq", 0),
                        time + "  ·  " + label + "  ·  " + outcomeLabel(outcome, fields),
                        actionLabel(action, kind, source, fields),
                        detailLabel(action, source, fields));
            } catch (Exception ignored) {
                return new Event(line, Integer.toHexString(line.hashCode()), "审计记录", "查看 JSON", "");
            }
        }

        private static String outcomeLabel(String outcome, JSONObject fields) {
            return switch (outcome) {
                case "called" -> "调用开始";
                case "attempted" -> "尝试发送";
                case "returned" -> "bytes_accepted_by_socket".equals(fields.optString("observation"))
                        ? "本机套接字已返回" : "接口已返回";
                case "response_received" -> "收到响应";
                case "blocked" -> "已阻断";
                case "failed" -> "NameNotFoundException".equals(fields.optString("error_type")) ? "查询未命中" : "调用失败";
                case "constructed" -> "已构造事件";
                case "observed" -> "已观察";
                case "installed" -> "已安装";
                case "unavailable" -> "不可用";
                case "unsupported" -> "未适配";
                case "dropped" -> "已丢弃";
                case "queue_result" -> switch (fields.optString("phase")) {
                    case "accepted" -> "发送队列已接纳";
                    case "rejected" -> "发送队列未接纳";
                    default -> "发送队列已返回";
                };
                case "visible" -> "已显示";
                case "requested" -> "已请求";
                case "enabled" -> "已开启";
                case "disabled" -> "已关闭";
                case "cleared" -> "已清空";
                default -> outcome;
            };
        }

        private static boolean http(String action) {
            return action.startsWith("http_") || action.equals("web_request") || action.equals("web_response")
                    || action.equals("web_failure") || action.equals("web_beacon_queued");
        }

        private static String actionLabel(String action, String kind, String source, JSONObject fields) {
            if (http(action)) {
                String method = fields.optString("method", "");
                if ("UNKNOWN".equals(method)) method = "";
                if ("FRAME".equals(method)) return "WebSocket 数据帧发送";
                String path = path(fields);
                if (!method.isEmpty() || !path.isEmpty())
                    return (method.isEmpty() ? "请求" : method) + (path.isEmpty() ? " 网络请求" : " " + path);
                return action.startsWith("web_") ? "网页网络请求" : "HTTP 请求";
            }
            if (action.startsWith("native_")) {
                String operation = action.substring("native_".length());
                String transport = fields.optString("transport", "").toUpperCase(Locale.ROOT);
                String prefix = "原生" + (transport.isEmpty() || transport.equals("UNKNOWN") ? "" : " " + transport);
                return switch (operation) {
                    case "connect" -> prefix + " 连接调用";
                    case "send", "sendto", "sendmsg", "sendmmsg" -> prefix + " 发送（" + operation + "）";
                    case "write", "writev" -> prefix + " 套接字写入（" + operation + "）";
                    default -> action;
                };
            }
            return switch (action) {
                case "api_access" -> apiLabel(kind, source);
                case "identifier_read" -> "访问 Android ID 接口";
                case "system_property_read" -> "查询系统属性";
                case "provider_query" -> kind.equals("contacts") ? "查询联系人接口"
                        : kind.equals("calendar") ? "查询日历接口" : "查询数据接口";
                case "environment_file_probe" -> "探测系统环境文件";
                case "environment_command" -> "调用环境检查命令";
                case "dns_lookup" -> "解析域名";
                case "socket_connect" -> "请求 TCP 连接";
                case "datagram_send" -> "调用 UDP 发送接口";
                case "connection_created" -> "创建网络连接对象";
                case "navigation" -> "请求网页导航";
                case "sift_collect" -> "Sift 设备采集入口";
                case "sift_queue" -> "Sift 事件入队接口";
                case "sift_dispatch" -> "Sift 上传协程入口";
                case "sift_device_properties" -> "构造 Sift 设备信息事件";
                case "sift_app_state" -> "构造 Sift 应用状态事件";
                case "analytics_context" -> "构造统计上下文";
                case "product_analytics_event" -> "构造产品统计事件";
                case "analytics_batch" -> "创建统计批量请求";
                case "web_installed" -> "网页审计已就绪";
                case "web_device_read" -> "访问网页设备信息接口";
                case "web_sensitive_api" -> webApiLabel(fields.optString("field"));
                case "web_dropped", "webview_rate_limit" -> "网页事件限流";
                case "webview_coverage" -> "网页审计覆盖状态";
                case "coverage", "hook_coverage", "target_adapter" -> "审计覆盖状态";
                case "environment_policy" -> "环境覆盖策略";
                case "activity_resumed" -> "应用页面进入前台";
                case "audit_panel_request" -> "请求打开审计面板";
                case "audit_panel_open" -> "审计面板已打开";
                case "observer_error" -> "审计观察点异常";
                default -> action;
            };
        }

        private static String apiLabel(String kind, String source) {
            String name = source.substring(source.lastIndexOf('#') + 1);
            return switch (name) {
                case "getApplicationInfo" -> "查询应用信息";
                case "getPackageInfo" -> "查询应用包信息";
                case "getInstalledApplications" -> "查询已安装应用";
                case "getInstalledPackages" -> "查询已安装应用包";
                case "queryIntentActivities" -> "查询可处理操作的应用";
                case "getPrimaryClip" -> "访问剪贴板读取接口";
                case "getPrimaryClipDescription" -> "查询剪贴板内容类型";
                case "hasPrimaryClip" -> "检查剪贴板是否有内容";
                case "getLastKnownLocation" -> "访问最近定位接口";
                case "getCurrentLocation", "requestSingleUpdate" -> "请求定位";
                case "requestLocationUpdates" -> "请求定位更新";
                case "getLatitude" -> "访问纬度接口";
                case "getLongitude" -> "访问经度接口";
                case "getAltitude" -> "访问海拔接口";
                case "getDeviceId" -> "访问设备标识接口";
                case "getImei" -> "访问 IMEI 接口";
                case "getMeid" -> "访问 MEID 接口";
                case "getSubscriberId" -> "访问 SIM 用户标识接口";
                case "getSimSerialNumber" -> "访问 SIM 序列号接口";
                case "getLine1Number" -> "访问本机号码接口";
                case "getSerial" -> "访问设备序列号接口";
                case "getNetworkOperatorName", "getSimOperatorName", "getNetworkOperator", "getSimOperator" -> "查询运营商信息";
                case "getSimCountryIso", "getNetworkCountryIso" -> "查询运营商国家信息";
                case "getSSID" -> "查询 Wi-Fi 名称接口";
                case "getBSSID" -> "查询 Wi-Fi 接入点标识";
                case "getMacAddress", "getHardwareAddress" -> "访问网卡地址接口";
                case "getIpAddress", "getInetAddresses", "getInterfaceAddresses" -> "查询本地网络地址";
                case "getScanResults" -> "查询 Wi-Fi 扫描结果";
                case "getSensorList", "getDynamicSensorList", "getDefaultSensor" -> "查询传感器接口";
                case "registerListener" -> kind.equals("sensor") ? "调用传感器监听接口" : "注册监听接口";
                case "startRecording" -> "请求开始录音";
                case "start" -> kind.equals("microphone") ? "请求开始媒体录制" : "调用启动接口";
                case "openCamera", "open" -> kind.equals("camera") ? "请求打开相机" : "调用打开接口";
                case "getIntProperty", "isCharging" -> "查询电池信息";
                case "getMemoryInfo" -> "查询内存信息";
                case "getTotalBytes", "getAvailableBytes", "getFreeBytes" -> "查询存储容量";
                case "captureEvent", "captureException", "captureMessage", "captureTransaction", "captureEnvelope" -> "调用 Sentry 遥测接口";
                default -> "调用接口 " + name;
            };
        }

        private static String webApiLabel(String field) {
            if (field.startsWith("geolocation.")) return "请求网页定位接口";
            if (field.startsWith("clipboard.")) return "访问网页剪贴板接口";
            if (field.equals("mediaDevices.getUserMedia")) return "请求网页媒体设备接口";
            if (field.equals("mediaDevices.enumerateDevices")) return "查询网页媒体设备";
            return "调用网页隐私接口";
        }

        private static String path(JSONObject fields) {
            String path = fields.optString("path", "");
            if (!path.isEmpty()) return path;
            String url = fields.optString("url", fields.optString("endpoint", ""));
            try {
                String parsed = new java.net.URI(url).getRawPath();
                return parsed == null ? "" : parsed;
            } catch (Exception ignored) { return ""; }
        }

        private static String destination(JSONObject fields) {
            String host = fields.optString("host", fields.optString("peer_address", ""));
            if (host.isEmpty()) {
                try {
                    String parsed = new java.net.URI(fields.optString("url", "")).getHost();
                    if (parsed != null) host = parsed;
                } catch (Exception ignored) {}
            }
            String port = fields.optString("port", "");
            if (port.isEmpty() || "0".equals(port)) return host;
            if (host.isEmpty()) return "目标端口 " + port;
            if (host.indexOf(':') >= 0 && !host.startsWith("[")) host = "[" + host + "]";
            return host + ":" + port;
        }

        private static String detailLabel(String action, String source, JSONObject fields) {
            ArrayList<String> lines = new ArrayList<>();
            String target = destination(fields);
            if (!target.isEmpty()) lines.add(target);
            ArrayList<String> metrics = new ArrayList<>();
            String status = fields.optString("status_code", "");
            if (!status.isEmpty()) metrics.add("HTTP " + status);
            String duration = fields.optString("duration_ms", "");
            if (!duration.isEmpty()) metrics.add(duration + " ms");
            String error = fields.optString("error_type", "");
            if (!error.isEmpty()) metrics.add(error);
            String bytes = fields.optString("bytes", "");
            if (!bytes.isEmpty()) {
                if ("bytes_accepted_by_socket".equals(fields.optString("observation")))
                    metrics.add("本机套接字接纳 " + bytes + " B");
                else if (action.equals("datagram_send") || action.startsWith("web_"))
                    metrics.add("请求体大小 " + bytes + " B");
            }
            String count = fields.optString("batch_count", "");
            if (!count.isEmpty() && !"0".equals(count)) metrics.add("消息数 " + count);
            String errno = fields.optString("errno", "");
            if (!errno.isEmpty() && !"0".equals(errno)) metrics.add("errno=" + errno);
            if (!metrics.isEmpty()) lines.add(String.join(" · ", metrics));
            if ("bytes_accepted_by_socket".equals(fields.optString("observation")))
                lines.add("对端接收状态未知");
            else if (http(action)) {
                String transport = fields.optString("transport", "");
                if (!transport.isEmpty()) lines.add(switch (transport) {
                    case "fetch" -> "网页 fetch";
                    case "xhr" -> "网页 XMLHttpRequest";
                    case "beacon" -> "网页 Beacon";
                    case "websocket" -> "WebSocket";
                    case "okhttp" -> "应用 HTTP 客户端";
                    case "urlconnection" -> "系统 HTTP 客户端";
                    default -> transport;
                });
            } else if (action.equals("api_access")) {
                String api = fields.optString("api", source);
                int hash = api.lastIndexOf('#');
                if (hash > 0) {
                    String owner = api.substring(0, hash);
                    int dot = owner.lastIndexOf('.');
                    lines.add(owner.substring(dot + 1) + api.substring(hash));
                } else if (!api.isEmpty()) lines.add(api);
            } else {
                String field = fields.optString("field", "");
                if (!field.isEmpty()) lines.add("接口 / 字段：" + field);
                String operation = fields.optString("operation", "");
                if (!operation.isEmpty()) lines.add("命令：" + operation);
                String reason = fields.optString("reason", "");
                if (!reason.isEmpty()) lines.add("策略：" + reason);
                if ("schema_only_values_not_read".equals(fields.optString("coverage")))
                    lines.add("记录字段结构，原值未写入日志");
                if (action.equals("coverage")) {
                    String hooks = fields.optString("hook_count", "");
                    if (!hooks.isEmpty()) lines.add("可用观察点 " + hooks + " · 未适配 " + fields.optString("count", "0"));
                }
            }
            if (lines.isEmpty() && !source.isEmpty()) lines.add(source);
            return String.join("\n", lines);
        }
    }

    private static final class EventAdapter extends BaseAdapter {
        private final Context context;
        private List<Event> items = List.of();
        EventAdapter(Context context) { this.context = context; }
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            TextView meta, title, subtitle;
            if (convertView instanceof LinearLayout reuse) {
                row = reuse; meta = (TextView) row.getChildAt(0);
                title = (TextView) row.getChildAt(1); subtitle = (TextView) row.getChildAt(2);
            } else {
                row = column(context);
                row.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 10));
                row.setBackground(shape(SURFACE, dp(context, 12)));
                meta = text(context, "", 10, ACCENT); meta.setMaxLines(2);
                title = text(context, "", 14, FG); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setMaxLines(2);
                subtitle = text(context, "", 11, MUTED); subtitle.setMaxLines(3);
                subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.addView(meta); row.addView(title); row.addView(subtitle);
            }
            Event event = items.get(position);
            meta.setText(event.meta); title.setText(event.title); subtitle.setText(event.subtitle);
            subtitle.setVisibility(event.subtitle.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(color); return view;
    }
    private static Button button(Context context, String label, View.OnClickListener click) {
        Button button = new Button(context);
        button.setText(label); button.setTextSize(12); button.setTextColor(ACCENT);
        button.setAllCaps(false); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 44)); button.setMinimumHeight(dp(context, 44));
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setBackgroundResource(android.R.color.transparent);
        button.setOnClickListener(click); return button;
    }
    private static GradientDrawable shape(int color, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color);
        background.setCornerRadius(radius); return background;
    }
    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
    private static void toast(Context context, String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        else MAIN.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
