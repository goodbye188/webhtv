package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.PorterDuff;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 悬浮光圈导航。替代 BottomNavigationView。
 *
 * <p>为什么自己写：Material 的 BottomNavigationView 在本项目的动态 LayoutParams +
 * WebHome chrome 环境下会丢失菜单项图标（加载中有图标、加载完消失、设置页从不显示），
 * 且横向最小宽度压不下去。这里改成自绘的圆点 + 展开列表，宽度只剩一个圆。
 *
 * <p>几何：光圈是一个 56dp 的圆，用 margin 在父容器内绝对定位，父容器 match_parent 铺满屏幕。
 * 位置按千分比存 Setting，换分辨率/横竖屏后能还原到相对位置。
 *
 * <p>触摸：orb 自己吞掉 DOWN 之后的所有事件，靠位移是否超过 touchSlop 区分「拖动」和「点击」，
 * 所以不需要 GestureDetector，也不会和下层 RecyclerView 的滑动打架（未展开时不消费任何事件）。
 */
public class NavOrbView extends FrameLayout {

    private static final int COLOR_NORMAL = 0xCCFFFFFF;
    private static final int COLOR_SELECTED = 0xFFFFFFFF;

    public interface OnNavItemClickListener {

        void onNavItemClick(int itemId);
    }

    private static class Entry {

        final int itemId;
        final View root;
        final ImageView icon;
        final TextView text;

        Entry(int itemId, View root, ImageView icon, TextView text) {
            this.itemId = itemId;
            this.root = root;
            this.icon = icon;
            this.text = text;
        }
    }

    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final int orbSize;
    private final int edge;
    private final int menuGap;
    private final int touchSlop;

    private View orb;
    private View scrim;
    private LinearLayout menu;

    private OnNavItemClickListener listener;
    private int selectedId = -1;
    private boolean expanded;
    private int topInset;
    private int bottomInset;

    private float downRawX;
    private float downRawY;
    private int startLeft;
    private int startTop;
    private boolean dragging;

    public NavOrbView(Context context) {
        this(context, null);
    }

    public NavOrbView(Context context, AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.view_nav_orb, this);
        orbSize = getResources().getDimensionPixelSize(R.dimen.nav_orb_size);
        edge = getResources().getDimensionPixelSize(R.dimen.nav_orb_edge);
        menuGap = getResources().getDimensionPixelSize(R.dimen.nav_orb_menu_gap);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        orb = findViewById(R.id.orb);
        scrim = findViewById(R.id.orb_scrim);
        menu = findViewById(R.id.orb_menu);
        setupEntries();
        orb.setOnTouchListener((v, event) -> onOrbTouch(event));
        scrim.setOnClickListener(v -> closeMenu());
        // 自己消费 insets 只用于约束光圈的可拖动范围，不写 root padding，
        // 和 WebHomeChromeController（唯一写 root padding 的地方）互不干扰。
        // 这里返回原 insets 而不是 CONSUMED，子 view 照常收到分发。
        ViewCompat.setOnApplyWindowInsetsListener(this, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topInset = bars.top;
            bottomInset = bars.bottom;
            applyOrbPosition();
            return windowInsets;
        });
    }

    private void setupEntries() {
        // 顺序即 XML 里的视觉顺序：历史/收藏高频放最前，后面才是页面导航。
        addEntry(R.id.history, R.id.orb_item_history, R.id.orb_icon_history, R.id.orb_text_history);
        addEntry(R.id.keep, R.id.orb_item_keep, R.id.orb_icon_keep, R.id.orb_text_keep);
        addEntry(R.id.live, R.id.orb_item_live, R.id.orb_icon_live, R.id.orb_text_live);
        addEntry(R.id.lab, R.id.orb_item_lab, R.id.orb_icon_lab, R.id.orb_text_lab);
        addEntry(R.id.setting, R.id.orb_item_setting, R.id.orb_icon_setting, R.id.orb_text_setting);
    }

    private void addEntry(int itemId, int rootId, int iconId, int textId) {
        View root = findViewById(rootId);
        Entry entry = new Entry(itemId, root, findViewById(iconId), findViewById(textId));
        entries.put(itemId, entry);
        root.setOnClickListener(v -> {
            if (listener != null) listener.onNavItemClick(itemId);
            closeMenu();
        });
    }

    public void setOnNavItemClickListener(OnNavItemClickListener listener) {
        this.listener = listener;
    }

    public void setItemVisible(int itemId, boolean visible) {
        Entry entry = entries.get(itemId);
        if (entry != null) entry.root.setVisibility(visible ? VISIBLE : GONE);
    }

    public boolean isItemVisible(int itemId) {
        Entry entry = entries.get(itemId);
        return entry != null && entry.root.getVisibility() == VISIBLE;
    }

    public int getSelectedItemId() {
        return selectedId;
    }

    public void setSelectedItemId(int itemId) {
        selectedId = itemId;
        for (Entry entry : entries.values()) {
            boolean selected = entry.itemId == itemId;
            entry.root.setBackgroundResource(selected ? R.drawable.bg_nav_orb_item : android.R.color.transparent);
            int color = selected ? COLOR_SELECTED : COLOR_NORMAL;
            entry.icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            entry.text.setTextColor(color);
        }
    }

    public void setItemOnLongClickListener(int itemId, OnLongClickListener listener) {
        Entry entry = entries.get(itemId);
        if (entry != null) entry.root.setOnLongClickListener(listener);
    }

    public boolean isOrbVisible() {
        return getVisibility() == VISIBLE;
    }

    public void setOrbVisible(boolean visible) {
        if (visible == isOrbVisible()) return;
        if (!visible) closeMenuNow();
        setVisibility(visible ? VISIBLE : GONE);
        if (!visible) return;
        // ViewGroup 不会给 GONE 的子 view 分发 insets，所以重新显示时主动要一次，
        // 否则 topInset/bottomInset 会停在进 immersive 之前的旧值，光圈可能被系统栏压住。
        ViewCompat.requestApplyInsets(this);
        post(this::applyOrbPosition);
    }

    /** 展开状态下按返回键先收起列表，返回 true 表示已消费。 */
    public boolean consumeBack() {
        if (!expanded) return false;
        closeMenu();
        return true;
    }

    public void openMenu() {
        if (expanded || !isOrbVisible()) return;
        expanded = true;
        scrim.setVisibility(VISIBLE);
        menu.setVisibility(VISIBLE);
        menu.animate().cancel();
        menu.setAlpha(0f);
        menu.setScaleX(0.86f);
        menu.setScaleY(0.86f);
        layoutMenu();
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start();
    }

    public void closeMenu() {
        if (!expanded) return;
        expanded = false;
        menu.animate().cancel();
        menu.animate().alpha(0f).scaleX(0.86f).scaleY(0.86f).setDuration(140)
                .withEndAction(this::closeMenuNow).start();
    }

    private void closeMenuNow() {
        expanded = false;
        menu.animate().cancel();
        menu.setVisibility(GONE);
        menu.setAlpha(1f);
        menu.setScaleX(1f);
        menu.setScaleY(1f);
        scrim.setVisibility(GONE);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        applyOrbPosition();
    }

    /** 光圈可拖动范围：左右各留 edge，上下避开状态栏和系统导航栏。 */
    private int minLeft() {
        return edge;
    }

    private int maxLeft() {
        return Math.max(minLeft(), getWidth() - orbSize - edge);
    }

    private int minTop() {
        return topInset + edge;
    }

    private int maxTop() {
        return Math.max(minTop(), getHeight() - bottomInset - orbSize - edge);
    }

    private void applyOrbPosition() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        int sx = Setting.getHomeNavOrbX();
        int sy = Setting.getHomeNavOrbY();
        int left;
        int top;
        if (sx < 0 || sy < 0) {
            // 默认右下角。top 抬到 FAB(56dp, 底边距物理底部 136dp => 顶边 高度-192dp) 上方 10dp。
            left = maxLeft();
            top = h - bottomInset - orbSize - ResUtil.dp2px(202);
        } else {
            int rangeX = Math.max(1, maxLeft() - minLeft());
            int rangeY = Math.max(1, maxTop() - minTop());
            left = minLeft() + rangeX * sx / 1000;
            top = minTop() + rangeY * sy / 1000;
        }
        setOrbBounds(clamp(left, minLeft(), maxLeft()), clamp(top, minTop(), maxTop()));
    }

    private void setOrbBounds(int left, int top) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) orb.getLayoutParams();
        if (lp.leftMargin == left && lp.topMargin == top) return;
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.leftMargin = left;
        lp.topMargin = top;
        orb.setLayoutParams(lp);
        if (expanded) layoutMenu();
    }

    private boolean onOrbTouch(MotionEvent event) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) orb.getLayoutParams();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startLeft = lp.leftMargin;
                startTop = lp.topMargin;
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) < touchSlop) return true;
                dragging = true;
                setOrbBounds(clamp(startLeft + (int) dx, minLeft(), maxLeft()),
                        clamp(startTop + (int) dy, minTop(), maxTop()));
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    snapToEdge();
                    persistPosition();
                } else {
                    openMenu();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) snapToEdge();
                dragging = false;
                return true;
            default:
                return false;
        }
    }

    /** 松手后吸到就近的左右边缘。 */
    private void snapToEdge() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) orb.getLayoutParams();
        boolean leftSide = lp.leftMargin + orbSize / 2 < getWidth() / 2;
        setOrbBounds(leftSide ? minLeft() : maxLeft(), lp.topMargin);
    }

    private void persistPosition() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) orb.getLayoutParams();
        int rangeX = Math.max(1, maxLeft() - minLeft());
        int rangeY = Math.max(1, maxTop() - minTop());
        Setting.putHomeNavOrbX(clamp((lp.leftMargin - minLeft()) * 1000 / rangeX, 0, 1000));
        Setting.putHomeNavOrbY(clamp((lp.topMargin - minTop()) * 1000 / rangeY, 0, 1000));
    }

    /**
     * 把列表贴到光圈上方（下方空间不够时改放下方），并与光圈同侧对齐。
     * 菜单在 orb_layer（match_parent）里靠 gravity 定位，所以要先 measure 才知道高度。
     */
    private void layoutMenu() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || menu.getVisibility() != VISIBLE) return;
        // 高度用「去掉上下系统栏后的可用高度」做 AT_MOST 上限：
        // 正常情况（5 项 ≈ 252dp，屏幕可用高度 ≈ 700dp+）用不到这个上限，
        // 分屏 / 小窗口等极端场景下至少不会让菜单溢出屏幕（超出部分会被裁掉，
        // 把光圈拖到别的位置就能看到剩余项）。
        int avail = h - topInset - bottomInset;
        menu.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(avail, MeasureSpec.AT_MOST));
        FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) orb.getLayoutParams();
        int orbTop = olp.topMargin;
        int orbBottom = olp.topMargin + orbSize;
        int menuHeight = menu.getMeasuredHeight();
        boolean above = orbTop - menuHeight - menuGap >= topInset;
        boolean leftSide = olp.leftMargin + orbSize / 2 < w / 2;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menu.getLayoutParams();
        int side = leftSide ? Gravity.START : Gravity.END;
        if (above) {
            lp.gravity = Gravity.TOP | side;
            lp.topMargin = orbTop - menuHeight - menuGap;
            lp.bottomMargin = 0;
        } else {
            lp.gravity = Gravity.BOTTOM | side;
            lp.bottomMargin = h - orbBottom + menuGap;
            lp.topMargin = 0;
        }
        menu.setLayoutParams(lp);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 未展开时保持不消费，事件继续传给下层的 RecyclerView / WebView。
        return expanded && scrim.getVisibility() == VISIBLE && super.onTouchEvent(event);
    }
}
