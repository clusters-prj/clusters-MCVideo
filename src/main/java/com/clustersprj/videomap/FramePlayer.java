package com.clustersprj.videomap;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Level;

/** ffmpeg を起動して rawvideo(rgb24) を読み取り、スクリーンへ流し込むスレッド。 */
final class FramePlayer implements Runnable {

    private final VideoMapPlugin plugin;
    private final Screen screen;
    private final List<String> command;
    private final boolean reconnect;

    private volatile boolean stopped;
    private volatile Process process;
    private Thread thread;

    FramePlayer(VideoMapPlugin plugin, Screen screen, List<String> command, boolean reconnect) {
        this.plugin = plugin;
        this.screen = screen;
        this.command = command;
        this.reconnect = reconnect;
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
                    runOnce(raw, width, height);
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
            screen.playerFinished(this);
        }
    }

    private void runOnce(byte[] raw, int width, int height) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(command);
        Process p = pb.start();
        process = p;

        Thread err = new Thread(() -> drainStderr(p), "VideoMap-ffmpeg-log-" + screen.name);
        err.setDaemon(true);
        err.start();

        try (InputStream in = p.getInputStream()) {
            while (!stopped && readFully(in, raw)) {
                convertAndPublish(raw, width, height);
            }
        } finally {
            p.destroy();
        }
    }

    private void drainStderr(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
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
        if (!stopped) screen.publish(tiles);
    }
}
