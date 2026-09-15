package com.fongmi.android.tv.proxy;

import android.content.Context;
import android.text.TextUtils;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
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
            + VERSION + "/mihomo-android-arm64-v8-" + VERSION + ".gz";
    private static final String URL_ARMV7 = "https://github.com/MetaCubeX/mihomo/releases/download/"
            + VERSION + "/mihomo-android-armv7-" + VERSION + ".gz";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mihomo-manager");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicReference<Process> PROCESS = new AtomicReference<>();
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();
    private static final Object LOCK = new Object();
    /** 内核就绪操作（内置解压 / 网络下载）互斥锁：防并发写同一文件。 */
    private static final Object ASYNC_LOCK = new Object();

    private MihomoManager() {
    }

    // ---------------------------------------------------------------- binary

    /**
     * mihomo 可执行文件：{@code filesDir/proxy/mihomo}（网络下载得到，ELF 头校验）。
     * 不再走 nativeLibraryDir/libmihomo.so（该 .so 已删除，内核只此一路）。
     */
    public static File binary(Context context) {
        return new File(context.getFilesDir(), "proxy/mihomo");
    }

    /** 内置内核资产（assets/mihomo/mihomo，按 ABI flavor 内置对应架构，未压缩 ELF 直接拷出）。 */
    private static final String ASSET_KERNEL = "mihomo/mihomo";

    /** APK 里是否内置了内核资产（仅 v7a 等无 .so 的包走这条拷出路径）。 */
    public static boolean hasEmbedded(Context context) {
        try {
            context.getAssets().open(ASSET_KERNEL).close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从内置资产拷出内核（离线可用，不碰网络）。assets 里是未压缩 ELF，直接拷出。
     *
     * @param progress 进度回调（0..1），可为 null
     * @return null 表示成功；否则为失败原因
     */
    public static String extractFromAssets(Context context, java.util.function.Consumer<DownloadProgress> progress) {
        if (!hasEmbedded(context)) return "APK 未内置内核资产";
        File bin = binary(context);
        if (isInstalled(context)) return null; // 已就绪（含并发方刚解压完）
        File tmp = new File(bin.getAbsolutePath() + ".asset");
        // 与网络下载共用 ASYNC_LOCK，防并发写同一文件
        synchronized (ASYNC_LOCK) {
            if (isInstalled(context)) return null;
            try {
                long got = 0;
                try (java.io.InputStream in = context.getAssets().open(ASSET_KERNEL);
                     FileOutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                        got += len;
                        if (progress != null) {
                            // 拷贝阶段（assets 流不报总长，按已拷字节显示）
                            progress.accept(new DownloadProgress(0.0f, got, -1));
                        }
                    }
                }
                if (!tmp.renameTo(bin)) {
                    // 跨文件系统 rename 失败兜底：直接拷
                    try (java.io.InputStream in = new FileInputStream(tmp);
                         FileOutputStream out = new FileOutputStream(bin)) {
                        byte[] buf = new byte[65536];
                        int len;
                        while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
                    }
                }
                bin.setExecutable(true, false);
                if (progress != null) progress.accept(new DownloadProgress(1.0f, 0, 0));
                if (isInstalled(context)) return null;
                return "内置内核拷出后校验失败";
            } catch (Exception e) {
                bin.delete();
                return "内置内核拷出失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                tmp.delete();
            }
        }
    }

    /**
     * 确保二进制就绪：内置资产优先（离线快），失败再走网络多源下载。
     *
     * @return null 表示已就绪；否则为失败原因
     */
    public static String ensureKernel(Context context, java.util.function.Consumer<DownloadProgress> progress) {
        if (isInstalled(context)) return null;
        if (hasEmbedded(context)) {
            String err = extractFromAssets(context, progress);
            if (err == null) return null;
            // 内置失败（如架构不匹配）→ 落回网络下载
        }
        return downloadKernel(context, progress);
    }

    /** mihomo 是否已下载（按大小判定，残留小文件不算）。 */
    public static boolean isInstalled(Context context) {
        File bin = binary(context);
        if (!bin.exists() || !bin.isFile() || bin.length() <= 10_000_000L) {
            return false;
        }

        // ELF magic: reject incomplete downloads, HTML error pages, etc.
        try (FileInputStream in = new FileInputStream(bin)) {
            byte[] magic = new byte[4];
            if (in.read(magic) != 4
                    || magic[0] != 0x7f
                    || magic[1] != 'E'
                    || magic[2] != 'L'
                    || magic[3] != 'F') {
                return false;
            }
        } catch (Exception e) {
            return false;
        }

        if (!bin.canExecute()) {
            try {
                bin.setExecutable(true, false);
            } catch (Exception ignored) {
            }
        }
        return bin.canExecute();
    }

    /**
     * 下载 mihomo 二进制（gzip 压缩的 ELF）。
     *
     * @return true 表示已可用（已存在或下载成功）
     */
    public static synchronized boolean ensureBinary(Context context, Runnable done) {
        File bin = binary(context);
        if (isInstalled(context)) return true;
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

    /**
     * 同步确保二进制就绪（阻塞直到下载完成）。供「更新订阅/启用」流程调用，
     * 避免异步下载未完成就 start() 导致「内核未下载」。
     *
     * @return null 表示已就绪；否则为失败原因
     */
    public static String ensureBinaryBlocking(Context context, int timeoutMs) {
        File bin = binary(context);
        if (isInstalled(context)) return null;
        CountDownLatch latch = new CountDownLatch(1);
        String[] err = { null };
        if (DOWNLOADING.compareAndSet(false, true)) {
            EXECUTOR.execute(() -> {
                try {
                    download(context, bin);
                } catch (Exception e) {
                    err[0] = "内核下载失败: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                } finally {
                    DOWNLOADING.set(false);
                    latch.countDown();
                }
            });
            try {
                if (!latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    return "内核下载超时（" + (timeoutMs / 1000) + "s），请检查网络后重试";
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (err[0] != null) return err[0];
            if (isInstalled(context)) return null;
            return "内核下载未完成";
        }
        // 别人正在下载：等它
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (DOWNLOADING.get() && System.currentTimeMillis() < deadline) sleep(200);
        return isInstalled(context) ? null : "内核下载未完成";
    }

    /** 下载进度。totalBytes>0 时 fraction 可靠；未知总长时 fraction=0，用 bytes 显示。 */
    public record DownloadProgress(float fraction, long bytes, long totalBytes) {
        public boolean totalKnown() {
            return totalBytes > 0;
        }
    }

    /**
     * 纯下载内核（只下二进制，不碰订阅、不启动进程）。
     * 多源 fallback：按序尝试各 GitHub 加速源，最后回退直连；单源超时/失败自动换下一个。
     *
     * @param progress 进度回调，可为 null
     * @return null 表示成功；否则为失败原因
     */
    public static String downloadKernel(Context context, java.util.function.Consumer<DownloadProgress> progress) {
        File bin = binary(context);
        if (isInstalled(context)) return null; // 已下载过（>10MB 才算）
        if (!DOWNLOADING.compareAndSet(false, true)) {
            // 别人正在下，简单等它完成
            long deadline = System.currentTimeMillis() + 120_000;
            while (DOWNLOADING.get() && System.currentTimeMillis() < deadline) sleep(300);
            return isInstalled(context) ? null : "内核下载未完成";
        }
        List<String> errors = new ArrayList<>();
        try {
            for (String candidate : candidateUrls()) {
                try {
                    download(context, bin, candidate, progress);
                    return null;
                } catch (Exception e) {
                    errors.add(candidate + " -> " + e.getMessage());
                    bin.delete(); // 清残留，避免下次被误判为已下载
                }
            }
            return "内核下载失败（全部来源均失败）: " + String.join(" | ", errors);
        } finally {
            DOWNLOADING.set(false);
        }
    }

    /** 候选下载源：官方仓库经 gh-proxy.com 强制加速（首选）→ ghfast.top 兜底 → 官方仓库直连。 */
    static List<String> candidateUrls() {
        String direct = is64Bit() ? URL_ARM64 : URL_ARMV7;
        List<String> list = new ArrayList<>();
        list.add("https://gh-proxy.com/" + direct);
        list.add("https://ghfast.top/" + direct);
        list.add(direct);
        return list;
    }

    /** 带进度的下载（流式读 + 解压）。progress 可为 null；直接用给定 url（已由调用方拼好加速前缀）。 */
    private static void download(Context context, File bin, String url, java.util.function.Consumer<DownloadProgress> progress) throws Exception {
        bin.getParentFile().mkdirs();
        File tmp = new File(bin.getAbsolutePath() + ".dl");
        try (okhttp3.Response res = OkHttp.newCall(OkHttp.client(120_000), url).execute()) {
            if (!res.isSuccessful() || res.body() == null) {
                throw new java.io.IOException(res.body() == null ? "空响应" : "HTTP " + res.code());
            }
            long total = res.body().contentLength(); // 可能 -1（加速源不报总长）
            try (java.io.InputStream body = res.body().byteStream();
                 FileOutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[65536];
                int len;
                long got = 0;
                while ((len = body.read(buf)) != -1) {
                    out.write(buf, 0, len);
                    got += len;
                    if (progress != null) {
                        float frac = total > 0 ? (float) Math.min(1.0, (double) got / total * 0.9) : 0.0f;
                        progress.accept(new DownloadProgress(frac, got, total));
                    }
                }
            }
            if (tmp.length() < 1_000_000) throw new java.io.IOException("下载内容过小，疑似失败");
            if (progress != null) progress.accept(new DownloadProgress(0.9f, 0, 0)); // 下载完成，开始解压
            gzipTo(tmp, bin, progress);
            bin.setExecutable(true, false);
            if (progress != null) progress.accept(new DownloadProgress(1.0f, 0, 0));
        } finally {
            tmp.delete();
        }
    }

    private static void download(Context context, File bin) throws Exception {
        List<String> errors = new ArrayList<>();
        for (String candidate : candidateUrls()) {
            try {
                download(context, bin, candidate, null);
                return;
            } catch (Exception e) {
                errors.add(e.getMessage());
                bin.delete();
            }
        }
        throw new java.io.IOException("内核下载失败（全部来源均失败）: " + String.join(" | ", errors));
    }

    private static void gzipTo(File src, File dst, java.util.function.Consumer<DownloadProgress> progress) throws Exception {
        try (InputStream in = new java.util.zip.GZIPInputStream(new java.io.FileInputStream(src));
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            long got = 0;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
                got += len;
                if (progress != null) progress.accept(new DownloadProgress(0.9f + (float) (got % 1000000) / 1000000 * 0.1f, 0, 0));
            }
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

    /** 内核是否运行中（仅看本进程内的子进程句柄，app 重启后为 false）。 */
    public static boolean isRunning() {
        Process p = PROCESS.get();
        return p != null && p.isAlive() && isPortOpen(Setting.getMihomoPort());
    }

    /**
     * 内核是否真实运行中（含 app 重启后接管的远端实例：端口在监听且 ext-ctl 带
     * secret=webhtv 应答）。UI 的开关/状态一律以此为准，避免"开关开着但内核早死了"。
     */
    public static boolean isRunning(Context context) {
        if (isRunning()) return true;
        return probeRemoteMihomo();
    }

    /**
     * 启动内核。
     * 二进制缺失 → 提示先下载；无订阅 config → 自动生成最小 config（纯空壳代理，先跑起来）。
     *
     * @return 失败原因；null 表示启动成功
     */
    public static String start(Context context) {
        if (!isInstalled(context)) return "内核未下载，请先点「下载内核」";
        File cfg = configFile(context);
        if (!cfg.exists()) {
            // 无订阅也能启动：生成最小 config（只有端口/控制器），内核空壳运行
            try {
                File parent = cfg.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                int p = Setting.getMihomoPort();
                java.nio.file.Files.write(cfg.toPath(),
                        normalizeConfig("", p, p + 1).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            if (!cfg.exists()) return "配置文件创建失败";
        }
        if (isRunning()) return null;
        int port = Setting.getMihomoPort();
        if (isPortOpen(port)) {
            // app 重启后静态句柄丢失，但上次的 mihomo 子进程可能还活着占着端口。
            // 探活确认是咱自己的 mihomo（ext-ctl 带 secret=webhtv 才应答）就直接接管，不报错。
            if (probeRemoteMihomo()) return null;
            return "端口 " + port + " 被其他程序占用，请换一个端口";
        }
        int ctl = port + 1;
        File bin = binary(context);
        // -d 指定可写工作目录：mihomo 启动时会对 homeDir 做 config.Init（MkdirAll + 建 config.yaml），
        // 不传则用默认目录，Android 上不可写 → Fatal 秒退 → 端口不监听。
        // 固定用 filesDir/proxy（可写）。
        File homeDir = new File(context.getFilesDir(), "proxy");
        if (!homeDir.exists() && !homeDir.mkdirs()) {
            return "内核目录创建失败: " + homeDir.getAbsolutePath();
        }
        try {
            if (!bin.exists()) {
                return "mihomo 二进制不存在: " + bin.getAbsolutePath();
            }
            if (!bin.isFile()) {
                return "mihomo 二进制不是普通文件: " + bin.getAbsolutePath();
            }
            if (!bin.canExecute()) {
                try {
                    bin.setExecutable(true, false);
                } catch (Exception ignored) {
                }
                if (!bin.canExecute()) {
                    return "mihomo 二进制没有执行权限: " + bin.getAbsolutePath();
                }
            }

            ProcessBuilder pb = new ProcessBuilder(
                    bin.getAbsolutePath(), "-d", homeDir.getAbsolutePath(),
                    "-f", cfg.getAbsolutePath(),
                    "-ext-ctl", "127.0.0.1:" + ctl,
                    "-secret", "webhtv");
            pb.redirectErrorStream(true);
            Process process;
            try {
                process = pb.start();
            } catch (Exception e) {
                // 显式捕获:exec 层失败(Permission denied / Exec format error / No such file)
                // 在这里抛,直接回显完整堆栈,避免被外层 catch 吃掉细节。
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                stop();
                return "mihomo 进程启动异常:\n" + sw;
            }
            StringBuilder logBuf = drain(process.getInputStream());
            // Detect an immediate crash and expose its output instead of only
            // reporting that the port was not listening.
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!process.isAlive()) {
                int exit = process.exitValue();
                return "mihomo 启动后立即退出（退出码 " + exit
                        + "，二进制 " + bin.getAbsolutePath() + "）" + logTail(logBuf);
            }
            PROCESS.set(process);
            // 等内核起来
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline && !isPortOpen(port)) sleep(100);
            if (!isPortOpen(port)) {
                // 进程秒退:带退出码定位(1=参数/config 错,126/127=不可执行/格式错,126=无执行权限)
                int exit = -1;
                try {
                    exit = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                            ? process.exitValue() : -1;
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                stop();
                String log = logTail(logBuf);
                return "内核启动失败（端口 " + port + " 未监听，退出码 " + exit
                        + "，二进制 " + bin.getAbsolutePath() + "）" + log;
            }
            return null;
        } catch (Exception e) {
            stop();
            return "启动失败: " + e.getMessage();
        }
    }

    /**
     * 等待内核稳定运行一小段时间，避免 start()/reload 后 UI 立即误报成功。
     */
    public static boolean waitUntilRunning(Context context, long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        do {
            if (isRunning(context)) return true;
            sleep(100);
        } while (System.currentTimeMillis() < deadline);
        return isRunning(context);
    }

    public static void stop() {
        Process p = PROCESS.getAndSet(null);
        if (p != null) p.destroy();
    }

    /** 经 ext-ctl 探活：端口上的进程是不是我们配的 mihomo（带 secret=webhtv 才应答 Bearer 请求）。 */
    public static boolean probeRemoteMihomo() {
        return !ctl("/version", "GET").isEmpty();
    }

    /** 停掉旧进程并起新的（用于切换端口/重载配置）。 */
    public static String restart(Context context) {
        stop();
        sleep(300);
        return start(context);
    }

    /**
     * 订阅/config 变更后的幂等动作：内核已运行（含接管远端实例）→ 重载新配置；未运行 → 直接启动。
     * 返回 null 成功；否则为失败原因（用于 toast 提示）。
     */
    public static String reloadOrStart(Context context) {
        if (isRunning(context)) {
            return reloadConfig(context);
        }
        return start(context);
    }

    /** 排空内核 stdout（防缓冲区写满阻塞），并保留最后 12KB 文本用于启动失败诊断。返回共享 StringBuilder。 */
    private static StringBuilder drain(InputStream in) {
        StringBuilder tail = new StringBuilder();
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) != -1) {
                    synchronized (tail) {
                        tail.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                        if (tail.length() > 12_000) tail.delete(0, tail.length() - 12_000);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "mihomo-drain");
        t.setDaemon(true);
        t.start();
        return tail;
    }

    private static String logTail(StringBuilder sb) {
        synchronized (sb) {
            String s = sb.toString().trim();
            return s.isEmpty() ? "" : "，内核输出: " + s;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- ext-ctl

    /** 最近一次 ext-ctl 响应码（-1 = 异常无响应），非 200 时供诊断拼进报错。 */
    static volatile int lastCtlCode = -1;
    static volatile String lastCtlError = "";

    private static String ctl(String path, String method) {
        int port = Setting.getMihomoPort();
        String m = method == null ? "GET" : method;
        lastCtlCode = -1;
        lastCtlError = "";
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + (port + 1) + path);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod(m);
            conn.setRequestProperty("Authorization", "Bearer webhtv");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(30000);
            // PUT/POST 必须带合法 body，否则 mihomo 拒绝（415/405）→ 被误判成「无响应」
            if ("PUT".equals(m) || "POST".equals(m)) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                byte[] body = m.equals("PUT") && path.equals("/configs")
                        ? "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8)
                        : new byte[0];
                conn.getOutputStream().write(body);
            }
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                // 保留非 2xx 响应码 + 响应体, 下次排查直接看到 401/405/415 而不是空串
                lastCtlCode = code;
                try (java.io.InputStream es = conn.getErrorStream()) {
                    if (es != null) lastCtlError = new String(es.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                } catch (Exception ignored) {
                }
                return "";
            }
            // 2xx 全算成功：PUT /configs 重载成功返回 204 No Content（无 body），
            // 2xx 时读 body（204 没有就返回空串，属正常），不再把 204 误判成失败。
            lastCtlCode = code;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
                return sb.toString();
            }
        } catch (Exception e) {
            lastCtlError = String.valueOf(e);
            return "";
        }
    }

    /** 更新订阅：拉取订阅 → 识别内容类型（clash YAML 直接改写 / vless:// 行列表转 YAML）→ 写 config.yaml。 */
    public static String updateSubscription(Context context, String url) {
        if (TextUtils.isEmpty(url)) return "订阅地址为空";
        try {
            byte[] data;
            try (okhttp3.Response res = OkHttp.newCall(OkHttp.client(60_000), url).execute()) {
                if (!res.isSuccessful()) return "订阅拉取失败: HTTP " + res.code();
                data = res.body().bytes();
            }
            String raw = new String(data, java.nio.charset.StandardCharsets.UTF_8).trim();
            int port = Setting.getMihomoPort();
            String normalized;
            if (isVlessLines(raw)) {
                // 订阅是 vless:// 节点行列表（可能 base64 编码）：内核只吃 clash YAML，先转换
                String decoded = maybeDecodeBase64(raw);
                normalized = buildConfigFromVless(decoded, port, port + 1, true);
                if (normalized == null) return "订阅解析失败：未找到有效的 vless:// 节点";
            } else {
                // 订阅本身就是 clash YAML config：按原逻辑改写受控键
                normalized = normalizeConfig(maybeDecodeBase64(raw), port, port + 1);
            }
            File cfg = configFile(context);
            File parent = cfg.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return "配置目录创建失败: " + parent.getAbsolutePath();
            }
            try (FileOutputStream out = new FileOutputStream(cfg)) {
                out.write(normalized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return null;
        } catch (Exception e) {
            return "订阅拉取失败: " + e.getMessage();
        }
    }

    /** 原始文本里是否含 vless:// 节点行（含 base64 解码后的情况）。 */
    static boolean isVlessLines(String raw) {
        if (raw == null) return false;
        if (raw.contains("vless://")) return true;
        String decoded = maybeDecodeBase64(raw);
        return decoded != null && decoded.contains("vless://");
    }

    /** 看起来像 base64（且不含 YAML 结构）则解码；失败原样返回。 */
    private static String maybeDecodeBase64(String raw) {
        if (raw == null) return null;
        String s = raw.replaceAll("\\s", "");
        if (s.length() < 32 || !s.matches("[A-Za-z0-9+/]+={0,2}")) return raw;
        try {
            String out = new String(java.util.Base64.getMimeDecoder().decode(s),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (out.contains("vless://") || out.contains("proxies:")) return out;
        } catch (Exception ignored) {
        }
        return raw;
    }

    /** 从 vless:// 行列表生成完整 clash YAML。返回 null 表示没有可解析的节点。 */
    static String buildConfigFromVless(String text, int port, int ctlPort, boolean withEch) {
        List<String[]> nodes = new ArrayList<>(); // {name, host, port, paramsCsv, uuid}
        for (String line : text.split("\\r?\\n")) {
            String l = line.trim();
            if (!l.startsWith("vless://")) continue;
            String[] p = parseVless(l);
            if (p != null) nodes.add(p);
        }
        if (nodes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("proxies:\n");
        List<String> names = new ArrayList<>();
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String[] n : nodes) {
            String name = n[0], host = n[1], pport = n[2], uuid = n[4];
            // mihomo 要求 proxy name 全局唯一：订阅里同地区节点常同名，重名时附 host 区分
            if (!used.add(name)) {
                name = name + " " + host;
                used.add(name);
                n[0] = name;
            }
            String query = n[3];
            Map<String, String> q = new HashMap<>();
            for (String pair : query.split("&")) {
                int eq = pair.indexOf('=');
                if (eq <= 0) continue;
                q.put(pair.substring(0, eq).trim(), unquote(pair.substring(eq + 1)));
            }
            boolean tls = "tls".equals(q.get("security"));
            sb.append("- name: ").append(yamlQuote(name)).append('\n');
            sb.append("  type: vless\n");
            sb.append("  server: ").append(host).append('\n');
            sb.append("  port: ").append(pport).append('\n');
            sb.append("  uuid: ").append(uuid).append('\n');
            String net = q.get("type");
            if (net != null && !"tcp".equals(net)) sb.append("  network: ").append(net).append('\n');
            if (tls) {
                sb.append("  tls: true\n");
                String sni = q.get("sni");
                if (sni == null || sni.isEmpty()) sni = q.get("host");
                if (sni != null && !sni.isEmpty()) sb.append("  servername: ").append(sni).append('\n');
                String fp = q.get("fp");
                if (fp != null && !fp.isEmpty()) sb.append("  fingerprint: ").append(fp).append('\n');
                String enc = q.get("encryption");
                if (enc != null && !enc.isEmpty() && !"none".equals(enc)) sb.append("  encryption: ").append(enc).append('\n');
                String ech = q.get("ech");
                if (withEch && ech != null && !ech.isEmpty()) {
                    sb.append("  ech-opts:\n");
                    sb.append("    enable: true\n");
                    int plus = ech.indexOf('+');
                    sb.append("    query-server-name: ")
                            .append(plus > 0 ? ech.substring(0, plus) : ech).append('\n');
                }
            }
            if ("ws".equals(net)) {
                sb.append("  ws-opts:\n");
                sb.append("    path: ").append(yamlQuote(q.get("path") == null ? "/" : q.get("path"))).append('\n');
                String wshost = q.get("host");
                if (wshost != null && !wshost.isEmpty()) {
                    sb.append("    headers:\n");
                    sb.append("      Host: ").append(wshost).append('\n');
                }
            }
            sb.append("  udp: true\n");
            names.add(name);
        }
        sb.append("proxy-groups:\n");
        sb.append("- name: PROXY\n");
        sb.append("  type: select\n");
        sb.append("  proxies:\n");
        for (String n : names) sb.append("  - ").append(yamlQuote(n)).append('\n');
        sb.append("  - DIRECT\n");
        sb.append("mixed-port: ").append(port).append('\n');
        sb.append("external-controller: 127.0.0.1:").append(ctlPort).append('\n');
        sb.append("secret: webhtv\n");
        sb.append("allow-lan: false\n");
        sb.append("mode: rule\n");
        sb.append("dns:\n");
        sb.append("  servers: [\"system\", \"8.8.8.8\", \"1.1.1.1\"]\n");
        sb.append("rules:\n");
        sb.append("- MATCH,PROXY\n");
        return sb.toString();
    }

    /**
     * 解析 vless://uuid@host:port?params#name → [name, host, port, paramsCsv, uuid]。
     * 解析不出返回 null。
     */
    static String[] parseVless(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("vless://([0-9a-fA-F][0-9a-fA-F\\-]{0,35})@([^:/?]+):(\\d+)\\?([^#]+)(?:#(.+))?$")
                .matcher(line.trim());
        if (!m.find()) return null;
        String uuid = m.group(1), host = m.group(2), port = m.group(3), query = m.group(4);
        String name = m.group(5);
        if (name == null) name = "vless-" + host;
        name = unquote(name);
        return new String[]{name, host, port, query, uuid};
    }

    private static String unquote(String s) {
        if (s == null) return null;
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String yamlQuote(String s) {
        // YAML 单引号字符串里单引号要双写转义
        return "'" + (s == null ? "" : s.replace("'", "''")) + "'";
    }

    /** ext-ctl 重载当前 config。 */
    public static String reloadConfig(Context context) {
        if (!isRunning() && !probeRemoteMihomo()) {
            return "配置重载失败：内核未运行";
        }
        ctl("/configs", "PUT");
        if (lastCtlCode < 200 || lastCtlCode >= 300) {
            return "配置重载失败：内核控制接口无响应 (HTTP " + lastCtlCode + " " + lastCtlError + ")";
        }
        // 重载后内核重新初始化需片刻，等端口稳定再报成功，避免误报
        long deadline = System.currentTimeMillis() + 3000;
        while (!isRunning(context) && System.currentTimeMillis() < deadline) sleep(100);
        return isRunning(context) ? null
                : "配置重载后内核未监听端口 " + Setting.getMihomoPort();
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

    /** 手动选择节点（ext-ctl PUT /proxies/{group}，成功返回 2xx，204 无 body 也算成功）。 */
    public static boolean selectProxy(Context context, String name) {
        ctl("/proxies/" + urlEncode(name), "PUT");
        return lastCtlCode >= 200 && lastCtlCode < 300;
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

    /** 把内核状态文本化（供设置卡片/弹窗显示）。去内部术语，只露开关级状态。 */
    public static String statusText(Context context) {
        if (!isRunning()) return "未启动";
        String p = currentProxy(context);
        return p.isEmpty() ? "运行中" : "运行中 · 节点 " + p;
    }

    /** 订阅里的节点总数（未运行返回 0）。同步 ext-ctl，UI 线程慎用（一般后台拉）。 */
    public static int nodeCount(Context context) {
        List<ProxyInfo> list = listProxies(context);
        return list == null ? 0 : list.size();
    }

    /** 内核下载完成后的回调（UI 线程刷新状态）。 */
    public static void onDownloadDone() {
        // no-op hook; UI 自行轮询
    }

    /** 节点条目（供节点列表 UI 用）。 */
    public static final class ProxyInfo {
        public final String name;
        public final String type;
        public final String delay;     // 毫秒字符串，无测速结果时为空
        public final boolean isGroup;

        ProxyInfo(String name, String type, String delay, boolean isGroup) {
            this.name = name; this.type = type; this.delay = delay; this.isGroup = isGroup;
        }
    }

    /** 拉取全部 proxy/group（含类型/延迟），供节点列表。未运行返回 null。 */
    public static List<ProxyInfo> listProxies(Context context) {
        if (!isRunning(context)) return null;
        String resp = ctl("/proxies", "GET");
        if (resp.isEmpty()) return null;
        List<ProxyInfo> out = new java.util.ArrayList<>();
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(resp);
            com.google.gson.JsonObject pro = root.getAsJsonObject().getAsJsonObject("proxies");
            if (pro == null) return out;
            for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : pro.entrySet()) {
                com.google.gson.JsonObject o = e.getValue().getAsJsonObject();
                String type = o.has("type") ? o.get("type").getAsString() : "";
                boolean group = "Selector".equalsIgnoreCase(type) || "Fallback".equalsIgnoreCase(type)
                        || "LoadBalance".equalsIgnoreCase(type) || "URLTest".equalsIgnoreCase(type);
                String delay = o.has("delay") && !o.get("delay").isJsonNull() ? String.valueOf(o.get("delay").getAsNumber()) : "";
                out.add(new ProxyInfo(e.getKey(), type, delay, group));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 对指定 group 全节点测速（POST /proxies/{name}/delay），返回毫秒（<0 失败）。 */
    public static int delayFor(Context context, String group) {
        return delayFor(context, group, 5000);
    }

    /** 测单个 proxy/ group 的真实握手延迟（内核本地直连节点服务器，不经任何测速网站）。 */
    static int delayFor(Context context, String name, int timeoutMs) {
        if (!isRunning(context)) return -1;
        String resp = ctl("/proxies/" + urlEncode(name) + "/delay?timeout=" + timeoutMs, "POST");
        if (resp.isEmpty()) return -1;
        try {
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(resp);
            com.google.gson.JsonObject o = root.getAsJsonObject();
            if (!o.has("delay") || o.get("delay").isJsonNull()) return -1;
            return o.get("delay").getAsInt();
        } catch (Exception e) {
            return -1;
        }
    }

    /** 真实节点（叶子 proxy，排除组容器和 DIRECT/REJECT），供测速/选择用。 */
    static List<ProxyInfo> listLeaves(Context context) {
        List<ProxyInfo> all = listProxies(context);
        List<ProxyInfo> out = new java.util.ArrayList<>();
        if (all == null) return out;
        for (ProxyInfo p : all) {
            if (p.isGroup) continue;
            if ("DIRECT".equalsIgnoreCase(p.name) || "REJECT".equalsIgnoreCase(p.name)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * 并行真测速：对所有叶子节点 8 并发各测一次真实握手延迟（每节点 3s 超时），
     * 进度回调 "测速 12/43"。返回测得延迟的节点数（0 = 全部失败或未运行）。
     */
    public static int testLatencyAll(Context context, java.util.function.Consumer<String> progress) {
        List<ProxyInfo> leaves = listLeaves(context);
        if (leaves.isEmpty()) return 0;
        int n = leaves.size();
        int parallel = Math.min(8, n);
        ExecutorService pool = Executors.newFixedThreadPool(parallel, r -> {
            Thread t = new Thread(r, "mihomo-delay");
            t.setDaemon(true);
            return t;
        });
        java.util.concurrent.ConcurrentHashMap<String, Integer> delays = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(n);
        for (ProxyInfo p : leaves) {
            pool.execute(() -> {
                int d;
                try {
                    d = delayFor(context, p.name, 3000);
                } catch (Exception e) {
                    d = -1;
                }
                if (d >= 0) delays.put(p.name, d);
                if (progress != null) progress.accept("测速 " + done.incrementAndGet() + "/" + n);
                latch.countDown();
            });
        }
        try {
            latch.await(90, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        pool.shutdown();
        LAST_DELAYS = delays;
        return delays.size();
    }

    /** 最近一次并行测速的结果（供 autoSelect 复用，避免重复测）。 */
    private static volatile java.util.concurrent.ConcurrentHashMap<String, Integer> LAST_DELAYS;

    /**
     * 自动选最优节点：对所有真实节点（叶子）取最近一次并行测速结果，选延迟最低者，
     * PUT 进 PROXY 组。若没有可复用的测速结果则先跑一次 testLatencyAll。
     * 返回选中的节点名；无节点/全失败返回 ""。
     *
     * <p>修复：旧实现测速列表里含 DIRECT（本地直连），其延迟恒为 0 最低，
     * 自动会永远选中 DIRECT → 代理形同虚设。现 listLeaves 已排除 DIRECT/REJECT。
     */
    public static String autoSelect(Context context, java.util.function.Consumer<String> progress) {
        if (!isRunning(context)) return "";
        // 有刚测过的延迟结果就复用；否则现场并行测一遍
        if (LAST_DELAYS == null || LAST_DELAYS.isEmpty()) {
            testLatencyAll(context, progress);
        }
        java.util.concurrent.ConcurrentHashMap<String, Integer> delays = LAST_DELAYS;
        if (delays == null || delays.isEmpty()) return "";
        String bestNode = "";
        int bestDelay = Integer.MAX_VALUE;
        for (java.util.Map.Entry<String, Integer> e : delays.entrySet()) {
            if (e.getValue() >= 0 && e.getValue() < bestDelay) {
                bestDelay = e.getValue();
                bestNode = e.getKey();
            }
        }
        if (bestNode.isEmpty()) return "";
        if (progress != null) progress.accept("选中 " + bestNode + " (" + bestDelay + "ms)");
        return selectProxy(context, "PROXY", bestNode) ? bestNode : "";
    }

    /** 在指定 group 内选某节点（PUT /proxies/{group} body={"name":...}）。 */
    public static boolean selectProxy(Context context, String group, String node) {
        if (!isRunning(context)) return false;
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + (Setting.getMihomoPort() + 1) + "/proxies/" + urlEncode(group));
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer webhtv");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            byte[] body = new String("{\"name\":\"" + node.replace("\"", "\\\"") + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            conn.getOutputStream().write(body);
            int code = conn.getResponseCode();
            // mihomo 选节点成功回 204 No Content，2xx 都算成功
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }
}
