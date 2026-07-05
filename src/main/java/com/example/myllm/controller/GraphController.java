package com.example.myllm.controller;

import com.example.myllm.config.GraphProperties;
import com.example.myllm.dto.GraphHealthResponse;
import com.example.myllm.service.FileEmbeddingService;
import com.example.myllm.service.GraphIndexTaskService;
import com.example.myllm.service.Neo4jAvailabilityService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Neo4j 图能力运维查询接口，不返回连接密码或业务正文。 */
@RestController
@RequestMapping("/api/graph")
@ConditionalOnProperty(prefix = "graph", name = "enabled", havingValue = "true")
public class GraphController {

    private final Neo4jAvailabilityService availabilityService;
    private final GraphProperties properties;
    private final FileEmbeddingService fileEmbeddingService;
    private final GraphIndexTaskService taskService;

    public GraphController(
            Neo4jAvailabilityService availabilityService,
            GraphProperties properties,
            FileEmbeddingService fileEmbeddingService,
            GraphIndexTaskService taskService) {
        this.availabilityService = availabilityService;
        this.properties = properties;
        this.fileEmbeddingService = fileEmbeddingService;
        this.taskService = taskService;
    }

    /** 实时检查 Bolt、认证和目标数据库，失败时返回 503。 */
    @GetMapping("/health")
    public ResponseEntity<GraphHealthResponse> health() {
        Neo4jAvailabilityService.Availability availability = availabilityService.check();
        GraphHealthResponse response = new GraphHealthResponse(
                availability.available(),
                availability.version(),
                availability.edition(),
                properties.getDatabase(),
                properties.getIndexing().isEnabled(),
                properties.getRetrieval().isEnabled(),
                properties.getRetrieval().isShadowMode(),
                availability.error());
        return ResponseEntity.status(availability.available() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .body(response);
    }

    /**
     * 为已有 pgvector 文件创建构图任务，不在 HTTP 请求中同步构图。
     *
     * @param limit 本次最多入队文件数，避免误操作一次提交过多任务
     */
    @PostMapping("/backfill")
    public ResponseEntity<Map<String, Object>> backfill(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(5000, limit));
        var files = fileEmbeddingService.listFiles().stream().limit(safeLimit).toList();
        files.forEach(file -> taskService.enqueueIndex(file.fileId(), file.fileName()));
        return ResponseEntity.accepted().body(Map.of(
                "queuedFiles", files.size(),
                "limit", safeLimit,
                "message", "历史文件构图任务已入队"));
    }
}
