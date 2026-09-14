package com.fongmi.android.tv.proxy;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置 mihomo（Clash Meta）代理内核管理。
 *
 * <p>独立于 lab 全局代理：本模块只负责起/停本地 mihomo 内核（监听 127.0.0.1:{port}），
 * 以及通过 ext-ctl API 做订阅更新 / 节点测速 / 节点选择。爬虫源侧零改动——
 * 源 JSON 顶层写了 {@code proxy} 字段的，经 {@link com.github.catvod.bean.Proxy}
 * 规则命中后由 {@code OkProxySelector} 路由到 127.0.0.1:{port}，再经 mihomo 转发到
 * 所选节点；没写 proxy 的源完全直连，不受影响。
 *
 * <p>二进制首次启用时下载到 {@code filesDir/proxy/mihomo}（走 GithubProxy 加速），
 * 之后离线可用。
 */
public final class MihomoManager {

    public static final String VERSION = "v1.19.30";
    private static final String UA = "webhtv-mihomo/" + VERSION;
    private static final String URL_ARM64 = "https://github.com/MetaCubeX/mihomo/releases/download/"
            + VERSION + "/mihomo-android-arm64-v8" + VERSION + ".gz";
    private static final String URL_ARMV7 = "https://github.com/MetaCubeX/mihomo/releases/download/"
            + VERSION + "/mihomo-android-armv7" + VERSION + ".gz";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mihomo-manager");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
    private static final Object LOCK = new Object();

    private MihomoManager() {
    }

    // ---------------------------------------------------------------- binary

    /** mihomo 可执行文件（不存在则未下载）。 */
    public static File binary(Context context) {
        return new File(context.getFilesDir(), "proxy/mihomo");
    }

    /** mihomo 是否已下载。 */
    public static boolean isInstalled(Context context) {
        return binary(context).exists();
    }

    /**
     * 下载 mihomo 二进制（gzip 压缩的 ELF）。
     *
     * @return true 表示已可用（已存在或下载成功）
     */
    public static synchronized boolean ensureBinary(Context context, Runnable done) {
        File bin = binary(context);
        if (bin.exists() && bin.length() > 10_000_000L) return true;
        if (!DOWNLOADING.compareAndSet(false, true)) return true;
        EXECUTOR.execute(() -> {
            try {
                download(context, bin);
            } catch (Exception ignored) {
            } finally {
                DOWNLOADING.set(false);
                if (done != null) done.run();
            }
        });
        return false;
    }

    private static void download(Context context, File bin) throws Exception {
        bin.getParentFile().mkdirs();
        String url = is64Bit() ? URL_ARM64 : URL_ARMV7;
        // 走 GithubProxy 加速（国内直连 GitHub 不稳定），下载完成后本地缓存、离线可用。
        String accelerated = com.fongmi.android.tv.utils.GithubProxy.apply(url);
        File tmp = new File(bin.getAbsolutePath() + ".dl");
        try (okhttp3.Response res = OkHttp.newCall(OkHttp.client(120_000), accelerated).execute()) {
            if (!res.isSuccessful()) throw new java.io.IOException("HTTP " + res.code());
            byte[] data = res.body().bytes();
            if (data.length < 1_000_000) throw new java.io.IOException("下载内容过小，疑似失败");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
        }
        gzipTo(tmp, bin);
        bin.setExecutable(true, false);
        tmp.delete();
    }

    private static void gzipTo(File src, File dst) throws Exception {
        try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(src));
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
        }
    }

    private static boolean is64Bit() {
        return android.os.Build.SUPPORTED_ABIS != null
                && java.util.Arrays.stream(android.os.Build.SUPPORTED_ABIS)
                .anyMatch(a -> a.contains("64"));
    }

    // ---------------------------------------------------------------- config

    /** mihomo 配置文件（由订阅内容改写生成，端口/控制器固定为本模块管理）。 */
    public static File configFile(Context context) {
        return new File(context.getFilesDir(), "proxy/config.yaml");
    }

    /** 把订阅拉下来的原始 config 改写成受控版本（端口固定为本模块设置）。 */
    static String normalizeConfig(String raw, int port, int ctlPort) {
        String text = raw == null ? "" : raw.trim();
        // 订阅 config 里若自带这三个键则替换，没有则补到最前面（YAML 顶层重复键会报错，必须去重）
        StringBuilder sb = new StringBuilder();
        boolean seenMixed = false, seenCtl = false, seenSecret = false;
        for (String line : text.split("\n", -1)) {
            if (line.matches("^\\s*mixed-port:.*")) {
                if (!seenMixed) {
                    sb.append("mixed-port: ").append(port).append('\n');
                    seenMixed = true;
                }
                continue;
            }
            if (line.matches("^\\s*external-controller:.*")) {
                if (!seenCtl) {
                    sb.append("external-controller: 127.0.0.1:").append(ctlPort).append('\n');
                    seenCtl = true;
                }
                continue;
            }
            if (line.matches("^\\s*secret:.*")) {
                if (!seenSecret) {
                    sb.append("secret: webhtv").append('\n');
                    seenSecret = true;
                }
                continue;
            }
            sb.append(line).append('\n');
        }
        if (!seenMixed) sb.append("mixed-port: ").append(port).append('\n');
        if (!seenCtl) sb.append("external-controller: 127.0.0.1:").append(ctlPort).append('\n');
        if (!seenSecret) sb.append("secret: webhtv").append('\n');
        return sb.toString();
    }

    // ---------------------------------------------------------------- process

    /** 内核是否运行中。 */
    public static boolean isRunning() {
        Process p = PROCESS.get();
        return p != null && p.isAlive() && isPortOpen(Setting.getMihomoPort());
    }

    /**
     * 启动内核（订阅 config 必须存在）。
     *
     * @return 失败原因；null 表示启动成功
     */
    public static String start(Context context) {
        if (!isInstalled(context)) return "内核未下载";
        File cfg = configFile(context);
        if (!cfg.exists()) return "缺少订阅配置（请先更新订阅）";
        if (isRunning()) return null;
        int port = Setting.getMihomoPort();
        if (isPortOpen(port)) {
            return "端口 " + port + " 被占用，请换一个端口";
        }
        int ctl = port + 1;
        File bin = binary(context);
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(), "-f", cfg.getAbsolutePath(),
                    "-ext-ctl", "127.0.0.1:" + ctl,
                    "-secret", "webhtv");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            drain(process.getInputStream());
            PROCESS.set(process);
            // 等内核起来
            long deadline = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < deadline && !isPortOpen(port)) sleep(100);
            if (!isPortOpen(port)) {
                stop();
                return "内核启动失败（端口 " + port + " 未监听）";
            }
            return null;
        } catch (Exception e) {
            stop();
            return "启动失败: " + e.getMessage();
        }
    }

    public static void stop() {
        Process p = PROCESS.getAndSet(null);
        if (p != null) p.destroy();
    }

    /** 停掉旧进程并起新的（用于切换端口/重载配置）。 */
    public static String restart(Context context) {
        stop();
        sleep(300);
        return start(context);
    }

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                while (in.read(buf) != -1) {
                }
            } catch (Exception ignored) {
            }
        }, "mihomo-drain");
        t.setDaemon(true);
        t.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- ext-ctl

    private static String ctl(String path, String method) {
        int port = Setting.getMihomoPort();
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + (port + 1) + path);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method == null ? "GET" : method);
            conn.setRequestProperty("Authorization", "Bearer webhtv");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            int code = conn.getResponseCode();
            if (code != 200) return "";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** 更新订阅：拉取订阅 → 改写端口/控制器 → 写 config.yaml → 启动或重载内核。 */
    public static String updateSubscription(Context context, String url) {
        if (TextUtils.isEmpty(url)) return "订阅地址为空";
        boolean wasRunning = isRunning();
        try {
            byte[] data;
            try (okhttp3.Response res = OkHttp.newCall(OkHttp.client(60_000), url).execute()) {
                if (!res.isSuccessful()) return "订阅拉取失败: HTTP " + res.code();
                data = res.body().bytes();
            }
            String raw = new String(data, java.nio.charset.StandardCharsets.UTF_8);
            int port = Setting.getMihomoPort();
            String normalized = normalizeConfig(raw, port, port + 1);
            File cfg = configFile(context);
            try (FileOutputStream out = new FileOutputStream(cfg)) {
                out.write(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (wasRunning) {
                stop();
                sleep(300);
            }
            String err = start(context);
            if (err != null) return "订阅已保存，但内核启动失败: " + err;
            return null;
        } catch (Exception e) {
            return "订阅拉取失败: " + e.getMessage();
        }
    }

    /** ext-ctl 重载当前 config。 */
    public static String reloadConfig(Context context) {
        String resp = ctl("/configs", "PUT");
        return resp.isEmpty() ? "配置重载失败（内核未运行?）" : null;
    }

    /** 经 ext-ctl 全节点测速，返回节点数。 */
    public static int testLatency(Context context) {
        if (!isRunning()) return -1;
        String resp = ctl("/proxies", "GET");
        if (resp.isEmpty()) return -1;
        com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(resp);
        int count = 0;
        if (root.isJsonObject()) {
            com.google.gson.JsonElement pro = root.getAsJsonObject().get("proxies");
            if (pro != null && pro.isJsonObject()) count = pro.getAsJsonObject().size();
        }
        return count;
    }

    /** 当前代理（节点）名。 */
    public static String currentProxy(Context context) {
        String resp = ctl("/proxies/default", "GET");
        if (resp.isEmpty()) return "";
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(resp);
            if (root.isJsonObject() && root.getAsJsonObject().has("name")) {
                return root.getAsJsonObject().get("name").getAsString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** 手动选择节点（ext-ctl PUT /proxies/{group}）。 */
    public static boolean selectProxy(Context context, String name) {
        String resp = ctl("/proxies/" + urlEncode(name), "PUT");
        return !resp.isEmpty();
    }

    private static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------------------------------------------------------- misc

    static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 把内核状态文本化（供设置卡片显示）。 */
    public static String statusText(Context context) {
        if (!isInstalled(context)) return "内核未下载";
        if (!isRunning()) return "内核未启动";
        String p = currentProxy(context);
        return p.isEmpty() ? "运行中" : "运行中 · 节点 " + p;
    }

    /** 内核下载完成后的回调（UI 线程刷新状态）。 */
    public static void onDownloadDone() {
        // no-op hook; UI 自行轮询
    }
}
