package com.fongmi.android.tv.proxy;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

/**
 * 照抄星落 5.9.9 com.fongmi.android.tv.proxy.MihomoManager 的实现（已逐方法核对 smali）。
 * 唯一差异：内核二进制换成自己编译的 mihomo v1.19.31（星落是 1.18.0）。
 *
 * 星落实测：裸 ProcessBuilder exec，无 service、无保活、无生命周期清理，孤儿事件 0。
 * 子进程继承 App UID，系统按 UID 整体杀，App 死则子进程跟着死。
 */
public final class MihomoManager {

    private static final String TAG = "MihomoManager";

    private static final int MIXED_PORT = 18890;
    private static final int CTL_PORT = 18891;
    private static final int READY_TRIES = 50;      // 50 × 100ms = 5 秒
    private static final int READY_INTERVAL = 100;
    private static final int CONNECT_TIMEOUT = 100;
    private static final int LOG_MAX_LINES = 2000;

    private static final String CRLF = "\r\n";
    private static final String CR = "\r";
    private static final String LF = "\n";

    /**
     * 分流规则已按要求全部删除：不做任何 DIRECT 判定，全部流量走代理组。
     * （原来有一堆 IP-CIDR/DOMAIN-SUFFIX 直连规则，会让爬虫站点被判定成国内直连而拿不到数据。）
     */
    static final String RULES_YAML = "\nrules:\n  - MATCH,XYS_PROXY\n";

    private Process process;
    private String lastError;
    private String lastConfig;
    private String lastSelected;
    private final StringBuilder logBuffer = new StringBuilder();
    private int bufferedLines;
    /** stop() 主动关闭时置 true，用于区分「主动停」和「异常崩」 */
    private volatile boolean isStopping = false;
    /** 内核异常退出回调（参数 = 内核日志全文），由界面层注册以便弹窗告警 */
    public volatile java.util.function.Consumer<String> onCrash = log -> {
    };

    private static class Loader {
        static final MihomoManager INSTANCE = new MihomoManager();
    }

    public static MihomoManager get() {
        return Loader.INSTANCE;
    }

    public static int getMixedPort() {
        return MIXED_PORT;
    }

    /** 星落 getProxyUrl：拼 http://127.0.0.1:18890#<Uri编码的节点名> */
    public static String getProxyUrl(String name) {
        StringBuilder sb = new StringBuilder("http://127.0.0.1:18890#");
        if (TextUtils.isEmpty(name)) name = "Mihomo";
        sb.append(Uri.encode(name));
        return sb.toString();
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    /**
     * 星落 start(String config, String selected)：写配置 → setExecutable → ProcessBuilder(so, -d, workDir, -f, config) → drain → waitReady。
     * 内部用 App.get() 取 context 和 Path.files("mihomo") 作工作目录（照星落，不显式传 Context）。
     */
    public synchronized boolean start(String config, String selected) {
        if (TextUtils.isEmpty(config)) {
            lastError = "配置为空";
            note(lastError);
            return false;
        }
        return launch(fixConfig(config, selected), MIXED_PORT);
    }

    /**
     * AVstar 新增：真连接测速专用启动。
     *
     * 和 start() 的区别是**不过 fixConfig**——直接用传进来的配置原样落盘，
     * 就绪探测打 readyPort 而不是 18890。这样测速配置可以自己定义 listeners
     * （每节点一个端口钉死该节点），完全不碰正在服务的 18890。
     *
     * 注意：调用方负责测完把正式配置 start() 回去。
     */
    public synchronized boolean startRaw(String config, int readyPort) {
        if (TextUtils.isEmpty(config)) {
            lastError = "测速配置为空";
            note(lastError);
            return false;
        }
        return launch(config, readyPort);
    }

    /** 测速 listener 端口段：19100 起，和正式的 18890/18891 完全分开 */
    public static final int SPEED_TEST_PORT_BASE = 19100;

    public static int getSpeedTestPort(int index) {
        return SPEED_TEST_PORT_BASE + index;
    }

    private boolean launch(String finalConfig, int readyPort) {
        stop();
        isStopping = false;
        lastError = null;
        logBuffer.setLength(0);
        bufferedLines = 0;
        try {
            File workDir = Path.files("mihomo");
            if (!workDir.exists() && !workDir.mkdirs()) {
                lastError = "创建内核工作目录失败：" + workDir.getAbsolutePath();
                note(lastError);
                return false;
            }
            File cfg = new File(workDir, "config.yaml");
            try {
                Path.write(cfg, finalConfig.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable e) {
                lastError = "配置文件写入失败: " + cfg.getAbsolutePath();
                note(lastError);
                return false;
            }

            File so = extractBinary();
            if (so == null || !so.exists()) {
                lastError = "二进制文件不存在: " + (so == null ? "null" : so.getAbsolutePath());
                note(lastError);
                return false;
            }
            so.setExecutable(true);
            note("启动: " + so.getAbsolutePath() + " -d " + workDir.getAbsolutePath() + " -f " + cfg.getAbsolutePath());

            process = new ProcessBuilder(so.getAbsolutePath(), "-d", workDir.getAbsolutePath(), "-f", cfg.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            drain(process);
            if (waitReady(readyPort)) {
                lastConfig = finalConfig;
                return true;
            }
            return false;
        } catch (Throwable e) {
            lastError = "启动异常: " + e.getMessage();
            note(lastError);
            return false;
        }
    }

    /** 星落 stop()：destroy → waitFor(2s) → 不行 destroyForcibly */
    public synchronized void stop() {
        isStopping = true;
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                note("进程被强制终止");
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable ignored) {
        }
        process = null;
    }

    /** 星落 waitReady()：50 次 × 100ms，每轮查 isAlive + readyPort 可连 */
    private boolean waitReady(int readyPort) {
        for (int i = 0; i < READY_TRIES; i++) {
            if (!isRunning()) {
                lastError = "Mihomo进程启动后退出\n日志:\n" + logBuffer;
                note(lastError);
                return false;
            }
            if (canConnect(readyPort)) {
                note("Mihomo启动成功，耗时" + (i * 100) + "ms 端口 " + readyPort);
                return true;
            }
            try {
                Thread.sleep(READY_INTERVAL);
            } catch (InterruptedException ignored) {
            }
        }
        lastError = "Mihomo在5秒内未就绪\n日志:\n" + logBuffer;
        note(lastError);
        return false;
    }

    /** 星落 canConnect()：纯 Java Socket 连 127.0.0.1:port，超时 100ms */
    private boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 星落 drain()：守护线程名 "mihomo-log"，常驻读 stdout。读到 EOF = 内核进程已退出，触发 onCrash 回调。 */
    private void drain(Process process) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) appendLog(line);
            } catch (Throwable ignored) {
            }
            // stdout 读到 EOF 说明进程退出。若不是我们主动 stop() 导致的，就是异常崩溃。
            if (!isStopping && isRunning()) {
                String err = logBuffer.toString();
                onCrash.accept(err);
            }
        }, "mihomo-log");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 壳子自己产生的状态消息（启动命令、启动成功耗时、进程被终止、lastError）。
     *
     * 只进 logcat，不进 logBuffer：mihomo 自己在 info 档下会原生输出
     * Initial configuration complete / HTTP proxy listening at:127.0.0.1:18890，
     * 壳子再往同一个缓冲里塞中文裸文本，两种格式混在一起显示就不是 mihomo 日志的样子了。
     * lastError 也走这里，但它照样写进 lastError 字段，失败弹窗和崩溃告警不受影响。
     */
    private void note(String text) {
        Log.d(TAG, text);
    }

    private static final String TIME_PREFIX = "time=\"";
    private static final String LOCAL_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX";
    private static volatile DateTimeFormatter localFormat;

    /**
     * mihomo 的时间戳永远是 UTC（形如 time="2026-10-16T10:30:00.248078840Z"），
     * 换算成本地时区再存。
     *
     * 为什么内核自己改不了：libmihomo.so 里没有编入 tzdata（在二进制里搜
     * Asia/Shanghai / tzdata 都是 0 命中），Go 的 time 包只能去系统路径找
     * /usr/share/zoneinfo/ 等，而 Android 一个都没有（Android 的时区数据在
     * /apex/com.android.runtime/etc/zoneinfo），查找必然失败回落 UTC。
     * 所以设 TZ 环境变量也没用，只能在我们这边改写。
     *
     * 在 appendLog 里改而不是只在界面改：UI、Log.d 进的 logcat、复制按钮
     * 三处一起是本地时间，不会出现「界面本地时间 / logcat UTC」对不上。
     */
    private String toLocalTime(String text) {
        if (!text.startsWith(TIME_PREFIX)) return text;
        int end = text.indexOf('"', TIME_PREFIX.length());
        if (end < 0) return text;
        try {
            Instant instant = Instant.parse(text.substring(TIME_PREFIX.length(), end));
            ZonedDateTime local = instant.atZone(ZoneId.systemDefault());
            if (local.getOffset().getTotalSeconds() == 0) return text;
            DateTimeFormatter formatter = localFormat;
            if (formatter == null) {
                formatter = DateTimeFormatter.ofPattern(LOCAL_FORMAT);
                localFormat = formatter;
            }
            return TIME_PREFIX + formatter.format(local) + text.substring(end);
        } catch (Throwable e) {
            return text;
        }
    }

    /**
     * 星落 appendLog：mihomo 原生 stdout 的唯一入口，进 logBuffer，超出上限丢最旧的。
     *
     * 改成记行数而不是每次 split：原来每写一行都要 logBuffer.toString() 全量拷贝
     * 再 split("\n") 生成整个数组。warning 档下日志稀疏无所谓，但 info 档每个 TCP
     * 连接一行，上限提到 2000 后就是每行分配一个 2000 元素的数组。
     * 而这段跑在 mihomo-log 守护线程上（drain stdout），它一慢就会顶住管道反把日志丢了。
     */
    private void appendLog(String raw) {
        String text = toLocalTime(raw);
        Log.d(TAG, text);
        if (bufferedLines > 0) logBuffer.append('\n');
        logBuffer.append(text);
        if (++bufferedLines <= LOG_MAX_LINES) return;
        String[] lines = logBuffer.toString().split("\n");
        int drop = lines.length - (LOG_MAX_LINES - 1);
        String[] kept = new String[lines.length - drop];
        System.arraycopy(lines, drop, kept, 0, kept.length);
        logBuffer.setLength(0);
        for (int i = 0; i < kept.length; i++) {
            logBuffer.append(kept[i]);
            if (i < kept.length - 1) logBuffer.append('\n');
        }
        bufferedLines = kept.length;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLog() {
        return logBuffer.toString();
    }

    public String getLastConfig() {
        return lastConfig;
    }

    public String getLastSelected() {
        return lastSelected;
    }

    // ---------------- 配置处理（照抄星落 fixConfig / buildSelectedConfig / extractBlock / putTopLevel / quote） ----------------

    /**
     * 星落 fixConfig：归一换行 → 有选中节点则重建 proxies/rules →
     * 逐条 putTopLevel 覆盖 mixed-port / allow-lan / bind-address / external-controller / log-level
     *
     * 已回退成星落原样：不加 find-process-mode（之前多加的，实测并非根因）。
     */
    private String fixConfig(String config, String selected) {
        config = config.replace(CRLF, LF).replace(CR, LF);
        if (!TextUtils.isEmpty(selected)) config = buildSelectedConfig(config, selected);
        config = putTopLevel(config, "mixed-port", String.valueOf(MIXED_PORT));
        config = putTopLevel(config, "allow-lan", "false");
        config = putTopLevel(config, "bind-address", "'127.0.0.1'");
        config = putTopLevel(config, "external-controller", "'127.0.0.1:" + CTL_PORT + "'");
        // warning 档下 mihomo 不产生 [TCP] ... match ... using ... 连接行（那是 info 级），
        // 想看「哪个请求走了哪个端口」必须 info。上游/星落这里写死 warning。
        config = putTopLevel(config, "log-level", "info");
        return config;
    }

    /** 星落 buildSelectedConfig：提取原 proxies 块（保留节点），重建 XYS_PROXY 选择组 + MATCH。 */
        private String buildSelectedConfig(String config, String selected) {
                String proxies = extractBlock(config, "proxies");
                if (TextUtils.isEmpty(proxies)) return config;
                StringBuilder sb = new StringBuilder();
                sb.append("mode: rule\nipv6: false\n");
                sb.append(proxies);
                sb.append("proxy-groups:\n  - name: XYS_PROXY\n    type: select\n    proxies:\n      - ");
                sb.append(quote(selected));
                sb.append("\n");
                return sb.toString() + RULES;
            }

    /** 分流规则已全部删除，只留 MATCH 走代理（与 RULES_YAML 一致） */
    private static final String RULES = "\nrules:\n  - MATCH,XYS_PROXY\n";

    /** 星落 extractBlock：提取从命中 "^<key>\\s*:.*" 的行开始，到下一个顶层 key 前的整块（含该块缩进行）。 */
        private String extractBlock(String config, String key) {
            String[] lines = config.split("\n", -1);
            StringBuilder sb = new StringBuilder();
            boolean capturing = false;
            for (String line : lines) {
                if (!capturing) {
                    capturing = line.matches("^" + key + "\\s*:.*");
                    if (capturing) {
                        sb.append(line).append('\n');
                    }
                    continue;
                }
                // 已进入块内：遇到新的顶层 key（非缩进、含冒号、非 "-" 开头）则结束
                if (!TextUtils.isEmpty(line) && !Character.isWhitespace(line.charAt(0)) && line.charAt(0) != '-' && line.contains(":")) {
                    break;
                }
                sb.append(line).append('\n');
            }
            return sb.toString();
        }

    /** 星落 putTopLevel：替换已存在的顶层 key，没有就插到最前 */
    private String putTopLevel(String config, String key, String value) {
        String[] lines = config.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        boolean replaced = false;
        for (String line : lines) {
            if (line.matches("^" + key + "\\s*:.*")) {
                sb.append(key).append(": ").append(value).append('\n');
                replaced = true;
            } else {
                sb.append(line).append('\n');
            }
        }
        if (!replaced) sb.insert(0, key + ": " + value + "\n");
        return sb.toString();
    }

    /** 星落 quote：加双引号并转义反斜杠和双引号 */
    private String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        sb.append(value.replace("\\", "\\\\").replace("\"", "\\\""));
        sb.append("\"");
        return sb.toString();
    }

    // ---------------- 内核二进制 ----------------

    /**
     * 内核放在 jniLibs/arm64-v8a/libmihomo.so，APK 安装后已解压在 app 私有目录，
     * 直接定位它并 setExecutable（星落同一做法）。仅 arm64-v8a。
     */
    private File extractBinary() {
        return new File(App.get().getApplicationInfo().nativeLibraryDir, "libmihomo.so");
    }
}
