package com.fongmi.android.tv.web;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.crawler.SpiderDebug;

/**
 * 播放直链捕获 WebViewClient。
 *
 * <p>背景：supremejav 之类的解析页对非浏览器 TLS 指纹开 CF 签名封禁
 * （error 1010），Go 桥的 chrome_131 模拟指纹必死；而 Chaquopy 的
 * dynamic_proxy 只能代理接口，py 无法继承 WebViewClient 这个类来重写
 * shouldInterceptRequest。所以在壳子里真继承一次，把命中结果通过
 * OnHit 接口回调给 py（py 侧用 dynamic_proxy 实现该接口即可）。
 *
 * <p>调试期把页面生命周期和所有网络请求都打进 SpiderDebug（tag=wv-cap），
 * 排查"WebView 挂上了但一直拦不到"这类问题；定位完成后可降为只记命中。
 */
public class WvCaptureClient extends WebViewClient {

    /** py 通过 dynamic_proxy 实现这个接口接收命中 URL。 */
    public interface OnHit {
        void hit(String url);
    }

    private final String[] mKeys;
    private final OnHit mHit;

    public WvCaptureClient(String[] keys, OnHit hit) {
        mKeys = keys == null || keys.length == 0 ? new String[]{".m3u8", ".mp4"} : keys;
        mHit = hit;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        SpiderDebug.log("wv-cap", "req %s", shortUrl(url));
        String low = url.toLowerCase();
        for (String key : mKeys) {
            if (low.contains(key)) {
                SpiderDebug.log("wv-cap", "🎯 HIT %s", shortUrl(url));
                if (mHit != null) {
                    try {
                        mHit.hit(url);
                    } catch (Throwable t) {
                        SpiderDebug.log("wv-cap", "hit callback error %s", t);
                    }
                }
                break;
            }
        }
        return null; // 放行：只旁路观察，不接管网络
    }

    @Override
    public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
        SpiderDebug.log("wv-cap", "page started %s", shortUrl(url));
    }

    @Override
    public void onPageFinished(WebView view, String url) {
        SpiderDebug.log("wv-cap", "page finished %s", shortUrl(url));
    }

    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
        if (request.isForMainFrame()) {
            SpiderDebug.log("wv-cap", "error %s code=%s desc=%s", shortUrl(request.getUrl().toString()), error.getErrorCode(), error.getDescription());
        }
    }

    private static String shortUrl(String url) {
        if (url == null) return "";
        return url.length() > 150 ? url.substring(0, 150) : url;
    }
}
