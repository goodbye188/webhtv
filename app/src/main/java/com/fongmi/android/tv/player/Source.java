package com.fongmi.android.tv.player;

import android.net.Uri;

import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.extractor.Force;
import com.fongmi.android.tv.player.extractor.JianPian;
import com.fongmi.android.tv.player.extractor.MpdEdlResolver;
import com.fongmi.android.tv.player.extractor.MpdSanitizer;
import com.fongmi.android.tv.player.extractor.Push;
import com.fongmi.android.tv.player.extractor.Strm;
import com.fongmi.android.tv.player.extractor.TVBus;
import com.fongmi.android.tv.player.extractor.Thunder;
import com.fongmi.android.tv.player.extractor.Video;
import com.fongmi.android.tv.player.extractor.Youtube;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.crawler.SpiderDebug;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class Source {

    private final List<Extractor> extractors;

    public Source() {
        extractors = new ArrayList<>();
        extractors.add(new Force());
        extractors.add(new JianPian());
        extractors.add(new Push());
        extractors.add(new Strm());
        extractors.add(new Thunder());
        extractors.add(new TVBus());
        extractors.add(new Video());
        extractors.add(new Youtube());
    }

    public static Source get() {
        return Loader.INSTANCE;
    }

    private Extractor getExtractor(Uri uri) {
        return extractors.stream().filter(extractor -> extractor.match(uri)).findFirst().orElse(null);
    }

    private void addCallable(Iterator<Episode> iterator, List<Callable<List<Episode>>> items, List<Episode> originals, boolean parseMagnets) {
        Episode episode = iterator.next();
        String url = episode.getUrl();
        if (Thunder.Parser.match(url)) {
            // 磁力解析要连 tracker/DHT 找 peer，不能放在详情页入口同步做：
            // 一页几十条磁力会把详情页卡在“加载中”（以前是 30 秒整页超时）。
            // 默认保留原磁力，点播时由 Thunder.fetch() 再解析；推送详情这种
            // 单条磁力入口可以显式传 true，保留原来展开成文件列表的行为。
            if (!parseMagnets && !Thunder.Parser.isTorrent(url)) {
                // 不解析也尽量给个可读的选集名：标准磁力的 dn 就是种子名。
                if (url.startsWith("magnet")) {
                    String name = Thunder.queryParameter(url, "dn");
                    if (name != null && !name.isEmpty()) episode.setName(name);
                }
                return;
            }
            items.add(Thunder.Parser.get(url));
            originals.add(episode);
            iterator.remove();
        } else if (Youtube.Parser.match(url)) {
            items.add(Youtube.Parser.get(url));
            originals.add(episode);
            iterator.remove();
        }
    }

    public void parse(Vod vod) throws Exception {
        parse(vod, false);
    }

    public void parse(Vod vod, boolean parseMagnets) throws Exception {
        try (ExecutorService executor = Executors.newCachedThreadPool()) {
            for (Flag flag : vod.getFlags()) {
                List<Callable<List<Episode>>> items = new ArrayList<>();
                List<Episode> originals = new ArrayList<>();
                Iterator<Episode> iterator = flag.getEpisodes().iterator();
                while (iterator.hasNext()) addCallable(iterator, items, originals, parseMagnets);
                List<Future<List<Episode>>> futures = executor.invokeAll(items, 30, TimeUnit.SECONDS);
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        flag.getEpisodes().addAll(futures.get(i).get());
                    } catch (CancellationException | ExecutionException e) {
                        // 解析失败/超时就把原始地址放回去：至少还能在点播时再试，
                        // 也让整页选集不会因为一条坏磁力而缺集。
                        if (i < originals.size()) flag.getEpisodes().add(originals.get(i));
                        SpiderDebug.log("parse", "episode parse failed: %s", e.getMessage());
                    }
                }
            }
        }
    }

    public String fetch(Result result) throws Exception {
        return fetch(result, PlayerSetting.getActivePlayer());
    }

    public String fetch(Result result, int playerType) throws Exception {
        Uri uri = result.getUrl().uri();
        String url = result.getUrl().v();
        int originalParse = result.getParse();
        Extractor extractor = getExtractor(uri);
        if (extractor != null) result.setParse(0);
        if (extractor instanceof Video) result.setParse(1);
        String fetched;
        try {
            fetched = extractor == null ? url : extractor.fetch(result);
        } catch (Exception e) {
            if (!(extractor instanceof Youtube) || originalParse != 1) throw e;
            result.setParse(1);
            return url;
        }
        if (extractor instanceof Youtube && fetched.isEmpty() && originalParse == 1) {
            result.setParse(1);
            return url;
        }
        String sanitized = MpdSanitizer.sanitize(fetched);
        if (MpdSanitizer.hasLimitedYoutubeAudio(sanitized)) {
            String referer = getHeader(result.getHeader(), "Referer");
            if (referer.contains("youtube.com/watch")) {
                try {
                    sanitized = new Youtube().fetch(referer);
                    SpiderDebug.log("mpd-sanitizer", "replaced limited jar MPD with app YouTube extractor");
                } catch (Exception e) {
                    SpiderDebug.log("mpd-sanitizer", "app YouTube extractor fallback failed: %s", e.getMessage());
                }
            }
        }
        if (MpdSanitizer.isYoutube(sanitized)) {
            Map<String, String> headers = result.getHeader();
            headers.put("User-Agent", "com.google.android.youtube/21.02.35 (Linux; U; Android 14) gzip");
            result.setHeader(headers);
        }
        boolean dash = isDash(result, sanitized);
        if (playerType == PlayerSetting.MPV && dash) {
            String resolved = MpdEdlResolver.resolve(sanitized, result.getHeader());
            if (!resolved.equals(sanitized)) result.setFormat(null);
            sanitized = resolved;
        } else if (dash) {
            sanitized = avoidStaleLocalManifest(sanitized);
        }
        return sanitized;
    }

    private String avoidStaleLocalManifest(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) return url;
            return uri.buildUpon().appendQueryParameter("player_ts", Long.toString(System.currentTimeMillis())).build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    private boolean isDash(Result result, String url) {
        String format = result.getFormat();
        if (MimeTypes.APPLICATION_MPD.equals(format) || "application/dash+xml".equalsIgnoreCase(format) || "dash".equalsIgnoreCase(format)) return true;
        String lower = url == null ? "" : url.toLowerCase(java.util.Locale.US);
        return lower.startsWith("data:application/dash+xml") || lower.contains(".mpd") || lower.contains("type=mpd") || lower.contains("format=mpd");
    }

    private String getHeader(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return "";
    }

    public void stop() {
        if (extractors == null) return;
        extractors.forEach(Extractor::stop);
    }

    /**
     * 退出播放页：只停后台下载，保留缓存。
     *
     * <p>目前只有 Thunder 有“停了但别删文件”的语义；其它 extractor
     * 没有常驻下载任务。切集仍走 {@link #stop()}。
     */
    public void pause() {
        if (extractors == null) return;
        for (Extractor extractor : extractors) {
            if (extractor instanceof Thunder thunder) thunder.pause();
        }
    }

    public void exit() {
        if (extractors == null) return;
        Task.execute(() -> extractors.forEach(Extractor::exit));
    }

    public interface Extractor {

        default String fetch(Result result) throws Exception {
            return fetch(result.getUrl().v());
        }

        String fetch(String url) throws Exception;

        boolean match(Uri uri);

        void stop();

        void exit();
    }

    private static class Loader {
        static volatile Source INSTANCE = new Source();
    }
}
