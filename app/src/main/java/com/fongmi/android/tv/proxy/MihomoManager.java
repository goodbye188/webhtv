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
    private static final int LOG_MAX_LINES = 200;

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
            appendLog(lastError);
            return false;
        }
        stop();
        isStopping = false;
        lastError = null;
        logBuffer.setLength(0);
        try {
            File workDir = Path.files("mihomo");
            if (!workDir.exists() && !workDir.mkdirs()) {
                lastError = "创建内核工作目录失败：" + workDir.getAbsolutePath();
                appendLog(lastError);
                return false;
            }
            File cfg = new File(workDir, "config.yaml");
            try {
                Path.write(cfg, fixConfig(config, selected).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable e) {
                lastError = "配置文件写入失败: " + cfg.getAbsolutePath();
                appendLog(lastError);
                return false;
            }

            File so = extractBinary();
            if (so == null || !so.exists()) {
                lastError = "二进制文件不存在: " + (so == null ? "null" : so.getAbsolutePath());
                appendLog(lastError);
                return false;
            }
            so.setExecutable(true);
            appendLog("启动: " + so.getAbsolutePath() + " -d " + workDir.getAbsolutePath() + " -f " + cfg.getAbsolutePath());

            process = new ProcessBuilder(so.getAbsolutePath(), "-d", workDir.getAbsolutePath(), "-f", cfg.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            drain(process);
            if (waitReady()) {
                lastConfig = config;
                if (selected == null) selected = "";
                lastSelected = selected;
                return true;
            }
            return false;
        } catch (Throwable e) {
            lastError = "启动异常: " + e.getMessage();
            appendLog(lastError);
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
                appendLog("进程被强制终止");
            }
        } catch (InterruptedException ignored) {
        } catch (Throwable ignored) {
        }
        process = null;
    }

    /** 星落 waitReady()：50 次 × 100ms，每轮查 isAlive + 18890 可连 */
    private boolean waitReady() {
        for (int i = 0; i < READY_TRIES; i++) {
            if (!isRunning()) {
                lastError = "Mihomo进程启动后退出\n日志:\n" + logBuffer;
                appendLog(lastError);
                return false;
            }
            if (canConnect()) {
                appendLog("Mihomo启动成功，耗时" + (i * 100) + "ms");
                return true;
            }
            try {
                Thread.sleep(READY_INTERVAL);
            } catch (InterruptedException ignored) {
            }
        }
        lastError = "Mihomo在5秒内未就绪\n日志:\n" + logBuffer;
        appendLog(lastError);
        return false;
    }

    /** 星落 canConnect()：纯 Java Socket 连 127.0.0.1:18890，超时 100ms */
    private boolean canConnect() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", MIXED_PORT), CONNECT_TIMEOUT);
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

    /** 星落 appendLog()：Log.d + 进 logBuffer，超过 200 行丢最旧的 */
    private void appendLog(String text) {
        Log.d(TAG, text);
        if (logBuffer.length() > 0) logBuffer.append('\n');
        logBuffer.append(text);
        String[] lines = logBuffer.toString().split("\n");
        if (lines.length <= LOG_MAX_LINES) return;
        int drop = lines.length - (LOG_MAX_LINES - 1);
        String[] kept = new String[lines.length - drop];
        System.arraycopy(lines, drop, kept, 0, kept.length);
        logBuffer.setLength(0);
        for (int i = 0; i < kept.length; i++) {
            logBuffer.append(kept[i]);
            if (i < kept.length - 1) logBuffer.append('\n');
        }
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
        config = putTopLevel(config, "log-level", "warning");
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
