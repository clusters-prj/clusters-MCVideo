package com.clustersprj.videomap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

/**
 * スクリーンの「いま見せる絵」から自分の担当タイルを描くレンダラー。
 * キャンバスは全プレイヤーで共有(non-contextual)。setPixel は値が変わったピクセルだけ dirty にするので、
 * 反映されたブロック分だけがパケットになる。メインスレッドでしか呼ばれない。
 */
final class TileRenderer extends MapRenderer {

    private final Screen screen;
    private final int index;
    private int lastVersion;

    TileRenderer(Screen screen, int index) {
        super(false);
        this.screen = screen;
        this.index = index;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        TileUpdateQueue q = screen.queue;
        int version = q.version(index);
        if (version == lastVersion) return;
        byte[] tile = q.displayed(index);
        for (int y = 0; y < 128; y++) {
            int row = y << 7;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, tile[row + x]);
            }
        }
        lastVersion = version;
    }
}
