package com.bluesounds.nasquire.app;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(Exception e) { return ResponseEntity.status(413).body(Map.of("error", "업로드 크기 상한 초과 / Upload too large")); }
    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "입력 또는 파일 처리 오류 / Invalid input or file processing error")); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(Exception e) { return ResponseEntity.status(403).body(Map.of("error", "접근 불가 / Forbidden")); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("error", "항목 없음 / Not found")); }
    @ExceptionHandler(EmbeddingSettings.KeyUnavailable.class)
    ResponseEntity<Map<String, String>> keyUnavailable(Exception e) { return ResponseEntity.status(503).body(Map.of("error", "암호화 키 파일 누락·불일치. 키를 복원하거나 오프라인 복구 후 다시 저장하세요 / Encryption key missing or changed. Restore it or recover offline, then save again")); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error", "현재 상태에서 처리 불가 / Conflict")); }
}
