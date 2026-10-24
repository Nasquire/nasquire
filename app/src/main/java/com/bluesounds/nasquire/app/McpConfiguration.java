package com.bluesounds.nasquire.app;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.*;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;

@Configuration
public class McpConfiguration {
    @Bean
    HttpServletStatelessServerTransport mcpTransport() {
        return HttpServletStatelessServerTransport.builder().messageEndpoint("/mcp").maxRequestSize(65536)
                .contextExtractor(req -> McpTransportContext.create(Map.of("identity", req.getAttribute("nasquire.token"))))
                .build();
    }
    @Bean
    ServletRegistrationBean<HttpServletStatelessServerTransport> mcpServlet(HttpServletStatelessServerTransport transport, McpStatelessSyncServer server) {
        var registration = new ServletRegistrationBean<>(transport, "/mcp");
        registration.setAsyncSupported(true); return registration;
    }
    @Bean(destroyMethod = "close")
    McpStatelessSyncServer mcpServer(HttpServletStatelessServerTransport transport, DocumentService documents, Storage storage) {
        var searchProperties = Map.<String, Object>of("query", Map.of("type", "string", "minLength", 1, "maxLength", 1000),
                "source", Map.of("type", "string", "description", "Optional source ID"), "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100));
        var documentProperties = new LinkedHashMap<String, Object>();
        documentProperties.put("path", Map.of("type", "string", "description", "Source-qualified path returned by search_documents"));
        for (String n : List.of("page_from", "page_to", "offset", "length")) documentProperties.put(n, Map.of("type", "integer", "minimum", n.equals("offset") ? 0 : 1));
        return McpServer.sync(transport).serverInfo("nasquire", "0.1.0")
                .toolCall(tool("search_documents", "Search indexed document chunks", searchProperties, List.of("query")), (context, request) ->
                    invoke(context, request, documents, storage, true))
                .toolCall(tool("get_document", "Read indexed text by page range or character offset/length (max 100000)", documentProperties, List.of("path")), (context, request) ->
                    invoke(context, request, documents, storage, false))
                .build();
    }
    private McpSchema.Tool tool(String name, String description, Map<String, Object> properties, List<String> required) {
        return McpSchema.Tool.builder(name, Map.of("type", "object", "properties", properties, "required", required, "additionalProperties", false))
                .description(description).build();
    }
    private McpSchema.CallToolResult invoke(McpTransportContext context, McpSchema.CallToolRequest request,
            DocumentService documents, Storage storage, boolean search) {
        var identity = (Storage.Identity)context.get("identity");
        int status = 200;
        try {
            if (identity == null) throw new IllegalArgumentException("토큰 인증 필요");
            Map<String, Object> args = request.arguments();
            Object result = search ? documents.search(identity, (String)args.get("query"), (String)args.get("source"), number(args, "limit") == null ? 20 : number(args, "limit"))
                    : documents.document(identity, (String)args.get("path"), number(args, "page_from"), number(args, "page_to"), number(args, "offset"), number(args, "length"));
            return McpSchema.CallToolResult.builder().content(List.of(McpSchema.TextContent.builder(McpJsonDefaults.getMapper().writeValueAsString(result)).build())).isError(false).build();
        } catch (Exception e) {
            status = 400;
            return McpSchema.CallToolResult.builder().content(List.of(McpSchema.TextContent.builder("문서를 조회할 수 없습니다 / Cannot access documents").build())).isError(true).build();
        } finally { storage.audit(identity == null ? "anonymous" : identity.tokenId(), "mcp " + request.name(), status); }
    }
    private Integer number(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) return null;
        if (!(value instanceof Number n) || n.doubleValue() != n.intValue()) throw new IllegalArgumentException("정수 범위 오류");
        return n.intValue();
    }
}
