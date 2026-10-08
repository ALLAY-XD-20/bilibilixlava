package dev.pawan.bilibilixlava;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** plugins.bilibilixlava.*  — minimal setup:  engine: enable */
@Component
@ConfigurationProperties(prefix = "plugins.bilibilixlava")
public class BilibiliXlavaConfig {

    private String engine = "enable";
    private String sourceName = "bilibili";
    private String sessdata = "";
    private int searchLimit = 10;
    private int playlistLimit = 100;
    private int maxRetries = 3;

    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public boolean isEngineEnabled() {
        String e = engine == null ? "enable" : engine.trim().toLowerCase(Locale.ROOT);
        return !(e.equals("disable") || e.equals("disabled") || e.equals("false")
                || e.equals("off") || e.equals("no") || e.equals("0"));
    }

    public String getSourceName() { return sourceName == null || sourceName.isBlank() ? "bilibili" : sourceName.trim(); }
    public void setSourceName(String v) { this.sourceName = v; }

    /** Optional SESSDATA cookie value (login). Helps with search/space limits and some restricted videos. */
    public String getSessdata() { return sessdata == null ? "" : sessdata.trim(); }
    public void setSessdata(String v) { this.sessdata = v; }

    public int getSearchLimit() { return searchLimit; }
    public void setSearchLimit(int v) { this.searchLimit = Math.max(1, Math.min(v, 50)); }

    public int getPlaylistLimit() { return playlistLimit; }
    public void setPlaylistLimit(int v) { this.playlistLimit = Math.max(1, Math.min(v, 500)); }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = Math.max(1, Math.min(v, 6)); }
}
