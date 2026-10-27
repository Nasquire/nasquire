package com.bluesounds.nasquire.app;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import tools.jackson.databind.*;

/** 자체 생성 키·8104 루프백 IdP. 외부 네트워크 없이 HTTP 교환과 실제 필터/세션을 검증한다. */
@SpringBootTest
@AutoConfigureMockMvc
class OidcIntegrationTest {
    static final Path TEMP;
    static final String ISSUER = "http://127.0.0.1:8104";
    static { try { TEMP = Files.createTempDirectory("nasquire-oidc-"); } catch (Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("nasquire.data", () -> TEMP.resolve("data").toString()); r.add("nasquire.public-url", () -> ISSUER);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired OidcSettings settings;
    @Autowired OidcService oidc;
    @Autowired Storage storage;
    @Autowired TotpService totp;
    @Autowired LoginAttempts attempts;
    @Autowired EmbeddingSettings secrets;
    @Autowired org.springframework.jdbc.core.JdbcTemplate db;
    HttpServer idp;
    RSAKey rsa, second;
    ECKey ec;
    String mode = "good", sub = "subject-1", email = "reader@example.com", nonce, challenge, currentKid = "rsa";
    boolean verified = true;
    List<String> groups = List.of();
    int tokenCalls, jwksCalls;
    @BeforeEach void fixture() throws Exception {
        rsa = new RSAKeyGenerator(2048).keyID("rsa").generate(); second = new RSAKeyGenerator(2048).keyID("rotated").generate(); ec = new ECKeyGenerator(Curve.P_256).keyID("ec").generate();
        idp = HttpServer.create(new InetSocketAddress("127.0.0.1",8104),0);
        idp.createContext("/.well-known/openid-configuration", exchange -> {
            if (mode.equals("slow")) { try { Thread.sleep(5500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } }
            if (mode.equals("redirect")) { exchange.getResponseHeaders().set("Location", ISSUER + "/jwks"); exchange.sendResponseHeaders(302,-1); exchange.close(); return; }
            if (mode.equals("oversize")) { reply(exchange, Map.of("padding", "x".repeat(262145))); return; }
            reply(exchange, Map.of("issuer", mode.equals("discovery-issuer") ? "https://idp.example.com" : ISSUER,
                "authorization_endpoint", ISSUER + "/authorize", "token_endpoint", ISSUER + "/token", "jwks_uri", ISSUER + "/jwks", "response_types_supported", List.of("code"), "code_challenge_methods_supported", List.of("S256"), "token_endpoint_auth_methods_supported", List.of(mode.equals("post-auth") ? "client_secret_post" : "client_secret_basic"))); });
        idp.createContext("/jwks", exchange -> { jwksCalls++; reply(exchange, new JWKSet(List.of((currentKid.equals("rotated") ? second : rsa).toPublicJWK(), ec.toPublicJWK())).toJSONObject()); });
        idp.createContext("/token", exchange -> {
            tokenCalls++;
            try {
                var form = params(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                assertEquals(challenge, OAuthService.challenge(form.get("code_verifier"))); assertEquals(ISSUER + "/oidc/callback", form.get("redirect_uri"));
                if (mode.equals("post-auth")) { assertNull(exchange.getRequestHeaders().getFirst("Authorization")); assertEquals("client",form.get("client_id")); assertEquals("fixture-secret",form.get("client_secret")); }
                else assertEquals("Basic " + Base64.getEncoder().encodeToString("client:fixture-secret".getBytes(StandardCharsets.UTF_8)), exchange.getRequestHeaders().getFirst("Authorization"));
                reply(exchange, Map.of("id_token", token(), "access_token", "never-used"));
            } catch (Exception e) { exchange.sendResponseHeaders(500,-1); exchange.close(); }
        });
        idp.start();
    }
    @AfterEach void stop() { if (idp != null) idp.stop(0); }
    void reply(com.sun.net.httpserver.HttpExchange e, Object body) throws java.io.IOException {
        byte[] bytes = json.writeValueAsBytes(body); e.getResponseHeaders().set("Content-Type","application/json"); e.sendResponseHeaders(200,bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    static Map<String,String> params(String query) {
        Map<String,String> values = new HashMap<>(); for (String item : query.split("&")) { var p = item.split("=",2); values.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8)); } return values;
    }
    String token() throws Exception {
        long now = System.currentTimeMillis();
        var b = new JWTClaimsSet.Builder().issuer(mode.equals("iss") ? "https://idp.example.com" : ISSUER).subject(sub).audience(mode.equals("aud") ? "other" : "client")
                .expirationTime(new Date(now + (mode.equals("exp") ? -60000 : 60000))).issueTime(new Date(now + (mode.equals("iat") ? 60000 : 0)))
                .claim("nonce",mode.equals("nonce") ? "wrong" : nonce).claim("email",email).claim("email_verified",verified).claim("groups",groups);
        if (mode.equals("nbf")) b.notBeforeTime(new Date(now+60000));
        if (mode.equals("azp")) b.audience(List.of("client","other"));
        if (mode.equals("none")) return new PlainJWT(b.build()).serialize();
        String alg = mode.equals("hs") ? "HS256" : mode.equals("ec") ? "ES256" : "RS256";
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.parse(alg)).keyID(mode.equals("ec") ? "ec" : currentKid).build(), b.build());
        if (mode.equals("hs")) jwt.sign(new MACSigner(new byte[32]));
        else if (mode.equals("ec")) jwt.sign(new ECDSASigner(ec));
        else jwt.sign(new RSASSASigner(mode.equals("signature") ? second : currentKid.equals("rotated") ? second : rsa));
        return jwt.serialize();
    }
    MvcResult admin(String path, String method, Object body) throws Exception {
        var req = switch (method) { case "PUT" -> put(path); case "POST" -> post(path); case "DELETE" -> delete(path); default -> get(path); };
        req.with(user("admin").roles("ADMIN")).with(csrf().asHeader());
        if (body != null) req.contentType("application/json").content(json.writeValueAsBytes(body));
        return mvc.perform(req).andReturn();
    }
    int create(String name, String role) throws Exception {
        var res = admin("/api/users","POST",Map.of("username",name,"password","example-password-123","role",role)); assertEquals(200,res.getResponse().getStatus()); return json.readTree(res.getResponse().getContentAsString()).path("id").asInt();
    }
    void match(int id, String value) throws Exception { assertEquals(200,admin("/api/users/"+id+"/oidc","PUT",Map.of("match",value)).getResponse().getStatus()); }
    record Flow(MockHttpSession session, String state) { }
    Flow start() throws Exception {
        var res = mvc.perform(get("/oidc/login")).andReturn(); assertEquals(302,res.getResponse().getStatus(),res.getResponse().getContentAsString());
        var q = params(URI.create(res.getResponse().getRedirectedUrl()).getRawQuery()); nonce = q.get("nonce"); challenge = q.get("code_challenge"); assertEquals("S256",q.get("code_challenge_method"));
        return new Flow((MockHttpSession)res.getRequest().getSession(false), q.get("state"));
    }
    MvcResult finish(Flow f, String state) throws Exception {
        attempts.reset("oidc:", "127.0.0.1", "127.0.0.1");
        return mvc.perform(get("/oidc/callback").session(f.session).param("code","fixture-code").param("state",state)).andReturn();
    }
    void denied() throws Exception { var f = start(); var r = finish(f,f.state); assertEquals(401,r.getResponse().getStatus(),mode); assertFalse(r.getResponse().getContentAsString().contains(email));
        assertNull(attempts.acquire("oidc:","127.0.0.1","127.0.0.1",System.currentTimeMillis()));
    }
    MockHttpSession accepted() throws Exception { var f = start(); var r = finish(f,f.state); assertEquals(302,r.getResponse().getStatus(),mode); assertNotEquals(f.session.getId(),r.getRequest().getSession(false).getId()); return (MockHttpSession)r.getRequest().getSession(false); }
    @Test void completeFlowAndSecurityBoundaries() throws Exception {
        storage.setup("admin","example-password-123");
        assertFalse(settings.enabled());
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        for (org.junit.jupiter.api.function.Executable call : List.<org.junit.jupiter.api.function.Executable>of(
                () -> storage.currentId(), () -> storage.admin(), () -> storage.sessionIdentity(),
                () -> storage.requireTokenOwner("missing"), () -> storage.tokens(), () -> storage.deleteInactiveTokens(null),
                () -> storage.generation(), () -> totp.enabled(), () -> totp.verify("123456", true),
                () -> totp.disable("123456"), () -> totp.enable("unused", "123456")))
            assertThrows(org.springframework.security.access.AccessDeniedException.class, call);
        storage.audit("anonymous", "sessionless-audit", 401);
        assertNull(db.queryForObject("SELECT actor_id FROM audit WHERE action='sessionless-audit'",Integer.class));
        try (var ignored = new AccountTestContext("unknown")) {
            assertThrows(org.springframework.security.access.AccessDeniedException.class, storage::currentId);
            storage.audit("anonymous", "unknown-audit", 401);
            assertNull(db.queryForObject("SELECT actor_id FROM audit WHERE action='unknown-audit'",Integer.class));
        }
        try (var ignored = new AccountTestContext("admin")) {
            var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
            context.setAuthentication(new org.springframework.security.authentication.AnonymousAuthenticationToken("fixture", "admin",
                    List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, storage::currentId);
            storage.audit("anonymous", "anonymous-principal-audit", 401);
            assertNull(db.queryForObject("SELECT actor_id FROM audit WHERE action='anonymous-principal-audit'",Integer.class));
        }
        assertEquals(401,mvc.perform(get("/api/oidc")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(put("/api/oidc").with(user("admin").roles("ADMIN")).contentType("application/json").content("{}")).andReturn().getResponse().getStatus());
        assertEquals(403,mvc.perform(get("/api/oidc").with(user("reader").roles("USER"))).andReturn().getResponse().getStatus());
        settings.save(Map.of("issuer",ISSUER,"clientId","client","clientSecret","fixture-secret","enabled","true"));
        String stored = Files.readString(TEMP.resolve("data/oidc.properties")); assertFalse(stored.contains("fixture-secret")); assertTrue(stored.contains("v2\\:"));
        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(TEMP.resolve("data/oidc.properties"))));
        assertFalse(json.writeValueAsString(settings.view()).contains("fixture-secret")); assertEquals(true,settings.view().get("hasSecret"));
        attempts.reset("oidc:","127.0.0.1","127.0.0.1");
        for (int i=0; i<6; i++) {
            assertEquals(401,mvc.perform(get("/oidc/callback").param("code","unused").param("state","unused")).andReturn().getResponse().getStatus());
            var cancelled = start();
            assertEquals(401,mvc.perform(get("/oidc/callback").session(cancelled.session).param("error","access_denied").param("state",cancelled.state)).andReturn().getResponse().getStatus());
            assertNull(cancelled.session.getAttribute("oidc.pending"));
        }
        try (var ticket = attempts.acquire("oidc:","127.0.0.1","127.0.0.1",System.currentTimeMillis())) { assertNotNull(ticket); }
        int reader = create("reader","USER"); match(reader,email);
        var session = accepted(); assertNotNull(session.getAttribute("SPRING_SECURITY_CONTEXT")); assertNotNull(session.getAttribute("admin.generation"));
        assertEquals(reader,session.getAttribute("account.id"));
        assertEquals(401,mvc.perform(post("/mcp").servletPath("/mcp").session(session).with(csrf().asHeader())).andReturn().getResponse().getStatus());
        mode = "ec"; accepted(); mode = "post-auth"; settings.save(Map.of("buttonLabel","Example login")); accepted(); mode = "good"; settings.save(Map.of("buttonLabel","OpenID Connect"));
        assertTrue(new OidcSettings(TEMP.resolve("data"),secrets,ISSUER).enabled());
        var outdated = start(); settings.save(Map.of("buttonLabel","OpenID Connect")); assertEquals(401,finish(outdated,outdated.state).getResponse().getStatus());
        var f = start(); int calls = tokenCalls; assertEquals(401,finish(f,"wrong").getResponse().getStatus()); assertEquals(calls,tokenCalls);
        assertEquals(401,finish(f,f.state).getResponse().getStatus()); // 불일치 뒤에도 소모
        f = start(); var original = (OidcService.Pending)f.session.getAttribute("oidc.pending");
        f.session.setAttribute("oidc.pending",new OidcService.Pending(original.state(),original.nonce(),original.verifier(),System.currentTimeMillis()-301000,original.revision()));
        assertEquals(401,finish(f,f.state).getResponse().getStatus());
        f = start(); assertEquals(302,finish(f,f.state).getResponse().getStatus());
        assertEquals(401,mvc.perform(get("/oidc/callback").param("state",f.state).param("code","fixture-code")).andReturn().getResponse().getStatus());
        for (String bad : List.of("nonce","signature","none","hs","iss","aud","exp","iat","nbf","azp")) { mode = bad; denied(); } mode = "good";
        int fetched = jwksCalls; currentKid = "rotated"; accepted(); assertEquals(fetched+1,jwksCalls);
        // 최초 연결에는 검증된 이메일이 필요하다. 이미 고정된 issuer/sub에는 이메일 변경을 신뢰하지 않는다.
        sub = "unverified"; email = "fresh@example.com"; int fresh = create("fresh","USER"); match(fresh,email); verified = false; denied(); verified = true;
        sub = "unlinked"; email = "unlinked@example.com"; denied();
        settings.save(Map.of("autoSignup","true")); var auto = accepted(); int autoId = (Integer)auto.getAttribute("account.id"); assertEquals("USER",storage.account(autoId).role()); assertTrue(storage.allowed(autoId).isEmpty());
        settings.save(Map.of("autoSignup","false"));
        sub = "attacker"; email = "reader@example.com"; denied(); settings.save(Map.of("autoSignup","true")); denied(); settings.save(Map.of("autoSignup","false"));
        sub = "subject-1"; db.update("UPDATE administrator SET active=FALSE WHERE id=?",reader); denied(); db.update("UPDATE administrator SET active=TRUE WHERE id=?",reader);
        settings.save(Map.of("groupClaim","groups","adminGroup","admins")); groups = List.of("admins"); accepted(); assertEquals("ADMIN",storage.account(reader).role());
        groups = List.of(); accepted(); assertEquals("USER",storage.account(reader).role());
        match(1,"admin@example.com"); sub = "admin-sub"; email = "admin@example.com"; denied(); assertEquals("ADMIN",storage.account(1).role());
        // 로컬 TOTP는 OIDC 뒤에도 권한 없는 기존 대기 흐름을 사용한다.
        settings.save(Map.of("adminGroup","admins")); groups = List.of("admins"); sub = "subject-1"; email = "reader@example.com";
        String totpSecret = Totp.secret();
        db.update("UPDATE administrator SET totp_secret=? WHERE id=?",secrets.encrypt("totp",totpSecret),reader);
        long beforeTotp = storage.account(reader).generation();
        var failedPending = accepted();
        assertEquals("USER",storage.account(reader).role()); assertEquals(beforeTotp,storage.account(reader).generation());
        attempts.reset("","127.0.0.1","reader");
        assertEquals(401,mvc.perform(post("/api/login/totp").session(failedPending).with(csrf().asHeader()).contentType("application/json").content("{\"code\":\"invalid\"}")).andReturn().getResponse().getStatus());
        assertEquals("USER",storage.account(reader).role()); assertEquals(beforeTotp,storage.account(reader).generation());
        failedPending.setAttribute("totp.pending",new TotpController.Pending("reader",System.currentTimeMillis()-301000,new java.util.concurrent.atomic.AtomicInteger()));
        assertEquals(401,mvc.perform(post("/api/login/totp").session(failedPending).with(csrf().asHeader()).contentType("application/json").content("{\"code\":\"123456\"}")).andReturn().getResponse().getStatus());
        assertNull(failedPending.getAttribute("oidc.deferred"));
        assertEquals("USER",storage.account(reader).role()); assertEquals(beforeTotp,storage.account(reader).generation());
        attempts.reset("","127.0.0.1","reader");
        var pending = accepted(); assertNotNull(pending.getAttribute("totp.pending")); assertNull(pending.getAttribute("SPRING_SECURITY_CONTEXT"));
        var totpResult = mvc.perform(post("/api/login/totp").session(pending).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("code",Totp.code(totpSecret,System.currentTimeMillis()/30000))))).andReturn();
        assertEquals(200,totpResult.getResponse().getStatus()); assertNotNull(pending.getAttribute("SPRING_SECURITY_CONTEXT"));
        assertEquals("ADMIN",storage.account(reader).role()); assertEquals(beforeTotp+1,storage.account(reader).generation());
        assertEquals(beforeTotp+1,pending.getAttribute("admin.generation"));
        // 마지막 ADMIN 보호는 TOTP 최종 승인에서도 적용한다.
        db.update("UPDATE administrator SET active=FALSE WHERE id=1");
        db.update("UPDATE administrator SET totp_step=-1 WHERE id=?",reader); groups = List.of();
        var lastAdmin = accepted(); long lastGeneration = storage.account(reader).generation();
        assertEquals(401,mvc.perform(post("/api/login/totp").session(lastAdmin).with(csrf().asHeader()).contentType("application/json").content(json.writeValueAsBytes(Map.of("code",Totp.code(totpSecret,System.currentTimeMillis()/30000))))).andReturn().getResponse().getStatus());
        assertEquals("ADMIN",storage.account(reader).role()); assertEquals(lastGeneration,storage.account(reader).generation());
        db.update("UPDATE administrator SET active=TRUE WHERE id=1");
        settings.save(Map.of("adminGroup",""));
        // sub 기준 연결 및 관리자 해제 후 자동 재연결 금지.
        settings.save(Map.of("linkBy","sub")); int subject = create("subject","USER"); match(subject,"exact-sub"); sub = "exact-sub"; accepted();
        assertEquals(200,admin("/api/users/"+subject+"/oidc","DELETE",null).getResponse().getStatus()); denied();
        // 계정 묘비는 유지하되 외부 연결은 해제하여 같은 subject를 새 계정에 연결한다.
        match(subject,"exact-sub"); accepted();
        assertEquals(200,admin("/api/users/"+subject,"DELETE",null).getResponse().getStatus());
        assertNull(storage.account(subject));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM oidc_links WHERE user_id=?",Integer.class,subject));
        assertNull(db.queryForObject("SELECT oidc_match FROM administrator WHERE id=?",String.class,subject));
        int replacement = create("replacement","USER"); match(replacement,"exact-sub");
        assertEquals(replacement,accepted().getAttribute("account.id"));
        assertTrue(replacement > subject);
        settings.save(Map.of("issuer","https://idp.example.com")); assertEquals(false,settings.view().get("hasSecret")); assertFalse(settings.enabled());
        assertThrows(IllegalArgumentException.class, () -> settings.save(Map.of("issuer","http://idp.example.com","enabled","true","clientSecret","fixture-secret")));
        settings.save(Map.of("issuer",ISSUER,"clientSecret","fixture-secret","enabled","true"));
        assertFalse(new OidcSettings(TEMP.resolve("data"),secrets,"http://app.example.com").enabled());
        Path key = TEMP.resolve("data/embedding.key"); byte[] backup = Files.readAllBytes(key); Files.delete(key);
        assertFalse(settings.enabled()); assertThrows(EmbeddingSettings.KeyUnavailable.class, () -> secrets.encrypt("embedding","new-key")); assertFalse(Files.exists(key));
        Files.write(key,new byte[32]); assertFalse(settings.enabled()); Files.write(key,backup); assertTrue(settings.enabled());
        assertEquals(200,admin("/api/oidc/secret","DELETE",null).getResponse().getStatus()); assertFalse(settings.enabled()); assertEquals(false,settings.view().get("hasSecret"));
        for (String bad : List.of("discovery-issuer","redirect","oversize")) { mode = bad; assertEquals(400,admin("/api/oidc/test","POST",null).getResponse().getStatus()); }
        mode = "slow"; long started = System.nanoTime(); assertEquals(400,admin("/api/oidc/test","POST",null).getResponse().getStatus());
        assertTrue(System.nanoTime()-started < java.time.Duration.ofSeconds(8).toNanos());
        assertTrue(db.queryForObject("SELECT COUNT(*) FROM audit WHERE action='oidc-login' AND actor_id=?",Integer.class,reader) > 0);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM audit WHERE action LIKE '%fixture-code%' OR action LIKE '%fixture-secret%' OR action LIKE '%never-used%'",Integer.class));
    }
}
