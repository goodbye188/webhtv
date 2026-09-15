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
 * <p>内核（libmihomo.so）由系统安装时自动解包，启用即起；
 * 仅内置缺失的包（v7a）才动态显示「下载内核」按钮。
 */
public class MihomoSourceDialog {

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final FragmentActivity activity;
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
        enableSwitch = view.findViewById(R.id.enableSwitch);
        statusText = view.findViewById(R.id.statusText);
        downloadButton = view.findViewById(R.id.downloadButton);
        downloadProgress = view.findViewById(R.id.downloadProgress);
        updateButton = view.findViewById(R.id.updateButton);
        testButton = view.findViewById(R.id.autoButton);
        nodesButton = view.findViewById(R.id.nodesButton);
        autoButton = view.findViewById(R.id.autoNodeButton);

        subscriptionInput.setText(Setting.getMihomoSubscription());
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

    /** 刷新状态行：开关级状态 + 节点数（后台拉 ext-ctl，避免卡 UI）。 */
    private void refreshStatus() {
        if (!MihomoManager.isRunning()) {
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
        setBusy(true, "正在更新订阅…");
        EXECUTOR.execute(() -> {
            String err = MihomoManager.updateSubscription(ctx(), url);
            final String[] startErr = { null };
            if (err == null) {
                startErr[0] = MihomoManager.reloadOrStart(ctx());
            }
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (err != null) Notify.show(err);
                else if (startErr[0] != null) Notify.show("订阅已保存，" + startErr[0]);
                else Notify.show(activity.getString(R.string.dialog_mihomo_started));
            });
        });
    }

    /** 全节点测速（ext-ctl /delay）。 */
    private void onTest() {
        setBusy(true, "正在测速…");
        EXECUTOR.execute(() -> {
            ensureRunning();
            int n = MihomoManager.testLatency(ctx());
            MAIN.post(() -> {
                setBusy(false, "");
                refreshStatus();
                if (n < 0) Notify.show(activity.getString(R.string.dialog_mihomo_nodes_empty));
                else Notify.show(activity.getString(R.string.dialog_mihomo_nodes_count, n) + " 测速完成");
            });
        });
    }

    /** 自动选最快节点。 */
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
        List<String> names = new ArrayList<>();
        for (MihomoManager.ProxyInfo p : proxies) names.add(p.name);
        final String[] finalNames = names.toArray(new String[0]);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.dialog_mihomo_nodes)
                .setItems(finalNames, (dlg, which) -> {
                    String name = finalNames[which];
                    if (MihomoManager.selectProxy(ctx(), name, name)) {
                        Notify.show("已选: " + name);
                    } else {
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
        Setting.putMihomoEnabled(enabled);
        if (!enabled) {
            MihomoManager.stop();
            refreshStatus();
            dialog.dismiss();
            return;
        }
        // 开启：确保内核就绪（arm64 .so 系统解包即内置；缺失才走网络下载）
        if (!MihomoManager.isInstalled(ctx())) {
            setBusy(true, "正在准备…");
            EXECUTOR.execute(() -> {
                MihomoManager.ensureKernel(ctx(), null);
                String err = MihomoManager.start(ctx());
                MAIN.post(() -> {
                    setBusy(false, "");
                    refreshStatus();
                    if (err != null) Notify.show(err);
                    else Notify.show(activity.getString(R.string.dialog_mihomo_started));
                    if (dialog != null && dialog.isShowing()) dialog.dismiss();
                });
            });
            return;
        }
        if (!MihomoManager.isRunning()) {
            EXECUTOR.execute(() -> {
                String err = MihomoManager.start(ctx());
                MAIN.post(() -> {
                    refreshStatus();
                    if (err != null) Notify.show(err);
                    else Notify.show(activity.getString(R.string.dialog_mihomo_started));
                });
            });
        }
        dialog.dismiss();
    }

    private static String text(EditText view) {
        return view.getText() == null ? "" : view.getText().toString().trim();
    }
}
