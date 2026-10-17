package com.clustersprj.videomap;

import java.util.Arrays;

/**
 * スクリーンの「次に見せる絵」と「いま見せている絵」の差分管理(Bukkit に依存しない)。
 *
 * <pre>
 *  FramePlayer(別スレッド) --offer()--> shipped(確定した最新絵) + pending(未反映ブロック)
 *  メインスレッド(毎tick)  --release(budget)--> displayed(マップに描く絵)
 * </pre>
 *
 * <ul>
 *   <li>タイル(マップ1枚)を 16x16 のブロック 8x8=64個に分け、前回確定した絵(shipped)と比べる。
 *       色の違うピクセル(パレット距離が閾値超え)が minPixels 個以上あるブロックだけ「変化」とみなす。
 *       小さなノイズは shipped が変わらないので無視され、ゆっくりした変化は積もって閾値を超えたら反映される。</li>
 *   <li>release は 1tick に反映するタイル数を budget 枚までに制限する。変化の大きいタイルを優先し、
 *       待たされたタイルは優先度が上がる(飢餓防止)。溢れた分は次のtick以降へ回る。</li>
 * </ul>
 */
final class TileUpdateQueue {

    static final int TILE_SIZE = 128;
    static final int TILE_PIXELS = TILE_SIZE * TILE_SIZE;
    static final int BLOCK = 16;
    static final int BLOCKS_PER_ROW = TILE_SIZE / BLOCK;
    /** 待った1tickあたりに足す優先度(ピクセル数換算)。 */
    static final int AGE_WEIGHT = 256;
    private static final int SCORE_CAP = 1 << 24;

    /** 差分検出の設定。pixelThreshold は RGB 0..255 換算の色差。 */
    record Settings(int pixelThresholdSq, int minPixels) {
        static Settings of(double pixelThreshold, int minPixels) {
            double t = Math.max(0, pixelThreshold);
            return new Settings((int) Math.min(Integer.MAX_VALUE, 9.0 * t * t), Math.max(1, minPixels));
        }
    }

    private final int tiles;
    private final byte[][] shipped;
    private final byte[][] displayed;
    private final long[] pendingMask;
    private final int[] pendingScore;
    private final int[] waited;
    private final int[] versions;

    private final Object offerLock = new Object();
    private final Object lock = new Object();
    private final long[] newMask;
    private final int[] newScore;

    TileUpdateQueue(int tiles, byte fill) {
        this.tiles = tiles;
        shipped = new byte[tiles][TILE_PIXELS];
        displayed = new byte[tiles][TILE_PIXELS];
        for (int i = 0; i < tiles; i++) {
            Arrays.fill(shipped[i], fill);
            Arrays.fill(displayed[i], fill);
        }
        pendingMask = new long[tiles];
        pendingScore = new int[tiles];
        waited = new int[tiles];
        versions = new int[tiles];
        Arrays.fill(versions, 1);
        newMask = new long[tiles];
        newScore = new int[tiles];
    }

    int tiles() {
        return tiles;
    }

    /** マップに描くべき絵。メインスレッドからだけ読む。 */
    byte[] displayed(int tile) {
        return displayed[tile];
    }

    /** displayed が変わるたびに増える。メインスレッドからだけ読む。 */
    int version(int tile) {
        return versions[tile];
    }

    /** 反映待ちのタイル数。 */
    int pendingTiles() {
        synchronized (lock) {
            int n = 0;
            for (long m : pendingMask) if (m != 0) n++;
            return n;
        }
    }

    /**
     * 新しいフレームを渡す。frame[tile] は 128x128 のマップ色。変化のあるブロックだけ pending になる。
     * force なら 1ピクセルでも違えば変化とみなす(画面クリア用)。frame は呼び出し後に再利用してよい。
     */
    void offer(byte[][] frame, Settings settings, boolean force) {
        synchronized (offerLock) {
            int thSq = force ? 0 : settings.pixelThresholdSq();
            int min = force ? 1 : settings.minPixels();
            int[] dist = ColorLut.DIST;
            for (int t = 0; t < tiles; t++) {
                byte[] a = frame[t];
                byte[] b = shipped[t];
                long mask = 0;
                int score = 0;
                for (int by = 0; by < BLOCKS_PER_ROW; by++) {
                    for (int bx = 0; bx < BLOCKS_PER_ROW; bx++) {
                        int base = (by * BLOCK) * TILE_SIZE + bx * BLOCK;
                        int count = 0;
                        for (int y = 0; y < BLOCK; y++) {
                            int off = base + y * TILE_SIZE;
                            for (int x = 0; x < BLOCK; x++) {
                                byte p = a[off + x];
                                byte q = b[off + x];
                                if (p != q && (force || dist[((p & 0xFF) << 8) | (q & 0xFF)] > thSq)) count++;
                            }
                        }
                        if (count >= min) {
                            mask |= 1L << (by * BLOCKS_PER_ROW + bx);
                            score += count;
                        }
                    }
                }
                newMask[t] = mask;
                newScore[t] = score;
            }
            synchronized (lock) {
                for (int t = 0; t < tiles; t++) {
                    long mask = newMask[t];
                    if (mask == 0) continue;
                    copyBlocks(frame[t], shipped[t], mask);
                    pendingMask[t] |= mask;
                    pendingScore[t] = Math.min(SCORE_CAP, pendingScore[t] + newScore[t]);
                }
            }
        }
    }

    /** 画面全体を単色にする(次の release で順次反映される)。 */
    void offerFill(byte color) {
        byte[][] f = new byte[tiles][TILE_PIXELS];
        for (byte[] t : f) Arrays.fill(t, color);
        offer(f, Settings.of(0, 1), true);
    }

    /**
     * pending のうち優先度の高いタイルから最大 budget 枚を displayed へ反映する。budget<=0 なら全部。
     * メインスレッドから毎tick呼ぶ。
     *
     * @return 反映したタイル数
     */
    int release(int budget) {
        synchronized (lock) {
            int n = 0;
            for (int t = 0; t < tiles; t++) if (pendingMask[t] != 0) n++;
            if (n == 0) return 0;

            long[] keys = new long[n];
            int k = 0;
            for (int t = 0; t < tiles; t++) {
                if (pendingMask[t] == 0) continue;
                long priority = (long) pendingScore[t] + (long) waited[t] * AGE_WEIGHT;
                keys[k++] = (priority << 32) | t;
            }
            int take = (budget <= 0 || budget >= n) ? n : budget;
            if (take < n) Arrays.sort(keys); // 昇順。末尾 take 個が優先度上位
            for (int i = 0; i < n; i++) {
                int t = (int) (keys[i] & 0xFFFFFFFFL);
                if (i >= n - take) {
                    copyBlocks(shipped[t], displayed[t], pendingMask[t]);
                    pendingMask[t] = 0;
                    pendingScore[t] = 0;
                    waited[t] = 0;
                    versions[t]++;
                } else {
                    waited[t]++;
                }
            }
            return take;
        }
    }

    private static void copyBlocks(byte[] from, byte[] to, long mask) {
        if (mask == -1L) {
            System.arraycopy(from, 0, to, 0, TILE_PIXELS);
            return;
        }
        while (mask != 0) {
            int bit = Long.numberOfTrailingZeros(mask);
            mask &= mask - 1;
            int base = (bit / BLOCKS_PER_ROW) * BLOCK * TILE_SIZE + (bit % BLOCKS_PER_ROW) * BLOCK;
            for (int y = 0; y < BLOCK; y++) {
                System.arraycopy(from, base + y * TILE_SIZE, to, base + y * TILE_SIZE, BLOCK);
            }
        }
    }
}
