# mori shop

Java 21以上 + SQLiteで動作する日本語ショッピングサイトのデモです。Amazon風の画面構成に、独自ブランドと同梱SVGイラストを使用しています。

**一時保存専用です。サーバープロセスの起動ごとに新しいSQLiteを作り、再起動・休止・再デプロイ後に以前のデータを引き継ぎません。** 実決済・発送はありません。

## ローカル起動

```sh
mvn clean package
java -jar target/mori-shop.jar
```

Java 21以上、Maven 3.9以上が必要です。http://localhost:8087 を開きます。実行用JARには画像・CSS・JavaScriptを同梱しており、外部CDNは使用しません。

## 機能

- 商品検索・カテゴリ・価格／在庫絞り込み・並び替え
- 商品詳細・お気に入り・カート・数量変更
- 架空の配送先入力・送料計算・デモ注文・注文履歴
- ブラウザごとのCookieによるデータ分離
- スマートフォン対応

12商品を起動時に登録。商品・評価・レビュー件数はサンプルです。表示価格は税込。小計3,500円以上は送料無料、それ未満は350円。価格はサーバーで計算し、注文登録・在庫減算・カート削除を一つのトランザクションで処理します。

## 一時保存の仕組み

起動時にOSの一時フォルダ内へ専用のランダムなディレクトリを作成し、その中の `shop.db` を使います。サーバーが稼働している間はページ再読み込み後も利用できます。次回起動時は必ず別のデータベースを作り、正常終了時には今回作った一時ファイルを削除します。強制停止で残った一時ファイルも次回起動時に再利用しません。`DB_PATH` による固定ファイル指定は廃止しました。旧版の `data/shop.db` は読み込みません。

SQLiteには配送先も一時的に保存するため、架空の情報を入力してください。会員登録・管理画面・カード決済・メール送信は実装していません。

## Renderへの公開

無料Web Service・Docker・永続ディスクなしの構成です。詳しくは [RENDER.md](RENDER.md) を参照してください。

## 設定

| 環境変数 | 初期値 | 説明 |
| --- | --- | --- |
| `HOST` | `127.0.0.1` | Dockerでは `0.0.0.0` |
| `PORT` | `8087` | Render設定は `10000` |
| `COOKIE_SECURE` | 未設定 | HTTPSのRender上では `true` |

## テスト

```sh
mvn test
```

セッション分離、数量・住所検証、送料、在庫不足時のロールバック、並行注文、CSRF、価格改ざん対策、ヘルスチェック、起動ごとの初期化を検証します。

## Docker

```sh
docker build -t mori-shop .
docker run --rm -p 8087:8087 mori-shop
```

ディスクやボリュームの指定は不要です。Javaは非rootのUID 10001で実行します。

## 主なファイル

- `src/main/java/com/example/shop/App.java` — HTTP・一時DB生成・CSRF・ヘルスチェック
- `src/main/java/com/example/shop/Store.java` — SQLite・商品・カート・注文処理
- `src/main/resources/public/` — HTML・CSS・JavaScript・画像
- `src/test/java/com/example/shop/ShopTest.java` — 自動テスト
- `render.yaml` — 無料Webサービス設定
