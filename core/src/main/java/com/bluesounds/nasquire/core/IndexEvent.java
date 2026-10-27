package com.bluesounds.nasquire.core;

/** 버전 키는 크기·수정 시각다. 내용 해시가 아니며 같은 메타데이터 변경은 감지하지 못한다. */
public record IndexEvent(Kind kind, String sourceId, String relativePath, long size, long mtime, String versionKey) {
    public enum Kind { ADDED, UPDATED, DELETED, FAILED }
    @FunctionalInterface
    public interface Listener { void onEvent(IndexEvent event); }
}
