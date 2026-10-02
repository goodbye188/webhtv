package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionBinding;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

/**
 * 代理订阅弹窗。照抄星落 ProxySubscriptionDialog 的全部业务逻辑，
 * 适配 AVstar 的 BaseAlertDialog 弹窗模板（getBinding/getBuilder/onStart/initView/initEvent）。
 * 布局 dialog_proxy_subscription.xml 照抄星落。
 */
public class ProxySubscriptionDialog extends BaseAlertDialog {

    private DialogProxySubscriptionBinding binding;
    private Runnable callback;

    public static ProxySubscriptionDialog create() {
        return new ProxySubscriptionDialog();
    }

    public static void show(Fragment fragment, Runnable callback) {
        if (fragment == null) return;
        for (androidx.fragment.app.Fragment child : fragment.getChildFragmentManager().getFragments()) {
            if (child != null) return;
        }
        create().callback(callback).show(fragment.getChildFragmentManager(), "proxy-subscription");
    }

    public ProxySubscriptionDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProxySubscriptionBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return new MaterialAlertDialogBuilder(requireActivity(), R.style.ThemeOverlay_WebHTV_LightDialog)
                .setView(getBinding().getRoot());
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog == null) return;
        Window window = dialog.getWindow();
        if (window == null) return;
        int width = ResUtil.getScreenWidth(requireContext());
        int height = ResUtil.getScreenHeight(requireContext());
        boolean land = ResUtil.isLand(requireContext());
        WindowManager.LayoutParams params = window.getAttributes();
        // 窗口背景透明（弹窗内容靠布局的 shape_shell_proxy_dialog 白色背景）
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.getDecorView().setPadding(0, 0, 0, 0);
        params.width = (int) (width * (land ? 0.62f : 0.94f));
        // 固定高度比例，保证底部 4 个按钮完整显示（WRAP_CONTENT 会因内容高被挤出屏幕）
        params.height = (int) (height * (land ? 0.9f : 0.6f));
        window.setAttributes(params);
        window.setLayout(params.width, params.height);
    }

    @Override
    protected void initView() {
        binding.url.setText(Setting.getProxySubscriptionUrl());
        binding.enable.setChecked(Setting.isProxySubscriptionEnabled());
        setStatus();
    }

    @Override
    protected void initEvent() {
        binding.enable.setOnCheckedChangeListener((buttonView, isChecked) -> {
            Setting.putProxySubscriptionEnabled(isChecked);
            if (isChecked) ProxySubscriptionManager.get().applySaved();
            else ProxySubscriptionManager.get().disable();
            setStatus();
            notifyChanged();
        });
        binding.update.setOnClickListener(this::onUpdate);
        binding.test.setOnClickListener(this::onTest);
        binding.auto.setOnClickListener(this::onAuto);
        binding.nodes.setOnClickListener(this::onNodes);
    }

    /**
     * 后台线程回主线程更新 UI。
     * 不能用 requireActivity().runOnUiThread()：Task.execute 的回调可能在弹窗已 dismiss 后才跑，
     * 此时 fragment 已 detach，requireActivity() 抛 IllegalStateException 直接崩壳
     * （堆栈 lambda$onUpdate$3 → requireActivity）。这里用主线程 Handler（与 fragment 生命周期无关）+ isAdded 兜底。
     */
    private void postUi(Runnable action) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Notify.dismiss();
            if (!isAdded()) return;
            try {
                action.run();
            } catch (Throwable ignored) {
            }
        });
    }

    private void onUpdate(View view) {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.proxy_sub_empty);
            return;
        }
        Setting.putProxySubscriptionUrl(url);
        Notify.progress(requireActivity());
        Task.execute(() -> {
            try {
                ProxySubscriptionManager.get().refresh(url);
                postUi(() -> {
                    setStatus();
                    notifyChanged();
                });
            } catch (Throwable e) {
                postUi(() -> showErrorDialog(getString(R.string.proxy_sub_fail), e.getMessage()));
            }
        });
    }

    private void onTest(View view) {
        if (ProxySubscriptionManager.get().isTesting()) {
            Notify.show("测速中...");
            return;
        }
        if (!ProxySubscriptionManager.get().hasNodes()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        ProxySubscriptionManager.get().setProgressCallback(this::setStatus);
        binding.test.setEnabled(false);
        binding.test.setText("测速中...");
        Notify.progress(requireActivity());
        setStatus();
        Task.execute(() -> {
            try {
                ProxySubscriptionManager.get().testAll();
                postUi(() -> {
                    ProxySubscriptionManager.get().setProgressCallback(null);
                    binding.test.setEnabled(true);
                    binding.test.setText(getString(R.string.proxy_sub_test));
                    setStatus();
                    notifyChanged();
                });
            } catch (Throwable e) {
                postUi(() -> {
                    ProxySubscriptionManager.get().setProgressCallback(null);
                    binding.test.setEnabled(true);
                    binding.test.setText(getString(R.string.proxy_sub_test));
                    setStatus();
                });
            }
        });
    }

    private void onAuto(View view) {
        List<ProxyNode> nodes = ProxySubscriptionManager.get().getNodes();
        if (nodes.isEmpty()) {
            onUpdate(view);
            return;
        }
        Notify.progress(requireActivity());
        Task.execute(() -> {
            try {
                ProxySubscriptionManager.get().autoSelect();
                postUi(() -> {
                    setStatus();
                    notifyChanged();
                });
            } catch (Throwable e) {
                postUi(() -> setStatus());
            }
        });
    }

    private void onNodes(View view) {
        List<ProxyNode> nodes = ProxySubscriptionManager.get().getNodes();
        if (nodes.isEmpty()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        showNodeList(nodes);
    }

    private void showNodeList(List<ProxyNode> nodes) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireActivity(), android.R.layout.simple_list_item_single_choice,
                nodes.stream().map(ProxyNode::getDisplay).toArray(String[]::new));
        int selected = getSelectedIndex(nodes);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.proxy_sub_nodes)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(adapter, selected, (dialog, which) -> selectNode(dialog, nodes, which))
                .show();
    }

    private void selectNode(DialogInterface dialog, List<ProxyNode> nodes, int index) {
        ProxyNode node = nodes.get(index);
        dialog.dismiss();
        Notify.progress(requireActivity());
        Task.execute(() -> {
            try {
                boolean ok = ProxySubscriptionManager.get().select(node);
                postUi(() -> {
                    if (ok) {
                        Notify.show("已选择节点: " + node.getName());
                    } else {
                        // 内核起不来：弹窗展示 lastError + 内核日志最后几行（Toast 太短看不到原因）
                        String err = com.fongmi.android.tv.proxy.MihomoManager.get().getLastError();
                        if (TextUtils.isEmpty(err)) err = "内核启动失败（原因未知）";
                        showErrorDialog("内核启动失败", err + "\n\n" + kernelLogTail());
                    }
                    setStatus();
                    notifyChanged();
                });
            } catch (Throwable e) {
                postUi(() -> {
                    Notify.show("选择节点出错: " + e.getMessage());
                    setStatus();
                });
            }
        });
    }

    private ProxyNode getFastest(List<ProxyNode> nodes) {
        ProxyNode best = null;
        for (ProxyNode node : nodes) {
            if (node.getLatency() <= 0) continue;
            if (best == null || node.getLatency() < best.getLatency()) best = node;
        }
        return best;
    }

    private int getSelectedIndex(List<ProxyNode> nodes) {
        String selected = Setting.getProxySubscriptionSelected();
        String coreName = Setting.getProxySubscriptionCoreName();
        for (int i = 0; i < nodes.size(); i++) {
            if (selected.equals(nodes.get(i).getUrl())) return i;
        }
        for (int i = 0; i < nodes.size(); i++) {
            if (!TextUtils.isEmpty(coreName) && coreName.equals(nodes.get(i).getName())) return i;
        }
        return 0;
    }

    /** 内核日志最后 8 行，用于失败弹窗（logBuffer 是 200 行环形缓冲，取尾部） */
    private String kernelLogTail() {
        String log = com.fongmi.android.tv.proxy.MihomoManager.get().getLog();
        if (TextUtils.isEmpty(log)) return "(内核无日志)";
        String[] lines = log.split("\n");
        int from = Math.max(0, lines.length - 8);
        StringBuilder sb = new StringBuilder("内核日志:\n");
        for (int i = from; i < lines.length; i++) sb.append(lines[i]).append('\n');
        return sb.toString();
    }

    /**
     * 错误弹窗：不自动消失（必须点「关闭」或「复制日志」）+ 带复制按钮。
     * 内核日志不截断——之前砍到 200 字会把真正的报错（通常在最后几行）截掉。
     */
    private void showErrorDialog(String title, String message) {
        if (!isAdded()) return;
        if (TextUtils.isEmpty(message)) message = "未知错误";
        final String log = message;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setMessage(log)
                .setPositiveButton("复制日志", (dialog, which) -> copyLog(log))
                .setNegativeButton("关闭", null)
                .show();
    }

    private void copyLog(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) requireActivity().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("proxy-log", text));
        } catch (Throwable ignored) {
        }
    }

    private void setStatus() {
        ProxySubscriptionManager manager = ProxySubscriptionManager.get();
        List<ProxyNode> nodes = manager.getNodes();
        int tested = manager.getTestedCount();
        int total = nodes.size();
        if (manager.isTesting() && tested < total) {
            binding.status.setText("共 " + total + " 个节点 · 测试中 " + tested + "/" + total);
        } else {
            long reachable = nodes.stream().filter(node -> node.getLatency() > 0).count();
            binding.status.setText(getString(R.string.proxy_sub_status, total, (int) reachable));
        }
        binding.hint.setVisibility(hasCoreNodes(nodes) ? View.VISIBLE : View.GONE);
        if (hasCoreNodes(nodes)) binding.hint.setText(getString(R.string.proxy_sub_need_core));
        else binding.hint.setText(getString(R.string.proxy_sub_hint));
    }

    private boolean hasCoreNodes(List<ProxyNode> nodes) {
        for (ProxyNode node : nodes) {
            if (node.needsCore()) return true;
        }
        return false;
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }
}