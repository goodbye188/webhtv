package com.fongmi.android.tv.utils;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.github.catvod.net.OkHttp;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import okhttp3.Response;
import okhttp3.ResponseBody;

public class TmdbImageSaver {

    private static final String ALBUM = "webhtv";
    private static final String TAG = "tmdb_image_save";

    public static void save(FragmentActivity activity, String url, Callback callback) {
        save(activity, url, "", "", callback);
    }

    /**
     * 保存远程图片到相册。
     *
     * @param displayName 期望的文件名（不含扩展名）；为空则按时间戳自动命名
     * @param referer     防盗链 Referer；为空则不带该头
     */
    public static void save(FragmentActivity activity, String url, String displayName, String referer, Callback callback) {
        String source = normalize(url);
        if (TextUtils.isEmpty(source)) {
            if (callback != null) callback.error(ResUtil.getString(R.string.detail_image_save_failed));
            return;
        }
        String name = TextUtils.isEmpty(displayName) ? fileName(source) : withExtension(displayName, extension(source));
        String ref = referer == null ? "" : referer.trim();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveScoped(source, name, ref, callback);
        } else {
            PermissionUtil.requestFile(activity, granted -> {
                if (!granted) {
                    if (callback != null) callback.error(ResUtil.getString(R.string.detail_image_permission_denied));
                    return;
                }
                saveLegacy(source, name, ref, callback);
            });
        }
    }

    private static void saveScoped(String url, String displayName, String referer, Callback callback) {
        Task.execute(() -> {
            String mimeType = mimeType(displayName);
            Uri uri = null;
            try {
                ContentResolver resolver = App.get().getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
                values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + ALBUM);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException(ResUtil.getString(R.string.detail_image_save_failed));
                try (OutputStream output = resolver.openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException(ResUtil.getString(R.string.detail_image_save_failed));
                    download(url, referer, output);
                }
                values.clear();
                values.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                success(callback, Environment.DIRECTORY_PICTURES + File.separator + ALBUM + File.separator + displayName);
            } catch (Exception e) {
                if (uri != null) App.get().getContentResolver().delete(uri, null, null);
                error(callback, e);
            }
        });
    }

    private static void saveLegacy(String url, String displayName, String referer, Callback callback) {
        Task.execute(() -> {
            String mimeType = mimeType(displayName);
            try {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM);
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException(ResUtil.getString(R.string.detail_image_save_failed));
                File file = uniqueFile(dir, displayName);
                try (OutputStream output = new FileOutputStream(file)) {
                    download(url, referer, output);
                }
                MediaScannerConnection.scanFile(App.get(), new String[]{file.getAbsolutePath()}, new String[]{mimeType}, null);
                success(callback, file.getAbsolutePath());
            } catch (Exception e) {
                error(callback, e);
            }
        });
    }

    private static void download(String url, String referer, OutputStream output) throws Exception {
        okhttp3.Call call;
        if (TextUtils.isEmpty(referer)) {
            call = OkHttp.newCall(url, TAG);
        } else {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            headers.put("Referer", referer);
            call = OkHttp.newCall(url, headers);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException(response.message());
            ResponseBody body = response.body();
            if (body == null) throw new IllegalStateException(ResUtil.getString(R.string.detail_image_save_failed));
            try (InputStream input = body.byteStream()) {
                byte[] buffer = new byte[16384];
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.flush();
            }
        }
    }

    private static File uniqueFile(File dir, String displayName) {
        File file = new File(dir, displayName);
        if (!file.exists()) return file;
        String name = displayName;
        String ext = "";
        int dot = displayName.lastIndexOf('.');
        if (dot >= 0) {
            name = displayName.substring(0, dot);
            ext = displayName.substring(dot);
        }
        int index = 1;
        do {
            file = new File(dir, name + "_" + index++ + ext);
        } while (file.exists());
        return file;
    }

    private static String fileName(String url) {
        String ext = extension(url);
        return "webhtv_tmdb_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date()) + ext;
    }

    /**
     * 把调用方给的名字压成合法文件名：去掉路径分隔符与文件系统非法字符，
     * 顺带补上按 URL 推断的扩展名。片名/章节名来自爬端，可能含 / : * 等字符，
     * 直接拼进 MediaStore 会写到意料之外的路径或抛 IllegalArgumentException。
     */
    private static String withExtension(String displayName, String ext) {
        String name = displayName.trim().replaceAll("[\\\\/:*?\"<>|\\x00-\\x1F]", "_");
        name = name.replaceAll("\\s+", " ").trim();
        // 连续的点会让 ".jpg" 变成隐藏文件，也可能撞上 "." / ".."
        while (name.contains("..")) name = name.replace("..", ".");
        if (name.startsWith(".")) name = name.substring(1);
        if (name.isEmpty()) name = "image";
        if (name.length() > 80) name = name.substring(0, 80).trim();
        if (name.isEmpty()) name = "image";
        return name.toLowerCase(Locale.US).endsWith(ext) ? name : name + ext;
    }

    private static String extension(String url) {
        String clean = url.split("\\?")[0].split("#")[0].toLowerCase(Locale.US);
        int slash = clean.lastIndexOf('/');
        int dot = clean.lastIndexOf('.');
        if (dot > slash) {
            String ext = clean.substring(dot);
            if (".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext) || ".webp".equals(ext)) return ext;
        }
        return ".jpg";
    }

    private static String mimeType(String displayName) {
        String lower = displayName.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String normalize(String url) {
        if (TextUtils.isEmpty(url)) return "";
        url = UrlUtil.convert(url.trim());
        if (url.startsWith("//")) url = "https:" + url;
        int header = url.indexOf("@Headers=");
        int cookie = url.indexOf("@Cookie=");
        int referer = url.indexOf("@Referer=");
        int agent = url.indexOf("@User-Agent=");
        int cut = firstPositive(header, cookie, referer, agent);
        if (cut > 0) url = url.substring(0, cut);
        return url.startsWith("http://") || url.startsWith("https://") ? url : "";
    }

    private static int firstPositive(int... values) {
        int result = -1;
        for (int value : values) {
            if (value <= 0) continue;
            result = result < 0 ? value : Math.min(result, value);
        }
        return result;
    }

    private static void success(Callback callback, String name) {
        if (callback != null) App.post(() -> callback.success(name));
    }

    private static void error(Callback callback, Exception e) {
        if (callback == null) return;
        String message = TextUtils.isEmpty(e.getMessage()) ? ResUtil.getString(R.string.detail_image_save_failed) : e.getMessage();
        App.post(() -> callback.error(message));
    }

    public interface Callback {

        void success(String name);

        void error(String message);
    }
}
