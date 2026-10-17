package com.fongmi.android.tv.web;

import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.crawler.SpiderDebug;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 让 WebView 加载的页面内容经壳子的 OkHttp 通道出网，从而走 mihomo。
 *
 * <p>背景：Chromium（WebView 的网络栈）不查 Java 的 ProxySelector，也不读我们的 OkProxySelector，
 * 所以 WebView 一直是直连；被墙站点连盾都看不到。壳子里真正带代理的只有 OkHttp
 * （见 OkHttp.client()），于是这里把 WebView 的每个请求改写成
 * {@code /webResource?url=...}，交回 WebResourceGateway 用 OkHttp 取回来再喂给 WebView。
 *
 * <p>为什么不直接 loadUrl("http://127.0.0.1:9978/webResource?url=...")：
 * 那样页面 origin 会变成 127.0.0.1，页内所有相对路径（CF 挑战的 /cdn-cgi/...）都会打错地方。
 * shouldInterceptRequest 保持 WebView 视角的 origin 不变，只换底层取数通道。
 *
 * <p>非 GET/HEAD（Cloudflare 挑战要发 POST）走 307 重定向到网关，
 * 由 WebView 自己带着原 method 和原 body 重发 —— WebResourceRequest 拿不到 body，
 * 但 WebView 有。重定向保不保留 body 取决于 Chromium 是否按规范实现 307，实测见日志。
 *
 * <p>用法（Chaquopy 的 py 源可直接挂）：
 * <pre>
 *   GW = jclass('com.fongmi.android.tv.web.GatewayWebViewClient')
 *   wv.setWebViewClient(GW())
 * </pre>
 */
public class GatewayWebViewClient extends WebViewClient {

    /** 不能代理的目标：本机地址（否则网关请求自己会再被拦一次，形成回环） */
    private static final Set<String> LOCAL_HOSTS = new HashSet<>(Arrays.asList(
            "127.0.0.1", "localhost", "::1", "0.0.0.0"));

    /** hop-by-hop 头，网关已经处理过，转发会造成冲突 */
    private static final Set<String> SKIP_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "host", "connection", "keep-alive", "proxy-connection", "transfer-encoding",
            "upgrade", "content-length", "accept-encoding"));

    /** 这些响应头由 WebView 自己管，塞回去会打架 */
    private static final Set<String> SKIP_RESPONSE_HEADERS = new HashSet<>(Arrays.asList(
            "connection", "keep-alive", "transfer-encoding", "content-length", "content-encoding"));

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;

    /** URL -> 最近一次 POST 的 body。由页面里的 shim 写入，由 shouldInterceptRequest 读取。 */
    private final java.util.concurrent.ConcurrentHashMap<String, String> bodies = new java.util.concurrent.ConcurrentHashMap<>();

    public GatewayWebViewClient() {
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) return null;
        String url = request.getUrl().toString();
        if (!proxiable(url)) return null;
        String method = request.getMethod();
        if (!"GET".equals(method) && !"HEAD".equals(method)) {
            // WebResourceRequest 没有 getRequestBody()，这里拿不到 body；
            // 试图用 307 让 WebView 带 body 重发也不行 —— WebResourceResponse 的构造器
            // 明确拒绝 3xx（statusCode can't be in the [300, 399] range）。
            // 所以 body 只能由页面里的 shim 提前截获，见注入脚本 JS_SHIM。
            String body = bodies.remove(url);
            if (body == null) {
                SpiderDebug.log("gateway-webview", "bypass method=%s url=%s reason=body-not-captured", method, url);
                return null;
            }
            SpiderDebug.log("gateway-webview", "forward %s url=%s body=%d via gateway", method, url, body.length());
            return fetch(url, method, request.getRequestHeaders(), body);
        }
        return fetch(url, method, request.getRequestHeaders(), null);
    }

    /**
     * 注入页面，截获 fetch / XMLHttpRequest 的 POST body。
     *
     * <p>Cloudflare 挑战靠 POST 上报数据，而 WebResourceRequest 拿不到 body、
     * 3xx 又被 WebResourceResponse 拒绝，所以这是唯一能拿到 body 的入口：
     * 在请求离开 WebView 之前先在 JS 里存一份，Java 侧再按 URL 取出来补发到网关。
     *
     * <p>必须在页面任何脚本执行之前注入。shouldInterceptRequest 里拿不到 WebView
     * 的 JS 上下文（且该回调不在 UI 线程），所以由调用方在 loadUrl 之前调用 inject()。
     */
    public static final String JS_SHIM =
            "(function(){" +
            "if(window.__gwShim)return;window.__gwShim=1;" +
            "var P=function(k,v){try{window.__gwBodies[k]=v}catch(e){}};" +
            "window.__gwBodies=window.__gwBodies||{};" +
            "var of=window.fetch;if(of){window.fetch=function(u,o){" +
            "try{var b=o&&o.body;if(b!=null&&typeof b!=='string'){b=null}" +
            "if(b)P(String(u&&u.url||u),b)}catch(e){}return of.apply(this,arguments)}};" +
            "var ox=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.send;" +
            "XMLHttpRequest.prototype.open=function(m,u){this.__gwM=m;this.__gwU=u;return ox.apply(this,arguments)};" +
            "XMLHttpRequest.prototype.send=function(b){try{" +
            "if(this.__gwM&&this.__gwM!=='GET'&&this.__gwM!=='HEAD'&&b!=null&&typeof b==='string'){" +
            "P(String(this.__gwU),b)}}catch(e){}return os.apply(this,arguments)};" +
            "if(navigator.sendBeacon){var ob=navigator.sendBeacon.bind(navigator);" +
            "navigator.sendBeacon=function(u,d){try{if(d!=null&&typeof d==='string')P(String(u),d)}catch(e){}" +
            "return ob(u,d)}}" +
            "})();";

    /**
     * 在页面加载前注入 body 截获脚本。必须在 UI 线程调用，且要在 loadUrl 之前。
     * {@code webView} 用 {@code WebView} 类型会引入 android.webkit 依赖，这里直接收 WebView。
     */
    public void inject(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(JS_SHIM, null);
            SpiderDebug.log("gateway-webview", "shim injected");
        } catch (Throwable e) {
            SpiderDebug.log("gateway-webview", "shim inject failed error=%s", e.getClass().getSimpleName());
        }
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
        // API 21 以下的老回调。minSdk 24 上基本不会走到，留着是为了不改变 WebViewClient 的默认契约。
        if (!proxiable(url)) return null;
        return fetch(url, "GET", null, null);
    }

    /** 只有 http/https 才代理；其它 scheme（data/blob/file/about/content）交回 WebView 原生处理。 */
    private boolean proxiable(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false;
        String host = hostOf(lower);
        return host != null && !LOCAL_HOSTS.contains(host);
    }

    private String hostOf(String lowerUrl) {
        int start = lowerUrl.indexOf("://") + 3;
        if (start < 3) return null;
        int end = lowerUrl.length();
        for (int i = start; i < lowerUrl.length(); i++) {
            char c = lowerUrl.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = lowerUrl.substring(start, end);
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }

    /**
     * 走本机网关取回内容。刻意用 HttpURLConnection 而不是 OkHttp：
     * 目标是 127.0.0.1，不需要代理；而 OkHttp.client() 挂了 OkProxySelector，
     * 用它发本机请求要额外依赖「本机地址豁免」那条规则（ProxySetting 的 local-target）。
     * 用裸连接就没有这个隐含依赖，也少一跳序列化。
     */
    private WebResourceResponse fetch(String url, String method, Map<String, String> requestHeaders, String body) {
        HttpURLConnection conn = null;
        try {
            boolean post = body != null;
            // 有 body 必须发真 POST：WebResourceGateway.getBody() 先看 session 的 method，
            // GET/HEAD 直接返回 null 就不读 body 了，所以不能拿 GET + body 参数糊过去。
            String target = gateway(url, requestHeaders, post);
            if (post) target = target + "&body=" + encode(body);

            conn = (HttpURLConnection) new URL(target).openConnection();
            conn.setRequestMethod(post ? "POST" : method);
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);

            int code = conn.getResponseCode();
            Map<String, String> headers = responseHeaders(conn);
            String type = headers.get("Content-Type");
            String mime = type == null ? "text/plain" : type;
            // Content-Type 里可能带 charset，WebResourceResponse 要拆开
            String encoding = null;
            int semi = mime.indexOf(';');
            if (semi >= 0) {
                encoding = charsetOf(mime.substring(semi));
                mime = mime.substring(0, semi).trim();
            }

            // 非 2xx 时 getInputStream() 会抛，错误页要从 getErrorStream() 取
            java.io.InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();

            SpiderDebug.log("gateway-webview", "%s %s -> %s mime=%s", method, url, code, mime);
            return new WebResourceResponse(mime, encoding, code, reason(code), headers, stream);
        } catch (Throwable e) {
            SpiderDebug.log("gateway-webview", "failed url=%s error=%s:%s", url, e.getClass().getSimpleName(), e.getMessage());
            SpiderDebug.log("gateway-webview", e);
            return null; // 交回 WebView 直连，至少不会白屏
        }
    }

    private String gateway(String url, Map<String, String> requestHeaders, boolean post) {
        Map<String, String> headers = new HashMap<>();
        if (requestHeaders != null) {
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                if (entry.getKey() == null) continue;
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                if (SKIP_REQUEST_HEADERS.contains(key) || entry.getValue() == null) continue;
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        // WebResourceGateway 靠 headers 参数决定要不要补 UA、要不要带 Cookie，
        // 所以这里连同 WebView 实际用的 UA 一起传过去，别让网关猜。
        headers.putIfAbsent("User-Agent", com.fongmi.android.tv.player.PlayerHelper.getUa());
        // CF 挑战要按原样重放 POST 的 Content-Type，否则服务端解析方式不同就拿不到 cf_clearance
        if (post) headers.putIfAbsent("Content-Type", "application/x-www-form-urlencoded");

        StringBuilder builder = new StringBuilder(Server.get().getAddress("/webResource?url=")).append(encode(url));
        if (!headers.isEmpty()) builder.append("&headers=").append(encode(App.gson().toJson(headers)));
        // 让网关把响应里的 Set-Cookie 写进 CookieManager，WebView 后续请求才带得上
        builder.append("&credentials=include");
        return builder.toString();
    }

    private Map<String, String> responseHeaders(HttpURLConnection conn) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
            String key = entry.getKey();
            if (key == null) continue;
            if (SKIP_RESPONSE_HEADERS.contains(key.toLowerCase(Locale.ROOT))) continue;
            List<String> values = entry.getValue();
            if (values == null || values.isEmpty()) continue;
            map.put(key, values.get(0));
        }
        return map;
    }

    private String charsetOf(String tail) {
        for (String part : tail.split(";")) {
            String p = part.trim();
            if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) return p.substring(8).trim();
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String reason(int code) {
        String[] reasons = {
                "", "OK", "Created", "Accepted", "Non-Authoritative", "No Content",
                "Partial Content", "Multiple Choices", "Moved Permanently", "Found", "See Other",
                "Not Modified", "Temporary Redirect", "Bad Request", "Unauthorized", "Forbidden",
                "Not Found", "Method Not Allowed", "Not Acceptable", "Request Timeout",
                "Conflict", "Gone", "Length Required", "Precondition Failed",
                "Internal Server Error", "Not Implemented", "Bad Gateway", "Service Unavailable",
                "Gateway Timeout", "HTTP Version Not Supported"
        };
        return code >= 0 && code < reasons.length ? reasons[code] : "Status " + code;
    }
}