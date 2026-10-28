package dev.agentos.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.lang.ref.WeakReference;

import dev.agentos.core.NumericPreferences;

/** One protected, non-restorable document transfer; no configuration or task authority is transported. */
public final class NumericPreferencesTransferActivity extends Activity {
    private static final int REQUEST_DOCUMENT = 1;

    private enum Phase { NEW, PICKER, WAITING_RESUME, IO, PREVIEW, RETURNING }

    private NumericPreferencesTransfers.Owner owner;
    private String nonce;
    private Phase phase = Phase.NEW;
    private boolean resumed, destroyed;
    private Uri stagedUri;
    private NumericPreferences previewPreferences;
    private TextView status, values;
    private Button apply;
    // The infrastructure retains this callback weakly. Its Activity reference is
    // weak as well, so a blocked document provider cannot retain the old window.
    private final NumericPreferencesTransfers.Callback callback = new ReadyCallback(this);

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setDecorFitsSystemWindows(false);
        try { nonce = getIntent().getStringExtra(NumericPreferencesTransfers.EXTRA_NONCE); }
        catch (RuntimeException malformed) { nonce = null; }
        // Even ordinary Back returns our nonce, never the document provider's
        // Intent. Main still verifies its own live owner before consuming it.
        setResult(RESULT_CANCELED, ownResult());
        if (savedInstanceState != null || nonce == null) {
            phase = Phase.RETURNING;
            finish();
            return;
        }
        owner = NumericPreferencesTransfers.claimBroker(nonce);
        if (owner == null) {
            phase = Phase.RETURNING;
            finish();
            return;
        }
        buildInterface();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (phase == Phase.RETURNING || destroyed || isFinishing()) return;
        if (!NumericPreferencesTransfers.isCurrent(owner)) { cancelAndFinish(); return; }
        if (phase == Phase.NEW) openDocumentPicker();
        else if (phase == Phase.WAITING_RESUME) beginIo();
    }

    @Override protected void onPause() {
        resumed = false;
        // Opening the picker necessarily pauses this Activity. Once its result
        // has been consumed, leaving the read/write or preview cancels its use.
        if (phase == Phase.IO || phase == Phase.PREVIEW || phase == Phase.WAITING_RESUME)
            cancelAndFinish();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        stagedUri = null;
        previewPreferences = null;
        // Successful/cancelled results awaiting Main must survive finish's
        // normal destruction. An unexpected destruction revokes this owner.
        if (phase != Phase.RETURNING && owner != null) NumericPreferencesTransfers.revoke(owner);
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        cancelAndFinish();
    }

    private void openDocumentPicker() {
        if (!resumed || phase != Phase.NEW || !NumericPreferencesTransfers.isCurrent(owner)) return;
        boolean importing = owner.mode == NumericPreferencesTransfers.Mode.IMPORT;
        Intent picker = new Intent(importing ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
                .addFlags(importing ? Intent.FLAG_GRANT_READ_URI_PERMISSION : Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (!importing) picker.putExtra(Intent.EXTRA_TITLE, "numeric-preferences.json");
        phase = Phase.PICKER;
        try { startActivityForResult(picker, REQUEST_DOCUMENT); }
        catch (RuntimeException unavailable) { failAndFinish(NumericPreferencesTransfers.Failure.UNAVAILABLE); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOCUMENT || phase != Phase.PICKER || destroyed || isFinishing()) return;
        // Consume this one picker response before examining provider-controlled
        // data. Duplicate responses cannot start another read or write.
        phase = Phase.WAITING_RESUME;
        if (!NumericPreferencesTransfers.isCurrent(owner)) { cancelAndFinish(); return; }
        if (resultCode != RESULT_OK) { cancelAndFinish(); return; }
        Uri selected = null;
        try {
            if (data != null) selected = data.getData();
            if (selected == null || !"content".equals(selected.getScheme())
                    || selected.getAuthority() == null || selected.getAuthority().isEmpty()) {
                failAndFinish(NumericPreferencesTransfers.Failure.INVALID_URI);
                return;
            }
            ClipData clip = data.getClipData();
            if (clip != null) {
                if (clip.getItemCount() != 1) {
                    failAndFinish(NumericPreferencesTransfers.Failure.INVALID_URI);
                    return;
                }
                ClipData.Item item = clip.getItemAt(0);
                if (!selected.equals(item.getUri()) || item.getIntent() != null
                        || item.getText() != null || item.getHtmlText() != null) {
                    failAndFinish(NumericPreferencesTransfers.Failure.INVALID_URI);
                    return;
                }
            }
        } catch (RuntimeException malformed) {
            failAndFinish(NumericPreferencesTransfers.Failure.INVALID_URI);
            return;
        }
        stagedUri = selected;
        // Android may deliver this callback before onResume. No provider access
        // occurs until this exact broker is again in the foreground.
        if (resumed) beginIo();
    }

    private void beginIo() {
        if (!resumed || phase != Phase.WAITING_RESUME || destroyed || isFinishing()) return;
        if (!NumericPreferencesTransfers.isCurrent(owner)) { cancelAndFinish(); return; }
        Uri selected = stagedUri;
        stagedUri = null;
        phase = Phase.IO;
        status.setText(owner.mode == NumericPreferencesTransfers.Mode.IMPORT
                ? "正在读取数值偏好…" : "正在写入数值偏好…");
        if (!NumericPreferencesTransfers.startIo(getApplicationContext(), owner, selected, callback))
            onIoReady(owner);
    }

    private void onIoReady(NumericPreferencesTransfers.Owner completed) {
        if (completed != owner || phase != Phase.IO || destroyed || isFinishing()) return;
        if (!resumed || !NumericPreferencesTransfers.isCurrent(owner)) { cancelAndFinish(); return; }
        NumericPreferencesTransfers.Result result = NumericPreferencesTransfers.peekResult(owner);
        if (result == null) { cancelAndFinish(); return; }
        if (result.outcome != NumericPreferencesTransfers.Outcome.IMPORTED) {
            returnReady();
            return;
        }
        if (result.preferences == null) { failAndFinish(NumericPreferencesTransfers.Failure.INVALID_FILE); return; }
        previewPreferences = result.preferences;
        phase = Phase.PREVIEW;
        status.setText("请核对四项数值。填入后仍需手动保存设置或开始任务才会生效。");
        values.setText("每次回复 Token 上限：" + previewPreferences.maxOutputTokens
                + "\n模型轮次上限：" + previewPreferences.maxModelRounds
                + "\n工具调用上限：" + previewPreferences.maxToolCalls
                + "\n活动时间上限：" + previewPreferences.maxActiveSeconds + " 秒");
        values.setVisibility(View.VISIBLE);
        apply.setVisibility(View.VISIBLE);
    }

    private void applyPreview() {
        if (!resumed || phase != Phase.PREVIEW || previewPreferences == null || destroyed || isFinishing()) return;
        if (!NumericPreferencesTransfers.isCurrent(owner)) { cancelAndFinish(); return; }
        NumericPreferencesTransfers.Result result = NumericPreferencesTransfers.peekResult(owner);
        if (result == null || result.outcome != NumericPreferencesTransfers.Outcome.IMPORTED
                || !previewPreferences.equals(result.preferences)) { cancelAndFinish(); return; }
        returnReady();
    }

    private void returnReady() {
        NumericPreferencesTransfers.Result result = NumericPreferencesTransfers.peekResult(owner);
        if (result == null || !NumericPreferencesTransfers.markReturning(owner)) { cancelAndFinish(); return; }
        // Keep these assignments on the main thread and before finish. Main
        // requires both our result code and the registry's trusted outcome.
        boolean success = result.outcome == NumericPreferencesTransfers.Outcome.IMPORTED
                || result.outcome == NumericPreferencesTransfers.Outcome.EXPORTED;
        setResult(success ? RESULT_OK : RESULT_CANCELED, ownResult());
        phase = Phase.RETURNING;
        stagedUri = null;
        previewPreferences = null;
        finish();
    }

    private void failAndFinish(NumericPreferencesTransfers.Failure failure) {
        if (phase == Phase.RETURNING) return;
        if (owner != null && NumericPreferencesTransfers.failFromBroker(owner, failure)) returnReady();
        else cancelAndFinish();
    }

    private void cancelAndFinish() {
        if (phase == Phase.RETURNING) return;
        if (owner != null) NumericPreferencesTransfers.cancelFromBroker(owner);
        setResult(RESULT_CANCELED, ownResult());
        phase = Phase.RETURNING;
        stagedUri = null;
        previewPreferences = null;
        finish();
    }

    private Intent ownResult() {
        Intent result = new Intent();
        if (nonce != null) result.putExtra(NumericPreferencesTransfers.EXTRA_NONCE, nonce);
        return result;
    }

    private void buildInterface() {
        boolean importing = owner.mode == NumericPreferencesTransfers.Mode.IMPORT;
        setTitle(importing ? "导入数值偏好" : "导出数值偏好");
        ScrollView scroll = new ScrollView(this);
        scroll.setSaveEnabled(false);
        scroll.setSaveFromParentEnabled(false);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.agent_background));
        scroll.setImportantForContentCapture(View.IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS);
        scroll.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(24), dp(24), dp(24));
        body.setSaveEnabled(false);
        body.setSaveFromParentEnabled(false);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        TextView title = text(importing ? "导入数值偏好" : "导出数值偏好", 22, true);
        title.setId(R.id.numeric_preferences_transfer_title);
        title.setAccessibilityHeading(true);
        body.addView(title, layout());
        body.addView(text("仅包含每次回复 Token 上限、模型轮次、工具调用和活动时间四项数值。"
                + "不包含密钥、模型连接、应用授权、执行模式或任务内容，也不是完整配置备份。", 15, false), layout());
        status = text(importing ? "请选择数值偏好文件。读取后会先展示预览。"
                : "请选择保存位置。本次只导出点击时的四项数值，不保存或更改本机配置。", 15, false);
        status.setId(R.id.numeric_preferences_transfer_status);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(status, layout());
        values = text("", 17, false);
        values.setId(R.id.numeric_preferences_transfer_values);
        values.setVisibility(View.GONE);
        body.addView(values, layout());
        apply = button("填入设置，尚未保存", R.id.numeric_preferences_transfer_apply);
        apply.setVisibility(View.GONE);
        apply.setOnClickListener(view -> applyPreview());
        body.addView(apply, layout());
        Button cancel = button("取消", R.id.numeric_preferences_transfer_cancel);
        cancel.setOnClickListener(view -> cancelAndFinish());
        body.addView(cancel, layout());
        setContentView(scroll);
    }

    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(getColor(R.color.agent_text));
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button button(String label, int id) {
        Button view = new Button(this);
        view.setId(id);
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setText(label);
        view.setTextSize(16);
        view.setAllCaps(false);
        view.setMinHeight(dp(48));
        return view;
    }

    private LinearLayout.LayoutParams layout() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        return params;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ReadyCallback implements NumericPreferencesTransfers.Callback {
        private final WeakReference<NumericPreferencesTransferActivity> activity;
        ReadyCallback(NumericPreferencesTransferActivity activity) { this.activity = new WeakReference<>(activity); }
        @Override public void onReady(NumericPreferencesTransfers.Owner owner) {
            NumericPreferencesTransferActivity current = activity.get();
            if (current != null) current.onIoReady(owner);
        }
    }
}
