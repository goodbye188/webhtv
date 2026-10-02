package com.fongmi.android.tv.proxy;

import android.graphics.Color;
import android.net.Uri;
import android.text.TextUtils;

/** 照抄星落 5.9.9 com.fongmi.android.tv.proxy.ProxyNode（已逐行核对 smali）。 */
public class ProxyNode {

    private String name;
    private String scheme;
    private String host;
    private int port;
    private String userInfo;
    private String rawUri;
    private String proxyYaml;
    private boolean supported;
    private long latency = -1;

    public static ProxyNode mihomo(String name) {
        return fromUri(MihomoManager.getProxyUrl(name));
    }

    public static ProxyNode fromUri(String uri) {
        Uri parsed = Uri.parse(uri);
        String scheme = normalizeScheme(parsed.getScheme());
        if (TextUtils.isEmpty(scheme)) return null;
        if (TextUtils.isEmpty(parsed.getHost())) return null;
        if (parsed.getPort() <= 0) return null;
        ProxyNode node = new ProxyNode();
        node.scheme = scheme;
        node.host = parsed.getHost();
        node.port = parsed.getPort();
        node.userInfo = parsed.getUserInfo();
        String fragment = parsed.getFragment();
        node.name = TextUtils.isEmpty(fragment) ? parsed.getHost() + ":" + parsed.getPort() : Uri.decode(fragment);
        node.supported = isSupported(scheme);
        node.latency = -1;
        return node;
    }

    public static ProxyNode unsupported(String name, String scheme, String host, int port) {
        return unsupported(name, scheme, host, port, null);
    }

    public static ProxyNode unsupported(String name, String scheme, String host, int port, String rawUri) {
        ProxyNode node = new ProxyNode();
        node.name = name;
        node.scheme = scheme;
        node.host = host;
        node.port = port;
        node.supported = false;
        node.latency = -1;
        node.rawUri = rawUri;
        return node;
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null) return "";
        scheme = scheme.toLowerCase();
        if ("socks5h".equals(scheme)) return "socks5";
        if ("socks".equals(scheme)) return "socks5";
        return scheme;
    }

    /** 星落只承认这三种能直接当代理用的，其余都要走内核转换 */
    private static boolean isSupported(String scheme) {
        return "http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme);
    }

    private String buildAuthority() {
        String address = getAddress();
        String info = getUserInfo();
        if (!TextUtils.isEmpty(info)) address = info + "@" + address;
        return address;
    }

    public String getUrl() {
        if (!isSupported()) return "";
        return new Uri.Builder()
                .scheme(getScheme())
                .encodedAuthority(buildAuthority())
                .fragment(getName())
                .build()
                .toString();
    }

    public String getDisplay() {
        String scheme = getScheme().isEmpty() ? "" : " [" + getScheme() + "]";
        String latencyText = "";
        if (latency > 0) {
            latencyText = " · " + latency + "ms";
        } else if (latency == -2) {
            latencyText = " · timeout";
        }
        return getName() + scheme + latencyText;
    }

    public int getLatencyColor() {
        if (latency > 0 && latency < 2000) return Color.parseColor("#4CAF50");
        return Color.parseColor("#F44336");
    }

    public boolean needsCore() {
        return !isSupported();
    }

    public boolean isSupported() {
        return supported;
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? getAddress() : name;
    }

    public String getScheme() {
        return TextUtils.isEmpty(scheme) ? "" : scheme;
    }

    public String getAddress() {
        return getHost() + ":" + getPort();
    }

    public String getHost() {
        return TextUtils.isEmpty(host) ? "" : host;
    }

    public int getPort() {
        return port;
    }

    public String getUserInfo() {
        return TextUtils.isEmpty(userInfo) ? "" : userInfo;
    }

    public String getRawUri() {
        return TextUtils.isEmpty(rawUri) ? "" : rawUri;
    }

    public String getProxyYaml() {
        return TextUtils.isEmpty(proxyYaml) ? "" : proxyYaml;
    }

    public void setProxyYaml(String proxyYaml) {
        this.proxyYaml = proxyYaml;
    }

    public long getLatency() {
        return latency;
    }

    public void setLatency(long latency) {
        this.latency = latency;
    }

    @Override
    public String toString() {
        return getDisplay();
    }
}
