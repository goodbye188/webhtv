package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.utils.Prefers;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * 收藏分组管理对话框：新建、重命名（点击）、删除（长按）分组。
 * 分组名保存在 SharedPreferences（JSON 数组）；删除分组时其中的收藏退回"未分组"。
 */
public class KeepGroupsDialog {

    public static final String KEY = "keep_groups";

    /**
     * 全部已知分组 = 已保存分组 ∪ 收藏数据里出现过的分组（防止引用悬空）。
     */
    @NonNull
    public static List<String> getGroups() {
        List<String> groups = new ArrayList<>();
        String json = Prefers.getString(KEY, "");
        if (!TextUtils.isEmpty(json)) {
            String[] saved = App.gson().fromJson(json, String[].class);
            if (saved != null) for (String group : saved) if (!TextUtils.isEmpty(group)) groups.add(group);
        }
        for (Keep keep : Keep.getVod()) {
            String group = keep.getGroup();
            if (!TextUtils.isEmpty(group) && !groups.contains(group)) groups.add(group);
        }
        groups.sort(String.CASE_INSENSITIVE_ORDER);
        return groups;
    }

    public static void putGroups(@NonNull List<String> groups) {
        List<String> clean = new ArrayList<>();
        for (String group : groups) if (!TextUtils.isEmpty(group) && !clean.contains(group)) clean.add(group);
        Prefers.getPrefers().edit().putString(KEY, App.gson().toJson(clean)).commit();
    }

    public static int countOf(@NonNull String group) {
        return AppDatabase.get().getKeepDao().countByGroup(group);
    }

    /** 把某条收藏移入指定分组（"" = 未分组）。 */
    public static void moveKeepToGroup(@NonNull Keep keep, @NonNull String group, @NonNull Runnable onDone) {
        keep.setGroup(group);
        keep.save();
        onDone.run();
    }

    /** 分组管理对话框。 */
    public static void show(@NonNull Context context) {
        List<String> groups = getGroups();

        ScrollView scroll = new ScrollView(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8));

        // 新建分组行：输入框 + 「添加」按钮
        LinearLayout inputRow = new LinearLayout(context);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(context);
        input.setHint(R.string.group_name_hint);
        input.setSingleLine(true);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        input.setPadding(0, dp(context, 10), 0, dp(context, 10));
        input.setBackground(ContextCompat.getDrawable(context, R.drawable.shape_keep_search_input));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        inputRow.addView(input, inputParams);
        com.google.android.material.button.MaterialButton add = new com.google.android.material.button.MaterialButton(context);
        add.setText(R.string.group_add);
        add.setAllCaps(false);
        LinearLayout.LayoutParams addParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addParams.leftMargin = dp(context, 8);
        inputRow.addView(add, addParams);
        body.addView(inputRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        input.setOnEditorActionListener((v, actionId, event) -> {
            createGroup(context, input, body);
            return true;
        });
        add.setOnClickListener(v -> createGroup(context, input, body));

        // 分组列表
        TextView empty = new TextView(context);
        empty.setText(R.string.group_empty);
        empty.setTextSize(13);
        empty.setTextColor(ContextCompat.getColor(context, R.color.white_70));
        empty.setPadding(0, dp(context, 10), 0, 0);
        body.addView(empty);
        refreshBody(context, body, false);

        scroll.addView(body, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.menu_group)
                .setView(scroll)
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private static void createGroup(@NonNull Context context, @NonNull EditText input, @NonNull LinearLayout body) {
        String name = input.getText() == null ? "" : input.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            Notify.show(context.getString(R.string.group_name_hint));
            return;
        }
        List<String> all = getGroups();
        if (all.contains(name)) {
            input.setText("");
            refreshBody(context, body, false);
            return;
        }
        all.add(name);
        putGroups(all);
        input.setText("");
        refreshBody(context, body, false);
        RefreshEvent.keep();
    }

    private static void refreshBody(@NonNull Context context, @NonNull LinearLayout body, boolean scrolled) {
        // 移除旧行（保留第一个 input 和空提示）
        while (body.getChildCount() > 2) body.removeViewAt(body.getChildCount() - 1);
        TextView empty = (TextView) body.getChildAt(1);
        List<String> groups = getGroups();
        empty.setVisibility(groups.isEmpty() ? View.VISIBLE : View.GONE);
        for (String group : groups) {
            View row = makeRow(context, group);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(context, 4);
            body.addView(row, params);
        }
    }

    @NonNull
    private static View makeRow(@NonNull final Context context, @NonNull final String group) {
        TextView row = new TextView(context);
        row.setText(group + "  (" + countOf(group) + ")");
        row.setTextSize(14);
        row.setTextColor(ContextCompat.getColor(context, R.color.white));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 8), dp(context, 10), dp(context, 8), 0);
        row.setTypeface(null, Typeface.NORMAL);
        row.setOnClickListener(v -> rename(context, group));
        row.setOnLongClickListener(v -> {
            delete(context, group);
            return true;
        });
        return row;
    }

    private static void rename(@NonNull final Context context, @NonNull final String current) {
        EditText field = new EditText(context);
        field.setText(current);
        field.setSelection(current.length());
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        FrameLayout wrap = new FrameLayout(context);
        wrap.setPadding(dp(context, 24), dp(context, 8), dp(context, 24), 0);
        wrap.addView(field, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.group_rename)
                .setView(wrap)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    String name = field.getText().toString().trim();
                    if (TextUtils.isEmpty(name) || name.equals(current)) return;
                    if (getGroups().contains(name)) {
                        Notify.show(name);
                        return;
                    }
                    List<String> groups = getGroups();
                    groups.remove(current);
                    groups.add(name);
                    putGroups(groups);
                    AppDatabase.get().getKeepDao().renameGroup(current, name);
                    RefreshEvent.keep();
                })
                .show();
    }

    private static void delete(@NonNull final Context context, @NonNull final String group) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.group_delete)
                .setMessage(String.format(context.getString(R.string.group_delete_confirm), group))
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (d, which) -> {
                    List<String> groups = getGroups();
                    groups.remove(group);
                    putGroups(groups);
                    AppDatabase.get().getKeepDao().deleteGroup(group);
                    RefreshEvent.keep();
                })
                .show();
    }

    private static int dp(@NonNull Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }
}
