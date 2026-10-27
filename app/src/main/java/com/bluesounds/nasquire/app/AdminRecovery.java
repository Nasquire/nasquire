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
        String dataArgument = null, username = null;
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--data=") && dataArgument == null) dataArgument = args[i].substring(7);
            else if (args[i].startsWith("--username=") && username == null) username = args[i].substring(11);
            else throw new IllegalArgumentException("사용법: --recover-admin --data=/data [--username=admin]");
        }
        if (dataArgument == null) throw new IllegalArgumentException("기존 데이터 폴더를 --data=/data로 지정하세요");
        Path data = Path.of(dataArgument).toRealPath();
        if (!Files.isRegularFile(data.resolve("metadata.mv.db"))) throw new IllegalArgumentException("기존 데이터 폴더가 필요합니다");
        char[] password;
        if (System.console() != null && System.console().isTerminal()) {
            password = System.console().readPassword("새 관리자 비밀번호: ");
            char[] confirm = password == null ? null : System.console().readPassword("새 관리자 비밀번호 확인: ");
            try {
                if (confirm == null || !Arrays.equals(password, confirm)) {
                    if (password != null) Arrays.fill(password, '\0');
                    throw new IllegalArgumentException("비밀번호 확인이 일치하지 않습니다");
                }
            } finally { if (confirm != null) Arrays.fill(confirm, '\0'); }
        } else password = readLine(System.in);
        if (password == null) throw new IllegalArgumentException(PasswordPolicy.MESSAGE);
        try {
            String text = new String(password);
            PasswordPolicy.validate(text);
            recover(data, text, username);
        } finally { Arrays.fill(password, '\0'); }
        System.out.println("관리자 비밀번호를 재설정하고 TOTP와 복구 코드를 해제했습니다.");
    }
    /** 파이프 입력은 한 줄, 최대 72 UTF-8 바이트. 줄 끝 CR(CRLF)은 제거한다. 인자/환경/로그로 비밀번호를 전달하지 않는다. */
    static char[] readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(); int value;
        while ((value = in.read()) != -1 && value != '\n') {
            if (line.size() >= 73) throw new IllegalArgumentException(PasswordPolicy.MESSAGE); line.write(value);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length > 0 && bytes[bytes.length - 1] == '\r' ? bytes.length - 1 : bytes.length;
        if (length > 72) throw new IllegalArgumentException(PasswordPolicy.MESSAGE);
        char[] result = new String(bytes, 0, length, StandardCharsets.UTF_8).toCharArray();
        Arrays.fill(bytes, (byte)0); return result;
    }
    private static void recover(Path data, String password, String username) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:file:" + data.resolve("metadata") + ";IFEXISTS=TRUE;DB_CLOSE_ON_EXIT=FALSE", "sa", "")) {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            var storage = new Storage(db);
            if (!storage.configured()) throw new IllegalArgumentException("관리자 없음");
            var admins = db.queryForList("SELECT username FROM administrator WHERE role='ADMIN' AND deleted=FALSE ORDER BY id", String.class);
            if (username == null) {
                if (admins.size() != 1) throw new IllegalArgumentException("--username으로 관리자를 지정하세요: " + String.join(", ", admins));
                username = admins.getFirst();
            }
            if (!admins.contains(username)) throw new IllegalArgumentException("지정한 관리자 없음");
            int id = storage.account(username).id();
            connection.setAutoCommit(false);
            try {
                db.update("UPDATE administrator SET password_hash=?,totp_secret=NULL,totp_step=-1,active=TRUE,generation=generation+1 WHERE id=?", new BCryptPasswordEncoder(12).encode(password), id);
                db.update("DELETE FROM recovery_codes WHERE user_id=?", id);
                db.update("INSERT INTO audit(occurred_at,actor,action,status,actor_id,actor_name) VALUES (?,'local','admin-recovery',200,?,?)", System.currentTimeMillis(), id, username); connection.commit();
            } catch (Exception e) { connection.rollback(); throw e; }
        }
    }
}
