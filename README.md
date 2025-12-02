# EDA Receiver (スマートフォンアプリ)

Pixel Watch 2/3で計測された皮膚電気活動（EDA）データを受信・表示・管理するためのAndroidアプリケーションです．  
Pixel Watchからリアルタイムでデータを受信し，グラフ表示や記録の閲覧が可能になっています．

<img width="1080" height="2400" alt="Screenshot_20251202-210452" src="https://github.com/user-attachments/assets/a5817046-f7bd-40b4-bfa1-6d8bcd755110" />
<img width="1080" height="2400" alt="Screenshot_20251202-210439" src="https://github.com/user-attachments/assets/b66cfaef-86e7-4db4-a8b7-e8ac4afd07c2" />


## 概要

Pixel Watchの「Pixel Watch EDA Logger」と連携することで，EDAデータの受信と管理が行えるようになっています．  
基本的には以下の機能が使用できるようになっています：

* **リアルタイム表示**: Watch側で計測中のEDA値（µS: マイクロジーメンス）をリアルタイムで受信し，グラフ表示．
* **データ受信**: 計測終了時，WatchからCSVファイルを自動受信し，端末のDownloadsフォルダへ保存．
* **ファイル管理**: 受信したCSVファイルの一覧表示，内容確認，他アプリへの共有．
* **リモート制御**: スマートフォンからWatch側の計測開始/停止を操作可能．

## 動作環境

* **ハードウェア**: Androidスマートフォン（Pixel推奨）
* **OS**: Android 8.0 (API 26) 以上
* **必須条件**: Pixel Watch 2 または Pixel Watch 3 とのペアリング

## セットアップとインストール

1.  このリポジトリをクローンまたはダウンロード
2.  Android StudioでプロジェクトをOpen
3.  スマートフォンを開発者モードにし，ADBデバッグを有効にしてPCと接続
4.  `app` モジュール（スマートフォン用）をビルドし，スマートフォンにインストール
5.  Pixel Watchと正常にペアリングされていることを確認

## 使用方法

1.  **権限の許可**: 初回起動時，通知 (`POST_NOTIFICATIONS`) およびストレージアクセス権限が求められた際には，「許可」を選択してください．
2.  **リアルタイムモニター**: 画面下部の "Monitor" タブをタップします．
    * 画面上の "Start" ボタンをタップすると，ペアリングされたPixel Watchへ計測開始コマンドが送信されます．
    * 画面には現在のEDA値がリアルタイムで表示され，折れ線グラフで推移を確認できます．
    * 計測中はWatch側からリアルタイムデータが継続的に送信されます．
3.  **計測終了**: "Stop" ボタンをタップします．
    * Watch側の計測が停止し，CSVファイルがスマートフォンへ自動転送されます．
    * 転送されたファイルはDownloadsフォルダに保存されます．
4.  **ファイル閲覧**: 画面下部の "Files" タブをタップします．
    * 受信したCSVファイルの一覧が日付順で表示されます．
    * ファイルをタップすると，CSVの内容を表形式で閲覧できます．
    * 共有アイコンから他のアプリへファイルをエクスポート可能です．

## 受信データ形式

Pixel Watchから受信し，Downloadsフォルダに保存されるCSVファイル (`EDA_Received_yyyyMMdd_HHmmss.csv`) のフォーマットは以下の通りです．

| カラム名 | 説明 |
| --- | --- |
| `SessionStartTime` | セッション開始時の日時文字列 |
| `Timestamp_ms` | システム時刻 (ミリ秒) |
| `Raw_mOhms` | センサーから取得した生の抵抗値 (mΩ) |
| `Converted_uS` | コンダクタンスに変換されたEDA値 (µS) |

**計算式:**
`Converted_uS = 1,000,000,000 / Raw_mOhms`

## 通信機能について

このアプリは `Wearable.getChannelClient` および `Wearable.getMessageClient` を使用してPixel Watchと通信します．
以下のパスでデータの送受信を行います：

* **CSVファイル受信**: Channel ID `/eda_csv` (Watch → Phone)
* **リアルタイムデータ受信**: Message Path `/realtime_eda` (Watch → Phone)
* **リモート開始コマンド送信**: Message Path `/start_recording` (Phone → Watch)
* **リモート停止コマンド送信**: Message Path `/stop_recording` (Phone → Watch)

※ 通信にはPixel Watchと正常にペアリングされている必要があります．Bluetooth接続が有効になっていることを確認してください．

## UI構成

アプリは2つのタブで構成されています：

### Filesタブ
* 受信したCSVファイルを日付順で一覧表示
* ファイル名，受信日時，ファイルサイズを表示
* タップでファイル内容を閲覧（表形式）
* 共有ボタンで他アプリへCSVをエクスポート

### Monitorタブ
* リアルタイムEDA値の数値表示（48sp，大きく表示）
* 過去200データポイントの折れ線グラフ（軸・目盛り付き）
* Start/Stopボタンによるリモート操作
* グラフは自動的にスクロールし，最新データを表示

## 注意事項

* **ペアリング**: 本アプリはPixel Watchとのペアリングが必須です．Wear OSアプリ経由で正常にペアリングされていることを確認してください．
* **Bluetooth**: データ通信にはBluetooth接続が必要です．接続が切れるとデータ受信ができなくなります．
* **バッテリー消費**: リアルタイムモニター使用中は継続的な通信が発生するため，両デバイスのバッテリー消費が早くなることに注意してください．
* **ストレージ**: 受信したCSVファイルはDownloadsフォルダに保存されます．Android 10以降ではスコープドストレージに対応しています．
* **権限**: Android 13以降では通知権限 (`POST_NOTIFICATIONS`) の許可が必要です．初回起動時に必ず許可してください．
