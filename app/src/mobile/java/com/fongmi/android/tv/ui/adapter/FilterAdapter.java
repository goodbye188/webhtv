package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.bean.Filter;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.databinding.AdapterFilterBinding;
import com.fongmi.android.tv.impl.FilterListener;
import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexWrap;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;

import java.util.List;
import java.util.stream.Collectors;

public class FilterAdapter extends RecyclerView.Adapter<FilterAdapter.ViewHolder> {

    private final FilterListener listener;
    private final List<Filter> mItems;

    public FilterAdapter(FilterListener listener, List<Filter> items) {
        this.listener = listener;
        this.mItems = items;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterFilterBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Filter item = mItems.get(position);
        FlexboxLayoutManager layoutManager = new FlexboxLayoutManager(holder.binding.recycler.getContext());
        layoutManager.setFlexDirection(FlexDirection.ROW);
        layoutManager.setFlexWrap(FlexWrap.WRAP);
        layoutManager.setJustifyContent(JustifyContent.FLEX_START);
        holder.binding.recycler.setLayoutManager(layoutManager);
        holder.binding.recycler.setHasFixedSize(false);
        holder.binding.recycler.setItemAnimator(null);
        holder.binding.recycler.setAdapter(new ValueAdapter(listener, item, () -> notifyItemChanged(position)));
        holder.binding.name.setText(label(item));
    }

    private String label(Filter item) {
        String chosen = item.getValue().stream().filter(Value::isSelected).map(Value::getN).collect(Collectors.joining(" "));
        return chosen.isEmpty() ? item.getName() : item.getName() + " · " + chosen;
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterFilterBinding binding;

        ViewHolder(@NonNull AdapterFilterBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}