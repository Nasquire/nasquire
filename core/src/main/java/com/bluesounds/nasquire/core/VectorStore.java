package com.bluesounds.nasquire.core;

import java.io.IOException;
import java.util.*;

/** 외부 벡터 저장소. 검색 결과는 조각 식별자뿐이며 본문·경로는 소스 범위를 적용한 Lucene 조회로만 얻는다. */
public interface VectorStore extends AutoCloseable {
    record Point(String source, String key, int offset, float[] vector) { }
    record Match(String source, String key, int offset, float score) { }
    /** 색인 메타에 기록하는 저장소 식별자. 바뀌면 재색인 전 벡터 검색을 막는다. */
    String id();
    /** 기존 점을 모두 버리고 지정 차원으로 다시 만든다. */
    void reset(int dimensions) throws IOException;
    /** 같은 key의 기존 점을 지우고 새 점으로 바꾼다. */
    void replace(String key, List<Point> points, int dimensions) throws IOException;
    void deleteKey(String key) throws IOException;
    void deleteSource(String source) throws IOException;
    /** sources는 비어 있지 않아야 하며 저장소 쪽 필터로 전달해야 한다. */
    List<Match> search(float[] vector, Set<String> sources, int limit) throws IOException;
    default void close() { }
}
