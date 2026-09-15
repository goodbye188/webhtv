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
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.fongmi.android.tv.utils.Notify;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 代理订阅配置卡片（对齐星落：设置页「代理订阅 关/开」，弹窗只露
 * 订阅地址 + 启用开关 + 更新/测速/节点/自动 四个按钮，不出现"内核"术语）。
 *
 * <p>内核（mihomo ELF）首次启用时下载到 filesDir/proxy/mihomo；
 * 内核未就绪时动态显示「下载内核」按钮。
 */
public class MihomoSourceDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final FragmentActivity activity;
    private final Runnable onStateChanged;
    private AlertDialog dialog;

    private EditText subscriptionInput;
    private SwitchMaterial enableSwitch;
    private TextView statusText;
    private View downloadButton;
    private LinearProgressIndicator downloadProgress;
    private View updateButton;
    private View testButton;
    private View nodesButton;
    private View autoButton;

    public static MihomoSourceDialog create(FragmentActivity activity) {
        return new MihomoSourceDialog(activity, null);
    }

    public static MihomoSourceDialog create(FragmentActivity activity, Runnable onStateChanged) {
        return new MihomoSourceDialog(activity, onStateChanged);
    }

    private MihomoSourceDialog(FragmentActivity activity, Runnable onStateChanged) {
        this.activity = activity;
        this.onStateChanged = onStateChanged;
    }

    public void show() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(
                activity, R.style.Theme_WebHTV_LightDialog);
        Context context = builder.getContext();
        View view = LayoutInflater.from(context).inflate(R.layout.dialog_mihomo_source, null);
        subscriptionInput = view.findViewById(R.id.subscriptionInput);
        enableSwitch = view.findViewById(R.id.enableSwitch);
        statusText = view.findViewById(R.id.statusText);
        downloadButton = view.findViewById(R.id.downloadButton);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        updateButton = view.findViewById(R.id.updateButton);
        testButton = view.findViewById(R.id.autoButton);
        nodesButton = view.findViewById(R.id.nodesButton);
        autoButton = view.findViewById(R.id.autoNodeButton);

        subscriptionInput.setText(Setting.getMihomoSubscription());
        // 开关表示用户保存的“启用代理订阅”设置，不跟 mihomo 的瞬时运行状态绑定。
        enableSwitch.setChecked(Setting.isMihomoEnabled());

        // 内置缺失（v7a）才露出下载按钮；arm64 包 .so 系统解包后即内置，不显示
        downloadButton.setVisibility(MihomoManager.isInstalled(ctx()) ? View.GONE : View.VISIBLE);

        downloadButton.setOnClickListener(v -> onDownload());
        updateButton.setOnClickListener(v -> onUpdate());
        testButton.setOnClickListener(v -> onTest());
        nodesButton.setOnClickListener(v -> onNodes());
        autoButton.setOnClickListener(v -> onAuto());

        dialog = builder
                .setTitle(R.string.dialog_mihomo_title)
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

    /** 刷新状态行：内核真实运行状态 + 节点数（后台拉 ext-ctl，避免卡 UI）。 */
    private void refreshStatus() {
        boolean running = MihomoManager.isRunning(ctx());
        if (!running) {
            statusText.setText(activity.getString(R.string.dialog_mihomo_status_idle));
            return;
        }
        EXECUTOR.execute(() -> {
            int n = MihomoManager.nodeCount(ctx());
            MAIN.post(() -> {
                if (dialog == null || !dialog.isShowing()) return;
                if (n > 0) statusText.setText(activity.getString(R.string.dialog_mihomo_nodes_count, n));
                else statusText.setText(activity.getString(R.string.dialog_mihomo_nodes_empty));
            });
        });
    }

    // ---------------------------------------------------------------- actions

    private void onDownload() {
        if (MihomoManager.isInstalled(ctx())) {
            Notify.show(activity.getString(R.string.dialog_mihomo_started));
            refreshStatus();
            return;
        }
        setBusy(true, "正在准备…");
        showProgress(true);
        EXECUTOR.execute(() -> {
            String err = MihomoManager.ensureKernel(ctx(), p -> {
                MAIN.post(() -> {
                    if (downloadProgress == null) return;
                    if (p.totalKnown()) {
                        int pct = Math.round(p.fraction() * 100);
                        downloadProgress.setProgress(pct);
                    }
                });
            });
            MAIN.post(() -> {
                setBusy(false, "");
                showProgress(false);
                refreshStatus();
                if (err != null) Notify.show(err);
                else Notify.show(activity.getString(R.string.dialog_mihomo_started));
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

        // 更新订阅只更新配置，不改变“启用代理订阅”的用户设置。
        // 如果当前本来就是启用状态，则更新后重载/启动内核；如果本来关闭，只保存订阅。
        final boolean enabled = enableSwitch.isChecked();
        setBusy(true, "正在更新订阅…");
        EXECUTOR.execute(() -> {
            String err = MihomoManager.updateSubscription(ctx(), url);
            String startErr = null;
            if (err == null && enabled) {
                startErr = MihomoManager.reloadOrStart(ctx());
            }
            final String finalStartErr = startErr;
            MAIN.post(() -> {
                setBusy(false, "");
                if (err != null) {
                    Notify.show(err);
                } else if (!enabled) {
                    // 保持关闭状态，不因“更新订阅”偷偷启动代理。
                    Setting.putMihomoSubscription(url);
                    enableSwitch.setChecked(false);
                    if (onStateChanged != null) onStateChanged.run();
                    Notify.show("订阅已保存");
                } else if (finalStartErr != null) {
                    // 启用状态仍然保留；这里只报告内核启动/重载失败。
                    Setting.putMihomoSubscription(url);
                    Setting.putMihomoEnabled(true);
                    enableSwitch.setChecked(true);
                    if (onStateChanged != null) onStateChanged.run();
                    Notify.show("订阅已保存，代理启动/重载失败：" + finalStartErr);
                } else {
                    Setting.putMihomoSubscription(url);
                    Setting.putMihomoEnabled(true);
                    enableSwitch.setChecked(true);
                    if (onStateChanged != null) onStateChanged.run();
                    Notify.show(activity.getString(R.string.dialog_mihomo_started));
                }
                refreshStatus();
            });
        });
    }

    /** 全节点真测速（并行对每个叶子节点做真实握手延迟）。 */
    private void onTest() {
        setBusy(true, "正在测速…");
        EXECUTOR.execute(() -> {
            ensureRunning();
            int ok = MihomoManager.testLatencyAll(ctx(), s -> {
                MAIN.post(() -> statusText.setText(s));
            });
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (ok <= 0) Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
                else Notify.show(activity.getString(R.string.dialog_mihomo_nodes_count_tested, ok, ok));
            });
        });
    }

    /** 自动选最快节点（延迟最低；刚点过测速会复用结果，否则现场并行测一遍）。 */
    private void onAuto() {
        setBusy(true, "正在自动测速选节点…");
        EXECUTOR.execute(() -> {
            ensureRunning();
            if (!MihomoManager.isRunning(ctx())) {
                MAIN.post(() -> {
                    setBusy(false, "");
                    Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
                    refreshStatus();
                });
                return;
            }
            String node = MihomoManager.autoSelect(ctx(), name -> {
                MAIN.post(() -> statusText.setText(name));
            });
            MAIN.post(() -> {
                setBusy(false, "");
                if (node.isEmpty()) Notify.show(activity.getString(R.string.dialog_mihomo_auto_fail));
                else Notify.show(activity.getString(R.string.dialog_mihomo_auto_done, node));
                refreshStatus();
            });
        });
    }

    /** 节点列表（点开才拉 ext-ctl）。 */
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

    private void showNodesList(List<MihomoManager.ProxyInfo> proxies) {
        // 只列真实节点（isGroup=false）；PROXY 组由 mihomo 自动生成，用户选节点就 PUT 进 PROXY。
        List<MihomoManager.ProxyInfo> leaves = new ArrayList<>();
        for (MihomoManager.ProxyInfo p : proxies) {
            if (!p.isGroup) leaves.add(p);
        }
        if (leaves.isEmpty()) {
            Notify.show(activity.getString(R.string.dialog_mihomo_nodes_empty));
            return;
        }
        List<String> names = new ArrayList<>();
        for (MihomoManager.ProxyInfo p : leaves) names.add(p.name);
        final String[] finalNames = names.toArray(new String[0]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_mihomo_nodes)
                .setItems(finalNames, (dlg, which) -> {
                    String name = finalNames[which];
                    // 统一经 PROXY 组选：mihomo 的 PUT /proxies/{group} 只接受 group 名；
                    // 节点是叶子，直接 PUT /proxies/{节点名} 必 404 → 旧代码报"选择失败"。
                    if (MihomoManager.selectProxy(ctx(), "PROXY", name)) {
                        Notify.show("已选: " + name);
                    } else {
                        Notify.show("选择失败: " + name);
                    }
                    dlg.dismiss();
                    refreshStatus();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    /** 后台线程调用：没在跑且内核二进制（.so/内置）就绪就拉起。 */
    private void ensureRunning() {
        if (MihomoManager.isRunning()) return;
        if (!MihomoManager.isInstalled(ctx())) return;
        MihomoManager.start(ctx());
    }

    private void setBusy(boolean busy, String status) {
        downloadButton.setEnabled(!busy);
        updateButton.setEnabled(!busy);
        testButton.setEnabled(!busy);
        nodesButton.setEnabled(!busy);
        autoButton.setEnabled(!busy);
        if (!TextUtils.isEmpty(status)) statusText.setText(status);
        else refreshStatus();
    }

    private void onSave() {
        String url = text(subscriptionInput);
        boolean enabled = enableSwitch.isChecked();
        Setting.putMihomoSubscription(url);

        if (!enabled) {
            Setting.putMihomoEnabled(false);
            MihomoManager.stop();
            if (onStateChanged != null) onStateChanged.run();
            refreshStatus();
            dialog.dismiss();
            return;
        }

        // 开启代理：先确认内核文件存在。
        if (!MihomoManager.isInstalled(ctx())) {
            // 用户的“启用”意图仍然保留，避免一次文件缺失把开关永久改成关。
            Setting.putMihomoEnabled(true);
            enableSwitch.setChecked(true);
            if (onStateChanged != null) onStateChanged.run();
            Notify.show(activity.getString(R.string.dialog_mihomo_kernel_missing));
            refreshStatus();
            return;
        }

        // 已经在运行：只保存启用状态。
        if (MihomoManager.isRunning(ctx())) {
            Setting.putMihomoEnabled(true);
            if (onStateChanged != null) onStateChanged.run();
            dialog.dismiss();
            return;
        }

        Setting.putMihomoEnabled(true);
        enableSwitch.setChecked(true);
        if (onStateChanged != null) onStateChanged.run();
        setBusy(true, "正在启动…");
        EXECUTOR.execute(() -> {
            String err = MihomoManager.start(ctx());
            MAIN.post(() -> {
                setBusy(false, "");
                // 启动失败不等于用户关闭了开关；保持启用设置，便于后续重试/自动接管。
                enableSwitch.setChecked(true);
                if (err != null) {
                    Notify.show("代理未启动: " + err);
                } else {
                    Notify.show(activity.getString(R.string.dialog_mihomo_started));
                }
                refreshStatus();
                if (dialog != null && dialog.isShowing()) dialog.dismiss();
            });
        });
    }

    private static String text(EditText view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }
}
