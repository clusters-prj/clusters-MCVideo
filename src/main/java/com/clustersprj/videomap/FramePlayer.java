package com.clustersprj.videomap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** ffmpeg を起動して rawvideo(rgb24) を読み取り、スクリーンへ流し込むスレッド。 */
final class FramePlayer implements Runnable {

    private final VideoMapPlugin plugin;
    private final Screen screen;
    private volatile List<String> command;
    /** 音声出力を付けた command が音声なしの入力で失敗したときに使う、映像のみの command。無ければ null。 */
    private final List<String> fallback;
    private final boolean reconnect;
    /** 音声(VC)に合わせて映像の表示を遅らせる量(ms)。0 なら即時。 */
    private final ScheduledExecutorService delayer;
    private final int videoDelayMs;

    private volatile boolean stopped;
    private volatile boolean noAudioStream;
    private volatile Process process;
    private Thread thread;

    FramePlayer(VideoMapPlugin plugin, Screen screen, List<String> command, boolean reconnect) {
        this(plugin, screen, command, null, reconnect, 0);
    }

    FramePlayer(VideoMapPlugin plugin, Screen screen, List<String> command, List<String> fallback,
                boolean reconnect, int videoDelayMs) {
        this.plugin = plugin;
        this.screen = screen;
        this.command = command;
        this.fallback = fallback;
        this.reconnect = reconnect;
        this.videoDelayMs = videoDelayMs;
        this.delayer = videoDelayMs > 0
                ? Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "VideoMap-delay-" + screen.name);
                    t.setDaemon(true);
                    return t;
                })
                : null;
    }

    void start() {
        thread = new Thread(this, "VideoMap-" + screen.name);
        thread.setDaemon(true);
        thread.start();
    }

    void shutdown() {
        stopped = true;
        Process p = process;
        if (p != null) p.destroy();
        if (delayer != null) delayer.shutdownNow();
        if (thread != null) thread.interrupt();
    }

    @Override
    public void run() {
        int width = screen.w * 128;
        int height = screen.h * 128;
        byte[] raw = new byte[width * height * 3];
        try {
            while (!stopped) {
                try {
                    if (runOnce(raw, width, height)) continue; // 映像のみで即やり直し
                } catch (IOException e) {
                    if (!stopped) {
                        plugin.getLogger().log(Level.WARNING, "[" + screen.name + "] ffmpeg error: " + e.getMessage());
                    }
                }
                if (stopped || !reconnect) break;
                Thread.sleep(2000);
            }
        } catch (InterruptedException ignored) {
            // shutdown
        } finally {
            Process p = process;
            if (p != null) p.destroy();
            if (delayer != null) delayer.shutdownNow();
            if (screen.playerFinished(this)) plugin.onPlayerFinished(screen);
        }
    }

    /** @return 音声なしの入力だったため映像のみの command に切り替えた(=すぐやり直すべき)なら true */
    private boolean runOnce(byte[] raw, int width, int height) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = pb.start();
        process = p;

        Thread err = new Thread(() -> drainStderr(p), "VideoMap-ffmpeg-log-" + screen.name);
        err.setDaemon(true);
        err.start();

        boolean gotFrame = false;
        try (InputStream in = p.getInputStream()) {
            while (!stopped && readFully(in, raw)) {
                gotFrame = true;
                convertAndPublish(raw, width, height);
            }
        } finally {
            p.destroy();
        }

        // 音声ストリームの無い入力に音声出力を付けると ffmpeg は起動時に失敗する。映像のみに切り替える。
        try {
            err.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!gotFrame && !stopped && fallback != null && noAudioStream && command != fallback) {
            plugin.getLogger().info("[" + screen.name + "] 音声ストリームが無いので映像のみで再生します");
            command = fallback;
            noAudioStream = false;
            plugin.onAudioUnavailable(screen);
            return true;
        }
        return false;
    }

    private void drainStderr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("does not contain any stream")) noAudioStream = true;
                if (!stopped) plugin.getLogger().info("[ffmpeg:" + screen.name + "] " + line);
            }
        } catch (IOException ignored) {
            // プロセス終了
        }
    }

    private static boolean readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private void convertAndPublish(byte[] raw, int width, int height) {
        if (stopped) return;
        int tw = screen.w;
        byte[][] tiles = new byte[tw * screen.h][128 * 128];
        byte[] lut = ColorLut.LUT;
        for (int y = 0; y < height; y++) {
            int rowTile = (y >> 7) * tw;
            int ty = (y & 127) << 7;
            int src = y * width * 3;
            for (int x = 0; x < width; x++) {
                int r = raw[src] & 0xFF;
                int g = raw[src + 1] & 0xFF;
                int b = raw[src + 2] & 0xFF;
                src += 3;
                tiles[rowTile + (x >> 7)][ty + (x & 127)] = lut[((r >> 3) << 10) | ((g >> 3) << 5) | (b >> 3)];
            }
        }
        if (stopped) return;
        if (delayer == null) {
            screen.publish(tiles);
        } else {
            // 音声は VC 経由で少し遅れて届くので、映像も同じだけ遅らせて口の動きとのズレを抑える
            try {
                delayer.schedule(() -> {
                    if (!stopped) screen.publish(tiles);
                }, videoDelayMs, TimeUnit.MILLISECONDS);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // 停止処理中
            }
        }
    }
}
