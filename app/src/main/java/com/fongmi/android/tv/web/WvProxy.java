package com.fongmi.android.tv.web;

import android.os.Handler;
import android.os.Looper;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

/**
 * 进程内 WebView 代理覆盖（androidx.webkit ProxyController）。
 *
 * <p>用途：过 Cloudflare 主动验证（"Just a moment"）时，py/js 源把 WebView 流量
 * 指到 mihomo mixed-port。HTTP 代理 CONNECT 隧道只转发加密字节，不触碰 TLS——
 * Chromium 的真浏览器指纹完整保留，同时 DNS 由 mihomo 远程解析，绕开污染。
 *
 * <p>为什么不用系统全局代理：需要 WRITE_SECURE_SETTINGS（部分 ROM adb 授权被封），
 * 且影响整机流量；本方案只影响本进程的 WebView，随调随清。
 *
 * <p>Chromium 对 127.0.0.1 有隐式绕过规则，壳子自身的环回页面不受影响。
 *
 * <p>py 源用法：
 * <pre>
 *   W = jclass('com.fongmi.android.tv.web.WvProxy')
 *   W.set('127.0.0.1:18890')   # 过盾前
 *   W.clear()                  # 过盾后（成功/取消/超时都要清）
 * </pre>
 */
public class WvProxy {

    /** 兜底自清：万一源侧流程死掉没来得及 clear，120 秒后自动解除，避免 WebView 常驻代理态。 */
    private static final long AUTO_CLEAR_MS = 120_000;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static boolean supported() {
        try {
            return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE);
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * hostPort 形如 127.0.0.1:18890。对所有 WebView 生效（进程级）。
     * 返回 false = 设备 WebView 版本太旧或调用失败，源应走降级路径。
     */
    public static boolean set(String hostPort) {
        if (hostPort == null || hostPort.isEmpty()) return false;
        if (!supported()) return false;
        try {
            ProxyConfig config = new ProxyConfig.Builder().addProxyRule(hostPort).addDirect().build();
            ProxyController.getInstance().setProxyOverride(config, Runnable::run, WvProxy::noop);
            MAIN.postDelayed(WvProxy::clear, AUTO_CLEAR_MS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public static boolean clear() {
        if (!supported()) return false;
        try {
            ProxyController.getInstance().clearProxyOverride(Runnable::run, WvProxy::noop);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    private static void noop() {
    }
}
