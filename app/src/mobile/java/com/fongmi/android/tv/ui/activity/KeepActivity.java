package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.databinding.ActivityKeepBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.adapter.KeepAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.KeepGroupsDialog;
import com.fongmi.android.tv.ui.dialog.SyncDialog;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class KeepActivity extends BaseActivity implements KeepAdapter.OnClickListener {

    private ActivityKeepBinding mBinding;
    private KeepAdapter mAdapter;
    private List<Keep> mAllKeeps = new ArrayList<>();
    private List<Keep> mShownKeeps = new ArrayList<>();
    private String mQuery = "";
    private String mGroup = "";

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, KeepActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityKeepBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        setRecyclerView();
        setupSearch();
        setupGroupTabs();
        getKeep();
    }

    private void setupSearch() {
        mBinding.searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim();
                mBinding.searchClear.setVisibility(mQuery.isEmpty() ? View.GONE : View.VISIBLE);
                applyFilter();
            }
        });
        mBinding.searchClear.setOnClickListener(v -> mBinding.searchInput.setText(""));
    }

    private void setupGroupTabs() {
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.group_all));
        names.addAll(KeepGroupsDialog.getGroups());
        LinearLayout container = mBinding.groupTabs;
        container.removeAllViews();
        android.content.res.ColorStateList tabText = androidx.core.content.ContextCompat.getColorStateList(this, R.color.selector_keep_group_tab_text);
        for (String name : names) {
            TextView tab = new TextView(this);
            tab.setText(name);
            tab.setTextSize(15);
            int padH = (int) (14 * getResources().getDisplayMetrics().density);
            int padV = (int) (8 * getResources().getDisplayMetrics().density);
            tab.setPadding(padH, padV, padH, padV);
            tab.setBackground(ContextCompat.getDrawable(this, R.drawable.shape_keep_group_tab));
            tab.setTextColor(tabText);
            String key = name.equals(getString(R.string.group_all)) ? "" : name;
            tab.setOnClickListener(v -> {
                mGroup = key;
                updateTabSelected(container, key);
                applyFilter();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.rightMargin = (int) (6 * getResources().getDisplayMetrics().density);
            container.addView(tab, params);
        }
        updateTabSelected(container, mGroup);
    }

    private void updateTabSelected(LinearLayout container, String selected) {
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            String label = ((TextView) child).getText().toString();
            child.setSelected(label.equals(getString(R.string.group_all)) ? selected.isEmpty() : selected.equals(label));
        }
    }

    private void applyFilter() {
        List<Keep> result = new ArrayList<>();
        for (Keep item : mAllKeeps) {
            if (!mGroup.isEmpty() && !mGroup.equals(safeGroup(item))) continue;
            if (!mQuery.isEmpty() && (item.getVodName() == null || !item.getVodName().toLowerCase().contains(mQuery.toLowerCase()))) continue;
            result.add(item);
        }
        mShownKeeps = result;
        showCurrent();
    }

    private static String safeGroup(Keep item) {
        String group = item.getGroup();
        return group == null ? "" : group;
    }

    private void showCurrent() {
        mAdapter.setItems(mShownKeeps, () -> {
            mBinding.progressLayout.showContent(true, mAdapter.getItemCount());
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    private void setRecyclerView() {
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setLayoutManager(new GridLayoutManager(this, Product.getColumn(this)));
        mBinding.recycler.setAdapter(mAdapter = new KeepAdapter(this));
        mBinding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                recyclerView.post(() -> {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) updateMarquee();
                    else mAdapter.setMarqueeRange(RecyclerView.NO_POSITION, RecyclerView.NO_POSITION);
                });
            }
        });
        mAdapter.setSize(Product.getSpec(this));
    }

    private void getKeep() {
        mAllKeeps = new ArrayList<>(Keep.getVod());
        if (!mGroup.isEmpty() && !KeepGroupsDialog.getGroups().contains(mGroup)) mGroup = "";
        setupGroupTabs();
        applyFilter();
    }

    private void updateMarquee() {
        if (mBinding.recycler.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            mAdapter.setMarqueeRange(RecyclerView.NO_POSITION, RecyclerView.NO_POSITION);
            return;
        }
        int[] range = findMarqueeRange(mBinding.recycler);
        mAdapter.setMarqueeRange(range[0], range[1]);
    }

    private int[] findMarqueeRange(RecyclerView recyclerView) {
        int first = RecyclerView.NO_POSITION;
        int last = RecyclerView.NO_POSITION;
        int top = recyclerView.getPaddingTop();
        int bottom = recyclerView.getHeight() - recyclerView.getPaddingBottom();
        for (int i = 0; i < recyclerView.getChildCount(); i++) {
            View child = recyclerView.getChildAt(i);
            View info = child.findViewById(R.id.history_info);
            int infoTop = child.getTop() + info.getTop();
            int infoBottom = child.getTop() + info.getBottom();
            if (infoBottom <= top || infoTop >= bottom) continue;
            int position = recyclerView.getChildAdapterPosition(child);
            if (position == RecyclerView.NO_POSITION) continue;
            first = first == RecyclerView.NO_POSITION ? position : Math.min(first, position);
            last = Math.max(last, position);
        }
        return new int[]{first, last};
    }

    private void onSync() {
        SyncDialog.create().keep().show(this);
    }

    private void onDelete() {
        if (mAdapter.isDelete()) {
            new MaterialAlertDialogBuilder(this).setTitle(R.string.dialog_delete_record).setMessage(R.string.dialog_delete_keep).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> mAdapter.clear()).show();
        } else if (mAdapter.getItemCount() > 0) {
            mAdapter.setDelete(true);
        }
    }

    private void loadConfig(Config config, Keep item) {
        VodConfig.load(config, new Callback() {
            @Override
            public void success() {
                VideoActivity.start(getActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType().equals(RefreshEvent.Type.KEEP)) getKeep();
    }

    @Override
    public void onItemClick(Keep item) {
        Config config = Config.find(item.getCid());
        if (config == null) SearchActivity.start(this, item.getVodName());
        else if (item.getCid() != VodConfig.getCid()) loadConfig(config, item);
        else VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(Keep item) {
        mAdapter.remove(item.delete(), () -> {
            if (mAdapter.getItemCount() == 0) mAdapter.setDelete(false);
            mBinding.recycler.post(this::updateMarquee);
        });
    }

    @Override
    public boolean onLongClick(Keep item) {
        showGroupMenu(item);
        return true;
    }

    private void showGroupMenu(@NonNull final Keep item) {
        List<String> options = new ArrayList<>();
        options.add(getString(R.string.group_ungrouped));
        options.addAll(KeepGroupsDialog.getGroups());

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.group_move_to)
                .setItems(options.toArray(new CharSequence[0]), (dialog, which) -> {
                    String target = which == 0 ? "" : options.get(which);
                    KeepGroupsDialog.moveKeepToGroup(item, target, () -> getKeep());
                })
                .setNeutralButton(R.string.menu_group, (dialog, which) -> KeepGroupsDialog.show(this))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_keep, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) onBackInvoked();
        else if (item.getItemId() == R.id.delete) onDelete();
        else if (item.getItemId() == R.id.sync) onSync();
        else if (item.getItemId() == R.id.group) KeepGroupsDialog.show(this);
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (mAdapter.isDelete()) mAdapter.setDelete(false);
        else super.onBackInvoked();
    }
}
