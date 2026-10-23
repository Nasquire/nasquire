package com.bluesounds.nasquire.app;

import com.bluesounds.nasquire.core.*;
import com.zaxxer.hikari.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration
public class AppConfiguration {
    @Bean
    Path dataFolder(@Value("${nasquire.data:./data}") String folder) throws IOException {
        var path = Path.of(folder).toAbsolutePath().normalize();
        Files.createDirectories(path);
        return path.toRealPath();
    }
    @Bean(destroyMethod = "close")
    DataSource dataSource(Path dataFolder) {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:h2:file:" + dataFolder.resolve("metadata") + ";DB_CLOSE_ON_EXIT=FALSE");
        config.setUsername("sa"); config.setPassword(""); config.setMaximumPoolSize(2); config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }
    @Bean(destroyMethod = "close")
    SearchIndex searchIndex(Path dataFolder) throws IOException { return new SearchIndex(dataFolder.resolve("index")); }
    @Bean(destroyMethod = "close")
    IndexingService indexingService(SearchIndex index, Storage storage,
            @Value("${nasquire.max-file-bytes:33554432}") long maxBytes,
            @Value("${nasquire.max-text-characters:2000000}") int maxCharacters,
            @Value("${nasquire.stable-millis:2000}") long stableMillis,
            @Value("${nasquire.scan-millis:10000}") long scanMillis) {
        if (maxBytes <= 0 || maxCharacters < 1200 || maxCharacters > 4000000 || stableMillis < 0 || scanMillis < 100)
            throw new IllegalArgumentException("색인 설정값 범위 오류");
        var service = new IndexingService(index, maxBytes, maxCharacters, stableMillis, List.of(new PdfTextExtractor(), new PlainTextExtractor()));
        service.start(storage::sources, scanMillis);
        return service;
    }
}
