package com.clustersprj.videomap;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** スクリーンの最新フレームから、自分の担当タイルを描くレンダラー。 */
final class TileRenderer extends MapRenderer {

    private final Screen screen;
    private final int index;
    private final Map<UUID, Integer> lastVersion = new ConcurrentHashMap<>();

    TileRenderer(Screen screen, int index) {
        super(true); // プレイヤーごとのキャンバス
        this.screen = screen;
        this.index = index;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
        byte[][] frame = screen.frame;
        if (frame == null) return;
        int version = screen.version;
        Integer last = lastVersion.get(player.getUniqueId());
        if (last != null && last == version) return;

        byte[] tile = frame[index];
        for (int y = 0; y < 128; y++) {
            int row = y << 7;
            for (int x = 0; x < 128; x++) {
                canvas.setPixel(x, y, tile[row + x]);
            }
        }
        lastVersion.put(player.getUniqueId(), version);
    }
}
