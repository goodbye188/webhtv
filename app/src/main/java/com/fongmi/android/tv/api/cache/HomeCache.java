package com.fongmi.android.tv.api.cache;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Result;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 首页数据缓存：「先显旧再刷新」的存储层。
 *
 * <p>键为站点 key。写入发生在网络请求线程（SiteViewModel.homeContent），
 * 读取同线程；主线程只允许调 {@link #has}（查内存镜像，零 IO）。</p>
 *
 * <p>只缓存分类非空的成功结果；TTL 7 天，过期视为无缓存。
 * 空结果不入缓存，刷新失败也不会覆盖旧缓存。</p>
 */
public class HomeCache {

    private static final long TTL = TimeUnit.DAYS.toMillis(7);
    private static final Gson GSON = new Gson();

    /** 进程内镜像：冷启动后由磁盘读回填，主线程 has() 零 IO 判断。 */
    private static final Map<String, Boolean> MEMORY = new HashMap<>();

    public static Result get(String key) {
        if (TextUtils.isEmpty(key)) return null;
        try {
            File file = file(key);
            if (!file.exists()) return null;
            String json = read(file);
            if (TextUtils.isEmpty(json)) return null;
            JsonObject wrapper = GSON.fromJson(json, JsonObject.class);
            if (wrapper == null || wrapper.get("time") == null) return null;
            if (System.currentTimeMillis() - wrapper.get("time").getAsLong() > TTL) return null;
            if (wrapper.get("result") == null) return null;
            Result result = GSON.fromJson(wrapper.get("result"), Result.class);
            if (result == null || result.getTypes().isEmpty()) return null;
            synchronized (MEMORY) {
                MEMORY.put(key, Boolean.TRUE);
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /** 只查内存镜像（主线程安全）；冷启动时返回 false，首次刷新会自然回填。 */
    public static boolean has(String key) {
        if (TextUtils.isEmpty(key)) return false;
        synchronized (MEMORY) {
            return MEMORY.containsKey(key);
        }
    }

    /** 必须在后台线程调用（SiteViewModel 的请求线程）。空结果直接丢弃。 */
    public static void put(String key, Result result) {
        if (TextUtils.isEmpty(key) || result == null) return;
        if (result.getTypes().isEmpty()) return;
        try {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("time", System.currentTimeMillis());
            wrapper.add("result", GSON.toJsonTree(result));
            File file = file(key);
            File dir = file.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            File temp = new File(dir, file.getName() + ".tmp");
            write(temp, GSON.toJson(wrapper));
            if (!temp.renameTo(file)) {
                // rename 失败（跨文件系统等）退化为直接写目标文件
                write(file, GSON.toJson(wrapper));
                temp.delete();
            }
            synchronized (MEMORY) {
                MEMORY.put(key, Boolean.TRUE);
            }
        } catch (Exception e) {
            // 缓存写失败不影响主流程
        }
    }

    public static void clear(String key) {
        if (TextUtils.isEmpty(key)) return;
        synchronized (MEMORY) {
            MEMORY.remove(key);
        }
        try {
            file(key).delete();
        } catch (Exception ignored) {
        }
    }

    private static File file(String key) {
        String name = key.replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.length() > 64) name = name.substring(0, 64) + "_" + Integer.toHexString(key.hashCode());
        return new File(new File(App.get().getFilesDir(), "home_cache"), name + ".json");
    }

    private static String read(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        }
    }

    private static void write(File file, String json) throws Exception {
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }
}
