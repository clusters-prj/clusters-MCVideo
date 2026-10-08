package com.clustersprj.videomap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

public final class VideoMapPlugin extends JavaPlugin implements TabExecutor {

    private static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Set<String> URL_SCHEMES = Set.of("http", "https", "rtmp", "rtmps", "rtsp", "srt");
    private static final List<String> SUBCOMMANDS = List.of("create", "remove", "play", "live", "stop", "list", "reload");

    private final Map<String, Screen> screens = new TreeMap<>();
    private VcAudio vcAudio;
    private File screensFile;
    private File videosDir;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        screensFile = new File(getDataFolder(), "screens.yml");
        videosDir = new File(getDataFolder(), "videos");
        if (!videosDir.exists() && !videosDir.mkdirs()) {
            getLogger().warning("videos フォルダを作成できませんでした");
        }
        vcAudio = new VcAudio(this);
        loadScreens();

        var cmd = getCommand("videomap");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getLogger().info("VideoMap 有効化: スクリーン " + screens.size() + " 件");
    }

    @Override
    public void onDisable() {
        for (Screen s : screens.values()) {
            stopScreen(s);
        }
    }

    // ------------------------------------------------------------ 音声(VC連携)

    /** 再生を止め、VC 側の音源も撤去する。 */
    private void stopScreen(Screen screen) {
        screen.stop();
        vcAudio.stop(screen.name);
    }

    /** 再生スレッドが自然終了したとき(FramePlayer から)。VC の音源を撤去する。 */
    void onPlayerFinished(Screen screen) {
        vcAudio.stop(screen.name);
    }

    /** 入力に音声が無く映像のみに切り替えたとき(FramePlayer から)。VC の音源を撤去する。 */
    void onAudioUnavailable(Screen screen) {
        vcAudio.stop(screen.name);
    }

    /** 音が出る位置 = スクリーンの中心。保存が無い古いスクリーンは、ロード済みの額縁から求める。 */
    private double[] screenCenter(Screen screen) {
        if (screen.centerKnown) return new double[]{screen.cx, screen.cy, screen.cz};
        double sx = 0, sy = 0, sz = 0;
        int n = 0;
        for (UUID id : screen.frameIds) {
            Entity e = Bukkit.getEntity(id);
            if (e == null) continue;
            sx += e.getLocation().getX();
            sy += e.getLocation().getY();
            sz += e.getLocation().getZ();
            n++;
        }
        if (n == 0) return null;
        return new double[]{sx / n, sy / n, sz / n};
    }

    // ------------------------------------------------------------ 永続化

    private void loadScreens() {
        screens.clear();
        if (!screensFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(screensFile);
        for (String name : yml.getKeys(false)) {
            ConfigurationSection sec = yml.getConfigurationSection(name);
            if (sec == null) continue;
            Screen screen = new Screen(name, sec.getString("world", ""), sec.getInt("w"), sec.getInt("h"));
            screen.mapIds.addAll(sec.getIntegerList("maps"));
            List<Double> center = sec.getDoubleList("center");
            if (center.size() == 3) {
                screen.cx = center.get(0);
                screen.cy = center.get(1);
                screen.cz = center.get(2);
                screen.centerKnown = true;
            }
            for (String id : sec.getStringList("frames")) {
                try {
                    screen.frameIds.add(UUID.fromString(id));
                } catch (IllegalArgumentException ignored) {
                    // 壊れたIDは無視
                }
            }
            if (screen.mapIds.size() != screen.w * screen.h) {
                getLogger().warning("スクリーン " + name + " のマップ数が不正です。スキップします");
                continue;
            }
            screen.fillBlack();
            attachRenderers(screen);
            screens.put(name, screen);
        }
    }

    private void saveScreens() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Screen s : screens.values()) {
            yml.set(s.name + ".world", s.worldName);
            yml.set(s.name + ".w", s.w);
            yml.set(s.name + ".h", s.h);
            yml.set(s.name + ".maps", s.mapIds);
            if (s.centerKnown) yml.set(s.name + ".center", List.of(s.cx, s.cy, s.cz));
            List<String> frames = new ArrayList<>();
            for (UUID id : s.frameIds) frames.add(id.toString());
            yml.set(s.name + ".frames", frames);
        }
        try {
            yml.save(screensFile);
        } catch (IOException e) {
            getLogger().severe("screens.yml の保存に失敗: " + e.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void attachRenderers(Screen screen) {
        for (int i = 0; i < screen.mapIds.size(); i++) {
            MapView view = Bukkit.getMap(screen.mapIds.get(i));
            if (view == null) {
                getLogger().warning("マップ #" + screen.mapIds.get(i) + " が見つかりません (" + screen.name + ")");
                continue;
            }
            setupView(view, new TileRenderer(screen, i));
        }
    }

    private static void setupView(MapView view, TileRenderer renderer) {
        new ArrayList<>(view.getRenderers()).forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(renderer);
    }

    // ------------------------------------------------------------ コマンド

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (args.length == 0) {
            usage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> cmdCreate(sender, args);
            case "remove" -> cmdRemove(sender, args);
            case "play" -> cmdPlay(sender, args, false);
            case "live" -> cmdPlay(sender, args, true);
            case "stop" -> cmdStop(sender, args);
            case "list" -> cmdList(sender);
            case "reload" -> cmdReload(sender);
            default -> usage(sender);
        }
        return true;
    }

    private void cmdReload(CommandSender sender) {
        reloadConfig();
        msg(sender, NamedTextColor.GREEN, "config.yml を再読み込みしたよ。再生中のスクリーンは、次に play / live したときから新しい設定になるよ");
    }

    private void usage(CommandSender s) {
        msg(s, NamedTextColor.YELLOW, "/videomap create <名前> <横> <縦>  ... 見ている壁面にスクリーンを作成");
        msg(s, NamedTextColor.YELLOW, "/videomap play <名前> <ファイル名|URL> ... 動画を再生");
        msg(s, NamedTextColor.YELLOW, "/videomap live <名前> <キー|URL> ... ライブ配信を映す(キーならRTMP待ち受け)");
        msg(s, NamedTextColor.YELLOW, "/videomap stop <名前> / remove <名前> / list / reload");
    }

    private void cmdCreate(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            msg(sender, NamedTextColor.RED, "このコマンドはゲーム内から実行してね");
            return;
        }
        if (args.length < 4) {
            msg(sender, NamedTextColor.RED, "使い方: /videomap create <名前> <横> <縦>");
            return;
        }
        String name = args[1];
        if (!NAME_PATTERN.matcher(name).matches()) {
            msg(sender, NamedTextColor.RED, "名前は英数字・_・- の32文字までだよ");
            return;
        }
        if (screens.containsKey(name)) {
            msg(sender, NamedTextColor.RED, "その名前のスクリーンはもうあるよ");
            return;
        }
        int w, h;
        try {
            w = Integer.parseInt(args[2]);
            h = Integer.parseInt(args[3]);
        } catch (NumberFormatException e) {
            msg(sender, NamedTextColor.RED, "横と縦は数字で指定してね");
            return;
        }
        int maxW = getConfig().getInt("max-width", 8);
        int maxH = getConfig().getInt("max-height", 6);
        if (w < 1 || h < 1 || w > maxW || h > maxH) {
            msg(sender, NamedTextColor.RED, "サイズは 1x1 〜 " + maxW + "x" + maxH + " の範囲で指定してね");
            return;
        }

        RayTraceResult hit = player.rayTraceBlocks(6);
        if (hit == null || hit.getHitBlock() == null || hit.getHitBlockFace() == null) {
            msg(sender, NamedTextColor.RED, "壁のブロックを見ながら実行してね(6ブロック以内)");
            return;
        }
        BlockFace face = hit.getHitBlockFace();
        int rx, rz;
        switch (face) {
            case NORTH -> { rx = -1; rz = 0; }
            case SOUTH -> { rx = 1; rz = 0; }
            case EAST -> { rx = 0; rz = -1; }
            case WEST -> { rx = 0; rz = 1; }
            default -> {
                msg(sender, NamedTextColor.RED, "壁の側面を見てね(床や天井には作れないよ)");
                return;
            }
        }

        // 見ている壁のブロックの手前が左下の角
        Block base = hit.getHitBlock().getRelative(face);
        World world = base.getWorld();
        Block[][] spots = new Block[h][w]; // [row(上=0)][col]
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                Block b = world.getBlockAt(
                        base.getX() + rx * col,
                        base.getY() + (h - 1 - row),
                        base.getZ() + rz * col);
                Block backing = b.getRelative(face.getOppositeFace());
                if (!b.getType().isAir() || !backing.getType().isSolid()) {
                    msg(sender, NamedTextColor.RED, "壁が足りないか、手前に障害物があるよ (" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")");
                    return;
                }
                spots[row][col] = b;
            }
        }

        Screen screen = new Screen(name, world.getName(), w, h);
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int index = row * w + col;
                @SuppressWarnings("deprecation")
                MapView view = Bukkit.createMap(world);
                setupView(view, new TileRenderer(screen, index));
                screen.mapIds.add(view.getId());

                ItemStack item = new ItemStack(Material.FILLED_MAP);
                MapMeta meta = (MapMeta) item.getItemMeta();
                meta.setMapView(view);
                item.setItemMeta(meta);

                ItemFrame frame = world.spawn(spots[row][col].getLocation(), ItemFrame.class, f -> {
                    f.setFacingDirection(face, true);
                    f.setItem(item, false);
                    f.setVisible(false);
                    f.setFixed(true);
                    f.setInvulnerable(true);
                    f.setSilent(true);
                });
                screen.frameIds.add(frame.getUniqueId());
            }
        }
        // 音の発生位置(画面中心)。ブロックの中心を平均する
        double sx = 0, sy = 0, sz = 0;
        for (Block[] row : spots) {
            for (Block b : row) {
                sx += b.getX() + 0.5;
                sy += b.getY() + 0.5;
                sz += b.getZ() + 0.5;
            }
        }
        screen.cx = sx / (w * h);
        screen.cy = sy / (w * h);
        screen.cz = sz / (w * h);
        screen.centerKnown = true;

        screen.fillBlack();
        screens.put(name, screen);
        saveScreens();
        msg(sender, NamedTextColor.GREEN, "スクリーン「" + name + "」(" + w + "x" + h + ") を作成したよ。/videomap play か live で映してみてね");
    }

    private void cmdRemove(CommandSender sender, String[] args) {
        Screen screen = requireScreen(sender, args);
        if (screen == null) return;
        stopScreen(screen);
        for (UUID id : screen.frameIds) {
            Entity e = Bukkit.getEntity(id);
            if (e != null) e.remove();
        }
        screens.remove(screen.name);
        saveScreens();
        msg(sender, NamedTextColor.GREEN, "スクリーン「" + screen.name + "」を削除したよ(ロード外のチャンクにあるフレームは残る場合があるよ)");
    }

    private void cmdStop(CommandSender sender, String[] args) {
        Screen screen = requireScreen(sender, args);
        if (screen == null) return;
        stopScreen(screen);
        msg(sender, NamedTextColor.GREEN, "「" + screen.name + "」を停止したよ");
    }

    private void cmdList(CommandSender sender) {
        if (screens.isEmpty()) {
            msg(sender, NamedTextColor.GRAY, "スクリーンはまだないよ");
            return;
        }
        for (Screen s : screens.values()) {
            msg(sender, NamedTextColor.AQUA, s.name + " (" + s.w + "x" + s.h + ", " + s.worldName + ") "
                    + (s.isPlaying() ? "▶ 再生中" : "■ 停止中"));
        }
    }

    private void cmdPlay(CommandSender sender, String[] args, boolean live) {
        if (args.length < 3) {
            msg(sender, NamedTextColor.RED, live
                    ? "使い方: /videomap live <名前> <キー|URL>"
                    : "使い方: /videomap play <名前> <ファイル名|URL>");
            return;
        }
        Screen screen = requireScreen(sender, args);
        if (screen == null) return;

        List<String> cmd;
        try {
            cmd = buildCommand(screen, args[2], live);
        } catch (IllegalArgumentException e) {
            msg(sender, NamedTextColor.RED, e.getMessage());
            return;
        }
        boolean reconnect = live && getConfig().getBoolean("live.reconnect", true);
        startPlayback(sender, screen, cmd, reconnect);

        if (live && !args[2].contains("://")) {
            int port = getConfig().getInt("live.listen-port", 1935);
            msg(sender, NamedTextColor.GREEN, "待ち受け開始! OBSの配信先は rtmp://<サーバーIP>:" + port + "/live  ストリームキー: " + args[2]);
        } else {
            msg(sender, NamedTextColor.GREEN, "「" + screen.name + "」で再生を開始したよ");
        }
    }

    /**
     * 再生を開始する。vc-audio が有効なら、先に VC 側の音源を作ってから(push が 404 にならないよう)
     * 音声出力付きの ffmpeg を起動する。音源を作れなければ映像のみで再生する。
     */
    private void startPlayback(CommandSender sender, Screen screen, List<String> videoOnly, boolean reconnect) {
        double[] center = vcAudio.enabled() ? screenCenter(screen) : null;
        if (center == null) {
            if (vcAudio.enabled()) {
                msg(sender, NamedTextColor.YELLOW, "スクリーンの位置が分からないので音声なしで再生するよ(作り直すと音が出るよ)");
            }
            screen.seq.incrementAndGet();
            screen.startPlayer(new FramePlayer(this, screen, videoOnly, reconnect));
            return;
        }

        int seq = screen.seq.incrementAndGet();
        List<String> withAudio = new ArrayList<>(videoOnly);
        withAudio.addAll(vcAudio.outputArgs(screen.name));
        int delay = vcAudio.videoDelayMs();

        vcAudio.start(screen.name, screen.worldName, center[0], center[1], center[2]).thenAccept(ok ->
                Bukkit.getScheduler().runTask(this, () -> {
                    if (screen.seq.get() != seq) {
                        // 音源作成中に stop / 再 play された
                        if (ok) vcAudio.stop(screen.name);
                        return;
                    }
                    if (ok) {
                        screen.startPlayer(new FramePlayer(this, screen, withAudio, videoOnly, reconnect, delay));
                    } else {
                        msg(sender, NamedTextColor.YELLOW, "VC に音声を流せなかったので映像のみで再生するよ(コンソールを確認してね)");
                        screen.startPlayer(new FramePlayer(this, screen, videoOnly, reconnect));
                    }
                }));
    }

    private Screen requireScreen(CommandSender sender, String[] args) {
        if (args.length < 2) {
            msg(sender, NamedTextColor.RED, "スクリーン名を指定してね");
            return null;
        }
        Screen screen = screens.get(args[1]);
        if (screen == null) {
            msg(sender, NamedTextColor.RED, "スクリーン「" + args[1] + "」は見つからないよ");
        }
        return screen;
    }

    // ------------------------------------------------------------ ffmpegコマンド組み立て

    private List<String> buildCommand(Screen screen, String input, boolean live) {
        int width = screen.w * 128;
        int height = screen.h * 128;
        int fps = Math.max(1, Math.min(20, getConfig().getInt("fps", 10)));
        boolean isUrl = input.contains("://");

        List<String> cmd = new ArrayList<>();
        cmd.add(getConfig().getString("ffmpeg-path", "ffmpeg"));
        cmd.addAll(List.of("-hide_banner", "-loglevel", "warning"));

        String source;
        if (isUrl) {
            String scheme = input.substring(0, input.indexOf("://")).toLowerCase(Locale.ROOT);
            if (!URL_SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("使えるURLは http / https / rtmp / rtmps / rtsp / srt だけだよ");
            }
            cmd.addAll(List.of("-protocol_whitelist", "http,https,tcp,tls,crypto,rtmp,rtsp,rtp,udp,srt"));
            source = input;
            if (live) {
                cmd.addAll(List.of("-fflags", "nobuffer", "-flags", "low_delay"));
            } else {
                if (getConfig().getBoolean("loop", false)) cmd.addAll(List.of("-stream_loop", "-1"));
                cmd.add("-re");
            }
        } else if (live) {
            // キー指定 -> ffmpeg が RTMP サーバーとして待ち受ける
            if (!NAME_PATTERN.matcher(input).matches()) {
                throw new IllegalArgumentException("ストリームキーは英数字・_・- の32文字までだよ");
            }
            int port = getConfig().getInt("live.listen-port", 1935);
            cmd.addAll(List.of("-protocol_whitelist", "tcp,rtmp", "-fflags", "nobuffer", "-flags", "low_delay", "-listen", "1"));
            source = "rtmp://0.0.0.0:" + port + "/live/" + input;
        } else {
            File file;
            try {
                File dir = videosDir.getCanonicalFile();
                file = new File(dir, input).getCanonicalFile();
                if (!file.toPath().startsWith(dir.toPath())) {
                    throw new IllegalArgumentException("videos フォルダの中のファイルだけ指定できるよ");
                }
            } catch (IOException e) {
                throw new IllegalArgumentException("ファイルパスを解決できなかったよ");
            }
            if (!file.isFile()) {
                throw new IllegalArgumentException("plugins/VideoMap/videos/ に「" + input + "」が見つからないよ");
            }
            cmd.addAll(List.of("-protocol_whitelist", "file"));
            if (getConfig().getBoolean("loop", false)) cmd.addAll(List.of("-stream_loop", "-1"));
            cmd.add("-re");
            source = file.getAbsolutePath();
        }

        cmd.add("-i");
        cmd.add(source);
        cmd.add("-an");

        String scale;
        if ("stretch".equalsIgnoreCase(getConfig().getString("aspect", "fit"))) {
            scale = "scale=" + width + ":" + height + ":flags=fast_bilinear";
        } else {
            scale = "scale=" + width + ":" + height + ":force_original_aspect_ratio=decrease:flags=fast_bilinear"
                    + ",pad=" + width + ":" + height + ":(ow-iw)/2:(oh-ih)/2:black";
        }
        cmd.addAll(List.of("-vf", "fps=" + fps + "," + scale, "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"));
        return cmd;
    }

    // ------------------------------------------------------------ タブ補完

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && List.of("remove", "play", "live", "stop").contains(sub)) {
            return filter(new ArrayList<>(screens.keySet()), args[1]);
        }
        if (args.length == 3 && sub.equals("play")) {
            File[] files = videosDir.listFiles(File::isFile);
            List<String> names = new ArrayList<>();
            if (files != null) for (File f : files) names.add(f.getName());
            return filter(names, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (o.toLowerCase(Locale.ROOT).startsWith(p)) out.add(o);
        }
        return out;
    }

    private static void msg(CommandSender sender, NamedTextColor color, String text) {
        sender.sendMessage(Component.text("[VideoMap] " + text, color));
    }
}
