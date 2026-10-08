package dev.pawan.bilibilixlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;

/** Thin Bilibili web-API client: metadata, WBI signing and stream URL resolution. */
public class BilibiliApi {

    static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    static final String REFERER = "https://www.bilibili.com/";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long WBI_TTL_MS = 60L * 60 * 1000;
    private static final int[] MIXIN_TAB = {
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61,
            26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36,
            20, 34, 44, 52
    };

    /** How a resolved stream must be played. */
    public enum Kind { M4A, MP3, HLS }

    public record Stream(String url, Kind kind) {}

    private final BilibiliXlavaConfig cfg;
    private final HttpClient http;
    private final String buvid3 = randomId(32);
    private final String buvid4 = randomId(36);
    private volatile String mixinKey;
    private volatile long mixinExpiry;

    public BilibiliApi(BilibiliXlavaConfig cfg) {
        this.cfg = cfg;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String randomId(int n) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }

    String cookie() {
        String base = "buvid3=" + buvid3 + "; buvid4=" + buvid4 + "; CURRENT_FNVAL=4048";
        return cfg.getSessdata().isBlank() ? base : base + "; SESSDATA=" + cfg.getSessdata();
    }

    // ------------------------------------------------------------------ HTTP

    /** GET a JSON endpoint. Does not check the Bilibili "code" field. */
    JsonNode getRaw(String url, String referer) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", UA)
                .header("Referer", referer == null ? REFERER : referer)
                .header("Cookie", cookie())
                .header("Accept-Language", "en-US,en;q=0.9")
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JSON.readTree(res.body());
    }

    /** GET and require code == 0; returns the whole root node. */
    JsonNode get(String url, String referer) throws Exception {
        JsonNode root = getRaw(url, referer);
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            String msg = root.path("message").asText(root.path("msg").asText("unknown error"));
            throw new IllegalStateException("Bilibili API error " + code + ": " + msg);
        }
        return root;
    }

    /** Follows redirects (b23.tv short links) and returns the final URL. */
    public String followRedirect(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", UA)
                .GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        return res.uri().toString();
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ WBI

    private String mixinKey() throws Exception {
        String k = mixinKey;
        if (k != null && System.currentTimeMillis() < mixinExpiry) return k;
        synchronized (this) {
            k = mixinKey;
            if (k != null && System.currentTimeMillis() < mixinExpiry) return k;

            JsonNode nav = getRaw("https://api.bilibili.com/x/web-interface/nav", null);
            JsonNode img = nav.path("data").path("wbi_img");
            String imgUrl = img.path("img_url").asText("");
            String subUrl = img.path("sub_url").asText("");
            if (imgUrl.isEmpty() || subUrl.isEmpty()) throw new IllegalStateException("Could not fetch Bilibili WBI keys");

            String raw = fileStem(imgUrl) + fileStem(subUrl);
            StringBuilder mix = new StringBuilder();
            for (int idx : MIXIN_TAB) {
                if (idx < raw.length()) mix.append(raw.charAt(idx));
            }
            mixinKey = mix.substring(0, Math.min(32, mix.length()));
            mixinExpiry = System.currentTimeMillis() + WBI_TTL_MS;
            return mixinKey;
        }
    }

    private static String fileStem(String url) {
        return url.substring(url.lastIndexOf('/') + 1, url.lastIndexOf('.'));
    }

    /** Signs query params with WBI and returns "a=b&c=d&wts=..&w_rid=..". */
    String signWbi(Map<String, Object> params) throws Exception {
        String mixin = mixinKey();
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, Object> e : params.entrySet()) {
            sorted.put(e.getKey(), String.valueOf(e.getValue()));
        }
        sorted.put("wts", String.valueOf(System.currentTimeMillis() / 1000));

        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (q.length() > 0) q.append('&');
            String v = e.getValue().replaceAll("[!'()*]", "");
            q.append(jsEncode(e.getKey())).append('=').append(jsEncode(v));
        }
        byte[] md5 = MessageDigest.getInstance("MD5").digest((q + mixin).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(32);
        for (byte b : md5) hex.append(String.format("%02x", b));
        return q + "&w_rid=" + hex;
    }

    /** encodeURIComponent-compatible encoding. */
    private static String jsEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("%7E", "~");
    }

    // ------------------------------------------------------------------ metadata

    public JsonNode search(String query) throws Exception {
        String ref = "https://search.bilibili.com/";
        JsonNode root = getRaw("https://api.bilibili.com/x/web-interface/search/type?search_type=video&keyword="
                + enc(query), ref);
        JsonNode result = root.path("data").path("result");
        if (result.isArray() && result.size() > 0) return result;

        root = getRaw("https://api.bilibili.com/x/web-interface/search/all/v2?keyword=" + enc(query), ref);
        for (JsonNode section : root.path("data").path("result")) {
            if ("video".equals(section.path("result_type").asText())) return section.path("data");
        }
        return null;
    }

    /** Video info by bvid ("BV...") or aid ("av123" / "123"). */
    public JsonNode videoInfo(String bvidOrAid) throws Exception {
        String q = bvidOrAid.startsWith("BV") ? "bvid=" + bvidOrAid
                : "aid=" + bvidOrAid.replaceFirst("(?i)^av", "");
        return get("https://api.bilibili.com/x/web-interface/view?" + q, null).path("data");
    }

    /** Bangumi season by ep id or season id. */
    public JsonNode season(boolean byEpisode, String id) throws Exception {
        String q = byEpisode ? "ep_id=" + id : "season_id=" + id;
        return get("https://api.bilibili.com/pgc/view/web/season?" + q, null).path("result");
    }

    public JsonNode audioInfo(String sid) throws Exception {
        return get("https://www.bilibili.com/audio/music-service-c/web/song/info?sid=" + sid, null).path("data");
    }

    public JsonNode audioMenuSongs(String sid, int limit) throws Exception {
        return get("https://www.bilibili.com/audio/music-service-c/web/song/of-menu?sid=" + sid
                + "&pn=1&ps=" + limit, null).path("data").path("data");
    }

    public String audioMenuTitle(String sid) {
        try {
            return get("https://www.bilibili.com/audio/music-service-c/web/menu/info?sid=" + sid, null)
                    .path("data").path("title").asText("Bilibili Album");
        } catch (Exception e) {
            return "Bilibili Album";
        }
    }

    public JsonNode liveInfo(String roomId) throws Exception {
        return get("https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId,
                "https://live.bilibili.com/" + roomId).path("data");
    }

    public JsonNode spaceVideos(String mid, int limit) throws Exception {
        Map<String, Object> p = new TreeMap<>();
        p.put("mid", mid);
        p.put("ps", Math.min(limit, 50));
        p.put("tid", 0);
        p.put("keyword", "");
        p.put("order", "pubdate");
        JsonNode root = get("https://api.bilibili.com/x/space/wbi/arc/search?" + signWbi(p), "https://space.bilibili.com/" + mid);
        return root.path("data").path("list").path("vlist");
    }

    // ------------------------------------------------------------------ streams

    /** Resolves a playable stream for a track identifier produced by BilibiliSourceManager. */
    public Stream resolveStream(String id) throws Exception {
        if (id.startsWith("live")) return liveStream(id.substring(4));
        if (id.startsWith("au")) return audioStream(id.substring(2));
        if (id.startsWith("ep")) return bangumiStream(id.substring(2));
        return videoStream(id);
    }

    private Stream videoStream(String id) throws Exception {
        String bvid = id.contains("?") ? id.substring(0, id.indexOf('?')) : id;
        int page = 1;
        int pi = id.indexOf("?p=");
        if (pi >= 0) {
            try { page = Integer.parseInt(id.substring(pi + 3)); } catch (NumberFormatException ignored) { }
        }

        JsonNode info = videoInfo(bvid);
        long cid = info.path("cid").asLong(0);
        for (JsonNode p : info.path("pages")) {
            if (p.path("page").asInt() == page) {
                cid = p.path("cid").asLong(cid);
                break;
            }
        }
        if (cid == 0) throw new IllegalStateException("Bilibili video has no cid");

        Map<String, Object> params = new TreeMap<>();
        params.put("bvid", bvid);
        params.put("cid", cid);
        params.put("qn", 120);
        params.put("fnval", 16);
        JsonNode data = get("https://api.bilibili.com/x/player/wbi/playurl?" + signWbi(params), null).path("data");
        return pickFromPlayurl(data);
    }

    private Stream bangumiStream(String epId) throws Exception {
        JsonNode root = get("https://api.bilibili.com/pgc/player/web/playurl?ep_id=" + epId + "&qn=64&fnval=16", null);
        JsonNode data = root.path("result");
        if (data.isMissingNode() || data.isNull() || data.path("dash").isMissingNode() && data.path("durl").isMissingNode()
                && data.path("video_info").isMissingNode()) {
            data = root.path("data");
        }
        JsonNode vi = data.path("video_info");
        if (!vi.isMissingNode() && !vi.isNull()) data = vi;
        return pickFromPlayurl(data);
    }

    /** Prefers DASH audio-only (m4a); falls back to a progressive file. */
    private static Stream pickFromPlayurl(JsonNode data) {
        JsonNode audio = data.path("dash").path("audio");
        if (audio.isArray() && audio.size() > 0) {
            String url = firstUrl(audio.path(0));
            if (url != null) return new Stream(url, Kind.M4A);
        }
        JsonNode durl = data.path("durl");
        if (durl.isArray() && durl.size() > 0) {
            String url = durl.path(0).path("url").asText("");
            if (!url.isEmpty()) return new Stream(url, Kind.M4A);
        }
        JsonNode video = data.path("dash").path("video");
        if (video.isArray() && video.size() > 0) {
            String url = firstUrl(video.path(0));
            if (url != null) return new Stream(url, Kind.M4A);
        }
        throw new IllegalStateException("No playable Bilibili stream (login/region/premium restriction?)");
    }

    private static String firstUrl(JsonNode n) {
        String u = n.path("base_url").asText("");
        if (u.isEmpty()) u = n.path("baseUrl").asText("");
        if (u.isEmpty()) {
            JsonNode backup = n.path("backup_url");
            if (!backup.isArray() || backup.size() == 0) backup = n.path("backupUrl");
            if (backup.isArray() && backup.size() > 0) u = backup.path(0).asText("");
        }
        return u.isEmpty() ? null : u;
    }

    private Stream audioStream(String sid) throws Exception {
        JsonNode data = get("https://www.bilibili.com/audio/music-service-c/web/url?sid=" + sid, null).path("data");
        String url = data.path("cdns").path(0).asText("");
        if (url.isEmpty()) throw new IllegalStateException("Bilibili audio stream not available");
        return new Stream(url, url.contains(".m4a") ? Kind.M4A : Kind.MP3);
    }

    private Stream liveStream(String roomId) throws Exception {
        JsonNode data = get("https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo?room_id=" + roomId
                + "&protocol=0,1&format=0,2&codec=0,1&qn=10000&platform=web&pt=web&no_playurl=0&mask=0",
                "https://live.bilibili.com/" + roomId).path("data");
        for (JsonNode stream : data.path("playurl_info").path("playurl").path("stream")) {
            if (!"http_hls".equals(stream.path("protocol_name").asText())) continue;
            for (JsonNode fmt : stream.path("format")) {
                JsonNode codec = fmt.path("codec").path(0);
                JsonNode ui = codec.path("url_info").path(0);
                String base = codec.path("base_url").asText("");
                if (base.isEmpty() || ui.isMissingNode()) continue;
                return new Stream(ui.path("host").asText("") + base + ui.path("extra").asText(""), Kind.HLS);
            }
        }
        throw new IllegalStateException("No HLS stream available for this Bilibili live room");
    }
}
