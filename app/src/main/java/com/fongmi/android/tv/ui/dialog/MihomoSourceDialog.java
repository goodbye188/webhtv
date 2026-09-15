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
    private com.google.android.material.progressindicator.LinearProgressIndicator downloadProgress;
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
        setBusy(true, "正在准备内核…");
        showProgress(true);
        EXECUTOR.execute(() -> {
            String err = MihomoManager.ensureKernel(ctx(), p -> {
                MAIN.post(() -> {
                    if (downloadProgress == null) return;
                    if (p.totalKnown()) {
                        int pct = Math.round(p.fraction() * 100);
                        downloadProgress.setProgress(pct);
                        statusText.setText("正在准备内核 " + pct + "%");
                    } else {
                        // 内置解压/加速源不报总长：状态文本显示已处理量
                        if (p.bytes() > 0) statusText.setText("正在准备内核 " + String.format(java.util.Locale.US, "%.1f", p.bytes() / 1048576.0) + " MB");
                    }
                });
            });
            MAIN.post(() -> {
                setBusy(false, "");
                showProgress(false);
                refreshStatus();
                if (err != null) Notify.show(err);
                else Notify.show("内核已就绪");
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
            String startErr = null;
            if (err == null) {
                // 订阅写入成功后让内核生效：运行中则重载新 config，没运行则直接拉起
                startErr = MihomoManager.reloadOrStart(ctx());
            }
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (err != null) Notify.show(err);
                else if (startErr != null) Notify.show("订阅已保存，" + startErr);
                else Notify.show("订阅已更新，内核已重载");
            });
        });
    }

    private void onAuto() {
        setBusy(true, "正在自动测速选节点…");
        EXECUTOR.execute(() -> {
            ensureRunning();
            if (!MihomoManager.isRunning()) {
                MAIN.post(() -> {
                    setBusy(false, "");
                    Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
                    refreshStatus();
                });
                return;
            }
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

    /** 确保内核在跑：没跑且内核二进制（或内置资产）就绪就拉起来。后台线程调用。 */
    private void ensureRunning() {
        if (MihomoManager.isRunning()) return;
        if (!MihomoManager.isInstalled(ctx()) && !MihomoManager.hasEmbedded(ctx())) return;
        MihomoManager.ensureKernel(ctx(), null);
        MihomoManager.start(ctx());
    }

    private void onNodes() {
        setBusy(true, "正在读取节点…");
        EXECUTOR.execute(() -> {
            ensureRunning();
            List<MihomoManager.ProxyInfo> proxies = MihomoManager.listProxies(ctx());
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (proxies == null || proxies.isEmpty()) {
                    Notify.show(activity.getString(R.string.dialog_mihomo_nodes_empty));
                    return;
                }
                showNodesList(proxies);
            });
        });
    }

    /** 节点列表弹窗：组在前(可整组切换), 节点在后(可手动单选)。点节点 = 选它所在的组 + 它。 */
    private void showNodesList(List<MihomoManager.ProxyInfo> proxies) {
        List<String> names = new ArrayList<>();
        for (MihomoManager.ProxyInfo p : proxies) {
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
        // 开启：确保内核就绪（内置资产则本地秒解压；都没有才提示走「下载内核」按钮）
        if (!MihomoManager.isInstalled(ctx())) {
            if (!MihomoManager.hasEmbedded(ctx())) {
                Notify.show("内核未下载，请先点「下载内核」");
                refreshStatus();
                dialog.dismiss();
                return;
            }
            // 内置资产：后台解压（快，离线）
            setBusy(true, "正在准备内核…");
            EXECUTOR.execute(() -> {
                MihomoManager.ensureKernel(ctx(), null);
                String err = startIfReady();
                MAIN.post(() -> {
                    setBusy(false, "");
                    refreshStatus();
                    if (err != null) Notify.show(err);
                    else Notify.show("代理内核已启动");
                });
                dialog.dismiss();
            });
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

    /** 内核就绪后启动（未运行才起）。返回启动失败原因；null 表示成功/无需启动。 */
    private String startIfReady() {
        if (!MihomoManager.isInstalled(ctx())) return "内核准备失败，请先点「下载内核」";
        if (MihomoManager.isRunning()) return null;
        return MihomoManager.start(ctx());
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
