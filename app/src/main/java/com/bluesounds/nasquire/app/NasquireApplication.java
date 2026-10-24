package com.bluesounds.nasquire.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NasquireApplication {
    public static void main(String[] args) throws Exception {
        // OCR 자식 프로세스: 첫 인자로만 인식하고 Spring·DB·소스 없이 작업 폴더의 입력만 렌더링·디코딩한 뒤 종료한다.
        if (args.length > 0 && args[0].equals("--nasquire.mode=ocr-worker")) {
            System.exit(com.bluesounds.nasquire.core.Providers.full().ocrWorker(java.util.Arrays.copyOfRange(args, 1, args.length)));
        }
        if (java.util.Arrays.asList(args).contains("--recover-admin")) { AdminRecovery.run(args); return; }
        // ML 전용 모드는 Spring 웹 컨텍스트(관리 화면·소스·검색·MCP·DB)를 만들지 않는다. full 빌드가 없으면 시작을 거부한다.
        if ("ml".equals(mode(args, System.getenv()))) { com.bluesounds.nasquire.core.Providers.full().ml(args, System.getenv()); return; }
        SpringApplication.run(NasquireApplication.class, args);
    }
    static String mode(String[] args, java.util.Map<String, String> environment) {
        for (String arg : args) if (arg.startsWith("--nasquire.mode=")) return arg.substring("--nasquire.mode=".length()).trim();
        String value = System.getProperty("nasquire.mode");
        return value != null ? value.trim() : environment.getOrDefault("NASQUIRE_MODE", "search").trim();
    }
}
