package com.example.shop;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.*;

class ShopTest {
    @TempDir Path dir;
    Store store;
    String sid;
    @BeforeEach void setup() throws Exception {store=new Store(dir.resolve("shop.db"));sid=store.session(null)[0];}
    @Test void persistenceAndIsolation() throws Exception {
        store.cart(sid,1,2);store.favorite(sid,1,true);
        var other=store.session(null)[0]; assertEquals(List.of(),store.state(other).get("cart"));
        var reopened=new Store(dir.resolve("shop.db"));
        assertEquals(1,((List<?>)reopened.state(sid).get("cart")).size());
        assertEquals(List.of(1),reopened.state(sid).get("favorites"));
        assertEquals(12,reopened.products().size());
    }
    @Test void validatesQuantityAndStock() throws Exception {
        assertThrows(IllegalArgumentException.class,()->store.cart(sid,1,-1));
        assertThrows(IllegalArgumentException.class,()->store.cart(sid,1,31));
        assertThrows(IllegalArgumentException.class,()->store.cart(sid,999,1));
        store.cart(sid,1,2);store.cart(sid,1,0);assertEquals(List.of(),store.state(sid).get("cart"));
    }
    @Test void checkoutCalculatesShippingAndClearsCart() throws Exception {
        store.cart(sid,8,1);String id=store.checkout(sid,"テスト","100-0001","デモ住所");
        var order=store.orders(sid).getFirst();assertEquals(id,order.get("id"));assertEquals(1890,order.get("total"));assertEquals(350,order.get("shipping"));
        assertEquals(List.of(),store.state(sid).get("cart"));
        assertEquals(34,store.products().get(7).get("stock"));
        assertEquals(List.of(),store.orders(store.session(null)[0]));
        assertThrows(IllegalArgumentException.class,()->store.checkout(sid,"テスト","1000001","住所"));
    }
    @Test void freeShippingAndOrderSnapshot() throws Exception {
        store.cart(sid,1,1);store.checkout(sid,"テスト","1000001","住所");
        var order=store.orders(sid).getFirst();assertEquals(7980,order.get("total"));assertEquals(0,order.get("shipping"));
        var items=(List<?>)order.get("items");assertEquals(7980,((Map<?,?>)items.getFirst()).get("price"));
    }
    @Test void staleStockRollsBackEntireOrder() throws Exception {
        store.cart(sid,1,1);store.cart(sid,2,25);
        var other=store.session(null)[0];store.cart(other,2,1);store.checkout(other,"別ユーザー","1000001","住所");
        assertThrows(IllegalArgumentException.class,()->store.checkout(sid,"テスト","1000001","住所"));
        assertEquals(30,store.products().getFirst().get("stock"));assertEquals(24,store.products().get(1).get("stock"));
        assertEquals(List.of(),store.orders(sid));assertEquals(2,((List<?>)store.state(sid).get("cart")).size());
    }
    @Test void validatesAddressWithoutMutatingCart() throws Exception {
        store.cart(sid,1,1);
        assertThrows(IllegalArgumentException.class,()->store.checkout(sid,"","1000001","住所"));
        assertThrows(IllegalArgumentException.class,()->store.checkout(sid,"名前","not-a-zip","住所"));
        assertEquals(1,((List<?>)store.state(sid).get("cart")).size());
    }
    @Test void concurrentCheckoutCannotOversell() throws Exception {
        var other=store.session(null)[0];store.cart(sid,1,30);store.cart(other,1,30);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var results=pool.invokeAll(List.of((Callable<Boolean>)()->{try{store.checkout(sid,"A","1000001","住所");return true;}catch(IllegalArgumentException e){return false;}},()->{try{store.checkout(other,"B","1000001","住所");return true;}catch(IllegalArgumentException e){return false;}}));
            assertNotEquals(results.get(0).get(),results.get(1).get());assertEquals(0,store.products().getFirst().get("stock"));
        }
    }
    @Test void httpSessionCsrfAndUntrustedTotals() throws Exception {
        var app=new App(dir.resolve("http.db"),"127.0.0.1",0);app.start();
        try(var client=HttpClient.newBuilder().cookieHandler(new CookieManager(null,CookiePolicy.ACCEPT_ALL)).build()) {
            String base="http://127.0.0.1:"+app.port();var mapper=new ObjectMapper();
            var page=client.send(HttpRequest.newBuilder(URI.create(base)).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,page.statusCode());assertTrue(page.body().contains("mori"));
            var session=client.send(HttpRequest.newBuilder(URI.create(base+"/api/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
            var token=mapper.readTree(session.body()).path("csrf").asText();assertTrue(session.headers().firstValue("set-cookie").orElseThrow().contains("HttpOnly"));
            var noToken=client.send(HttpRequest.newBuilder(URI.create(base+"/api/cart")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString("{\"product\":1,\"quantity\":1}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(403,noToken.statusCode());
            var cart=client.send(HttpRequest.newBuilder(URI.create(base+"/api/cart")).header("Content-Type","application/json").header("X-CSRF-Token",token).POST(HttpRequest.BodyPublishers.ofString("{\"product\":1,\"quantity\":1,\"price\":1}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,cart.statusCode());
            var checkout=client.send(HttpRequest.newBuilder(URI.create(base+"/api/checkout")).header("Content-Type","application/json").header("X-CSRF-Token",token).POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"テスト\",\"postal\":\"1000001\",\"address\":\"架空の住所\",\"total\":0}")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(201,checkout.statusCode());
            var orders=client.send(HttpRequest.newBuilder(URI.create(base+"/api/orders")).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(7980,mapper.readTree(orders.body()).get(0).path("total").asInt());
            var malformed=client.send(HttpRequest.newBuilder(URI.create(base+"/api/cart")).header("Content-Type","application/json").header("X-CSRF-Token",token).POST(HttpRequest.BodyPublishers.ofString("{bad")).build(),HttpResponse.BodyHandlers.ofString());assertEquals(400,malformed.statusCode());
        } finally {app.stop();}
    }
    @Test void healthChecksDatabaseWithoutCreatingSession() throws Exception {
        Path database=dir.resolve("health.db");
        var app=new App(database,"127.0.0.1",0);app.start();
        try(var client=HttpClient.newHttpClient()) {
            var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+"/healthz")).GET().build();
            var healthy=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,healthy.statusCode());assertTrue(healthy.body().contains("ok"));
            assertTrue(healthy.headers().firstValue("set-cookie").isEmpty());
            for(String path:List.of("/","/healthz")) {
                var head=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+app.port()+path)).method("HEAD",HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                assertEquals(200,head.statusCode());assertEquals("",head.body());
            }
            try(var c=java.sql.DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
                try(var rows=s.executeQuery("SELECT count(*) FROM sessions")){assertTrue(rows.next());assertEquals(0,rows.getInt(1));}
                s.execute("ALTER TABLE products RENAME TO unavailable_products");
            }
            var unhealthy=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertEquals(503,unhealthy.statusCode());
        } finally {app.stop();}
    }
    @Test void eachStartupUsesAFreshTemporaryDatabase() throws Exception {
        Path first=App.temporaryDatabase(),second=App.temporaryDatabase();
        try {
            assertNotEquals(first,second);
            var a=new Store(first);var s=a.session(null)[0];a.cart(s,1,1);a.checkout(s,"テスト","1000001","架空住所");
            var b=new Store(second);
            assertEquals(List.of(),b.orders(s));
            assertEquals(30,b.products().getFirst().get("stock"));
            assertNotEquals(s,b.session(s)[0]);
        } finally {
            for(Path file:List.of(first,second)) {
                for(String suffix:List.of("-wal","-shm","-journal",""))java.nio.file.Files.deleteIfExists(Path.of(file+suffix));
                java.nio.file.Files.deleteIfExists(file.getParent());
            }
        }
    }
}
