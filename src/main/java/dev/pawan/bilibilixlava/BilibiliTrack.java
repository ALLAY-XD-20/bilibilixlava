package dev.pawan.bilibilixlava;

import com.sedmelluq.discord.lavaplayer.container.mp3.Mp3AudioTrack;
import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * Playable Bilibili track. Resolves a fresh stream URL on every attempt (CDN links expire),
 * retries with back-off and resumes from the last position if playback dies mid-track.
 */
public class BilibiliTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(BilibiliTrack.class);

    private final BilibiliSourceManager mgr;

    public BilibiliTrack(AudioTrackInfo info, BilibiliSourceManager mgr) {
        super(info);
        this.mgr = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        int max = mgr.getConfig().getMaxRetries();
        long resumeAt = 0;
        Exception last = null;

        for (int attempt = 1; attempt <= max; attempt++) {
            try {
                BilibiliApi.Stream s = mgr.getApi().resolveStream(trackInfo.identifier);

                if (s.kind() == BilibiliApi.Kind.HLS) {
                    InternalAudioTrack hls = mgr.loadStreamTrack(s.url());
                    if (hls == null) throw new IllegalStateException("Could not open Bilibili live HLS stream");
                    processDelegate(hls, executor);
                    return;
                }

                try (HttpInterface http = mgr.getHttpInterface();
                     PersistentHttpStream stream = new PersistentHttpStream(http, new URI(s.url()), null)) {
                    if (resumeAt > 0) executor.setPosition(resumeAt);
                    InternalAudioTrack delegate = s.kind() == BilibiliApi.Kind.MP3
                            ? new Mp3AudioTrack(trackInfo, stream)
                            : new MpegAudioTrack(trackInfo, stream);
                    processDelegate(delegate, executor);
                    return;
                }
            } catch (InterruptedException e) {
                throw e; // track was stopped
            } catch (Exception e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
                resumeAt = Math.max(resumeAt, executor.getPosition());
                log.warn("Bilibili playback attempt {}/{} failed for {}: {}", attempt, max,
                        trackInfo.identifier, e.getMessage());
                if (attempt < max) Thread.sleep(300L * attempt);
            }
        }

        if (last instanceof FriendlyException fe) throw fe;
        throw new FriendlyException("Bilibili stream unavailable: " + trackInfo.title,
                FriendlyException.Severity.COMMON, last);
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new BilibiliTrack(trackInfo, mgr);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return mgr;
    }
}
