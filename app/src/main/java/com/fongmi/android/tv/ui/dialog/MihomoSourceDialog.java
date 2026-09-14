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

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.List;
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
    private View downloadButton;
    private View downloadProgress;
    private View updateButton;
    private View autoButton;
    private View nodesButton;
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
        downloadButton = view.findViewById(R.id.downloadButton);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        updateButton = view.findViewById(R.id.updateButton);
        autoButton = view.findViewById(R.id.autoButton);
        nodesButton = view.findViewById(R.id.nodesButton);
        stopButton = view.findViewById(R.id.stopButton);

        subscriptionInput.setText(Setting.getMihomoSubscription());
        portInput.setText(String.valueOf(Setting.getMihomoPort()));
        enableSwitch.setChecked(Setting.isMihomoEnabled());

        downloadButton.setOnClickListener(v -> onDownload());
        updateButton.setOnClickListener(v -> onUpdate());
        autoButton.setOnClickListener(v -> onAuto());
        nodesButton.setOnClickListener(v -> onNodes());
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

    private void onDownload() {
        if (MihomoManager.isInstalled(ctx())) {
            Notify.show("内核已下载");
            refreshStatus();
            return;
        }
        setBusy(true, "正在下载内核…");
        showProgress(true);
        EXECUTOR.execute(() -> {
            String err = MihomoManager.downloadKernel(ctx(), f -> {
                int p = Math.round(f * 100);
                MAIN.post(() -> {
                    if (downloadProgress != null) {
                        downloadProgress.setProgress(p);
                        statusText.setText("正在下载内核 " + p + "%");
                    }
                });
            });
            MAIN.post(() -> {
                setBusy(false, "");
                showProgress(false);
                refreshStatus();
                if (err != null) Notify.show(err);
                else Notify.show("内核已下载完成");
            });
        });
    }

    private void showProgress(boolean show) {
        if (downloadProgress != null) downloadProgress.setVisibility(show ? View.VISIBLE : View.GONE);
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

    private void onAuto() {
        if (!MihomoManager.isRunning()) {
            Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
            return;
        }
        setBusy(true, "正在自动测速选节点…");
        EXECUTOR.execute(() -> {
            String node = MihomoManager.autoSelect(ctx(), name -> {
                MAIN.post(() -> statusText.setText("测速中: " + name));
            });
            MAIN.post(() -> {
                setBusy(false, "");
                if (node.isEmpty()) Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
                else Notify.show(activity.getString(R.string.dialog_mihomo_auto_done, node));
                refreshStatus();
            });
        });
    }

    private void onNodes() {
        if (!MihomoManager.isRunning()) {
            Notify.show(activity.getString(R.string.dialog_mihomo_nodes_empty));
            return;
        }
        List<MihomoManager.ProxyInfo> proxies = MihomoManager.listProxies(ctx());
        if (proxies == null || proxies.isEmpty()) {
            Notify.show(activity.getString(R.string.dialog_mihomo_nodes_empty));
            return;
        }
        // 列表: 组在前(可整组切换), 节点在后(可手动单选)。点节点 = 选它所在的组 + 它
        StringBuilder items = new StringBuilder();
        List<String> names = new ArrayList<>();
        for (MihomoManager.ProxyInfo p : proxies) {
            String label = p.name + (p.isGroup ? "  ⚙" : "");
            items.append(label).append('\n');
            names.add(p.name);
        }
        final String[] finalNames = names.toArray(new String[0]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_mihomo_nodes)
                .setItems(finalNames, (dialog, which) -> {
                    String name = finalNames[which];
                    // 点组: 把该组设为当前出口(组默认选最优子节点的行为由 mihomo 内部策略定)
                    // 点节点: 需要知道它所在组 —— 简化: 先试直接选它(mihomo 对单节点 PUT 无效时回退组)
                    if (MihomoManager.selectProxy(ctx(), name, name)) {
                        Notify.show("已选: " + name);
                    } else {
                        // 尝试遍历所有组找到包含该节点的组
                        List<MihomoManager.ProxyInfo> all = MihomoManager.listProxies(ctx());
                        boolean found = false;
                        if (all != null) {
                            for (MihomoManager.ProxyInfo g : all) {
                                if (!g.isGroup || g.name.equals(name)) continue;
                                if (MihomoManager.selectProxy(ctx(), g.name, name)) {
                                    Notify.show("已选 " + g.name + " → " + name);
                                    found = true;
                                    break;
                                }
                            }
                        }
                        if (!found) Notify.show("选择失败: " + name);
                    }
                    dialog.dismiss();
                    refreshStatus();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void onStop() {
        MihomoManager.stop();
        refreshStatus();
    }

    private void setBusy(boolean busy, String status) {
        downloadButton.setEnabled(!busy);
        updateButton.setEnabled(!busy);
        autoButton.setEnabled(!busy);
        nodesButton.setEnabled(!busy);
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
        Setting.putMihomoEnabled(enabled);
        if (!enabled) {
            // 关闭：只停进程，别的都不碰
            MihomoManager.stop();
            refreshStatus();
            dialog.dismiss();
            return;
        }
        // 开启：纯粹只启停内核。没内核就提示去点「下载内核」，不自动下载、不碰订阅。
        if (!MihomoManager.isInstalled(ctx())) {
            Notify.show("内核未下载，请先点「下载内核」");
            refreshStatus();
            dialog.dismiss();
            return;
        }
        if (!MihomoManager.isRunning()) {
            EXECUTOR.execute(() -> {
                String err = MihomoManager.start(ctx());
                MAIN.post(() -> {
                    refreshStatus();
                    if (err != null) Notify.show(err);
                    else Notify.show("代理内核已启动");
                });
            });
        }
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
