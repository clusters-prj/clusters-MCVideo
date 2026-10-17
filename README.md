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
`build/libs/videomap-<version>.jar` を `plugins/` に入れる。

CI(`.github/workflows/build.yml`)がビルドして Reposilite に公開する。`build.gradle.kts` の `version` が `-SNAPSHOT` なら全ブランチが `/snapshots/videomap/<ブランチ>/<version>/` へ、`-SNAPSHOT` を外して main に push すると `/releases/videomap/<version>/` へ公開される(リポジトリ/組織の Secrets に `REPOSILITE_USER` / `REPOSILITE_TOKEN` が必要。無ければ公開だけスキップ)。

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
5. `/videomap reload` で config.yml を再読み込み(再生中のスクリーンには次の play / live から反映)

権限は `videomap.admin`(デフォルト OP)。

## 音声(VC連携)
映像と一緒に、動画の音を [Minecraft-Spatial-Audio-Link](https://github.com/swmr71/Minecraft-Spatial-Audio-Link) のボイスチャットで流せる。
スクリーンの位置が音の発生源になり、VC に繋いでいるプレイヤーが近づくと聞こえる(HRTF で方向も分かる)。

`config.yml` の `vc-audio` を設定:
```yaml
vc-audio:
  enabled: true
  base-url: "http://10.2.1.5:8010"   # msal-node
  api-key: "<msal-node の PLUGIN_API_KEY>"
  range: 32
```
- 音声の無い動画/配信は自動で映像のみになる
- 既存のスクリーンは位置が保存されていないので、額縁がロード済みなら自動で求める。出ない場合は作り直す
- 音が少し遅れるので映像側を `video-delay-ms` だけ遅らせてズレを抑えている(環境に合わせて調整)
- RTMP 待ち受け(`live <名前> <キー>`)で配信に音声が無い場合、最初の接続は映像のみへ切り替わる際に切れる。OBS が自動再接続する

## 大きい画面で重いとき(差分更新)
マップ1枚=128x128 を 16x16 のブロック(64個)に分け、**前に確定した絵と比べて変わったブロックだけ**を更新する。
さらに 1tick に更新するマップ枚数に上限を設け、変化の大きいマップから優先して反映する(溢れた分は次のtickへ)。
アニメの静かなシーンは通信がほぼ0、激しいシーンでも一度に全マップが動いてサーバーが固まることがなくなる。

`config.yml` の `block-update`:
- `max-maps-per-tick` (48): 1tick・1スクリーンの更新上限。固まるなら下げる。0 で無制限
- `pixel-threshold` (20): この色差以下のピクセルは「変わってない」扱い。上げるほど軽くなるが絵がざらつく/更新が遅れる
- `block-min-pixels` (4): ブロック内でこれ以上のピクセルが変わったときだけ更新。上げるほど細かい動きが落ちる

前提: `paper-world-defaults.yml` の `maps.item-frame-cursor-update-interval` を **1** にすること(10 のままだと更新が間引かれる)。
`/videomap reload` で設定を変えたら、次の play / live から差分検出の設定が反映される(上限枚数は即時)。

## 補足
- マップは映像のみ。音は上の VC 連携か、リソースパックなど別途。
- 帯域節約のため既定は 10fps。マップ枚数 x fps がそのまま通信量になるので、大きい画面ほど fps を下げるのがおすすめ。
- 色はマップのパレット(約240色)に量子化されるので、実写はやや油絵っぽくなる。
- OBS 配信はルーター二重構成の都合上、LAN内から繋ぐのが簡単。
