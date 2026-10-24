package com.bluesounds.nasquire.app;

import java.io.IOException;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiErrors {
    @ExceptionHandler({IllegalArgumentException.class, IOException.class})
    ResponseEntity<Map<String, String>> invalid(Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "입력 또는 파일 처리 오류 / Invalid input or file processing error")); }
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Map<String, String>> denied(Exception e) { return ResponseEntity.status(403).body(Map.of("error", "접근 불가 / Forbidden")); }
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, String>> missing(Exception e) { return ResponseEntity.status(404).body(Map.of("error", "항목 없음 / Not found")); }
    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error", "현재 상태에서 처리 불가 / Conflict")); }
}
