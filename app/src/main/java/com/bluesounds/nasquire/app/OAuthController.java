package com.bluesounds.nasquire.app;

import jakarta.servlet.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
class OAuthController {
    private final OAuthService oauth;
    private final Storage storage;
    OAuthController(OAuthService oauth, Storage storage) { this.oauth = oauth; this.storage = storage; }
    @ExceptionHandler(OAuthService.Error.class)
    ResponseEntity<Map<String, String>> error(OAuthService.Error error) {
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).body(Map.of("error", error.error));
    }
    @GetMapping({"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/mcp"})
    Map<String, Object> resource() {
        oauth.requireEnabled();
        return Map.of("resource", oauth.resource, "authorization_servers", List.of(oauth.issuer), "scopes_supported", List.of(OAuthService.SCOPE), "bearer_methods_supported", List.of("header"));
    }
    @GetMapping("/.well-known/oauth-authorization-server") Map<String, Object> metadata() {
        oauth.requireEnabled();
        var result = new LinkedHashMap<String, Object>();
        result.put("issuer", oauth.issuer);
        for (var endpoint : Map.of("authorization_endpoint", "authorize", "token_endpoint", "token", "registration_endpoint", "register", "revocation_endpoint", "revoke").entrySet()) result.put(endpoint.getKey(), oauth.issuer + "/oauth/" + endpoint.getValue());
        result.put("code_challenge_methods_supported", List.of("S256")); result.put("response_types_supported", List.of("code"));
        result.put("grant_types_supported", List.of("authorization_code", "refresh_token")); result.put("token_endpoint_auth_methods_supported", List.of("none"));
        result.put("revocation_endpoint_auth_methods_supported", List.of("none")); result.put("scopes_supported", List.of(OAuthService.SCOPE));
        result.put("authorization_response_iss_parameter_supported", true); return result;
    }
    @PostMapping(value = "/oauth/register", consumes = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, Object> input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(oauth.register(input));
    }
    static Map<String, String> parameters(HttpServletRequest request) {
        var result = new HashMap<String, String>();
        request.getParameterMap().forEach((key, values) -> {
            if (key.length() > 100 || values.length != 1 || values[0].length() > 4096) throw OAuthService.invalid("invalid_request");
            result.put(key, values[0]);
        });
        if (result.size() > 20) throw OAuthService.invalid("invalid_request"); return result;
    }
    @PostMapping(value = "/oauth/token", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Map<String, Object>> token(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(oauth.token(parameters(request)));
    }
    @PostMapping(value = "/oauth/revoke", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> revoke(HttpServletRequest request) {
        var p = parameters(request); oauth.revoke(p.get("client_id"), p.get("token")); return ResponseEntity.ok().cacheControl(CacheControl.noStore()).build();
    }
    private static boolean admin(Authentication auth) { return auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")); }
    @GetMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    String authorize(HttpServletRequest request, HttpServletResponse response, Authentication auth, CsrfToken csrf) {
        var a = oauth.validate(parameters(request));
        secure(response, a.redirect());
        if (!admin(auth)) return page("Administrator login / 관리자 로그인", """
                <p>Sign in to approve this MCP client / MCP 클라이언트 승인을 위해 로그인하세요.</p>
                <form id="login"><label>Username / 사용자명 <input name="username" autocomplete="username" required></label>
                <label>Password / 비밀번호 <input name="password" type="password" autocomplete="current-password" required></label><button>Sign in / 로그인</button></form>
                <form id="totp" hidden><label>TOTP / 복구 코드 <input name="code" autocomplete="one-time-code" required></label><button>Verify / 확인</button></form><p id="error" role="alert"></p>
                <script src="/oauth/login.js"></script>
                """);
        return consentPage(request, a, csrf, "");
    }
    private String consentPage(HttpServletRequest request, OAuthService.Authorization a, CsrfToken csrf, String error) {
        String nonce = OAuthService.random();
        // 세션마다 승인 대기는 하나만 두어 임의 클라이언트 요청의 메모리 사용을 제한한다.
        request.getSession().setAttribute("oauth.pending", a);
        request.getSession().setAttribute("oauth.nonce", nonce);
        StringBuilder body = new StringBuilder("<p>Client / 클라이언트: <strong>" + esc(oauth.client(a.client()).get("NAME").toString()) + "</strong></p><p>Redirect host / 이동 호스트: " + esc(URI.create(a.redirect()).getHost()) + "</p><p>Scope / 요청 범위: mcp:read (Read only / 읽기 전용)</p><form method=\"post\" action=\"/oauth/authorize\">");
        body.append(hidden(csrf.getParameterName(), csrf.getToken())).append(hidden("nonce", nonce));
        if (!error.isEmpty()) body.append("<p role=\"alert\">").append(esc(error)).append("</p>");
        var sources = storage.sources();
        for (var s : sources) body.append("<label><input type=\"checkbox\" name=\"sources\" value=\"").append(esc(s.id())).append("\">").append(esc(s.name())).append("</label>");
        if (sources.isEmpty()) body.append("<p>No sources available. Add a source first / 소스를 먼저 등록하세요.</p>");
        else body.append("<button name=\"decision\" value=\"approve\">Allow / 승인</button>");
        body.append("<button name=\"decision\" value=\"deny\">Deny / 거부</button></form>");
        return page("Approve MCP access / MCP 접근 승인", body.toString());
    }
    @PostMapping(value = "/oauth/authorize", produces = MediaType.TEXT_HTML_VALUE)
    String consent(HttpServletRequest request, HttpServletResponse response, Authentication auth, CsrfToken csrf) {
        oauth.requireEnabled(); secure(response);
        if (!admin(auth)) throw new OAuthService.Error("access_denied", 401);
        var session = request.getSession(false);
        if (session == null) throw OAuthService.invalid("invalid_request");
        OAuthService.Authorization a;
        synchronized (session) {
            a = (OAuthService.Authorization)session.getAttribute("oauth.pending");
            String nonce = (String)session.getAttribute("oauth.nonce");
            if (a == null || nonce == null || !nonce.equals(request.getParameter("nonce")) || System.currentTimeMillis() - a.created() > 600000) throw OAuthService.invalid("invalid_request");
            session.removeAttribute("oauth.pending"); session.removeAttribute("oauth.nonce");
        }
        secure(response, a.redirect());
        String decision = request.getParameter("decision"), result;
        if ("approve".equals(decision)) {
            String[] sources = request.getParameterValues("sources");
            if (sources == null || sources.length == 0) {
                var pending = new OAuthService.Authorization(a.client(), a.redirect(), a.state(), a.challenge(), oauth.now());
                return consentPage(request, pending, csrf, "Select at least one source / 소스를 하나 이상 선택하세요.");
            }
            result = "code=" + enc(oauth.approve(a, Arrays.asList(sources)));
        } else if ("deny".equals(decision)) { oauth.audit("deny", 200); result = "error=access_denied"; }
        else throw OAuthService.invalid("invalid_request");
        response.setStatus(302);
        response.setHeader("Location", a.redirect() + (a.redirect().contains("?") ? "&" : "?") + result + "&state=" + enc(a.state()) + "&iss=" + enc(oauth.issuer));
        return null;
    }
    @GetMapping(value = "/oauth/login.js", produces = "text/javascript") String loginScript() {
        oauth.requireEnabled();
        return """
                const login = document.getElementById('login'), totp = document.getElementById('totp');
                async function submit(form, url, json) {
                  const auth = await (await fetch('/api/auth')).json();
                  const data = new FormData(form), headers = {[auth.csrfHeader]: auth.csrfToken};
                  headers['Content-Type'] = json ? 'application/json' : 'application/x-www-form-urlencoded';
                  const response = await fetch(url, {method:'POST', headers, body:json ? JSON.stringify(Object.fromEntries(data)) : new URLSearchParams(data)});
                  await response.text();
                  form.reset();
                  if (response.status === 202) { login.hidden=true; totp.hidden=false; }
                  else if (response.ok) location.reload();
                  else document.getElementById('error').textContent='Login failed / 로그인 실패 (' + response.status + ')';
                }
                login.onsubmit=e=>{e.preventDefault();submit(login,'/api/login',false).catch(()=>document.getElementById('error').textContent='Request failed / 요청 실패');};
                totp.onsubmit=e=>{e.preventDefault();submit(totp,'/api/login/totp',true).catch(()=>document.getElementById('error').textContent='Request failed / 요청 실패');};
                """;
    }
    @GetMapping("/api/oauth") Map<String, Object> view() { return oauth.view(); }
    @PostMapping("/api/oauth/revoke") void adminRevoke(@RequestBody Map<String, String> p) { oauth.adminRevoke(p.get("kind"), p.get("id")); }
    static void secure(HttpServletResponse response) {
        response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        response.setHeader("Referrer-Policy", "no-referrer"); response.setHeader("Cache-Control", "no-store");
    }
    static void secure(HttpServletResponse response, String redirect) {
        secure(response);
        // Chromium은 form-action을 POST 뒤 302 이동에도 적용한다. 검증한 콜백 origin만 허용한다.
        URI uri = URI.create(redirect);
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        response.setHeader("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self' " + origin);
    }
    static String page(String title, String content) {
        return "<!doctype html><html lang=\"en\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>Nasquire</title><link rel=\"stylesheet\" href=\"/oauth/style.css\"><main><a href=\"/\">Nasquire</a><h1>" + title + "</h1>" + content + "</main></html>";
    }
    @GetMapping(value = "/oauth/style.css", produces = "text/css") String style() {
        oauth.requireEnabled();
        return "body{font:16px system-ui,sans-serif;background:#f5f5f5;color:#20232a}main{max-width:640px;margin:5vh auto;background:white;padding:2rem;border-radius:12px}label{display:block;margin:1rem 0}a{color:#1e3a8a}:focus-visible{outline:2px solid #3463d6;outline-offset:2px}input,button{font:inherit;padding:.5rem;max-width:100%;box-sizing:border-box}button{margin:.5rem;cursor:pointer}@media(prefers-color-scheme:dark){body{background:#15171b;color:#eee}main{background:#24272e}a{color:#a9c3ff}}";
    }
    static String hidden(String key, String value) { return "<input type=\"hidden\" name=\"" + esc(key) + "\" value=\"" + esc(value) + "\">"; }
    static String esc(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;"); }
    static String enc(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
