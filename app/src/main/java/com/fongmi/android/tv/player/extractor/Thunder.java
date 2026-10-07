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
        // 两种magnet 来源，参数形态完全不同，不能混用：
        //  1) TVBox 旧推送格式：magnet 指向本地 .torrent 文件，并带 ?index=&name=
        //     —— path 是文件路径、index/name 是种子内第几个文件
        //  2) 标准磁力：magnet:?xt=urn:btih:... —— 没有 path，也没有 index/name
        //     之前一律按第1 种处理，标准磁力会在下面 Integer.parseInt(null) 处抛
        //     NumberFormatException，表现为「推送播放点了没反应」。
        //     第 2 种走 Parser 已经验证可用的那条路（迅雷自己解析 magnet）。
        if (isLocalTorrent(uri)) return addTorrentTask(uri);
        return playStandardMagnet(url);
    }

    /** 是否为「本地 .torrent 文件 + index」的旧推送格式。 */
    private static boolean isLocalTorrent(Uri uri) {
        String path = uri.getPath();
        return !TextUtils.isEmpty(path) && uri.getQueryParameter("index") != null;
    }

    /**
     * 标准磁力：交给迅雷 parse 出分集，取第一个媒体的可播地址。
     * 与 {@link Parser} 走的是同一个 XLTaskHelper.parse() + getTorrentInfo() 流程，
     * 区别是这里只需要一个地址（fetch 的契约），Parser 需要整份分集列表。
     */
    private String playStandardMagnet(String url) throws Exception {
        File folder = Path.thunder(Util.md5(url));
        GetTaskId parsed = XLTaskHelper.get().parse(url, folder);
        if (parsed == null) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
        taskId = parsed;
        waitDone(parsed);
        TorrentInfo info = XLTaskHelper.get().getTorrentInfo(parsed.getSaveFile());
        List<TorrentFileInfo> medias = info == null ? null : info.getMedias();
        if (medias == null || medias.isEmpty()) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
        String playUrl = medias.get(0).getPlayUrl();
        if (TextUtils.isEmpty(playUrl)) throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
        return playUrl;
    }

    /** 轮询等迅雷解析/取到种子信息，状态 2 即就绪。上限 10 秒，与解析选集时一致。 */
    private static void waitDone(GetTaskId taskId) {
        for (int i = 0; i < 100; i++) {
            XLTaskInfo taskInfo = XLTaskHelper.get().getTaskInfo(taskId);
            if (taskInfo != null && taskInfo.getTaskStatus() == 2) return;
            SystemClock.sleep(100);
        }
    }

    private String addTorrentTask(Uri uri) throws Exception {
        File torrent = new File(uri.getPath());
        File parent = torrent.getParentFile();
        String name = uri.getQueryParameter("name");
        int index = Integer.parseInt(uri.getQueryParameter("index"));
        taskId = XLTaskHelper.get().addTorrentTask(torrent, parent, index);
        for (int i = 0; i < 100; i++) {
            XLTaskInfo info = XLTaskHelper.get().getBtSubTaskInfo(taskId, index).mTaskInfo;
            if (info.mTaskStatus == 3) throw new ExtractException(info.getErrorMsg());
            if (info.mTaskStatus != 0) return XLTaskHelper.get().getLocalUrl(new File(parent, name));
            SystemClock.sleep(100);
        }
        throw new ExtractException(ResUtil.getString(R.string.error_play_timeout));
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

    @Override
    public void exit() {
        XLTaskHelper.get().release();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(magnet|thunder|ed2k):.*");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find() || isTorrent(url);
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        private static boolean isTorrent(String url) {
            return !url.startsWith("magnet") && url.split(";")[0].toLowerCase().endsWith(".torrent");
        }

        private Episode create(GetTaskId taskId) {
            return Episode.create(taskId.getFileName(), taskId.getRealUrl());
        }

        private Episode create(TorrentFileInfo info) {
            return Episode.create(info.getFileName(), info.getSize(), info.getPlayUrl());
        }

        @Override
        public List<Episode> call() {
            boolean torrent = isTorrent(url);
            GetTaskId taskId = XLTaskHelper.get().parse(url, Path.thunder(Util.md5(url)));
            if (!torrent && !taskId.getRealUrl().startsWith("magnet")) return Arrays.asList(create(taskId));
            if (torrent && url.startsWith("http")) Download.create(url, taskId.getSaveFile()).get();
            if (!torrent) waitDone(taskId);
            try {
                return XLTaskHelper.get().getTorrentInfo(taskId.getSaveFile()).getMedias().stream().map(this::create).toList();
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
