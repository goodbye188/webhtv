package com.fongmi.android.tv.player.extractor;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.exception.ExtractException;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.Download;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;
import com.xunlei.downloadlib.XLTaskHelper;
import com.xunlei.downloadlib.parameter.GetTaskId;
import com.xunlei.downloadlib.parameter.TorrentFileInfo;
import com.xunlei.downloadlib.parameter.TorrentInfo;
import com.xunlei.downloadlib.parameter.XLTaskInfo;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

public class Thunder implements Source.Extractor {

    private GetTaskId taskId;

    @Override
    public boolean match(Uri uri) {
        return List.of("magnet", "ed2k").contains(UrlUtil.scheme(uri));
    }

    @Override
    public String fetch(String url) throws Exception {
        if (!url.startsWith("magnet")) return addThunderTask(url);
        Uri uri = Uri.parse(url);
        // 两种 magnet 来源，参数形态完全不同，不能混用：
        //  1) TVBox 旧推送格式：magnet 指向本地 .torrent 文件，并带 ?index=&name=
        //     —— path 是文件路径、index/name 是种子内第几个文件
        //  2) 标准磁力：magnet:?xt=urn:btih:... —— 没有 path，也没有 index/name
        //     第 2 种走迅雷自己解析 magnet 的那条路。
        if (isLocalTorrent(uri)) return addTorrentTask(uri);
        return playStandardMagnet(uri);
    }

    /** 是否为「本地 .torrent 文件 + index」的旧推送格式。 */
    private static boolean isLocalTorrent(Uri uri) {
        String path = uri.getPath();
        return !TextUtils.isEmpty(path) && queryParameter(uri.toString(), "index") != null;
    }

    /**
     * 从 URL 里取查询参数。
     *
     * <p>不能用 {@link Uri#getQueryParameter(String)}：标准磁力是
     * {@code magnet:?xt=urn:btih:...} 这种 opaque URI，Android 对它取 query
     * 会直接抛 {@code UnsupportedOperationException: This isn't a hierarchical URI}，
     * 表现为详情页磁力点播放瞬间失败（logcat 里是 Thunder.indexOf 的栈）。
     * 这里自己按字符串解析，两种形态都能用。
     */
    public static String queryParameter(String url, String name) {
        if (TextUtils.isEmpty(url)) return null;
        int start = url.indexOf('?');
        if (start < 0) return null;
        int end = url.indexOf('#', start);
        String query = end < 0 ? url.substring(start + 1) : url.substring(start + 1, end);
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !name.equals(pair.substring(0, eq))) continue;
            return Uri.decode(pair.substring(eq + 1));
        }
        return null;
    }

    /**
     * 标准磁力：先解析出种子信息，再按 Parser 生成的「.torrent + name + index」
     * 旧格式启动正式下载任务。
     *
     * <p>注意不能直接返回 {@link TorrentFileInfo#getPlayUrl()}：那是
     * {@code magnet://<torrent路径>?name=&index=} 形式，播放器打不开，必须再经过
     * {@link #addTorrentTask(Uri)} 换成迅雷的本地播放地址。
     */
    private String playStandardMagnet(Uri uri) throws Exception {
        String url = uri.toString();
        // 元数据目录独立于详情页 Parser 的目录，避免 stop() 删任务时把
        // 详情页选集依赖的 .torrent 一起删掉。
        File meta = Path.thunder(Util.md5(url) + "-meta");
        GetTaskId parsed = XLTaskHelper.get().parse(url, meta);
        if (parsed == null) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
        waitDone(parsed);
        File torrent = parsed.getSaveFile();
        TorrentInfo info = XLTaskHelper.get().getTorrentInfo(torrent);
        List<TorrentFileInfo> medias = info == null ? null : info.getMedias();
        if (medias == null || medias.isEmpty()) {
            XLTaskHelper.get().stopTask(parsed);
            throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
        }
        TorrentFileInfo media = findMedia(medias, indexOf(uri));
        // 元数据任务到此为止，正式下载交给 addTorrentTask（它自己会建 BT 任务）。
        XLTaskHelper.get().stopTask(parsed);
        Uri local = Uri.parse("magnet://" + torrent.getAbsolutePath()
                + "?name=" + Uri.encode(media.getFileName())
                + "&index=" + media.getFileIndex());
        return addTorrentTask(local);
    }

    private static TorrentFileInfo findMedia(List<TorrentFileInfo> medias, int index) {
        if (index < 0) return medias.get(0);
        for (TorrentFileInfo media : medias) if (media.getFileIndex() == index) return media;
        return medias.get(0);
    }

    private static int indexOf(Uri uri) {
        String value = queryParameter(uri.toString(), "index");
        if (TextUtils.isEmpty(value)) return -1;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * 轮询等迅雷解析/取到种子信息，状态 2 即就绪。
     *
     * <p>冷门磁力连 tracker/DHT 可能要十几秒，上限放到 30 秒；同时直接看
     * saveFile 是否已落盘，避免只依赖状态码而提前放弃。
     */
    private static boolean waitDone(GetTaskId taskId) {
        for (int i = 0; i < 300; i++) {
            XLTaskInfo taskInfo = XLTaskHelper.get().getTaskInfo(taskId);
            int status = taskInfo == null ? -1 : taskInfo.getTaskStatus();
            File save = taskId.getSaveFile();
            boolean exists = save != null && save.exists();
            if (status == 2 || (exists && save.length() > 0)) return true;
            if (status == 3) return false;
            SystemClock.sleep(100);
        }
        return false;
    }

    private String addTorrentTask(Uri uri) throws Exception {
        File torrent = new File(uri.getPath());
        // 下载目录必须和 .torrent 所在目录分开：
        // Parser 生成的每条选集都指向同一个 .torrent，而 stop() 会
        // deleteTask(savePath)。以前 savePath 就是 .torrent 的父目录，
        // 播完一次就把整页选集依赖的 .torrent 全删了 —— 详情页第一集能播，
        // 之后同页其它集、重播全部失败（推送反而因为先重新解析过而正常）。
        File parent = playFolder(torrent);
        String name = queryParameter(uri.toString(), "name");
        String value = queryParameter(uri.toString(), "index");
        int index = TextUtils.isEmpty(value) ? 0 : Integer.parseInt(value);
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, index);
        for (int i = 0; i < 300; i++) {
            XLTaskInfo info = XLTaskHelper.get().getBtSubTaskInfo(taskId, index).mTaskInfo;
            if (info == null) {
                SystemClock.sleep(100);
                continue;
            }
            if (info.mTaskStatus == 3) throw new ExtractException(info.getErrorMsg());
            if (info.mTaskStatus != 0) {
                String local = XLTaskHelper.get().getLocalUrl(new File(parent, name));
                if (TextUtils.isEmpty(local)) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
                return local;
            }
            SystemClock.sleep(100);
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
    }

    private static File playFolder(File torrent) {
        return Path.thunder(Util.md5(torrent.getAbsolutePath()) + "-play");
    }

    private String addThunderTask(String url) {
        File folder = Path.thunder(Util.md5(url));
        taskId = XLTaskHelper.get().addThunderTask(url, folder);
        return XLTaskHelper.get().getLocalUrl(taskId.getSaveFile());
    }

    @Override
    public void stop() {
        if (taskId == null) return;
        XLTaskHelper.get().deleteTask(taskId);
        XLTaskHelper.get().release();
        taskId = null;
    }

    /**
     * 退出播放时只停任务，不删文件。
     *
     * <p>和 {@link #stop()} 的区别：
     * <ul>
     *   <li>{@code stop()} 用于切集，deleteTask 会连 savePath 一起删；</li>
     *   <li>{@code pause()} 用于退出播放页，stopTask 只停下载，已下载的 mp4
     *       和 .torrent 都留在缓存里，下次进来可以接着下/直接播。</li>
     * </ul>
     * 必须把 taskId 置空：否则下次起播时 SiteApi 开头的 source.stop()
     * 会拿旧 taskId 做 deleteTask，把刚保留的缓存又删掉。
     */
    public void pause() {
        if (taskId == null) return;
        try {
            XLTaskHelper.get().stopTask(taskId);
        } catch (Throwable ignored) {
        }
        taskId = null;
    }

    @Override
    public void exit() {
        pause();
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(?i)^(magnet|thunder|ed2k):");

        public static boolean match(String url) {
            if (url == null) return false;
            // 必须从协议头开始匹配。以前用 find()，"cl://magnet:?..." 这种
            // 爬虫占位符也会命中，结果拿 cl:// 开头的伪链接去调迅雷 parse，
            // 得到一个假的单集任务（还不会 stopTask）。详情页应保留爬虫
            // 自己生成的占位地址，等 playerContent 还原成真磁力。
            return PATTERN.matcher(url).find() || isTorrent(url);
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        public static boolean isTorrent(String url) {
            return !url.startsWith("magnet") && url.split(";")[0].toLowerCase().endsWith(".torrent");
        }

        private Episode create(GetTaskId taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(TorrentFileInfo info) {
            return Episode.create(info.getFileName(), info.getSize(), info.getPlayUrl());
        }

        @Override
        public List<Episode> call() throws Exception {
            boolean torrent = isTorrent(url);
            GetTaskId taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
            if (taskId == null) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
            String realUrl = taskId.getRealUrl();
            if (!torrent && (realUrl == null || !realUrl.startsWith("magnet"))) return Arrays.asList(create(taskId));
            if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                TorrentInfo info = XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile());
                List<TorrentFileInfo> medias = info == null ? null : info.getMedias();
                if (medias == null || medias.isEmpty()) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
                return medias.stream().map(this::create).toList();
            } finally {
                XLTaskHelper.get().stopTask(taskId);
            }
        }

        // 复用外层实现：原来这里的写法 getTaskInfo(taskId).getTaskStatus() 在
        // getTaskInfo 返回 null 时会 NPE，外层版本有判空。
        private void waitDone(GetTaskId taskId) {
            Thunder.waitDone(taskId);
        }
    }
}
