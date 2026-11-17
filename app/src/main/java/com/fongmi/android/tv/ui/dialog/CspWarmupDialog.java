package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.CspWarmup;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textview.MaterialTextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class CspWarmupDialog {

    private CspWarmupDialog() {
    }

    public static void show(Fragment fragment, Runnable callback) {
        if (fragment == null || !fragment.isAdded()) return;
        show(fragment.requireContext(), fragment.getChildFragmentManager(), callback);
    }

    public static void show(FragmentActivity activity, Runnable callback) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        show(activity, activity.getSupportFragmentManager(), callback);
    }

    private static void show(Context context, FragmentManager manager, Runnable callback) {
        if (manager == null || manager.isStateSaved()) return;
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, null, createPanel(context, manager, callback, holder));
        holder[0].show();
    }

    private static LinearLayout createPanel(Context context, FragmentManager manager, Runnable callback, Dialog[] holder) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MaterialTextView title = new MaterialTextView(context);
        title.setText(R.string.setting_csp_warmup);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        titleParams.rightMargin = dp(context, 16);
        header.addView(title, titleParams);

        MaterialButton enabled = topSwitch(context);
        enabled.setOnClickListener(view -> {
            Setting.putCspWarmup(!Setting.isCspWarmup());
            styleTopSwitch(enabled);
            run(callback);
        });
        header.addView(enabled, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 36)));

        LinearLayout modes = new LinearLayout(context);
        modes.setGravity(Gravity.CENTER_VERTICAL);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modesParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modesParams.topMargin = dp(context, 18);
        root.addView(modes, modesParams);

        MaterialButton defaultButton = modeButton(context, R.string.setting_csp_warmup_default, Setting.getCspWarmupSelectedMode() == Setting.CSP_WARMUP_DEFAULT);
        defaultButton.setOnClickListener(view -> {
            Setting.putCspWarmupMode(Setting.CSP_WARMUP_DEFAULT);
            run(callback);
            if (holder[0] != null) holder[0].dismiss();
        });
        modes.addView(defaultButton, modeParams(context, 0));

        MaterialButton customButton = modeButton(context, R.string.setting_csp_warmup_custom, Setting.getCspWarmupSelectedMode() == Setting.CSP_WARMUP_CUSTOM);
        customButton.setOnClickListener(view -> {
            if (holder[0] != null) holder[0].dismiss();
            App.post(() -> showSitePicker(context, manager, callback), 120);
        });
        modes.addView(customButton, modeParams(context, 8));

        return root;
    }

    private static void showSitePicker(Context context, FragmentManager manager, Runnable callback) {
        if (manager == null || manager.isStateSaved()) return;
        List<Site> sites = warmableSites();
        if (sites.isEmpty()) {
            Notify.show(R.string.setting_csp_warmup_no_site);
            return;
        }
        Dialog[] holder = new Dialog[1];
        holder[0] = LightDialog.create(context, null, createPickerPanel(context, sites, callback, holder));
        holder[0].show();
    }

    /**
     * 专用选站面板：搜索框 + 「全部/已选」分类 + 可勾列表 + 确定/取消。
     * 选择语义与原 ChoiceDialog.showMulti 完全一致（checkedSites 初始化、warmKey 去重、
     * selectedKeys 保存），只是把展示层从通用组件里拆出来支持搜索和分类过滤。
     */
    private static LinearLayout createPickerPanel(Context context, List<Site> sites, Runnable callback, Dialog[] holder) {
        boolean[] checked = checkedSites(sites);
        String[] query = {""};
        int[] tab = {0};                      // 0=全部 1=已选
        Runnable[] refresher = new Runnable[1];

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 18);
        root.setPadding(pad, dp(context, 16), pad, dp(context, 16));

        MaterialTextView title = new MaterialTextView(context);
        title.setText(R.string.setting_csp_warmup_select_site);
        title.setTextColor(Color.parseColor("#202124"));
        title.setTextSize(18);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        EditText search = new EditText(context);
        search.setHint(R.string.setting_csp_warmup_search_hint);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setPadding(dp(context, 4), dp(context, 10), dp(context, 4), dp(context, 6));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                query[0] = s == null ? "" : s.toString().trim();
                if (refresher[0] != null) refresher[0].run();
            }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchParams.topMargin = dp(context, 10);
        root.addView(search, searchParams);

        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton allButton = modeButton(context, R.string.setting_csp_warmup_tab_all, true);
        MaterialButton selectedButton = modeButton(context, R.string.setting_csp_warmup_tab_selected, false);
        allButton.setOnClickListener(view -> {
            tab[0] = 0;
            styleModeButton(allButton, true);
            styleModeButton(selectedButton, false);
            if (refresher[0] != null) refresher[0].run();
        });
        selectedButton.setOnClickListener(view -> {
            tab[0] = 1;
            styleModeButton(selectedButton, true);
            styleModeButton(allButton, false);
            if (refresher[0] != null) refresher[0].run();
        });
        LinearLayout.LayoutParams allParams = new LinearLayout.LayoutParams(0, dp(context, 36), 1);
        LinearLayout.LayoutParams selectedParams = new LinearLayout.LayoutParams(0, dp(context, 36), 1);
        selectedParams.leftMargin = dp(context, 8);
        tabs.addView(allButton, allParams);
        tabs.addView(selectedButton, selectedParams);
        LinearLayout.LayoutParams tabsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tabsParams.topMargin = dp(context, 10);
        root.addView(tabs, tabsParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        MaterialButton[] rows = new MaterialButton[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            MaterialButton row = createPickerRow(context, sites.get(i), i, checked, refresher);
            rows[i] = row;
            list.addView(row);
        }
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 360));
        scrollParams.topMargin = dp(context, 10);
        root.addView(scroll, scrollParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        MaterialButton cancel = modeButton(context, R.string.dialog_negative, false);
        cancel.setOnClickListener(view -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        MaterialButton confirm = modeButton(context, R.string.dialog_positive, true);
        confirm.setOnClickListener(view -> {
            List<String> keys = selectedKeys(sites, checked);
            if (keys.isEmpty()) {
                Notify.show(R.string.setting_csp_warmup_select_required);
                return;
            }
            Setting.putCspWarmupSites(keys);
            Setting.putCspWarmupMode(Setting.CSP_WARMUP_CUSTOM);
            if (holder[0] != null) holder[0].dismiss();
            run(callback);
        });
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(0, dp(context, 40), 1);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(context, 40), 1);
        confirmParams.leftMargin = dp(context, 8);
        actions.addView(cancel, cancelParams);
        actions.addView(confirm, confirmParams);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionsParams.topMargin = dp(context, 12);
        root.addView(actions, actionsParams);

        refresher[0] = () -> refreshPickerRows(rows, sites, checked, query[0], tab[0], confirm);
        refresher[0].run();
        return root;
    }

    private static MaterialButton createPickerRow(Context context, Site site, int index, boolean[] checked, Runnable[] refresher) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextSize(14);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 40));
        button.setMinimumHeight(dp(context, 40));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setCornerRadius(dp(context, 6));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        button.setText(siteLabel(site));
        button.setOnClickListener(view -> {
            checked[index] = !checked[index];
            if (refresher[0] != null) refresher[0].run();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 42));
        params.bottomMargin = dp(context, 6);
        button.setLayoutParams(params);
        return button;
    }

    private static void refreshPickerRows(MaterialButton[] rows, List<Site> sites, boolean[] checked, String query, int tab, MaterialButton confirm) {
        String needle = query.toLowerCase();
        Set<String> taken = new HashSet<>();
        for (int i = 0; checked != null && i < checked.length; i++) {
            if (checked[i]) taken.add(CspWarmup.warmKey(sites.get(i)));
        }
        int selectedCount = 0;
        for (int i = 0; checked != null && i < checked.length; i++) {
            if (checked[i]) selectedCount++;
            Site site = sites.get(i);
            boolean matchQuery = needle.isEmpty() || siteLabel(site).toString().toLowerCase().contains(needle);
            boolean matchTab = tab == 0 || checked[i];
            boolean enabled = checked[i] || !taken.contains(CspWarmup.warmKey(site));
            rows[i].setEnabled(enabled);
            rows[i].setFocusable(enabled);
            rows[i].setFocusableInTouchMode(enabled && Util.isLeanback());
            styleModeButton(rows[i], checked[i]);
            rows[i].setAlpha(enabled ? 1.0f : 0.45f);
            rows[i].setText(checked[i] ? "√ " + siteLabel(site) : siteLabel(site));
            rows[i].setVisibility(matchQuery && matchTab ? View.VISIBLE : View.GONE);
        }
        confirm.setText((selectedCount > 0 ? confirm.getContext().getString(R.string.dialog_positive) + "(" + selectedCount + ")" : confirm.getContext().getString(R.string.dialog_positive)));
    }

    private static List<Site> warmableSites() {
        List<Site> sites = new ArrayList<>();
        for (Site site : VodConfig.get().getSites()) if (CspWarmup.isWarmable(site)) sites.add(site);
        return sites;
    }

    private static boolean[] checkedSites(List<Site> sites) {
        Set<String> keys = new LinkedHashSet<>(Setting.getCspWarmupSites());
        Set<String> warmKeys = new HashSet<>();
        boolean[] checked = new boolean[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            Site site = sites.get(i);
            if (!keys.contains(site.getKey())) continue;
            if (!warmKeys.add(CspWarmup.warmKey(site))) continue;
            checked[i] = true;
        }
        return checked;
    }

    private static List<String> selectedKeys(List<Site> sites, boolean[] checked) {
        List<String> keys = new ArrayList<>();
        Set<String> warmKeys = new HashSet<>();
        for (int i = 0; checked != null && i < checked.length && i < sites.size(); i++) {
            Site site = sites.get(i);
            if (!checked[i] || !warmKeys.add(CspWarmup.warmKey(site))) continue;
            keys.add(site.getKey());
        }
        return keys;
    }

    private static CharSequence siteLabel(Site site) {
        String name = site.getDisplayName();
        return TextUtils.isEmpty(site.getApi()) ? name : name + " · " + site.getApi();
    }

    private static void run(Runnable callback) {
        if (callback != null) callback.run();
    }

    private static MaterialButton topSwitch(Context context) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setMinWidth(dp(context, 84));
        button.setMinimumWidth(dp(context, 84));
        button.setMinHeight(dp(context, 36));
        button.setMinimumHeight(dp(context, 36));
        button.setPadding(dp(context, 10), 0, dp(context, 10), 0);
        button.setCornerRadius(dp(context, 6));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        styleTopSwitch(button);
        return button;
    }

    private static void styleTopSwitch(MaterialButton button) {
        Context context = button.getContext();
        boolean enabled = Setting.isCspWarmup();
        button.setText(enabled ? R.string.setting_enable : R.string.setting_disable);
        button.setAlpha(enabled ? 1.0f : 0.65f);
        button.setTextColor(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_text));
        button.setBackgroundTintList(ContextCompat.getColorStateList(context, R.color.dialog_tonal_button_bg));
        button.setStrokeWidth(0);
    }

    private static MaterialButton modeButton(Context context, int text, boolean selected) {
        MaterialButton button = new MaterialButton(context);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(15);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(context, 40));
        button.setMinimumHeight(dp(context, 40));
        button.setPadding(dp(context, 8), 0, dp(context, 8), 0);
        button.setCornerRadius(dp(context, 6));
        button.setFocusable(true);
        button.setFocusableInTouchMode(Util.isLeanback());
        styleModeButton(button, selected);
        return button;
    }

    private static void styleModeButton(MaterialButton button, boolean selected) {
        Context context = button.getContext();
        ColorStateList bg = ContextCompat.getColorStateList(context, selected ? R.color.dialog_tonal_button_bg : R.color.dialog_outlined_button_bg);
        ColorStateList fg = ContextCompat.getColorStateList(context, selected ? R.color.dialog_tonal_button_text : R.color.dialog_outlined_button_text);
        button.setBackgroundTintList(bg);
        button.setTextColor(fg);
        button.setStrokeColor(ContextCompat.getColorStateList(context, R.color.dialog_outlined_button_stroke));
        button.setStrokeWidth(selected ? 0 : dp(context, 1));
    }

    private static LinearLayout.LayoutParams modeParams(Context context, int marginStart) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(context, 40), 1);
        params.leftMargin = dp(context, marginStart);
        return params;
    }

    private static int dp(Context context, int value) {
        return ResUtil.dp2px(value);
    }
}
