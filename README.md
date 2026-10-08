# VideoMap

マイクラのアイテムフレーム+マップで、動画やライブ配信を映す Paper プラグイン。
Velocity 配下なら、映したいバックエンド(Paper 26.2)側に入れてね。

## 必要なもの
- Paper 26.2 / Java 25
- サーバーマシンに `ffmpeg`(PATHに通すか config.yml で指定)

## ビルド
```
gradle build
```
`build/libs/VideoMap-1.0.0.jar` を `plugins/` に入れる。

## 使い方
1. 壁に向かって立ち、見ているブロックが**左下の角**になるようにスクリーンを作成
   `/videomap create hall 4 2`  (マップ 横4 x 縦2 枚、手前に空きが必要)
2. 動画: `plugins/VideoMap/videos/` に mp4 などを置いて
   `/videomap play hall movie.mp4`  (http(s) の URL も可)
3. ライブ:
   - キー指定 `/videomap live hall mykey` → サーバーが RTMP 待ち受け
     OBS: サーバー `rtmp://<サーバーIP>:1935/live` / ストリームキー `mykey`
   - URL指定 `/videomap live hall rtmp://example/live/stream` (rtmp/rtsp/srt/http)
4. `/videomap stop hall` / `/videomap remove hall` / `/videomap list`

権限は `videomap.admin`(デフォルト OP)。

## 補足
- 音声は出ない(マップは映像のみ)。音は別途、リソースパックや外部配信で。
- 帯域節約のため既定は 10fps。マップ枚数 x fps がそのまま通信量になるので、大きい画面ほど fps を下げるのがおすすめ。
- 色はマップのパレット(約240色)に量子化されるので、実写はやや油絵っぽくなる。
- OBS 配信はルーター二重構成の都合上、LAN内から繋ぐのが簡単。
