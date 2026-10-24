package com.bluesounds.nasquire.app;

import java.util.*;
import org.springframework.stereotype.Component;

/** 실패만 누적하고 요청 스레드를 재우지 않고 다음 허용 시각을 적용한다. */
@Component
class LoginAttempts {
    record Failure(int count, long retryAt) { }
    private final Map<String, Failure> failures = new LinkedHashMap<>(16, .75f, true);
    private String account(String account) { return "account:" + SecurityConfiguration.hash(account == null ? "" : account); }
    synchronized boolean allowed(String ip, String account, long now) {
        failures.entrySet().removeIf(e -> now - e.getValue().retryAt > 900000);
        return allowedKey("ip:" + ip, now) && allowedKey(account(account), now);
    }
    private boolean allowedKey(String key, long now) { var value = failures.get(key); return value == null || value.retryAt <= now; }
    synchronized void failed(String ip, String account, long now) {
        for (String key : List.of("ip:" + ip, account(account))) {
            var previous = failures.get(key);
            int count = previous == null || previous.count >= 5 ? 1 : previous.count + 1;
            if (!failures.containsKey(key) && failures.size() >= 4096) failures.remove(failures.keySet().iterator().next());
            failures.put(key, new Failure(count, now + (count >= 5 ? 900000 : 1000L << (count - 1))));
        }
    }
    synchronized void succeeded(String ip, String account) { failures.remove("ip:" + ip); failures.remove(account(account)); }
}
