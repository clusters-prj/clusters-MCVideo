package com.clustersprj.videomap;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 動画の音声を msal-node（Minecraft-Spatial-Audio-Link）のボイスチャット側で流すためのクライアント。
 * スクリーンの位置に「音源」を作り、ffmpeg が音声を PCM で push する。近づくと聞こえ、離れると聞こえなくなる。
 */
final class VcAudio {

    private final VideoMapPlugin plugin;
    // HTTP/1.1 固定。既定の HTTP/2 は cleartext で h2c アップグレードを試み、msal-node に拒否される。
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    VcAudio(VideoMapPlugin plugin) {
        this.plugin = plugin;
    }

    private String baseUrl() {
        String url = plugin.getConfig().getString("vc-audio.base-url", "http://127.0.0.1:8010");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String apiKey() {
        return plugin.getConfig().getString("vc-audio.api-key", "");
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean("vc-audio.enabled", false) && !apiKey().isBlank();
    }

    /** 映像のクライアント側表示を音声に合わせて遅らせる量(ms)。 */
    int videoDelayMs() {
        return Math.max(0, Math.min(3000, plugin.getConfig().getInt("vc-audio.video-delay-ms", 400)));
    }

    /** スクリーン名は英数字・_・- のみなのでそのまま音源 ID にできる。 */
    static String audioId(String screenName) {
        return "videomap-" + screenName;
    }

    /** ffmpeg の追加出力: 音声だけをモノラル 48kHz の s16le で msal-node へ HTTP チャンク POST する。 */
    List<String> outputArgs(String screenName) {
        return List.of(
                "-vn", "-ac", "1", "-ar", "48000", "-f", "s16le",
                "-headers", "X-MSAL-Key: " + apiKey() + "\r\n",
                "-method", "POST", "-chunked_post", "1",
                baseUrl() + "/api/vc/plugin/audio/" + audioId(screenName) + "/push");
    }

    /** 音源を作る（置き換え可）。push の前に完了している必要がある。 */
    CompletableFuture<Boolean> start(String screenName, String world, double x, double y, double z) {
        double range = plugin.getConfig().getDouble("vc-audio.range", 32);
        double volume = plugin.getConfig().getDouble("vc-audio.volume", 1.0);
        String body = "{\"id\":" + json(audioId(screenName))
                + ",\"world\":" + json(world)
                + ",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"range\":" + range + ",\"volume\":" + volume + "}";
        return post("/api/vc/plugin/audio/start", body);
    }

    /** 音源を撤去する（失敗しても msal 側が一定時間で自動撤去する）。 */
    void stop(String screenName) {
        if (!enabled()) return;
        post("/api/vc/plugin/audio/stop", "{\"id\":" + json(audioId(screenName)) + "}");
    }

    private CompletableFuture<Boolean> post(String path, String json) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("X-MSAL-Key", apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .handle((response, error) -> {
                    if (error != null) {
                        plugin.getLogger().warning("[vc-audio] " + path + " に接続できません: " + error.getMessage());
                        return false;
                    }
                    if (response.statusCode() / 100 != 2) {
                        plugin.getLogger().warning("[vc-audio] " + path + " が HTTP " + response.statusCode() + " を返しました: " + response.body());
                        return false;
                    }
                    return true;
                });
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }
}
