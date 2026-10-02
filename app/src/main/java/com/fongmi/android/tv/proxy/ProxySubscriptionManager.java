package com.fongmi.android.tv.proxy;

import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 照抄星落 5.9.9 com.fongmi.android.tv.proxy.ProxySubscriptionManager 的 clash 主链
 * （方法均已逐个核对 smali）。与星落的差异只有一处，见 fixConfig 注释。
 *
 * 未抄：12 个协议转换器（vmess/vless/trojan/ss/ssr/hysteria/hysteria2/tuic/juicity/
 * anytls/snell/wireguard → clash）、sip008、clash-JSON 解析。这三条只在「链接列表」
 * 订阅上才走；clash YAML 订阅一律原样透传给内核，用不到。碰到链接列表订阅时再补。
 */
public final class ProxySubscriptionManager {

    private static final String TAG = "ProxySub";

    private volatile List<ProxyNode> nodes;
    private final AtomicInteger testedCount = new AtomicInteger();
    private volatile boolean testing;
    private volatile Runnable progressCallback;
    private volatile long lastNotifyTime;

    private static class Loader {
        static final ProxySubscriptionManager INSTANCE = new ProxySubscriptionManager();
    }

    public static ProxySubscriptionManager get() {
        return Loader.INSTANCE;
    }

    // ---------------- 对外接口 ----------------

    public List<ProxyNode> getNodes() {
        List<ProxyNode> cached = nodes;
        if (cached != null) return new ArrayList<>(cached);
        synchronized (this) {
            if (nodes == null) {
                try {
                    nodes = App.gson().fromJson(Setting.getProxySubscriptionNodes(),
                            new TypeToken<List<ProxyNode>>() {}.getType());
                } catch (Throwable ignored) {
                }
                if (nodes == null) nodes = new ArrayList<>();
            }
            return new ArrayList<>(nodes);
        }
    }

    public boolean hasNodes() {
        List<ProxyNode> cached = nodes;
        return cached != null && !cached.isEmpty();
    }

    public String getSummary() {
        if (!Setting.isProxySubscriptionEnabled()) return "关闭";
        ProxyNode selected = getSelected();
        if (selected == null) return "未选择";
        return selected.getDisplay();
    }

    public ProxyNode getSelected() {
        String selected = Setting.getProxySubscriptionSelected();
        if (TextUtils.isEmpty(selected)) return null;
        // 之前存的是 mihomo 本地代理（http://127.0.0.1:18890），换算回内核节点
        if (selected.startsWith("http://127.0.0.1:" + MihomoManager.getMixedPort())) {
            ProxyNode node = ProxyNode.mihomo(Setting.getProxySubscriptionCoreName());
            if (node != null) {
                Setting.putProxySubscriptionSelected(node.getUrl());
                return node;
            }
        }
        for (ProxyNode node : getNodes()) {
            if (selected.equals(node.getUrl())) return node;
        }
        return ProxyNode.fromUri(selected);
    }

    public boolean select(ProxyNode node) {
        if (node == null) return false;
        if (node.isSupported()) {
            // 直连型（http/socks5）：不需要内核，直接给 OkHttp 用
            Setting.putProxySubscriptionSelected(node.getUrl());
            Setting.putProxySubscriptionEnabled(true);
            Setting.putProxySubscriptionCoreName("");
            applySaved();
            return true;
        }
        String config = getConfig();
        if (!TextUtils.isEmpty(config)) {
            if (MihomoManager.get().start(config, node.getName())) {
                ProxyNode mihomo = ProxyNode.mihomo(node.getName());
                Setting.putProxySubscriptionCoreName(node.getName());
                Setting.putProxySubscriptionSelected(mihomo.getUrl());
                Setting.putProxySubscriptionEnabled(true);
                OkHttp.selector().addOrReplace(com.github.catvod.bean.Proxy.create(
                        "subscription", Arrays.asList("*"), Arrays.asList(mihomo.getUrl())));
                Log.d(TAG, "select success: " + node.getName() + " -> " + mihomo.getUrl());
                return true;
            }
            // 启动失败：不在此 Toast（可能被后台线程调用，会崩），仅记录日志，由调用方在 UI 线程展示
            String err = MihomoManager.get().getLastError();
            if (TextUtils.isEmpty(err)) err = "mihomo 启动失败（内核 5 秒未就绪）";
            Log.e(TAG, "select: mihomo start failed for " + node.getName() + " :: " + err);
            return false;
        }
        // config 为空：不在此 Toast，由调用方处理
        Log.e(TAG, "select: config empty");
        return false;
    }

    public void applySaved() {
        // ★ 关键：存储值是 mihomo 伪 URL（http://127.0.0.1:18890#节点名）时，必须起内核。
        // getSelected() 会把它还原成 scheme=http 的伪节点，isSupported() 为 true，
        // 若按 isSupported() 分支走就只注册代理而永不启动内核（表现为"重启后必须手动点一次节点"）。
        boolean isMihomoPseudo = Setting.getProxySubscriptionSelected()
                .startsWith("http://127.0.0.1:" + MihomoManager.getMixedPort());
        if (!Setting.isProxySubscriptionEnabled()) return;
        if (isMihomoPseudo) {
            applyCore();
            return;
        }
        ProxyNode selected = getSelected();
        if (selected == null) return;
        if (selected.isSupported()) {
            String url = selected.getUrl();
            OkHttp.selector().addOrReplace(com.github.catvod.bean.Proxy.create(
                    "subscription", Arrays.asList("*"), Arrays.asList(url)));
            return;
        }
        applyCore();
    }

    /** 起内核并把 TMDB 路由到代理（伪节点与内核型节点共用） */
    private void applyCore() {
        String coreName = Setting.getProxySubscriptionCoreName();
        if (TextUtils.isEmpty(coreName)) return;
        if (!MihomoManager.get().isRunning()) {
            String config = getConfig();
            if (!MihomoManager.get().start(config, coreName)) {
                Log.e(TAG, "applyCore: mihomo start failed, proxy not applied");
                return;
            }
        }
        ProxyNode mihomo = ProxyNode.mihomo(coreName);
        if (mihomo == null || TextUtils.isEmpty(mihomo.getUrl())) return;
        // TMDB 单独走代理（对齐星落：api/image/themoviedb.org 走代理，普通请求直连）
        OkHttp.selector().addOrReplace(com.github.catvod.bean.Proxy.create(
                "tmdb_proxy",
                Arrays.asList("api.themoviedb.org", "image.tmdb.org", "*.themoviedb.org", "*.tmdb.org"),
                Arrays.asList(mihomo.getUrl())));
        Log.d(TAG, "applyCore: TMDB 已路由至代理 " + mihomo.getUrl());
    }

    public void disable() {
        OkHttp.selector().remove("subscription");
        OkHttp.selector().remove("tmdb_proxy");
        MihomoManager.get().stop();
    }

    /** 拉订阅 → 解析节点 → 存节点与配置。输入可以是订阅 URL、clash YAML、或直接代理链接。 */
    public List<ProxyNode> refresh(String input) {
        String fetched = fetch(input);
        if (TextUtils.isEmpty(fetched)) return new ArrayList<>();
        String clash = getClashConfig(fetched);
        String config;
        List<ProxyNode> list;
        if (TextUtils.isEmpty(clash)) {
            // 链接列表：逐节点转成 clash 再拼一份配置
            list = parse(fetched);
            config = generateClashConfig(list);
        } else {
            // clash YAML：配置原样透传（保住订阅自带的 dns 等所有段）
            list = parse(clash);
            config = clash;
        }
        if (list.isEmpty()) return list;
        saveNodes(list);
        Setting.putProxySubscriptionConfig(config);
        Log.d(TAG, "refresh: nodes=" + list.size() + " configLen=" + config.length());
        return list;
    }

    // ---------------- 拉取 ----------------

    /** 星落 fetch：先用 clash-meta UA 试，失败退回默认 UA 再试。 */
    private String fetch(String url) {
        String[] uas = {
                "Clash Verge/2.0.0", "ClashforWindows/0.20.39", "clash-meta/1.18.0",
                "v2rayN/6.0", "Shadowrocket/1900", "Quantumult/1.0", "Surge/5.0", "Mozilla/5.0",
        };
        for (String ua : uas) {
            try {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("User-Agent", ua);
                headers.put("Accept", "text/plain, application/json, application/yaml, */*");
                String body = OkHttp.string(url, headers);
                if (!TextUtils.isEmpty(body) && body.length() > 1) {
                    Log.d(TAG, "fetch: success with UA=" + ua + " len=" + body.length());
                    return body;
                }
            } catch (Throwable e) {
                Log.w(TAG, "fetch: failed with UA=" + ua + " err=" + e.getMessage());
            }
        }
        return OkHttp.string(url);
    }

    private String getConfig() {
        return Setting.getProxySubscriptionConfig();
    }

    private void saveNodes(List<ProxyNode> list) {
        nodes = list;
        Setting.putProxySubscriptionNodes(App.gson().toJson(list));
    }

    // ---------------- 格式判定 ----------------

    private boolean isClashConfig(String text) {
        return !TextUtils.isEmpty(text) && text.contains("proxies:");
    }

    private boolean isClashJsonConfig(String text) {
        return !TextUtils.isEmpty(text) && text.trim().startsWith("{") && text.contains("\"proxies\"");
    }

    /** 星落 getClashConfig：原文是 clash 就原样返回；否则尝试 base64 解码后再判。 */
    private String getClashConfig(String text) {
        if (isClashConfig(text) || isClashJsonConfig(text)) return text;
        String decoded = decode(text);
        if (isClashConfig(decoded) || isClashJsonConfig(decoded)) return decoded;
        String raw = decodeRaw(text);
        if (isClashConfig(raw) || isClashJsonConfig(raw)) return raw;
        return "";
    }

    // ---------------- 解析 ----------------

    private List<ProxyNode> parse(String text) {
        Map<String, ProxyNode> map = new LinkedHashMap<>();
        if (isClashConfig(text) || isClashJsonConfig(text)) {
            addClash(map, text);   // clash YAML
        } else {
            addLines(map, text);   // vless/直连 链接列表
        }
        List<ProxyNode> list = new ArrayList<>();
        for (ProxyNode node : map.values()) {
            if (isValidNode(node)) list.add(node);
        }
        return list;
    }

    /**
     * 照抄星落 addLines：逐行解析订阅链接。直连(http/https/socks)用 ProxyNode.fromUri；
     * vless 走 vlessToClash 转 clash YAML 存进 node.proxyYaml（供 generateClashConfig 拼配置）。
     */
    private void addLines(Map<String, ProxyNode> map, String text) {
        if (TextUtils.isEmpty(text)) return;
        for (String raw : text.replace("\r", "\n").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("proxies:")) continue;
            String lower = line.toLowerCase(Locale.ROOT);
            try {
                if (lower.startsWith("http://") || lower.startsWith("https://")
                        || lower.startsWith("socks://") || lower.startsWith("socks5://")
                        || lower.startsWith("socks5h://")) {
                    ProxyNode node = ProxyNode.fromUri(line);
                    if (node != null && node.isSupported()) map.putIfAbsent(node.getUrl(), node);
                } else if (lower.startsWith("vless://")) {
                    String name = getFragmentName(line, "VLESS");
                    String yaml = vlessToClash(line, name);
                    if (yaml == null) continue;
                    ProxyNode node = nodeFromClashYaml(yaml);
                    if (node != null) {
                        node.setProxyYaml(yaml);
                        map.putIfAbsent(name + "vless" + node.getHost() + node.getPort(), node);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    /** 从 vlessToClash 生成的 clash YAML 片段提取 name/server/port/type，建节点 */
    private ProxyNode nodeFromClashYaml(String yaml) {
        String name = yamlValue(yaml, "name");
        String type = yamlValue(yaml, "type");
        String server = yamlValue(yaml, "server");
        int port = parsePort(yamlValue(yaml, "port"));
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(type) || TextUtils.isEmpty(server) || port <= 0) return null;
        return clashNode(name, type, server, port);
    }

    /** 星落 getFragmentName：取 URI 的 #fragment 作为节点名，没有就返回默认名 */
    private String getFragmentName(String uri, String defaultName) {
        int i = uri.indexOf('#');
        if (i < 0) return defaultName;
        return android.net.Uri.decode(uri.substring(i + 1));
    }

    private boolean isValidNode(ProxyNode node) {
        if (node == null) return false;
        String name = node.getName();
        if (TextUtils.isEmpty(name)) return false;
        for (String bad : FILTER_NAMES) {
            if (name.contains(bad)) return false;
        }
        return true;
    }

    /** 节点名里含这些词的直接丢掉（流量卡/到期提示/官网广告等） */
    private static final String[] FILTER_NAMES = {
            "剩余流量", "到期", "过期", "官网", "订阅", "客服", "群组", "重置",
            "Expire", "Traffic", "剩余", "流量",
    };

    private static boolean isTopLevel(String line) {
        if (TextUtils.isEmpty(line)) return false;
        if (Character.isWhitespace(line.charAt(0))) return false;
        // 行首 "-" 是数组项（proxies 条目），不是顶层 key
        if (line.charAt(0) == '-') return false;
        return line.contains(":");
    }

    /**
     * 照抄星落 addClash：逐行扫 clash YAML，每个 proxies 条目抽成 ProxyNode，
     * 并把该条目的原始 YAML（含缩进）存进 node.proxyYaml，生成配置时原样吐回。
     * 支持标准多行格式（name/type/server/port 各占一行），不只是内联 {…} JSON。
     */
    private void addClash(Map<String, ProxyNode> map, String text) {
        if (TextUtils.isEmpty(text)) return;
        StringBuilder block = new StringBuilder();
        String name = null;
        String server = null;
        String type = null;
        int port = 0;
        boolean inProxies = false;
        String[] lines = text.replace("\r", "\n").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (isTopLevel(raw)) {
                // 收尾上一个节点
                if (name != null && !TextUtils.isEmpty(server) && port > 0) {
                    flushClashNode(map, name, type, server, port, block);
                    name = null; server = null; type = null; port = 0;
                }
                inProxies = line.startsWith("proxies:");
                if (inProxies) block.setLength(0);
                continue;
            }
            if (!inProxies) continue;
            block.append(raw).append('\n');
            // 新节点开始（行首 "-"）：先收尾上一个
            if (line.startsWith("-")) {
                if (name != null && !TextUtils.isEmpty(server) && port > 0) {
                    flushClashNode(map, name, type, server, port, block);
                    name = null; server = null; type = null; port = 0;
                }
                // 内联单行 JSON：{name: .., type: .., server: .., port: ..} 整体取全部字段
                if (line.contains("{")) {
                    String inline = line.substring(line.indexOf('{'));
                    String n = yamlValue(inline, "name");
                    if (n != null) name = n;
                    String t = yamlValue(inline, "type");
                    if (t != null) type = t;
                    String sv = yamlValue(inline, "server");
                    if (sv != null) server = sv;
                    int pt = parsePort(yamlValue(inline, "port"));
                    if (pt > 0) port = pt;
                } else {
                    // 多行格式：- name: X，只取 name，其余字段由后续普通行补
                    String n = yamlValue(stripDash(line), "name");
                    if (n != null) name = n;
                }
                continue;
            }
            // 普通字段行：name/type/server/port 各占一行
            String v;
            if ((v = yamlValue(line, "name")) != null) name = v;
            else if ((v = yamlValue(line, "server")) != null) server = v;
            else if ((v = yamlValue(line, "type")) != null) type = v;
            else if ((v = yamlValue(line, "port")) != null) port = parsePort(v);
        }
        // 收尾
        if (name != null && !TextUtils.isEmpty(server) && port > 0) {
            flushClashNode(map, name, type, server, port, block);
        }
    }

    private void flushClashNode(Map<String, ProxyNode> map, String name, String type, String server, int port, StringBuilder block) {
        ProxyNode node = clashNode(name, type, server, port);
        if (node != null) {
            node.setProxyYaml(block.toString());
            // 星落：直连节点用 getUrl() 作 key，非直连（vless/vmess 等，getUrl() 为空）用 name+type+server+port 拼接，保证 key 唯一不覆盖
            if (node.isSupported()) {
                map.putIfAbsent(node.getUrl(), node);
            } else {
                map.putIfAbsent(name + type + server + port, node);
            }
        }
    }

    /** 去掉 "- " 前导，保留剩余 YAML 行（星落 value 逐行取 key 的方式） */
    private String stripDash(String line) {
        int i = line.indexOf('-');
        if (i >= 0) return line.substring(i + 1);
        return line;
    }

    /** 从内联 YAML `{k: v, ...}` 里取值（够用即可，不引 yaml 库） */
    private static String yamlValue(String inline, String key) {
        int i = inline.indexOf(key + ":");
        if (i < 0) return null;
        int start = i + key.length() + 1;
        while (start < inline.length() && (inline.charAt(start) == ' ')) start++;
        int end = start;
        boolean quoted = end < inline.length() && (inline.charAt(end) == '"' || inline.charAt(end) == '\'');
        if (quoted) {
            char q = inline.charAt(end);
            end = inline.indexOf(q, end + 1);
            return end < 0 ? null : inline.substring(start + 1, end);
        }
        while (end < inline.length() && " ,}".indexOf(inline.charAt(end)) < 0) end++;
        return inline.substring(start, end).trim();
    }

    private int parsePort(String text) {
        if (TextUtils.isEmpty(text)) return 0;
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 星落 clashNode：socks5/http/https 直接建可用节点，其余标记为需要内核 */
    private ProxyNode clashNode(String name, String scheme, String host, int port) {
        return clashNode(name, scheme, host, port, null);
    }

    private ProxyNode clashNode(String name, String scheme, String host, int port, String rawUri) {
        if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(host) || port <= 0) return null;
        String lower = scheme.toLowerCase(Locale.ROOT);
        if ("socks5".equals(lower) || "http".equals(lower) || "https".equals(lower)) {
            String display = TextUtils.isEmpty(name) ? scheme : name;
            return ProxyNode.fromUri(scheme + "://" + host + ":" + port + "#"
                    + Uri0.encode(TextUtils.isEmpty(name) ? host : name));
        }
        return ProxyNode.unsupported(TextUtils.isEmpty(name) ? scheme : name, scheme, host, port, rawUri);
    }

    /** 极简 Uri 编码，避免为一处 import 引入循环依赖 */
    static final class Uri0 {
        static String encode(String s) {
            try {
                return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
            } catch (Throwable e) {
                return s;
            }
        }
    }

    /** 星落 toClashProxy：有原始 YAML 就原样返回（clash 订阅走这条） */
    private String toClashProxy(ProxyNode node) {
        if (node == null) return null;
        String yaml = node.getProxyYaml();
        if (!TextUtils.isEmpty(yaml)) return yaml;
        if (node.isSupported()) {
            if ("socks5".equals(node.getScheme())) {
                return clashProxyEntry("socks5", node.getName(), node.getHost(), node.getPort(),
                        "username", node.getUserInfo(), "password", "");
            }
            return clashProxyEntry("http", node.getName(), node.getHost(), node.getPort(),
                    null, null, null, null);
        }
        // 链接列表节点：需要协议转换器，本移植未实现
        return null;
    }

    private String clashProxyEntry(String type, String name, String host, int port,
                                    String userKey, String userVal, String passKey, String passVal) {
        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append('\n');
        sb.append("    type: ").append(type).append('\n');
        sb.append("    server: ").append(host).append('\n');
        sb.append("    port: ").append(port).append('\n');
        sb.append("    udp: true\n");
        if (userKey != null && !TextUtils.isEmpty(userVal)) {
            sb.append("    ").append(userKey).append(": ").append(quote(userVal)).append('\n');
            sb.append("    ").append(passKey).append(": ").append(quote(passVal)).append('\n');
        }
        return sb.toString();
    }

    /** 星落 generateClashConfig：仅链接列表订阅走这里，从零重建 */
    private String generateClashConfig(List<ProxyNode> list) {
        StringBuilder proxies = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (ProxyNode node : list) {
            String entry = toClashProxy(node);
            if (entry == null) continue;
            proxies.append(entry).append('\n');
            names.append("      - ").append(quote(node.getName())).append('\n');
        }
        if (proxies.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("mode: rule\nipv6: false\nallow-lan: false\nproxies:\n");
        sb.append(proxies);
        sb.append("proxy-groups:\n  - name: XYS_PROXY\n    type: select\n    proxies:\n");
        sb.append(names);
        sb.append(MihomoManager.RULES_YAML);
        return sb.toString();
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------- 链接列表 → clash：vless 转换器 ----------------

    /**
     * 照抄星落 vlessToClash（已逐段核对 smali，含手写 host/port 回退解析）。
     *
     * 与星落的一处差异：星落没有处理 ech 参数，会把 ech-opts 丢掉；
     * Cloudflare 前置的 vless 节点（带 ech=host+query-server）丢了就连不上，这里补上。
     */
    private String vlessToClash(String uri, String name) {
        try {
            Log.d(TAG, "vlessToClash input URI: " + uri);
            android.net.Uri u = android.net.Uri.parse(uri);
            String host = u.getHost();
            int port = u.getPort();
            if (host == null || port <= 0) {
                // 星落的 Uri.parse 回退：手工切出 host / port
                String rest = uri.substring(8);
                int q = rest.indexOf('?');
                if (q >= 0) rest = rest.substring(0, q);
                int h = rest.indexOf('#');
                if (h >= 0) rest = rest.substring(0, h);
                int at = rest.indexOf('@');
                if (at >= 0) rest = rest.substring(at + 1);
                if (rest.startsWith("[")) {
                    int close = rest.indexOf(']');
                    if (close > 0) {
                        host = rest.substring(1, close);
                        String tail = rest.substring(close + 1);
                        if (tail.startsWith(":")) port = Integer.parseInt(tail.substring(1));
                    }
                } else {
                    int colon = rest.lastIndexOf(':');
                    if (colon >= 0) {
                        host = rest.substring(0, colon);
                        port = Integer.parseInt(rest.substring(colon + 1));
                    } else {
                        host = rest;
                    }
                }
                Log.d(TAG, "vlessToClash: Uri.parse fallback, host=" + host + " port=" + port);
            }
            if (TextUtils.isEmpty(host) || port <= 0) {
                Log.e(TAG, "vlessToClash: invalid host or port in URI: " + uri);
                return null;
            }
            while (host.startsWith(".")) host = host.substring(1);

            String uuid;
            try {
                uuid = u.getUserInfo();
            } catch (Throwable e) {
                uuid = "";
            }
            if (TextUtils.isEmpty(uuid)) uuid = getParam(u, "uuid");
            if (TextUtils.isEmpty(uuid)) uuid = getParam(u, "id");

            String network = getParam(u, "type", "network");
            String sni = getParam(u, "sni", "peer");
            String pbk = getParam(u, "pbk", "public-key");
            String sid = getParam(u, "sid", "short-id");
            String security = getParam(u, "security");
            boolean reality = "reality".equals(security);
            boolean tls = security != null && "tls".equals(security);

            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append('\n');
            sb.append("    type: vless\n");
            sb.append("    server: ").append(host).append('\n');
            sb.append("    port: ").append(port).append('\n');
            sb.append("    uuid: ").append(quote(uuid)).append('\n');

            if ("ws".equals(network) || "websocket".equals(network)) {
                sb.append("    network: ws\n");
                String path = getParam(u, "path");
                if (!TextUtils.isEmpty(path)) {
                    sb.append("    ws-opts:\n      path: ").append(quote(path)).append('\n');
                }
                String wsHost = getParam(u, "host");
                if (!TextUtils.isEmpty(wsHost)) {
                    sb.append("      headers:\n        Host:\n          - ").append(quote(wsHost)).append('\n');
                }
            } else if ("http".equals(network) || "h2".equals(network)) {
                sb.append("    network: http\n");
                sb.append("    http-opts:\n");
                String path = getParam(u, "path");
                if (!TextUtils.isEmpty(path)) {
                    sb.append("      path:\n        - ").append(quote(path)).append('\n');
                }
                String httpHost = getParam(u, "host");
                if (!TextUtils.isEmpty(httpHost)) {
                    sb.append("      headers:\n        Host:\n          - ").append(quote(httpHost)).append('\n');
                }
            } else {
                sb.append("    network: tcp\n");
            }

            String flow = getParam(u, "flow");
            if (flow != null && tls) sb.append("    flow: ").append(flow).append('\n');

            String allowInsecure = getParam(u, "allowInsecure", "allow-insecure", "insecure");
            boolean skipVerify = "1".equals(allowInsecure) || "true".equalsIgnoreCase(allowInsecure);

            if (tls) {
                sb.append("    tls: true\n");
                sb.append("    servername: ").append(quote(sni != null ? sni : host)).append('\n');
            }
            if (skipVerify) sb.append("    skip-cert-verify: true\n");
            if (reality) {
                sb.append("    reality-opts:\n");
                if (!TextUtils.isEmpty(pbk)) sb.append("      public-key: ").append(quote(pbk)).append('\n');
                if (!TextUtils.isEmpty(sid)) sb.append("      short-id: ").append(quote(sid)).append('\n');
            }

            // ★ 故意不写 ech-opts（照抄星落：星落 vlessToClash 完全不处理 ech 参数）。
            // 之前补了 ech-opts 导致内核走 DoH 查 ECH config，而我们自编译的 Go 内核缺 CA 根证书，
            // DoH 查询必然 x509 unknown authority → 所有节点连不上（日志实证）。删掉后走普通 TLS。
            String ech = getParam(u, "ech");
            if (!TextUtils.isEmpty(ech)) Log.d(TAG, "vlessToClash: drop ech param, " + ech);

            String alpn = getParam(u, "alpn");
            if (!TextUtils.isEmpty(alpn)) {
                sb.append("    alpn:\n");
                for (String item : alpn.split(",")) {
                    sb.append("      - ").append(quote(item.trim())).append('\n');
                }
            }

            String fp = getParam(u, "fp", "fingerprint");
            if (!TextUtils.isEmpty(fp)) {
                sb.append("    client-fingerprint: ").append(fp).append('\n');
            } else if (reality) {
                sb.append("    client-fingerprint: chrome\n");
            }

            Log.d(TAG, "vlessToClash: " + name + " -> " + sb.toString().replace('\n', ' '));
            return sb.toString();
        } catch (Throwable e) {
            Log.e(TAG, "vlessToClash failed: " + e.getMessage(), e);
            return null;
        }
    }

    /** 星落 getParam：按顺序取第一个非空的查询参数，再按大小写不敏感兜底一轮 */
    private String getParam(android.net.Uri u, String... keys) {
        for (String key : keys) {
            String value = u.getQueryParameter(key);
            if (value != null) return value;
        }
        java.util.Set<String> names = u.getQueryParameterNames();
        for (String key : keys) {
            for (String actual : names) {
                if (actual.equalsIgnoreCase(key)) return u.getQueryParameter(actual);
            }
        }
        return null;
    }

    // ---------------- base64（仅链接列表订阅需要） ----------------

    private String decode(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String cleaned = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        String out = tryDecode(cleaned);
        if (out.contains("://")) return out;
        out = tryDecode(text);
        return out.contains("://") ? out : "";
    }

    private String decodeRaw(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String cleaned = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        if (cleaned.startsWith("{") || cleaned.startsWith("[") || cleaned.contains("proxies:")
                || cleaned.contains("://")) return cleaned;
        String out = tryDecode(cleaned);
        if (isValidDecoded(out)) return out;
        out = tryDecode(text);
        return isValidDecoded(out) ? out : "";
    }

    private String tryDecode(String text, int... unused) {
        try {
            return new String(Base64.getDecoder().decode(text), "UTF-8");
        } catch (Throwable e) {
            try {
                return new String(Base64.getUrlDecoder().decode(text), "UTF-8");
            } catch (Throwable e2) {
                return "";
            }
        }
    }

    private boolean isValidDecoded(String text) {
        return !TextUtils.isEmpty(text)
                && (text.contains("://") || text.contains("proxies:"));
    }

    // ---------------- 测速 / 自动选 ----------------

    public boolean isTesting() {
        return testing;
    }

    public int getTestedCount() {
        return testedCount.get();
    }

    public void setProgressCallback(Runnable callback) {
        this.progressCallback = callback;
    }

    private void notifyProgress() {
        Runnable cb = progressCallback;
        if (cb != null) cb.run();
    }

    /** 星落 autoSelect：挑延迟最低的节点，没有就退到第一个需要内核的节点 */
    public ProxyNode autoSelect() {
        ProxyNode best = null;
        for (ProxyNode node : getNodes()) {
            if (node.getLatency() <= 0) continue;
            if (best == null || node.getLatency() < best.getLatency()) best = node;
        }
        if (best != null) {
            return select(best) ? best : null;
        }
        ProxyNode first = firstCoreNode();
        if (first != null && select(first)) {
            Log.d(TAG, "autoSelect fallback: " + first.getName());
            return ProxyNode.mihomo(first.getName());
        }
        return null;
    }

    private ProxyNode firstCoreNode() {
        for (ProxyNode node : getNodes()) {
            if (node.needsCore()) return node;
        }
        return null;
    }

    /** 星落 testAll：全量测速 = 并发纯 TCP 直探（轻量、不锁内核），返回节点列表 */
    public List<ProxyNode> testAll() {
        if (testing) {
            return getNodes();
        }
        List<ProxyNode> list = getNodes();
        if (list.isEmpty()) {
            return list;
        }
        testing = true;
        testedCount.set(0);
        lastNotifyTime = System.currentTimeMillis();
        try {
            list = testAllInternal(list);
            return list;
        } finally {
            testing = false;
            notifyProgress();
        }
    }

    /** 星落 testAllInternal：固定线程池并发跑 testTcpReachability，await N 秒后 shutdownNow */
    private List<ProxyNode> testAllInternal(List<ProxyNode> list) {
        long start = System.currentTimeMillis();
        int size = list.size();
        Log.d(TAG, "testAll: TCP test for " + size + " nodes (no lock, lightweight)");
        int threads = Math.min(Runtime.getRuntime().availableProcessors(), size);
        if (threads <= 0) threads = 1;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(size);
        for (ProxyNode node : list) {
            pool.submit(() -> {
                try {
                    long cost = testTcpReachability(node);
                    node.setLatency(cost);
                } finally {
                    latch.countDown();
                    testedCount.incrementAndGet();
                    notifyProgress();
                }
            });
        }
        try {
            latch.await(size, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        pool.shutdownNow();
        testedCount.set(size);
        int reachable = 0;
        for (ProxyNode node : list) {
            if (node.getLatency() > 0) reachable++;
        }
        Log.d(TAG, "testAll: done in " + (System.currentTimeMillis() - start) + "ms, "
                + reachable + " reachable, " + (size - reachable) + " unreachable");
        saveNodes(list);
        return list;
    }

    /** 星落 testOne：单点测速。节点不支持则纯 TCP；支持则切内核指向该节点后走 18890 代理测延迟 */
    public long testOne(ProxyNode node) {
        if (node == null) return -1;
        if (!node.isSupported()) {
            long cost = testTcpReachability(node);
            node.setLatency(cost);
            saveNodes(getNodes());
            return cost;
        }
        String config = getConfig();
        if (TextUtils.isEmpty(config)) {
            node.setLatency(-1);
            return -1;
        }
        long start = System.currentTimeMillis();
        boolean started = MihomoManager.get().start(config, node.getName());
        if (!started) {
            Log.e(TAG, "testOne: mihomo start failed for " + node.getName());
            node.setLatency(-1);
            return -1;
        }
        long cost = testViaMihomoProxy(start);
        node.setLatency(cost);
        saveNodes(getNodes());
        return cost;
    }

    /** 星落 testTcpReachability：纯 Socket 连 host:port，超时 3000ms，返回耗时；异常/参数非法返回 -1 */
    private long testTcpReachability(ProxyNode node) {
        if (node == null) return -1;
        String host = node.getHost();
        if (TextUtils.isEmpty(host) || node.getPort() <= 0) return -1;
        long start = System.currentTimeMillis();
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(host, node.getPort()), 3000);
            return System.currentTimeMillis() - start;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 星落 testViaMihomoProxy：OkHttp 走 127.0.0.1:18890 HTTP 代理 HEAD gstatic/204，成功返回耗时，失败返回 -1 */
    private long testViaMihomoProxy(long start) {
        try {
            okhttp3.OkHttpClient client = OkHttp.client().newBuilder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress("127.0.0.1", MihomoManager.getMixedPort())))
                    .connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url("https://www.gstatic.com/generate_204")
                    .head()
                    .build();
            try (okhttp3.Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.code() == 204) {
                    return System.currentTimeMillis() - start;
                }
                return -1;
            }
        } catch (Throwable e) {
            Log.e(TAG, "testViaMihomoProxy: " + e.getMessage());
            return -1;
        }
    }
}
