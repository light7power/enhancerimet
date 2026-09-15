package me.firesun.dingtalk.enhancement.modern;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SettingsActivity extends Activity {
    private static final int PAGE = Color.rgb(246, 247, 249);
    private static final int CARD = Color.WHITE;
    private static final int PRIMARY = Color.rgb(35, 35, 35);
    private static final int SECONDARY = Color.rgb(138, 142, 150);
    private static final int ACCENT = Color.rgb(255, 103, 79);
    private MiuixToggle recallToggle;
    private MiuixToggle readToggle;
    private final ExecutorService configExecutor = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAGE);
        getWindow().setNavigationBarColor(PAGE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAGE);
        LinearLayout page = column();
        page.setPadding(dp(20), dp(28), dp(20), dp(28));
        scroll.addView(page);

        page.addView(label("钉钉增强", 30, PRIMARY, true), lp(-1, -2));
        page.addView(label("DingTalk Enhancement  ·  Rimet 8.5.5", 13, SECONDARY, false),
                lp(-1, -2, 0, 5));

        LinearLayout hero = card(20);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        hero.setPadding(dp(18), dp(14), dp(14), dp(14));
        LinearLayout heroText = column();
        heroText.addView(label("模块已就绪", 17, PRIMARY, true));
        heroText.addView(label("在钉钉重启后生效", 13, SECONDARY, false), lp(-1, -2, 0, 5));
        hero.addView(heroText, lp(0, -2, 1, 0));
        TextView mark = label("●", 24, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        hero.addView(mark, lp(dp(42), dp(42), 0, 0));
        page.addView(hero, lp(-1, -2, 0, 24));

        page.addView(section("消息保护"), lp(-1, -2, 0, 8));
        LinearLayout protection = card(18);
        recallToggle = addSettingRow(protection, "防撤回", "保留文本、图片和其他类型的原消息", "anti_recall", true);
        addDivider(protection);
        readToggle = addSettingRow(protection, "屏蔽已读", "不向会话发送已读状态", "mask_read", true);
        page.addView(protection, lp(-1, -2, 0, 22));

        page.addView(section("关于模块"), lp(-1, -2, 0, 8));
        LinearLayout about = card(18);
        addInfoRow(about, "当前版本", "2.0.1");
        addDivider(about);
        addInfoRow(about, "界面风格", "Miuix Light");
        addDivider(about);
        addInfoRow(about, "特别鸣谢", "DingtalkEnhancement · libxposed");
        page.addView(about, lp(-1, -2));

        page.addView(section("插件工具"), lp(-1, -2, 0, 20));
        LinearLayout tools = card(18);
        addActionRow(tools, "生成本机配置", "扫描当前安装的钉钉并验证 Hook 签名", false);
        addDivider(tools);
        addActionRow(tools, "尝试修复插件", "清除旧配置后重新扫描并验证", true);
        page.addView(tools, lp(-1, -2));

        TextView footer = label("修改设置后，请完全退出并重新打开钉钉。", 12, SECONDARY, false);
        footer.setGravity(Gravity.CENTER);
        page.addView(footer, lp(-1, -2, 0, 24));
        setContentView(scroll);
        refreshConfig();
        scroll.postDelayed(this::refreshConfig, 600);
    }

    @Override protected void onResume() {
        super.onResume();
        if (recallToggle != null) refreshConfig();
    }

    private void refreshConfig() {
        if (App.service == null) return;
        try {
            android.content.SharedPreferences prefs = App.service.getRemotePreferences("config");
            recallToggle.setChecked(prefs.getBoolean("anti_recall", true));
            readToggle.setChecked(prefs.getBoolean("mask_read", true));
        } catch (Throwable ignored) {
            // The service can disappear while the settings page is open.
        }
    }

    private MiuixToggle addSettingRow(LinearLayout parent, String title, String summary,
                                      String key, boolean checked) {
        LinearLayout row = row();
        LinearLayout text = column();
        text.addView(label(title, 16, PRIMARY, false));
        text.addView(label(summary, 12, SECONDARY, false), lp(-1, -2, 0, 5));
        row.addView(text, lp(0, -2, 1, 0));
        MiuixToggle toggle = new MiuixToggle(this);
        toggle.setChecked(checked);
        toggle.setContentDescription(title);
        toggle.setOnToggleChanged(value -> {
            if (App.service != null) {
                App.service.getRemotePreferences("config").edit().putBoolean(key, value).apply();
            }
        });
        row.addView(toggle, lp(dp(52), dp(32), 0, 0));
        parent.addView(row, lp(-1, -2));
        return toggle;
    }

    private void addInfoRow(LinearLayout parent, String title, String value) {
        LinearLayout row = row();
        row.addView(label(title, 16, PRIMARY, false), lp(0, -2, 1, 0));
        row.addView(label(value, 14, SECONDARY, false), lp(-2, -2, 0, 0));
        parent.addView(row, lp(-1, -2));
    }

    private void addActionRow(LinearLayout parent, String title, String summary, boolean repair) {
        LinearLayout row = row();
        LinearLayout text = column();
        text.addView(label(title, 16, PRIMARY, false));
        text.addView(label(summary, 12, SECONDARY, false), lp(-1, -2, 0, 5));
        row.addView(text, lp(0, -2, 1, 0));
        TextView arrow = label("›", 26, SECONDARY, false);
        arrow.setGravity(Gravity.CENTER);
        row.addView(arrow, lp(dp(28), dp(36), 0, 0));
        row.setClickable(true);
        row.setOnClickListener(v -> runConfigAction(repair));
        parent.addView(row, lp(-1, -2));
    }

    private void runConfigAction(boolean repair) {
        Toast.makeText(this, repair ? "正在尝试修复插件…" : "正在生成本机配置…", Toast.LENGTH_SHORT).show();
        configExecutor.execute(() -> {
            if (repair) LocalConfigManager.clear(this);
            LocalConfigManager.Result result = LocalConfigManager.generate(this, repair);
            runOnUiThread(() -> Toast.makeText(this, result.message(), Toast.LENGTH_LONG).show());
        });
    }

    @Override protected void onDestroy() {
        configExecutor.shutdownNow();
        super.onDestroy();
    }

    private void addDivider(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(Color.rgb(239, 240, 242));
        parent.addView(divider, lp(-1, 1, 0, 0));
        ((LinearLayout.LayoutParams) divider.getLayoutParams()).leftMargin = dp(18);
    }

    private TextView section(String value) { return label(value, 13, SECONDARY, true); }

    private LinearLayout card(float radius) {
        LinearLayout view = column();
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(dp(radius));
        view.setBackground(bg);
        view.setElevation(dp(1));
        return view;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(15), dp(16), dp(15));
        return row;
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return view;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private LinearLayout.LayoutParams lp(int w, int h, int weight, int top) {
        LinearLayout.LayoutParams value = new LinearLayout.LayoutParams(w, h, weight);
        value.topMargin = dp(top);
        return value;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class MiuixToggle extends View {
        interface OnToggleChanged { void onChanged(boolean checked); }
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean checked;
        private OnToggleChanged listener;

        MiuixToggle(Context context) {
            super(context);
            setClickable(true);
            setOnClickListener(v -> {
                checked = !checked;
                invalidate();
                if (listener != null) listener.onChanged(checked);
            });
        }

        void setChecked(boolean value) { checked = value; invalidate(); }
        void setOnToggleChanged(OnToggleChanged value) { listener = value; }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float d = getResources().getDisplayMetrics().density;
            float h = 30 * d;
            float w = 50 * d;
            float left = (getWidth() - w) / 2f;
            float top = (getHeight() - h) / 2f;
            paint.setColor(checked ? ACCENT : Color.rgb(220, 222, 226));
            canvas.drawRoundRect(left, top, left + w, top + h, h / 2, h / 2, paint);
            paint.setColor(Color.WHITE);
            float radius = 11 * d;
            float cx = checked ? left + w - radius - 4 * d : left + radius + 4 * d;
            canvas.drawCircle(cx, top + h / 2, radius, paint);
        }
    }
}
