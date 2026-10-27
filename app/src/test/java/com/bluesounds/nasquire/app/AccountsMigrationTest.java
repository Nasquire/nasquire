package com.bluesounds.nasquire.app;

import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

class AccountsMigrationTest {
    @Test void legacyAdministratorCredentialsTokensAndGrantsRemainOwnedByFirstAdmin() throws Exception {
        try (var ignored = new AccountTestContext("legacy"); var connection = DriverManager.getConnection("jdbc:h2:mem:"+UUID.randomUUID(),"sa","")) {
            var data = new SingleConnectionDataSource(connection,true); var db = new JdbcTemplate(data);
            db.execute("CREATE TABLE administrator(id INT PRIMARY KEY CHECK(id=1),username VARCHAR(100) NOT NULL,password_hash VARCHAR(100) NOT NULL,totp_secret VARCHAR(512),totp_step BIGINT DEFAULT -1 NOT NULL,generation BIGINT DEFAULT 0 NOT NULL)");
            db.execute("ALTER TABLE administrator ADD CONSTRAINT username_nonempty CHECK (LENGTH(username)>0)");
            db.update("INSERT INTO administrator VALUES(1,'legacy','unchanged-hash','unchanged-secret',123,9)");
            db.execute("CREATE TABLE recovery_codes(code_hash VARCHAR(64) PRIMARY KEY)"); db.update("INSERT INTO recovery_codes VALUES('unchanged-code')");
            db.execute("CREATE TABLE tokens(id VARCHAR(36) PRIMARY KEY,name VARCHAR(200) NOT NULL,token_hash VARCHAR(64) UNIQUE NOT NULL,expires_at BIGINT NOT NULL,sources CLOB NOT NULL,revoked BOOLEAN DEFAULT FALSE)");
            db.update("INSERT INTO tokens VALUES('legacy-token','client',?,?,'',FALSE)",SecurityConfiguration.hash("legacy-value"),System.currentTimeMillis()+600000);
            var storage = new Storage(db);
            assertEquals("ADMIN",storage.account(1).role()); assertEquals(9,storage.generation());
            var preserved = db.queryForMap("SELECT password_hash,totp_secret,totp_step FROM administrator WHERE id=1");
            assertEquals("unchanged-hash",preserved.get("PASSWORD_HASH")); assertEquals("unchanged-secret",preserved.get("TOTP_SECRET")); assertEquals(123L,preserved.get("TOTP_STEP"));
            assertEquals(1,db.queryForObject("SELECT user_id FROM recovery_codes WHERE code_hash='unchanged-code'",Integer.class));
            assertEquals(1,storage.tokens().getFirst().ownerId()); assertNotNull(storage.authenticate(SecurityConfiguration.hash("legacy-value")));
            var tx = new DataSourceTransactionManager(data);
            new OAuthService(db,storage,tx,false,"",new LoginAttempts(),"");
            db.execute("ALTER TABLE oauth_grants DROP COLUMN owner_id");
            db.update("INSERT INTO oauth_grants(id,client_id,redirect_uri,audience,sources,code_hash,challenge,code_expires,expires) VALUES('grant','client','https://example.com/callback','https://example.com/mcp','',?,'challenge',123,456)",SecurityConfiguration.hash("old-code"));
            new OAuthService(db,storage,tx,false,"",new LoginAttempts(),"");
            assertEquals(1,db.queryForObject("SELECT owner_id FROM oauth_grants WHERE id='grant'",Integer.class));
            assertEquals(SecurityConfiguration.hash("old-code"),db.queryForObject("SELECT code_hash FROM oauth_grants WHERE id='grant'",String.class));
            new Storage(db); assertEquals(9,storage.generation());
            db.update("INSERT INTO administrator(id,username,password_hash,role) VALUES(2,'reader','hash','USER')");
            assertNotNull(storage.account(2));
            db.update("INSERT INTO administrator(id,username,password_hash) VALUES(3,'default-user','hash')");
            assertEquals("USER",storage.account(3).role());
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> db.update("INSERT INTO administrator(id,username,password_hash) VALUES(4,'','hash')"));
            new Storage(db);
            assertEquals("ADMIN",storage.account(1).role());
            assertEquals("USER",storage.account(3).role());
        }
    }
    void recover(Path folder, String username) throws Exception {
        var input = System.in;
        try {
            System.setIn(new java.io.ByteArrayInputStream("changed-password-123\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            AdminRecovery.run(username == null ? new String[]{"--recover-admin","--data="+folder} : new String[]{"--recover-admin","--data="+folder,"--username="+username});
        } finally { System.setIn(input); }
    }
    @Test void recoverySelectsAdminRefusesAmbiguityAndPreservesOtherUsers() throws Exception {
        Path folder = Files.createTempDirectory("nasquire-recovery-accounts-"); String url="jdbc:h2:file:"+folder.resolve("metadata");
        try (var connection=DriverManager.getConnection(url,"sa","")) {
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true)); var storage=new Storage(db); storage.setup("admin","example-password-123");
            db.update("INSERT INTO administrator(id,username,password_hash,role,active,totp_secret,generation) VALUES(2,'second','old','ADMIN',FALSE,'encrypted',7),(3,'reader','preserved','USER',TRUE,NULL,2)");
            db.update("INSERT INTO recovery_codes(code_hash,user_id) VALUES('second-code',2),('reader-code',3)");
        }
        assertTrue(assertThrows(IllegalArgumentException.class,()->recover(folder,null)).getMessage().contains("second"));
        assertThrows(IllegalArgumentException.class,()->recover(folder,"reader")); recover(folder,"second");
        try (var connection=DriverManager.getConnection(url,"sa","")) {
            var db=new JdbcTemplate(new SingleConnectionDataSource(connection,true)); var storage=new Storage(db);
            assertTrue(storage.account(2).active()); assertEquals(8,storage.account(2).generation());
            assertTrue(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches("changed-password-123",storage.user("second").getPassword()));
            assertNull(db.queryForObject("SELECT totp_secret FROM administrator WHERE id=2",String.class));
            assertEquals("preserved",db.queryForObject("SELECT password_hash FROM administrator WHERE id=3",String.class));
            assertEquals(List.of("reader-code"),db.queryForList("SELECT code_hash FROM recovery_codes",String.class));
            assertEquals(2,db.queryForObject("SELECT actor_id FROM audit WHERE action='admin-recovery'",Integer.class));
        }
    }
}
