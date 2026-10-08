package dev.pawan.bilibilixlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bilibili AudioSourceManager.
 *
 * Inputs: bilisearch:QUERY | bilibili:QUERY, video (BV/av, ?p=N), bangumi (ep/ss), audio (au/am),
 *         live rooms, space (uploader) pages, b23.tv short links.
 */
public class BilibiliSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(BilibiliSourceManager.class);

    static final String[] SEARCH_PREFIXES = {"bilisearch:", "bilibili:"};

    private static final String HOST = "(?:www\\.|m\\.)?bilibili\\.com";
    private static final Pattern VIDEO = Pattern.compile(
            "^https?://" + HOST + "/video/(BV[0-9A-Za-z]+|[aA][vV]\\d+)(?:[/?#].*)?$");
    private static final Pattern PAGE = Pattern.compile("[?&]p=(\\d+)");
    private static final Pattern BANGUMI = Pattern.compile(
            "^https?://" + HOST + "/bangumi/play/(ep|ss)(\\d+)(?:[/?#].*)?$");
    private static final Pattern AUDIO = Pattern.compile(
            "^https?://" + HOST + "/audio/(au|am)(\\d+)(?:[/?#].*)?$");
    private static final Pattern LIVE = Pattern.compile(
            "^https?://live\\.bilibili\\.com/(?:h5/|blanc/|blackboard/)?(\\d+)(?:[/?#].*)?$");
    private static final Pattern SPACE = Pattern.compile(
            "^https?://space\\.bilibili\\.com/(\\d+)(?:[/?#].*)?$");
    private static final Pattern SHORT = Pattern.compile(
            "^https?://(?:b23\\.tv|bili2233\\.cn)/[^\\s]+$", Pattern.CASE_INSENSITIVE);

    private final BilibiliXlavaConfig config;
    private final BilibiliApi api;
    private final HttpInterfaceManager httpManager;
    private volatile AudioPlayerManager playerManager;

    public BilibiliSourceManager(BilibiliXlavaConfig config) {
        this.config = config;
        this.api = new BilibiliApi(config);
        this.httpManager = HttpClientTools.createDefaultThreadLocalManager();
        // Bilibili CDNs reject requests without a Referer / browser User-Agent.
        httpManager.configureBuilder(b -> {
            b.setUserAgent(BilibiliApi.UA);
            b.setDefaultHeaders(List.of(
                    new BasicHeader("Referer", BilibiliApi.REFERER),
                    new BasicHeader("Accept-Language", "en-US,en;q=0.9")));
        });
        httpManager.configureRequests(rc -> RequestConfig.copy(rc)
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(20_000)
                .build());
    }

    public void setPlayerManager(AudioPlayerManager manager) { this.playerManager = manager; }
    public BilibiliXlavaConfig getConfig() { return config; }
    public BilibiliApi getApi() { return api; }
    public HttpInterface getHttpInterface() { return httpManager.getInterface(); }

    @Override
    public String getSourceName() { return config.getSourceName(); }

    // ------------------------------------------------------------------ loading

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference ref) {
        String id = ref.identifier;
        if (id == null) return null;
        try {
            for (String prefix : SEARCH_PREFIXES) {
                if (id.startsWith(prefix)) return search(id.substring(prefix.length()).trim());
            }
            return resolveUrl(id, 0);
        } catch (Exception e) {
            log.warn("Bilibili load failed for {}: {}", id, e.getMessage());
            throw new FriendlyException("Bilibili load failed: " + e.getMessage(),
                    FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    private AudioItem resolveUrl(String url, int depth) throws Exception {
        Matcher m = VIDEO.matcher(url);
        if (m.matches()) return video(m.group(1), url);

        m = BANGUMI.matcher(url);
        if (m.matches()) return bangumi(m.group(1).equals("ep"), m.group(2));

        m = AUDIO.matcher(url);
        if (m.matches()) return m.group(1).equals("au") ? audioTrack(m.group(2)) : audioAlbum(m.group(2));

        m = LIVE.matcher(url);
        if (m.matches()) return live(m.group(1));

        m = SPACE.matcher(url);
        if (m.matches()) return space(m.group(1));

        if (depth == 0 && SHORT.matcher(url).matches()) {
            String target = api.followRedirect(url);
            if (!target.equals(url)) return resolveUrl(target, 1);
            return AudioReference.NO_TRACK;
        }
        return null; // not ours
    }

    // ------------------------------------------------------------------ search

    private AudioItem search(String query) throws Exception {
        if (query.isEmpty()) return AudioReference.NO_TRACK;
        JsonNode result = api.search(query);
        List<AudioTrack> tracks = new ArrayList<>();
        if (result != null && result.isArray()) {
            for (JsonNode item : result) {
                if (tracks.size() >= config.getSearchLimit()) break;
                String bvid = item.path("bvid").asText("");
                if (bvid.isEmpty()) continue;
                tracks.add(track(bvid,
                        stripTags(item.path("title").asText("Unknown")),
                        item.path("author").asText("Unknown"),
                        parseDuration(item.path("duration").asText("")),
                        item.path("arcurl").asText("https://www.bilibili.com/video/" + bvid),
                        fixUrl(item.path("pic").asText("")), false));
            }
        }
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist("Bilibili search: " + query, tracks, null, true);
    }

    // ------------------------------------------------------------------ video

    private AudioItem video(String idOrAid, String url) throws Exception {
        JsonNode d = api.videoInfo(idOrAid);
        String bvid = d.path("bvid").asText("");
        if (bvid.isEmpty()) return AudioReference.NO_TRACK;

        String title = d.path("title").asText("Unknown");
        String author = d.path("owner").path("name").asText("Unknown");
        String pic = fixUrl(d.path("pic").asText(""));
        JsonNode pages = d.path("pages");
        int requested = 0;
        Matcher pm = PAGE.matcher(url);
        if (pm.find()) requested = Integer.parseInt(pm.group(1));

        if (pages.isArray() && pages.size() > 1) {
            List<AudioTrack> tracks = new ArrayList<>();
            for (JsonNode p : pages) {
                int n = p.path("page").asInt(1);
                if (requested > 0 && n != requested) continue;
                tracks.add(track(bvid + "?p=" + n,
                        title + " - " + p.path("part").asText("P" + n),
                        author, p.path("duration").asLong(0) * 1000L,
                        "https://www.bilibili.com/video/" + bvid + "?p=" + n, pic, false));
            }
            if (tracks.isEmpty()) return AudioReference.NO_TRACK;
            if (requested > 0) return tracks.get(0);
            return new BasicAudioPlaylist(title, capped(tracks), null, false);
        }
        return track(bvid, title, author, d.path("duration").asLong(0) * 1000L,
                "https://www.bilibili.com/video/" + bvid, pic, false);
    }

    // ------------------------------------------------------------------ bangumi

    private AudioItem bangumi(boolean byEpisode, String id) throws Exception {
        JsonNode r = api.season(byEpisode, id);
        String seasonTitle = r.path("season_title").asText(r.path("title").asText("Bilibili Season"));
        List<AudioTrack> tracks = new ArrayList<>();
        AudioTrack target = null;
        for (JsonNode ep : r.path("episodes")) {
            String epId = ep.path("id").asText("");
            if (epId.isEmpty()) continue;
            String epTitle = ep.path("title").asText("");
            String longTitle = ep.path("long_title").asText("");
            AudioTrack t = track("ep" + epId,
                    longTitle.isEmpty() ? epTitle : epTitle + " - " + longTitle,
                    seasonTitle, ep.path("duration").asLong(0),
                    ep.path("link").asText("https://www.bilibili.com/bangumi/play/ep" + epId),
                    fixUrl(ep.path("cover").asText("")), false);
            tracks.add(t);
            if (byEpisode && epId.equals(id)) target = t;
        }
        if (target != null) return target;
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist(seasonTitle, capped(tracks), null, false);
    }

    // ------------------------------------------------------------------ audio

    private AudioItem audioTrack(String sid) throws Exception {
        return audioFrom(api.audioInfo(sid));
    }

    private AudioTrack audioFrom(JsonNode d) {
        String id = d.path("id").asText("");
        return track("au" + id, d.path("title").asText("Unknown"), d.path("uname").asText("Unknown"),
                d.path("duration").asLong(0) * 1000L, "https://www.bilibili.com/audio/au" + id,
                fixUrl(d.path("cover").asText("")), false);
    }

    private AudioItem audioAlbum(String sid) throws Exception {
        JsonNode songs = api.audioMenuSongs(sid, config.getPlaylistLimit());
        List<AudioTrack> tracks = new ArrayList<>();
        if (songs.isArray()) {
            for (JsonNode s : songs) {
                if (!s.path("id").asText("").isEmpty()) tracks.add(audioFrom(s));
            }
        }
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist(api.audioMenuTitle(sid), tracks, null, false);
    }

    // ------------------------------------------------------------------ live / space

    private AudioItem live(String roomId) throws Exception {
        JsonNode d = api.liveInfo(roomId);
        if (d.path("live_status").asInt(0) != 1) throw new IllegalStateException("Room is not live");
        String rid = d.path("room_id").asText(roomId);
        return track("live" + rid, d.path("title").asText("Bilibili Live"), "Room " + rid,
                Units.DURATION_MS_UNKNOWN, "https://live.bilibili.com/" + rid,
                fixUrl(d.path("user_cover").asText("")), true);
    }

    private AudioItem space(String mid) throws Exception {
        JsonNode list = api.spaceVideos(mid, config.getPlaylistLimit());
        List<AudioTrack> tracks = new ArrayList<>();
        String first = null;
        if (list.isArray()) {
            for (JsonNode v : list) {
                String bvid = v.path("bvid").asText("");
                if (bvid.isEmpty()) continue;
                String author = v.path("author").asText("Unknown");
                if (first == null) first = author;
                tracks.add(track(bvid, v.path("title").asText("Unknown"), author,
                        parseDuration(v.path("length").asText("")),
                        "https://www.bilibili.com/video/" + bvid, fixUrl(v.path("pic").asText("")), false));
            }
        }
        if (tracks.isEmpty()) return AudioReference.NO_TRACK;
        return new BasicAudioPlaylist("Uploads by " + first, tracks, null, false);
    }

    // ------------------------------------------------------------------ helpers

    private AudioTrack track(String identifier, String title, String author, long length,
                             String uri, String artwork, boolean stream) {
        AudioTrackInfo info = new AudioTrackInfo(title, author, length <= 0 && !stream ? Units.DURATION_MS_UNKNOWN : length,
                identifier, stream, uri, artwork == null || artwork.isEmpty() ? null : artwork, null);
        return new BilibiliTrack(info, this);
    }

    private List<AudioTrack> capped(List<AudioTrack> tracks) {
        int limit = config.getPlaylistLimit();
        return tracks.size() > limit ? new ArrayList<>(tracks.subList(0, limit)) : tracks;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").strip();
    }

    private static String fixUrl(String u) {
        if (u == null) return "";
        if (u.startsWith("//")) return "https:" + u;
        if (u.startsWith("http://")) return "https://" + u.substring(7);
        return u;
    }

    /** "mm:ss", "hh:mm:ss" or plain seconds -> milliseconds. */
    static long parseDuration(String s) {
        if (s == null || s.isBlank()) return 0;
        try {
            String[] parts = s.trim().split(":");
            long sec = 0;
            for (String p : parts) sec = sec * 60 + Long.parseLong(p.trim());
            return sec * 1000L;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Opens a raw stream URL (used for live HLS) through the other registered sources. */
    public InternalAudioTrack loadStreamTrack(String url) {
        AudioPlayerManager pm = playerManager;
        if (pm == null) return null;
        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();
        pm.loadItem(url, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { future.complete(List.of(track)); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { future.complete(new ArrayList<>(playlist.getTracks())); }
            @Override public void noMatches() { future.complete(List.of()); }
            @Override public void loadFailed(FriendlyException exception) { future.complete(List.of()); }
        });
        try {
            List<AudioTrack> r = future.get(15, TimeUnit.SECONDS);
            if (r.isEmpty()) return null;
            AudioTrack clone = r.get(0).makeClone();
            return clone instanceof InternalAudioTrack internal ? internal : null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("Bilibili stream open failed: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ encoding

    @Override public boolean isTrackEncodable(AudioTrack track) { return true; }
    @Override public void encodeTrack(AudioTrack track, DataOutput output) { }
    @Override public AudioTrack decodeTrack(AudioTrackInfo info, DataInput input) { return new BilibiliTrack(info, this); }

    @Override
    public void shutdown() {
        try {
            httpManager.close();
        } catch (IOException e) {
            log.error("Failed closing Bilibili http manager", e);
        }
    }
}
