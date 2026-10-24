package com.bluesounds.nasquire.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.Arrays;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** 웹 컨텍스트를 만들지 않는 오프라인 복구 명령. H2 파일 잠금으로 동시 서버 사용을 거부한다. */
final class AdminRecovery {
    static void run(String[] args) throws Exception {
        if (args.length != 2 || !args[1].startsWith("--data=")) throw new IllegalArgumentException("사용법: --recover-admin --data=/data");
        Path data = Path.of(args[1].substring(7)).toRealPath();
        if (!Files.isRegularFile(data.resolve("metadata.mv.db"))) throw new IllegalArgumentException("기존 데이터 폴더가 필요합니다");
        char[] password;
        if (System.console() != null) password = System.console().readPassword("새 관리자 비밀번호: ");
        else {
            // 파이프 입력은 한 줄, 최대 72 UTF-8 바이트. 인자/환경/로그로 비밀번호를 전달하지 않는다.
            ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
            while ((value = System.in.read()) != -1 && value != '\n') {
                if (line.size() >= 72) throw new IllegalArgumentException("비밀번호 상한 초과"); line.write(value);
            }
            password = line.toString(StandardCharsets.UTF_8).toCharArray();
        }
        if (password == null) throw new IllegalArgumentException("비밀번호 필요");
        try {
            String text = new String(password);
            if (text.length() < 12 || text.getBytes(StandardCharsets.UTF_8).length > 72) throw new IllegalArgumentException("비밀번호 12자 이상/UTF-8 72바이트 이하 필요");
            recover(data, text);
        } finally { Arrays.fill(password, '\0'); }
        System.out.println("관리자 비밀번호를 재설정하고 TOTP와 복구 코드를 해제했습니다.");
    }
    private static void recover(Path data, String password) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + data.resolve("metadata") + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var storage = new Storage(db);
            if (!storage.configured()) throw new IllegalArgumentException("관리자 없음");
            connection.setAutoCommit(false);
            try {
                db.update("UPDATE administrator SET password_hash=?,totp_secret=NULL,totp_step=-1 WHERE id=1", new BCryptPasswordEncoder(12).encode(password));
                db.update("DELETE FROM recovery_codes"); storage.audit("local", "admin-recovery", 200); connection.commit();
            } catch (Exception e) { connection.rollback(); throw e; }
        }
    }
}
