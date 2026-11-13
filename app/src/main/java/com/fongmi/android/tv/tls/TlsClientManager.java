package com.fongmi.android.tv.tls;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * TLS 指纹桥管理器 —— 管理独立 Go 二进制 libtlsclient.so 的生命周期。
 *
 * <p>与 MihomoManager 完全同一模式：二进制放 jniLibs/arm64-v8a（"lib*.so" 命名
 * 让安装器解压出来，useLegacyPackaging 保证是真实文件），裸 ProcessBuilder exec。
 *
 * <p>职责：懒启动（首次收到带 imp 参数的 /webResource 请求才拉起）、孤儿复用
 * （上一版进程可能还活着，先 ping 再启动）、端口挑选（17890-17899 逐个试，
 * 生效端口写入 files/tlsclient_port）。
 *
 * <p>生命周期策略与 mihomo 一致：启动后不管，不做生命周期清理。
 */
public class TlsClientManager {

    private static final int PORT_MIN = 17890;
    private static final int PORT_MAX = 17899;
    private static final String PING = "/ping";

    private static volatile TlsClientManager instance;

    private Process process;
    private File so;
    private File portFile;
    private int port;

    public static TlsClientManager get() {
        if (instance == null) {
            synchronized (TlsClientManager.class) {
                if (instance == null) instance = new TlsClientManager();
            }
        }
        return instance;
    }

    /** 懒启动入口：返回生效端口，失败返回 0。 */
    public static int ensure() {
        return get().start();
    }

    private File getSo() {
        if (so == null) so = new File(App.get().getApplicationInfo().nativeLibraryDir, "libtlsclient.so");
        return so;
    }

    private File getPortFile() {
        if (portFile == null) portFile = new File(App.get().getFilesDir(), "tlsclient_port");
        return portFile;
    }

    /**
     * 启动（或复用孤儿进程）。顺序：
     * 1. 已有 process 且 ping 通 → 直接返回；
     * 2. 上次记录的端口/默认端口段里有活的孤儿 → 收编复用；
     * 3. 逐端口试启动。
     */
    private synchronized int start() {
        if (!getSo().exists()) {
            SpiderDebug.log("tlsclient", "binary missing");
            return 0;
        }
        getSo().setExecutable(true);
        if (process != null && process.isAlive() && ping(port)) return port;
        // 孤儿复用：先扫已记录端口，再扫默认段。
        int remembered = readPortFile();
        if (remembered > 0 && ping(remembered)) {
            port = remembered;
            return port;
        }
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            if (p != remembered && ping(p)) {
                port = p;
                savePort(p);
                return port;
            }
        }
        stop();
        for (int p = PORT_MIN; p <= PORT_MAX; p++) {
            try {
                SpiderDebug.log("tlsclient", "start on port %d", p);
                process = new ProcessBuilder(getSo().getAbsolutePath(), "-port", String.valueOf(p))
                        .redirectErrorStream(true)
                        .start();
                drain(process);
                if (waitReady(p)) {
                    port = p;
                    savePort(p);
                    return port;
                }
                SpiderDebug.log("tlsclient", "port %d not ready, next", p);
                stop();
            } catch (Throwable e) {
                SpiderDebug.log("tlsclient", e);
            }
        }
        return 0;
    }

    private boolean waitReady(int p) {
        for (int i = 0; i < 30; i++) {
            if (process == null || !process.isAlive()) return false;
            if (ping(p)) return true;
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
        }
        return false;
    }

    private void drain(Process p) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) SpiderDebug.log("tlsclient", "%s", line);
            } catch (Throwable ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private boolean ping(int p) {
        if (p < PORT_MIN || p > PORT_MAX) return false;
        OkHttpClient client = new OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build();
        Request request = new Request.Builder().url("http://127.0.0.1:" + p + PING).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            return response.code() == 200 && body != null && body.string().contains("\"ok\"");
        } catch (Throwable e) {
            return false;
        }
    }

    private int readPortFile() {
        try {
            if (!getPortFile().exists()) return 0;
            byte[] bytes = new byte[(int) getPortFile().length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(getPortFile())) {
                //noinspection ResultOfMethodCallIgnored
                in.read(bytes);
            }
            String s = new String(bytes, StandardCharsets.UTF_8).trim();
            return TextUtils.isEmpty(s) ? 0 : Integer.parseInt(s);
        } catch (Throwable e) {
            return 0;
        }
    }

    private void savePort(int p) {
        try {
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(getPortFile())) {
                out.write(String.valueOf(p).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized void stop() {
        if (process == null) return;
        try {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException ignored) {
        } catch (Throwable ignored) {
        }
        process = null;
    }
}
