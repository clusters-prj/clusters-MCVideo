package com.clustersprj.videomap;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TileUpdateQueueTest {

    private static final byte BLACK = ColorLut.BLACK;
    private static final byte WHITE = ColorLut.LUT[(31 << 10) | (31 << 5) | 31];
    private static final TileUpdateQueue.Settings S = TileUpdateQueue.Settings.of(20, 4);

    private static byte[][] frame(int tiles, byte c) {
        byte[][] f = new byte[tiles][TileUpdateQueue.TILE_PIXELS];
        for (byte[] t : f) Arrays.fill(t, c);
        return f;
    }

    /** tile の (bx,by) ブロックを color で塗る。 */
    private static void paintBlock(byte[][] f, int tile, int bx, int by, byte color) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                f[tile][(by * 16 + y) * 128 + bx * 16 + x] = color;
            }
        }
    }

    @Test
    void identicalFrameChangesNothing() {
        var q = new TileUpdateQueue(4, BLACK);
        q.offer(frame(4, BLACK), S, false);
        assertEquals(0, q.pendingTiles());
        assertEquals(0, q.release(10));
    }

    @Test
    void onlyChangedBlockIsCopied() {
        var q = new TileUpdateQueue(2, BLACK);
        var f = frame(2, BLACK);
        paintBlock(f, 1, 3, 2, WHITE);
        q.offer(f, S, false);
        assertEquals(1, q.pendingTiles());
        int v0 = q.version(0), v1 = q.version(1);
        assertEquals(1, q.release(10));
        assertEquals(v0, q.version(0));
        assertEquals(v1 + 1, q.version(1));
        assertArrayEquals(f[1], q.displayed(1));
        assertEquals(0, q.pendingTiles());
    }

    @Test
    void noiseBelowThresholdIsIgnored() {
        var q = new TileUpdateQueue(1, BLACK);
        var f = frame(1, BLACK);
        // 3 ピクセルだけ白(minPixels=4 未満)
        f[0][0] = WHITE;
        f[0][1] = WHITE;
        f[0][2] = WHITE;
        q.offer(f, S, false);
        assertEquals(0, q.pendingTiles());
        // 色差が小さい(隣のパレット色)なら全面でも無視
        byte[] pair = closePair();
        var q2 = new TileUpdateQueue(1, pair[0]);
        q2.offer(frame(1, pair[1]), S, false);
        assertEquals(0, q2.pendingTiles());
    }

    /** 異なるが閾値以下に近いパレット色の組。 */
    private static byte[] closePair() {
        for (int a = 4; a < 248; a++) {
            for (int b = 4; b < 248; b++) {
                int d = ColorLut.DIST[(a << 8) | b];
                if (a != b && d > 0 && d <= S.pixelThresholdSq()) return new byte[]{(byte) a, (byte) b};
            }
        }
        throw new AssertionError("近い色の組が無い");
    }

    @Test
    void slowDriftAccumulatesAgainstShippedFrame() {
        var q = new TileUpdateQueue(1, BLACK);
        var f = frame(1, BLACK);
        f[0][0] = WHITE;
        f[0][1] = WHITE;
        f[0][2] = WHITE;
        q.offer(f, S, false);
        assertEquals(0, q.pendingTiles());
        f[0][3] = WHITE; // 4個目で閾値到達(前回確定した絵との比較なので積もる)
        q.offer(f, S, false);
        assertEquals(1, q.pendingTiles());
    }

    @Test
    void budgetCapsPerTickAndOverflowCarriesOver() {
        int n = 10;
        var q = new TileUpdateQueue(n, BLACK);
        q.offer(frame(n, WHITE), S, false);
        assertEquals(n, q.pendingTiles());
        assertEquals(3, q.release(3));
        assertEquals(7, q.pendingTiles());
        assertEquals(3, q.release(3));
        assertEquals(3, q.release(3));
        assertEquals(1, q.release(3));
        assertEquals(0, q.release(3));
        for (int t = 0; t < n; t++) assertArrayEquals(frame(1, WHITE)[0], q.displayed(t));
    }

    @Test
    void zeroBudgetMeansUnlimited() {
        var q = new TileUpdateQueue(6, BLACK);
        q.offer(frame(6, WHITE), S, false);
        assertEquals(6, q.release(0));
    }

    @Test
    void biggerChangeWinsPriority() {
        var q = new TileUpdateQueue(3, BLACK);
        var f = frame(3, BLACK);
        paintBlock(f, 0, 0, 0, WHITE);                      // 1 ブロック
        for (int b = 0; b < 8; b++) paintBlock(f, 1, b, 0, WHITE); // 8 ブロック
        paintBlock(f, 2, 0, 0, WHITE);
        paintBlock(f, 2, 1, 0, WHITE);                      // 2 ブロック
        q.offer(f, S, false);
        int v0 = q.version(0), v1 = q.version(1), v2 = q.version(2);
        assertEquals(1, q.release(1));
        assertEquals(v1 + 1, q.version(1));
        assertEquals(v0, q.version(0));
        assertEquals(v2, q.version(2));
        assertEquals(1, q.release(1));
        assertEquals(v2 + 1, q.version(2));
        assertEquals(1, q.release(1));
        assertEquals(v0 + 1, q.version(0));
    }

    @Test
    void waitingTileEventuallyBeatsBusyTile() {
        var q = new TileUpdateQueue(2, BLACK);
        var f = frame(2, BLACK);
        paintBlock(f, 0, 0, 0, WHITE); // 小さな変化(256px)
        q.offer(f, S, false);
        int v0 = q.version(0);
        boolean released = false;
        for (int tick = 0; tick < 60 && !released; tick++) {
            // タイル1は毎tick大きく変化し続ける
            var g = frame(2, BLACK);
            paintBlock(g, 0, 0, 0, WHITE);
            byte c = (tick % 2 == 0) ? WHITE : BLACK;
            for (int b = 0; b < 8; b++) for (int r = 0; r < 4; r++) paintBlock(g, 1, b, r, c);
            q.offer(g, S, false);
            q.release(1);
            released = q.version(0) != v0;
        }
        assertTrue(released, "待たされたタイルが飢餓状態のまま");
    }

    @Test
    void laterFrameOverwritesPendingBlock() {
        var q = new TileUpdateQueue(1, BLACK);
        var f = frame(1, BLACK);
        paintBlock(f, 0, 0, 0, WHITE);
        q.offer(f, S, false);
        var g = frame(1, BLACK);
        byte other = ColorLut.LUT[(31 << 10)]; // 赤系
        paintBlock(g, 0, 0, 0, other);
        q.offer(g, S, false);
        q.release(1);
        assertArrayEquals(g[0], q.displayed(0));
    }

    @Test
    void fillForcesEvenSmallDifferences() {
        var q = new TileUpdateQueue(1, closePair()[0]);
        byte near = closePair()[1];
        var f = frame(1, near);
        q.offer(f, TileUpdateQueue.Settings.of(0, 1), true);
        q.release(0);
        assertArrayEquals(f[0], q.displayed(0));
        q.offerFill(BLACK);
        q.release(0);
        assertArrayEquals(frame(1, BLACK)[0], q.displayed(0));
    }
}
