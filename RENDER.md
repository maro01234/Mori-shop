# Render無料プランでのデプロイ

## 構成

- Docker Web Service / Java 21 / Singapore / Free / 1インスタンス
- 永続ディスクなし。起動ごとに新しい一時SQLiteを作成
- `HOST=0.0.0.0`, `PORT=10000`, `COOKIE_SECURE=true`
- ヘルスチェック `/healthz`：SQLite読み取り成功時200、失敗時503
- Javaメモリ上限：コンテナメモリの60%

無料サービスはアクセスがないと休止し、次のアクセス時の起動に時間がかかる場合があります。カート・お気に入り・注文履歴・在庫はサーバー再起動で初期化されます。実決済・発送はありません。

## 別の環境へデプロイする場合

1. このフォルダの中身をGitHubリポジトリのルートへ配置します。
2. Renderの **New → Blueprint** でそのリポジトリを選びます。
3. Blueprint Pathは `render.yaml`。プランがFree、ディスクがないことを確認して作成します。
4. デプロイ完了後に表示される `https://…onrender.com` を開きます。

Web Serviceを手動作成する場合も、Runtime Docker・Plan Free・Region Singapore・Health Check `/healthz` とし、同梱 `render.yaml` の環境変数を設定します。Docker Commandは空欄にします。

親リポジトリのサブフォルダとして登録する場合は Root Directory を `mori-shop` にしてください。Dockerfile Pathは `./Dockerfile`、Build Contextは `.` です。

## 検証

```sh
mvn test
render blueprints validate render.yaml --output json
```

公開後に `/healthz` と商品一覧を確認します。起動ごとに別のSQLiteを作るため、前の注文履歴は復元されません。旧版の固定SQLiteファイルや永続ディスクの設定は使用しません。

## 公式資料

- [無料サービスの仕様](https://render.com/docs/free)
- [Blueprint設定](https://render.com/docs/blueprint-spec)
