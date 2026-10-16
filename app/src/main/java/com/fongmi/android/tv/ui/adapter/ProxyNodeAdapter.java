package com.fongmi.android.tv.ui.adapter;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterProxyNodeBinding;
import com.fongmi.android.tv.proxy.ProxyNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 代理订阅弹窗的节点列表。延迟单独一栏并按 ProxyNode.getLatencyColor() 上色，
 * 列表本身由 ProxySubscriptionDialog 按延迟升序排好再传进来，这里不排序。
 */
public class ProxyNodeAdapter extends RecyclerView.Adapter<ProxyNodeAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<ProxyNode> mItems = new ArrayList<>();
    private String mSelectedUrl;
    private String mSelectedName;

    public ProxyNodeAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {

        void onClick(ProxyNode node);
    }

    /**
     * selectedUrl / selectedName 分别对应直接可用的节点和要走 mihomo 内核的节点，
     * 两者都传进来比：内核节点的 getUrl() 恒为 ""，只比 url 匹配不上。
     */
    public void setItems(List<ProxyNode> items, String selectedUrl, String selectedName) {
        mItems.clear();
        mItems.addAll(items);
        mSelectedUrl = selectedUrl;
        mSelectedName = selectedName;
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterProxyNodeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ProxyNode node = mItems.get(position);
        holder.binding.selected.setChecked(isSelected(node));
        holder.binding.name.setText(displayName(node));
        holder.binding.latency.setText(node.getLatencyText());
        holder.binding.latency.setTextColor(node.getLatencyColor());
        // 按 ProxyNode 本身点，不传 position：列表可能因重新测速而重排，index 会失效
        holder.itemView.setOnClickListener(view -> listener.onClick(node));
    }

    private boolean isSelected(ProxyNode node) {
        if (mSelectedUrl != null && mSelectedUrl.equals(node.getUrl())) return true;
        return mSelectedName != null && mSelectedName.equals(node.getName());
    }

    /** 名字和协议合成一栏（协议决定要不要走 mihomo 内核，得让用户看见），延迟拆出去单独上色 */
    private String displayName(ProxyNode node) {
        String scheme = node.getScheme();
        return TextUtils.isEmpty(scheme) ? node.getName() : node.getName() + " [" + scheme + "]";
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterProxyNodeBinding binding;

        public ViewHolder(@NonNull AdapterProxyNodeBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}