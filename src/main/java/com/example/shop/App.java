package com.example.shop;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.Executors;

public final class App {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final Store store;
    private final HttpServer server;
    public App(Path database,String host,int port) throws Exception {
        store=new Store(database); server=HttpServer.create(new InetSocketAddress(host,port),0);
        server.createContext("/",this::handle); server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
    public void start(){server.start();}
    public void stop(){server.stop(0);}
    public int port(){return server.getAddress().getPort();}
    static Path temporaryDatabase() throws java.io.IOException {
        return Files.createTempDirectory("mori-shop-").resolve("shop.db");
    }
    public static void main(String[] args) throws Exception {
        var env=System.getenv();
        Path database=temporaryDatabase();
        var app=new App(database,env.getOrDefault("HOST","127.0.0.1"),Integer.parseInt(env.getOrDefault("PORT","8087")));
        app.start(); System.out.println("mori shop: http://localhost:"+app.port());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{
            app.server.stop(10);
            for(String suffix:List.of("-wal","-shm","-journal","")) {
                try {Files.deleteIfExists(Path.of(database+suffix));} catch(Exception ignored) {}
            }
            try {Files.deleteIfExists(database.getParent());} catch(Exception ignored) {}
        }));
    }
    private void handle(HttpExchange x) {
        try {
            x.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            x.getResponseHeaders().set("Referrer-Policy","same-origin");
            x.getResponseHeaders().set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
            String path=x.getRequestURI().getPath(), method=x.getRequestMethod();
            if(path.equals("/healthz") && method.equals("GET")) {
                x.getResponseHeaders().set("Cache-Control","no-store");
                try {store.health();json(x,200,Map.of("status","ok"));}
                catch(Exception e){json(x,503,Map.of("status","unavailable"));}
                return;
            }
            if(!path.startsWith("/api/")){ staticFile(x,path,method);return; }
            x.getResponseHeaders().set("Cache-Control","no-store");
            if(path.equals("/api/products") && method.equals("GET")){json(x,200,store.products());return;}
            String cookie=x.getRequestHeaders().getFirst("Cookie"), sid=null;
            if(cookie!=null)for(String v:cookie.split(";"))if(v.trim().startsWith("mori_session="))sid=v.trim().substring(13);
            if(sid!=null && sid.length()>=2 && sid.startsWith("\"") && sid.endsWith("\""))sid=sid.substring(1,sid.length()-1);
            var session=store.session(sid);
            if(!session[0].equals(sid))x.getResponseHeaders().add("Set-Cookie","mori_session="+session[0]+"; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000"+("true".equals(System.getenv("COOKIE_SECURE"))?"; Secure":""));
            sid=session[0];
            if(method.equals("GET")) {
                switch(path) {
                    case "/api/session" -> json(x,200,Map.of("csrf",session[1],"state",store.state(sid)));
                    case "/api/products" -> json(x,200,store.products());
                    case "/api/state" -> json(x,200,store.state(sid));
                    case "/api/orders" -> json(x,200,store.orders(sid));
                    default -> json(x,404,Map.of("error","ページが見つかりません。"));
                } return;
            }
            if(!method.equals("POST")){json(x,405,Map.of("error","許可されていない操作です。"));return;}
            if(!session[1].equals(x.getRequestHeaders().getFirst("X-CSRF-Token"))){json(x,403,Map.of("error","セッションを確認できません。ページを再読み込みしてください。"));return;}
            String type=x.getRequestHeaders().getFirst("Content-Type");
            if(type==null||!type.startsWith("application/json")){json(x,415,Map.of("error","JSON形式で送信してください。"));return;}
            byte[] bytes=x.getRequestBody().readNBytes(16385);
            if(bytes.length>16384){json(x,413,Map.of("error","入力が長すぎます。"));return;}
            JsonNode body;
            try {body=JSON.readTree(bytes);if(body==null||!body.isObject())throw new IllegalArgumentException();}
            catch(Exception e){throw new IllegalArgumentException("入力形式が正しくありません。");}
            switch(path) {
                case "/api/cart" -> {store.cart(sid,integer(body,"product"),integer(body,"quantity"));json(x,200,store.state(sid));}
                case "/api/favorites" -> {if(!body.path("active").isBoolean())throw new IllegalArgumentException("お気に入りの指定が不正です。");store.favorite(sid,integer(body,"product"),body.path("active").asBoolean());json(x,200,store.state(sid));}
                case "/api/checkout" -> {var id=store.checkout(sid,body.path("name").asText(),body.path("postal").asText(),body.path("address").asText());json(x,201,Map.of("id",id,"state",store.state(sid)));}
                default -> json(x,404,Map.of("error","ページが見つかりません。"));
            }
        } catch(IllegalArgumentException e){try{json(x,400,Map.of("error",e.getMessage()));}catch(Exception ignored){}}
        catch(Exception e){e.printStackTrace();try{json(x,500,Map.of("error","処理に失敗しました。もう一度お試しください。"));}catch(Exception ignored){}}
        finally{x.close();}
    }
    private static int integer(JsonNode b,String k){if(!b.path(k).isIntegralNumber()||!b.path(k).canConvertToInt())throw new IllegalArgumentException("数量・商品番号が正しくありません。");return b.path(k).intValue();}
    private static void json(HttpExchange x,int status,Object data)throws Exception {send(x,status,"application/json; charset=utf-8",JSON.writeValueAsBytes(data));}
    private static void send(HttpExchange x,int status,String type,byte[] data)throws Exception{x.getResponseHeaders().set("Content-Type",type);x.sendResponseHeaders(status,data.length);x.getResponseBody().write(data);}
    private static void staticFile(HttpExchange x,String path,String method)throws Exception {
        if(!method.equals("GET")){json(x,405,Map.of("error","Method not allowed"));return;}
        if(path.equals("/"))path="/index.html";
        if(!path.matches("/[a-zA-Z0-9_./-]+")||path.contains("..")){json(x,404,Map.of("error","Not found"));return;}
        try(var in=App.class.getResourceAsStream("/public"+path)) {
            if(in==null){json(x,404,Map.of("error","Not found"));return;}
            String type=path.endsWith(".css")?"text/css":path.endsWith(".js")?"text/javascript":path.endsWith(".svg")?"image/svg+xml":path.endsWith(".html")?"text/html":"application/octet-stream";
            send(x,200,type+"; charset=utf-8",in.readAllBytes());
        }
    }
}
