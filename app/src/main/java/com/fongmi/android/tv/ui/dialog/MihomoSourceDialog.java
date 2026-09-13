package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.proxy.MihomoManager;
import com.fongmi.android.tv.setting.Setting;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.fongmi.android.tv.utils.Notify;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 代理内核（Mihomo）配置卡片。
 *
 * <p>照 {@link TmdbSourceDialog} 的卡片模式：订阅地址 + 端口 + 启用开关 +
 * 手动更新/测速/停止。订阅<b>手动</b>刷新（不做内置自动订阅），
 * 启用后本地起 mihomo 内核监听 127.0.0.1:port，
 * 爬虫源声明 proxy 指向该端口的走内核、其余直连。
 */
public class MihomoSourceDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final FragmentActivity activity;
    private AlertDialog dialog;

    private EditText subscriptionInput;
    private EditText portInput;
    private SwitchMaterial enableSwitch;
    private TextView statusText;
    private View updateButton;
    private View testButton;
    private View stopButton;

    public static MihomoSourceDialog create(FragmentActivity activity) {
        return new MihomoSourceDialog(activity);
    }

    private MihomoSourceDialog(FragmentActivity activity) {
        this.activity = activity;
    }

    public void show() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                activity, R.style.Theme_WebHTV_LightDialog);
        Context context = builder.getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_mihomo_source, null);
        subscriptionInput = view.findViewById(R.id.subscriptionInput);
        portInput = view.findViewById(R.id.portInput);
        enableSwitch = view.findViewById(R.id.enableSwitch);
        statusText = view.findViewById(R.id.statusText);
        updateButton = view.findViewById(R.id.updateButton);
        testButton = view.findViewById(R.id.testButton);
        stopButton = view.findViewById(R.id.stopButton);

        subscriptionInput.setText(Setting.getMihomoSubscription());
        portInput.setText(String.valueOf(Setting.getMihomoPort()));
        enableSwitch.setChecked(Setting.isMihomoEnabled());

        updateButton.setOnClickListener(v -> onUpdate());
        testButton.setOnClickListener(v -> onTest());
        stopButton.setOnClickListener(v -> onStop());

        dialog = builder
                .setTitle(R.string.setting_mihomo)
                .setView(view)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> onSave())
                .setNegativeButton(R.string.dialog_negative, null)
                .create();
        dialog.show();
        LightDialog.apply(dialog);
        refreshStatus();
    }

    private Context ctx() {
        return activity.getApplicationContext();
    }

    private void refreshStatus() {
        statusText.setText(MihomoManager.statusText(ctx()));
    }

    private void onUpdate() {
        String url = text(subscriptionInput);
        if (TextUtils.isEmpty(url)) {
            Notify.show(activity.getString(R.string.dialog_mihomo_subscription_hint));
            return;
        }
        setBusy(true, "正在更新订阅…");
        EXECUTOR.execute(() -> {
            String err = MihomoManager.updateSubscription(ctx(), url);
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (err != null) Notify.show(err);
                else Notify.show("订阅已更新");
            });
        });
    }

    private void onTest() {
        setBusy(true, "正在测速…");
        EXECUTOR.execute(() -> {
            int count = MihomoManager.testLatency(ctx());
            MAIN.post(() -> {
                setBusy(false, "");
                if (count < 0) Notify.show("内核未运行");
                else Notify.show("共 " + count + " 个节点");
            });
        });
    }

    private void onStop() {
        MihomoManager.stop();
        refreshStatus();
    }

    private void setBusy(boolean busy, String status) {
        updateButton.setEnabled(!busy);
        testButton.setEnabled(!busy);
        stopButton.setEnabled(!busy);
        if (!TextUtils.isEmpty(status)) statusText.setText(status);
        else refreshStatus();
    }

    private void onSave() {
        String url = text(subscriptionInput);
        int port = parseInt(portInput);
        boolean enabled = enableSwitch.isChecked();
        Setting.putMihomoSubscription(url);
        Setting.putMihomoPort(port);
        if (enabled && !Setting.isMihomoEnabled()) {
            // 首次启用：后台下载内核（如缺失）+ 启动
            EXECUTOR.execute(() -> {
                boolean ok = MihomoManager.ensureBinary(ctx(), null);
                String err = ok ? null : "内核下载未完成";
                if (err == null) err = MihomoManager.start(ctx());
                MAIN.post(() -> {
                    refreshStatus();
                    if (err != null) Notify.show(err);
                });
            });
        } else if (!enabled) {
            MihomoManager.stop();
        }
        Setting.putMihomoEnabled(enabled);
        dialog.dismiss();
    }

    private static String text(EditText view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }

    private static int parseInt(EditText view) {
        try {
            return Integer.parseInt(text(view));
        } catch (NumberFormatException e) {
            return 18890;
        }
    }
}
