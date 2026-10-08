package com.clustersprj.videomap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 1つの映像スクリーン(マップ w x h 枚)。 */
final class Screen {

    final String name;
    final String worldName;
    final int w;
    final int h;
    final List<Integer> mapIds = new ArrayList<>();
    final List<UUID> frameIds = new ArrayList<>();

    /** 画面の中心座標(音の発生位置)。作成時に保存する。古い screens.yml には無い。 */
    volatile double cx, cy, cz;
    volatile boolean centerKnown;

    /** 再生要求の世代。非同期の音源作成中に stop / 再 play されたら古い要求を捨てるために使う。 */
    final AtomicInteger seq = new AtomicInteger();

    /** 最新フレーム。tiles[row * w + col] が 128x128 のマップカラー配列。 */
    volatile byte[][] frame;
    volatile int version;

    private FramePlayer player;

    Screen(String name, String worldName, int w, int h) {
        this.name = name;
        this.worldName = worldName;
        this.w = w;
        this.h = h;
    }

    void publish(byte[][] tiles) {
        this.frame = tiles;
        this.version++;
    }

    void fillBlack() {
        byte[][] tiles = new byte[w * h][128 * 128];
        for (byte[] t : tiles) Arrays.fill(t, ColorLut.BLACK);
        publish(tiles);
    }

    synchronized void startPlayer(FramePlayer p) {
        stopPlayerQuietly();
        player = p;
        p.start();
    }

    /** 再生を止めて画面を黒にする。 */
    synchronized void stop() {
        seq.incrementAndGet();
        stopPlayerQuietly();
        fillBlack();
    }

    private void stopPlayerQuietly() {
        if (player != null) {
            player.shutdown();
            player = null;
        }
    }

    /** 再生スレッドが終了したときに呼ばれる。現在のプレイヤーだったら true（置き換えで止められた古い方は false）。 */
    synchronized boolean playerFinished(FramePlayer p) {
        if (player == p) {
            player = null;
            fillBlack();
            return true;
        }
        return false;
    }

    synchronized boolean isPlaying() {
        return player != null;
    }
}
