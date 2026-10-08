package dev.pawan.bilibilixlava;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** BilibiliXlava — Bilibili source for Lavalink v4. Author: Pawan */
@Service
public class BilibiliXlavaPlugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BilibiliXlavaPlugin.class);

    private final BilibiliXlavaConfig config;

    public BilibiliXlavaPlugin(BilibiliXlavaConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (!config.isEngineEnabled()) {
            log.info("BilibiliXlava engine: disable — Bilibili source not registered");
            return manager;
        }
        BilibiliSourceManager source = new BilibiliSourceManager(config);
        source.setPlayerManager(manager);
        manager.registerSourceManager(source);
        log.info("BilibiliXlava engine: enable — Bilibili source '{}' registered{}", config.getSourceName(),
                config.getSessdata().isBlank() ? "" : " (logged-in session)");
        return manager;
    }
}
