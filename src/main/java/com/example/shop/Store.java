package com.example.shop;

import java.sql.*;
import java.util.*;
import java.nio.file.*;

/** All database operations use bound parameters; checkout is a single transaction. */
public final class Store {
    private final String url;
    public Store(Path file) throws Exception {
        Files.createDirectories(file.toAbsolutePath().getParent());
        url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (var c = connection(); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS products(id INTEGER PRIMARY KEY,name TEXT NOT NULL,brand TEXT NOT NULL,category TEXT NOT NULL,price INTEGER NOT NULL,original INTEGER NOT NULL,rating REAL NOT NULL,reviews INTEGER NOT NULL,stock INTEGER NOT NULL CHECK(stock>=0),art TEXT NOT NULL,color TEXT NOT NULL,description TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY,csrf TEXT NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS cart(session TEXT NOT NULL REFERENCES sessions(id),product INTEGER NOT NULL REFERENCES products(id),quantity INTEGER NOT NULL CHECK(quantity BETWEEN 1 AND 99),PRIMARY KEY(session,product))");
            s.execute("CREATE TABLE IF NOT EXISTS favorites(session TEXT NOT NULL REFERENCES sessions(id),product INTEGER NOT NULL REFERENCES products(id),PRIMARY KEY(session,product))");
            s.execute("CREATE TABLE IF NOT EXISTS orders(id TEXT PRIMARY KEY,session TEXT NOT NULL REFERENCES sessions(id),name TEXT NOT NULL,postal TEXT NOT NULL,address TEXT NOT NULL,total INTEGER NOT NULL,shipping INTEGER NOT NULL,created TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            s.execute("CREATE TABLE IF NOT EXISTS order_items(order_id TEXT NOT NULL REFERENCES orders(id),product INTEGER NOT NULL REFERENCES products(id),name TEXT NOT NULL,price INTEGER NOT NULL,quantity INTEGER NOT NULL,art TEXT NOT NULL)");
        }
        seed();
    }
    private Connection connection() throws SQLException {
        var c = DriverManager.getConnection(url);
        try (var s=c.createStatement()) { s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA busy_timeout=5000"); }
        return c;
    }
    private static List<Map<String,Object>> query(Connection c,String sql,Object... args) throws SQLException {
        try (var p=c.prepareStatement(sql)) {
            bind(p,args);
            try (var r=p.executeQuery()) {
                var out=new ArrayList<Map<String,Object>>();
                while(r.next()) { var row=new LinkedHashMap<String,Object>(); for(int i=1;i<=r.getMetaData().getColumnCount();i++) row.put(r.getMetaData().getColumnLabel(i),r.getObject(i)); out.add(row); }
                return out;
            }
        }
    }
    private static void bind(PreparedStatement p,Object... args) throws SQLException { for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]); }
    private static int update(Connection c,String sql,Object... args) throws SQLException { try(var p=c.prepareStatement(sql)){bind(p,args);return p.executeUpdate();} }
    public List<Map<String,Object>> products() throws SQLException {try(var c=connection()){return query(c,"SELECT * FROM products ORDER BY id");}}
    public void health() throws SQLException {
        try(var c=connection()) {query(c,"SELECT id FROM products LIMIT 1");}
    }
    public String[] session(String id) throws SQLException {
        try(var c=connection()) {
            if(id!=null) { var found=query(c,"SELECT id,csrf FROM sessions WHERE id=?",id); if(!found.isEmpty())return new String[]{id,found.getFirst().get("csrf").toString()}; }
            var sid=UUID.randomUUID().toString(); var csrf=UUID.randomUUID().toString();
            update(c,"INSERT INTO sessions(id,csrf) VALUES(?,?)",sid,csrf); return new String[]{sid,csrf};
        }
    }
    public Map<String,Object> state(String sid) throws SQLException {
        try(var c=connection()) {
            return Map.of("cart",query(c,"SELECT p.*,c.quantity FROM cart c JOIN products p ON p.id=c.product WHERE c.session=? ORDER BY p.id",sid),"favorites",query(c,"SELECT product FROM favorites WHERE session=?",sid).stream().map(x->x.get("product")).toList());
        }
    }
    public synchronized void cart(String sid,int product,int quantity) throws SQLException {
        if(quantity<0||quantity>99)throw new IllegalArgumentException("数量は0〜99個で指定してください。");
        try(var c=connection()) {
            var p=query(c,"SELECT stock FROM products WHERE id=?",product);
            if(p.isEmpty())throw new IllegalArgumentException("商品が見つかりません。");
            if(quantity>((Number)p.getFirst().get("stock")).intValue())throw new IllegalArgumentException("在庫数を超えています。");
            if(quantity==0)update(c,"DELETE FROM cart WHERE session=? AND product=?",sid,product);
            else update(c,"INSERT INTO cart(session,product,quantity) VALUES(?,?,?) ON CONFLICT(session,product) DO UPDATE SET quantity=excluded.quantity",sid,product,quantity);
        }
    }
    public void favorite(String sid,int product,boolean active) throws SQLException {
        try(var c=connection()) {
            if(query(c,"SELECT id FROM products WHERE id=?",product).isEmpty())throw new IllegalArgumentException("商品が見つかりません。");
            if(active)update(c,"INSERT OR IGNORE INTO favorites(session,product) VALUES(?,?)",sid,product);
            else update(c,"DELETE FROM favorites WHERE session=? AND product=?",sid,product);
        }
    }
    public synchronized String checkout(String sid,String name,String postal,String address) throws SQLException {
        if(name==null||name.isBlank()||name.length()>80)throw new IllegalArgumentException("お名前を80文字以内で入力してください。");
        if(postal==null||!postal.matches("[0-9]{3}-?[0-9]{4}"))throw new IllegalArgumentException("郵便番号を正しく入力してください。");
        if(address==null||address.isBlank()||address.length()>300)throw new IllegalArgumentException("配送先住所を300文字以内で入力してください。");
        try(var c=connection()) {
            c.setAutoCommit(false);
            try {
                var cart=query(c,"SELECT p.*,c.quantity FROM cart c JOIN products p ON p.id=c.product WHERE c.session=?",sid);
                if(cart.isEmpty())throw new IllegalArgumentException("カートに商品がありません。");
                int subtotal=0;
                for(var p:cart) {
                    int quantity=((Number)p.get("quantity")).intValue();
                    if(update(c,"UPDATE products SET stock=stock-? WHERE id=? AND stock>=?",quantity,p.get("id"),quantity)!=1)throw new IllegalArgumentException(p.get("name")+"の在庫が不足しています。");
                    subtotal+=((Number)p.get("price")).intValue()*quantity;
                }
                int shipping=subtotal>=3500?0:350;
                String id="MR-"+UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
                update(c,"INSERT INTO orders(id,session,name,postal,address,total,shipping) VALUES(?,?,?,?,?,?,?)",id,sid,name.trim(),postal,address.trim(),subtotal+shipping,shipping);
                for(var p:cart)update(c,"INSERT INTO order_items(order_id,product,name,price,quantity,art) VALUES(?,?,?,?,?,?)",id,p.get("id"),p.get("name"),p.get("price"),p.get("quantity"),p.get("art"));
                update(c,"DELETE FROM cart WHERE session=?",sid); c.commit(); return id;
            } catch(SQLException|RuntimeException e) { c.rollback(); throw e; }
        }
    }
    public List<Map<String,Object>> orders(String sid) throws SQLException {
        try(var c=connection()) {
            var orders=query(c,"SELECT * FROM orders WHERE session=? ORDER BY created DESC,rowid DESC",sid);
            for(var o:orders)o.put("items",query(c,"SELECT * FROM order_items WHERE order_id=?",o.get("id")));
            return orders;
        }
    }
    private void seed() throws SQLException {
        Object[][] rows={
            {1,"ワイヤレス ノイズキャンセリング ヘッドホン","SOUNDPEAK","家電・カメラ",7980,12980,4.7,1248,30,"headphones","#ece8e1","音楽に、もっと深く。周囲の雑音を抑えるアクティブノイズキャンセリング搭載。最大40時間再生、軽量240g。USB-C充電ケーブル・専用ケース付属。"},
            {2,"コンパクト メカニカルキーボード K68","keystudio","パソコン・周辺機器",6480,8980,4.6,863,25,"keyboard","#e4ebe8","心地よい打鍵感と、すっきりしたデスク。68キー・日本語配列・Bluetooth / USB-C接続。WindowsとmacOSに対応。"},
            {3,"温度調節付き ドリップケトル 0.8L","暮らしの道具","ホーム・キッチン",4980,6980,4.5,672,40,"kettle","#ece6df","一杯を、丁寧に。細口ノズルで注ぎやすい電気ケトル。容量0.8L、温度調節・保温機能・空焚き防止機能付き。"},
            {4,"ミニマル デスクライト / サンド","LUMI","ホーム・キッチン",3280,4980,4.8,421,18,"lamp","#eee5d8","やわらかな光で、毎日の作業を快適に。3段階調光のLEDデスクライト。省スペース設計、USB電源、角度調節可能。"},
            {5,"毎日に寄り添う キャンバストート","mori essentials","ファッション",1980,2480,4.4,356,45,"bag","#e9e8dc","丈夫なコットンキャンバスの定番トート。A4サイズに対応し、内側ポケット付き。通勤にも週末のお出かけにも。"},
            {6,"ポータブル Bluetooth スピーカー","SOUNDPEAK","家電・カメラ",3980,5980,4.6,928,24,"speaker","#e1e7ea","小さなボディに、豊かなサウンド。最大12時間再生、防滴仕様。ストラップ付きでアウトドアにも持ち出せます。"},
            {7,"真空断熱 ステンレスボトル 500ml","DAYTRIP","スポーツ・アウトドア",2480,3280,4.7,1582,60,"bottle","#e1e8de","ちょうどいい温度を、いつでも。保温・保冷対応の真空断熱ボトル。持ち運びやすい500ml、洗いやすい広口設計。"},
            {8,"暮らしを整える、小さな習慣","mori books","本・文房具",1540,1540,4.5,218,35,"book","#ece5dc","忙しい日々に、自分だけの余白を。部屋づくりや日々の習慣を見直すヒントを収めた、全192ページの暮らしの本。"},
            {9,"エルゴノミック ワークチェア","form & room","ホーム・キッチン",18980,24980,4.6,307,12,"chair","#e2e7e6","長時間の作業をしっかり支えるワークチェア。高さ調節、ランバーサポート、通気性のよい背もたれ。組み立て式。"},
            {10,"クラシック フィルムスタイル カメラ","Focal","家電・カメラ",32800,39800,4.8,184,10,"camera","#e8e4dd","日常を、特別な一枚に。2400万画素のコンパクトデジタルカメラ。手ぶれ補正、フルHD動画撮影、充電池付属。"},
            {11,"アーバン ウォーキングシューズ","DAYTRIP","ファッション",5480,7480,4.3,593,22,"shoe","#e8e5df","一歩を軽く、街へ出かけよう。クッション性に優れた軽量スニーカー。デモ商品は26cm・アイボリーの単一仕様です。"},
            {12,"セラミックポットのグリーン","葉と暮らし","ホーム・キッチン",2780,3480,4.7,265,20,"plant","#e1e8df","お部屋にひとつ、緑のアクセント。手入れ不要のアーティフィシャルグリーンとマットなセラミックポットのセット。高さ約30cm。"}
        };
        try(var c=connection()) {for(var row:rows)update(c,"INSERT OR IGNORE INTO products VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",row);}
    }
}
