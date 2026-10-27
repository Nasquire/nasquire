package com.bluesounds.nasquire.app;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.*;

// 고정 검증 포트를 다음 HTTP 픽스처에 반환한다.
@org.springframework.test.annotation.DirtiesContext
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class AccountsIntegrationTest {
    static final Path TEMP;
    static { try { TEMP = Files.createTempDirectory("nasquire-accounts-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString()); r.add("server.port", () -> "8104");
        r.add("nasquire.oauth.enabled", () -> "true"); r.add("nasquire.public-url", () -> "http://127.0.0.1:8104");
        r.add("nasquire.scan-millis", () -> "100"); r.add("nasquire.stable-millis", () -> "10");
    }
    @Autowired ObjectMapper mapper;
    @Autowired Storage storage;
    @Autowired OAuthService oauth;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    @Autowired LoginAttempts attempts;
    class Browser {
        HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        String csrf;
        HttpResponse<String> call(String path, String method, Object body, String bearer) throws Exception {
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8104" + path));
            if (csrf != null) req.header("X-CSRF-TOKEN", csrf);
            if (bearer != null) req.header("Authorization", "Bearer " + bearer);
            if (path.equals("/mcp")) req.header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", "2025-11-25");
            if (body != null) req.header("Content-Type", "application/json");
            return client.send(req.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode json(String path, String method, Object body, int status) throws Exception {
            var res = call(path, method, body, null); assertEquals(status, res.statusCode(), path); return res.body().isBlank() ? null : mapper.readTree(res.body());
        }
        void auth() throws Exception { csrf = json("/api/auth", "GET", null, 200).get("csrfToken").asText(); }
        int login(String name) throws Exception {
            auth(); attempts.reset("", "127.0.0.1", name);
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:8104/api/login")).header("X-CSRF-TOKEN", csrf).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + name + "&password=example-password-123")).build();
            int result = client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode(); auth(); return result;
        }
    }
    String source(Browser admin, String name) throws Exception {
        Path folder = Files.createDirectory(TEMP.resolve(name)); Files.writeString(folder.resolve("note.txt"), "accountboundary evidence " + name);
        return admin.json("/api/sources", "POST", Map.of("name", name, "path", folder.toString()), 200).get("id").asText();
    }
    int create(Browser admin, String name, String role, List<String> sources) throws Exception {
        return admin.json("/api/users", "POST", Map.of("username",name,"password","example-password-123","role",role,"sources",sources),200).get("id").asInt();
    }
    Map<String,Object> token(String name, List<String> sources) { return Map.of("name", name, "sources", sources, "expiresAt", System.currentTimeMillis()+600000); }
    @Test void permissionsOwnersSessionsAndConcurrentLastAdministrator() throws Exception {
        Browser admin = new Browser(); admin.auth();
        admin.json("/api/setup", "POST", Map.of("username","admin","password","example-password-123"),200); assertEquals(200,admin.login("admin"));
        String a = source(admin,"allowed"), b = source(admin,"private");
        int id = create(admin,"reader","USER",List.of(a)); int empty = create(admin,"empty","USER",List.of());
        Browser reader = new Browser(); assertEquals(200,reader.login("reader"));
        assertEquals("USER",reader.json("/api/auth","GET",null,200).get("role").asText());
        for (String path : List.of("/api/users","/api/sources","/api/uploads/"+a,"/api/shares","/api/indexing","/api/embeddings","/api/full","/api/oauth","/api/oidc","/api/audit"))
            assertEquals(403,reader.call(path,"GET",null,null).statusCode(),path);
        assertEquals(403,reader.call("/api/users","POST",Map.of(),null).statusCode());
        assertEquals(403,reader.call("/api/oidc","PUT",Map.of(),null).statusCode());
        assertEquals(403,reader.call("/api/oidc/secret","DELETE",null,null).statusCode());
        assertEquals(403,reader.call("/api/oauth/revoke","POST",Map.of("kind","grant","id","missing"),null).statusCode());
        assertEquals(403,reader.call("/api/oidc/test","POST",Map.of(),null).statusCode());
        Browser noSources = new Browser(); assertEquals(200,noSources.login("empty"));
        assertEquals(0,noSources.json("/api/search?query=accountboundary","GET",null,200).size());
        reader.json("/api/search?query=accountboundary&source="+b,"GET",null,403);
        reader.json("/api/document?path="+b+":note.txt","GET",null,403);
        reader.json("/api/download?path="+b+":note.txt","GET",null,403);
        reader.json("/api/tokens","POST",token("outside",List.of(b)),403);
        // 검색·다운로드 성공을 기다리고 현재 세션과 토큰의 티켓을 각각 만든다.
        for (int i=0;i<100 && reader.json("/api/search?query=accountboundary","GET",null,200).isEmpty();i++) Thread.sleep(50);
        var hits = reader.json("/api/search?query=accountboundary","GET",null,200); assertFalse(hits.isEmpty());
        for (var hit : hits) assertEquals(a,hit.get("source").asText());
        String ticket = reader.json("/api/download?path="+a+":note.txt","GET",null,200).get("url").asText();
        var own = reader.json("/api/tokens","POST",token("own",List.of(a)),200); String bearer = own.get("value").asText(), tokenId = own.get("id").asText();
        String tokenTicket = mapper.readTree(reader.call("/api/download?path="+a+":note.txt","GET",null,bearer).body()).get("url").asText();
        var another = admin.json("/api/tokens","POST",token("admin token",List.of()),200);
        reader.json("/api/tokens/"+another.get("id").asText(),"DELETE",null,403);
        assertEquals(1,reader.json("/api/tokens","GET",null,200).size());
        assertTrue(admin.json("/api/tokens","GET",null,200).toString().contains("reader"));
        assertEquals(List.of(a),storage.authenticate(SecurityConfiguration.hash(bearer)).sources());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> reader.call("/api/search?query=accountboundary", "GET", null, bearer).statusCode());
            var second = executor.submit(() -> reader.call("/api/search?query=accountboundary", "GET", null, bearer).statusCode());
            assertEquals(200,first.get(10,TimeUnit.SECONDS)); assertEquals(200,second.get(10,TimeUnit.SECONDS));
        }
        assertEquals(403,reader.call("/api/search?query=accountboundary&source="+b,"GET",null,bearer).statusCode());
        var mcp = Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name","search_documents","arguments",Map.of("query","accountboundary","source",b)));
        var denied = reader.call("/mcp","POST",mcp,bearer); assertEquals(200,denied.statusCode()); assertTrue(mapper.readTree(denied.body()).get("result").get("isError").asBoolean());
        // 실제 USER 승인 HTML에는 허용 소스만 보이며 승인은 소유자에 연결된다.
        String verifier = "a".repeat(43), redirect = "http://127.0.0.1:8104/callback";
        String client = oauth.register(Map.of("client_name","Example client","redirect_uris",List.of(redirect)),"127.0.0.1").get("client_id").toString();
        String query = "/oauth/authorize?client_id="+client+"&redirect_uri="+URLEncoder.encode(redirect,java.nio.charset.StandardCharsets.UTF_8)+"&response_type=code&code_challenge_method=S256&code_challenge="+OAuthService.challenge(verifier)+"&resource="+URLEncoder.encode(oauth.resource,java.nio.charset.StandardCharsets.UTF_8);
        var consent = reader.call(query,"GET",null,null); assertEquals(200,consent.statusCode()); assertTrue(consent.body().contains("value=\""+a+"\"")); assertFalse(consent.body().contains("value=\""+b+"\""));
        var context = org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(storage.user("reader"),null,storage.user("reader").getAuthorities()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        String code;
        try { code = oauth.approve(new OAuthService.Authorization(client,redirect,null,OAuthService.challenge(verifier),System.currentTimeMillis()),List.of(a)); }
        finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        String oauthBearer = oauth.token(Map.of("grant_type","authorization_code","code",code,"code_verifier",verifier,"client_id",client,"redirect_uri",redirect,"resource",oauth.resource)).get("access_token").toString();
        assertEquals(List.of(a),oauth.authenticate(SecurityConfiguration.hash(oauthBearer)).sources());
        admin.json("/api/users/"+id,"PUT",Map.of("sources",List.of()),200);
        assertEquals(401,reader.call("/api/account","GET",null,null).statusCode());
        assertEquals(403,new Browser().call(ticket.replace("http://127.0.0.1:8104",""),"GET",null,null).statusCode());
        assertEquals(403,new Browser().call(tokenTicket.replace("http://127.0.0.1:8104",""),"GET",null,null).statusCode());
        assertEquals(List.of(),storage.authenticate(SecurityConfiguration.hash(bearer)).sources());
        assertEquals(List.of(),oauth.authenticate(SecurityConfiguration.hash(oauthBearer)).sources());
        assertEquals(403,reader.call("/api/download?path="+a+":note.txt","GET",null,bearer).statusCode());
        admin.json("/api/users/"+id,"PUT",Map.of("sources",List.of(a),"active",false),200);
        assertNull(storage.authenticate(SecurityConfiguration.hash(bearer))); assertNull(oauth.authenticate(SecurityConfiguration.hash(oauthBearer)));
        assertEquals(401,reader.login("reader"));
        admin.json("/api/users/"+id,"PUT",Map.of("active",true),200); assertEquals(200,reader.login("reader"));
        // USER의 TOTP는 다른 계정과 분리되며 관리자 초기화는 대기 세션까지 무효화한다.
        var enrollment = reader.json("/api/totp/enroll","POST",Map.of("password","example-password-123"),200);
        var recovery = reader.json("/api/totp/enable","POST",Map.of("code",Totp.code(enrollment.get("secret").asText(),System.currentTimeMillis()/30000)),200);
        assertFalse(admin.json("/api/totp","GET",null,200).get("enabled").asBoolean());
        assertEquals(202,reader.login("reader"));
        reader.json("/api/login/totp","POST",Map.of("code",recovery.get("recoveryCodes").get(0).asText()),200); reader.auth();
        reader.json("/api/logout","POST",null,204); assertEquals(202,reader.login("reader"));
        admin.json("/api/users/"+id+"/totp-reset","POST",null,200);
        reader.json("/api/login/totp","POST",Map.of("code",recovery.get("recoveryCodes").get(1).asText()),403);
        assertEquals(200,reader.login("reader"));
        admin.json("/api/users/"+id+"/password","POST",Map.of("password","changed-password-123"),200);
        assertEquals(401,reader.call("/api/account","GET",null,null).statusCode());
        admin.json("/api/users/"+id,"DELETE",null,200); assertNull(storage.identity(tokenId));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM audit WHERE action LIKE 'token-create%' AND actor_name='reader' AND actor_id=?",Integer.class,id));
        // 사용자 역할 변경은 기존 세션을 폐기하며 다음 로그인부터 새 역할을 적용한다.
        admin.json("/api/users/"+empty,"PUT",Map.of("role","ADMIN"),200);
        assertEquals(401,noSources.call("/api/account","GET",null,null).statusCode()); assertEquals(200,noSources.login("empty"));
        noSources.json("/api/users","GET",null,200);
        admin.json("/api/users/"+empty,"PUT",Map.of("role","USER"),200);
        assertEquals(401,noSources.call("/api/users","GET",null,null).statusCode());
        // 관리자가 하나일 때 세 종류 변경을 모두 거부한다.
        admin.json("/api/users/1","PUT",Map.of("role","USER"),409); admin.json("/api/users/1","PUT",Map.of("active",false),409); admin.json("/api/users/1","DELETE",null,409);
        int second = create(admin,"second","ADMIN",List.of()); Browser secondAdmin = new Browser(); assertEquals(200,secondAdmin.login("second"));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var f1=executor.submit(() -> { start.await(); return admin.call("/api/users/1","PUT",Map.of("active",false),null).statusCode(); });
            var f2=executor.submit(() -> { start.await(); return secondAdmin.call("/api/users/"+second,"PUT",Map.of("role","USER"),null).statusCode(); });
            start.countDown(); assertEquals(Set.of(200,409),Set.of(f1.get(),f2.get()));
        }
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM administrator WHERE role='ADMIN' AND active=TRUE",Integer.class));
    }
}
