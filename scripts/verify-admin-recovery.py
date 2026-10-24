#!/usr/bin/env python3
"""빌드된 JAR의 오프라인 복구·DB 잠금 거부를 임시 데이터로 검증한다(비밀값 미출력)."""
import pathlib
import secrets
import subprocess
import tempfile
import zipfile

root = pathlib.Path(__file__).resolve().parent.parent
jar = root / 'app/build/libs/app-0.1.0.jar'
with tempfile.TemporaryDirectory(prefix='nasquire-recovery-') as temporary:
    folder = pathlib.Path(temporary)
    with zipfile.ZipFile(jar) as archive:
        library = next(name for name in archive.namelist() if name.startswith('BOOT-INF/lib/h2-'))
        h2 = folder / 'h2.jar'
        h2.write_bytes(archive.read(library))
    helper = folder / 'RecoveryProbe.java'
    helper.write_text('''
import java.sql.*;
class RecoveryProbe {
    public static void main(String[] args) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:h2:file:" + args[1] + "/metadata", "sa", "")) {
            var statement = db.createStatement();
            if (args[0].equals("seed")) {
                statement.execute("CREATE TABLE administrator(id INT PRIMARY KEY,username VARCHAR(100),password_hash VARCHAR(100),totp_secret VARCHAR(512),totp_step BIGINT DEFAULT -1 NOT NULL)");
                statement.execute("INSERT INTO administrator VALUES(1,'admin','old-hash','encrypted-placeholder',123)");
                statement.execute("CREATE TABLE recovery_codes(code_hash VARCHAR(64) PRIMARY KEY)");
                statement.execute("INSERT INTO recovery_codes VALUES('hashed-placeholder')");
            } else if (args[0].equals("hold")) {
                System.out.println("READY"); System.out.flush(); System.in.read();
            } else {
                var row = statement.executeQuery("SELECT * FROM administrator"); row.next();
                if (row.getString("password_hash").equals("old-hash") || row.getString("totp_secret") != null || row.getLong("totp_step") != -1) throw new AssertionError("관리자 상태 오류");
                row = statement.executeQuery("SELECT COUNT(*) FROM recovery_codes"); row.next(); if (row.getInt(1) != 0) throw new AssertionError("복구 코드 잔존");
                row = statement.executeQuery("SELECT COUNT(*) FROM audit WHERE action='admin-recovery'"); row.next(); if (row.getInt(1) != 1) throw new AssertionError("감사 오류");
            }
        }
    }
}
''')
    data = folder / 'data'
    data.mkdir()
    def probe(mode):
        return ['java', '--class-path', str(h2), str(helper), mode, str(data)]
    subprocess.run(probe('seed'), check=True, capture_output=True)
    command = ['java', '-jar', str(jar), '--recover-admin', '--data=' + str(data)]
    password = secrets.token_urlsafe(24)
    # 별도 JVM의 파일 잠금은 실행 중 서버와 같은 H2 배타 접근 조건이다.
    holder = subprocess.Popen(probe('hold'), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        assert holder.stdout.readline().strip() == 'READY'
        locked = subprocess.run(command, input=password + '\n', text=True, capture_output=True, timeout=30)
        assert locked.returncode != 0, 'DB 잠금 상태 복구가 허용됨'
        assert password not in locked.stdout + locked.stderr
    finally:
        holder.communicate('\n', timeout=15)
        assert holder.returncode == 0
    recovered = subprocess.run(command, input=password + '\n', text=True, capture_output=True, timeout=30)
    assert recovered.returncode == 0, '오프라인 복구 실패'
    assert password not in recovered.stdout + recovered.stderr
    assert '관리자 비밀번호를 재설정' in recovered.stdout
    subprocess.run(probe('check'), check=True, capture_output=True)
    # 인자 순서 오류도 웹 서버를 시작하지 않고 실패해야 한다.
    invalid = subprocess.run(['java', '-jar', str(jar), '--data=' + str(data), '--recover-admin'], input='', capture_output=True, text=True, timeout=15)
    assert invalid.returncode != 0
print('PASS packaged recovery: locked DB denied, password reset, TOTP/recovery removal, audit, malformed arguments denied; no web startup')
