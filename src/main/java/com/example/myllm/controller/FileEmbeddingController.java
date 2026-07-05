package com.example.myllm.controller;

import com.example.myllm.dto.FileEmbeddingResponse;
import com.example.myllm.dto.GraphIndexTaskResponse;
import com.example.myllm.dto.ParseCapabilitiesResponse;
import com.example.myllm.dto.ParseEstimateResponse;
import com.example.myllm.dto.UploadTaskCreateResponse;
import com.example.myllm.dto.UploadTaskStatusResponse;
import com.example.myllm.service.FileUploadTaskService;
import com.example.myllm.service.FileIngestionCleanupService;
import com.example.myllm.service.ParseEstimateService;
import com.example.myllm.dto.VectorFileSummary;
import com.example.myllm.dto.VectorSearchResponse;
import com.example.myllm.service.DocumentParseService;
import com.example.myllm.service.GraphIndexTaskService;
import com.example.myllm.support.docforge.DocForgeServiceException;
import com.example.myllm.support.minio.MinioStorageException;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import com.example.myllm.service.FileEmbeddingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/files")
public class FileEmbeddingController {

    private final FileEmbeddingService fileEmbeddingService;
    private final DocumentParseService documentParseService;
    private final GraphIndexTaskService graphIndexTaskService;
    private final FileUploadTaskService fileUploadTaskService;
    private final ParseEstimateService parseEstimateService;

    public FileEmbeddingController(
            FileEmbeddingService fileEmbeddingService,
            DocumentParseService documentParseService,
            GraphIndexTaskService graphIndexTaskService,
            FileUploadTaskService fileUploadTaskService,
            ParseEstimateService parseEstimateService) {
        this.fileEmbeddingService = fileEmbeddingService;
        this.documentParseService = documentParseService;
        this.graphIndexTaskService = graphIndexTaskService;
        this.fileUploadTaskService = fileUploadTaskService;
        this.parseEstimateService = parseEstimateService;
    }

    @GetMapping("/parse-capabilities")
    public ResponseEntity<ParseCapabilitiesResponse> parseCapabilities() {
        return ResponseEntity.ok(documentParseService.getCapabilities());
    }

    @PostMapping("/upload/async")
    public ResponseEntity<UploadTaskCreateResponse> uploadAsync(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkStrategy", defaultValue = "${rag.chunking.default-strategy:fixed}")
            String chunkStrategy,
            @RequestParam(value = "parseMode", defaultValue = "${document.parsing.default-mode:auto}") String parseMode) {
        try {
            return ResponseEntity.accepted()
                    .body(fileUploadTaskService.createTask(file, chunkStrategy, parseMode));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    @GetMapping("/tasks/{taskId}")
    public ResponseEntity<UploadTaskStatusResponse> taskStatus(@PathVariable("taskId") String taskId) {
        return fileUploadTaskService.findStatus(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks/{taskId}/result")
    public ResponseEntity<FileEmbeddingResponse> taskResult(@PathVariable("taskId") String taskId) {
        return fileUploadTaskService.findResult(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public ResponseEntity<UploadTaskStatusResponse> cancelTask(@PathVariable("taskId") String taskId) {
        try {
            fileUploadTaskService.cancel(taskId);
            return fileUploadTaskService.findStatus(taskId)
                    .map(ResponseEntity::ok)
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.NOT_FOUND, "取消成功，但任务状态不存在: " + taskId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @GetMapping("/parse-estimate")
    public ResponseEntity<ParseEstimateResponse> parseEstimate(
            @RequestParam("parseMode") String parseMode,
            @RequestParam("fileSizeBytes") long fileSizeBytes) {
        if (fileSizeBytes <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fileSizeBytes 必须大于 0");
        }
        return ResponseEntity.ok(parseEstimateService.estimate(parseMode, fileSizeBytes));
    }

    @PostMapping("/upload")
    public ResponseEntity<FileEmbeddingResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "chunkStrategy", defaultValue = "${rag.chunking.default-strategy:fixed}")
            String chunkStrategy,
            @RequestParam(value = "parseMode", defaultValue = "${document.parsing.default-mode:auto}") String parseMode) {
        try {
            return ResponseEntity.ok(fileEmbeddingService.embedAndStore(file, chunkStrategy, parseMode));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (DocForgeServiceException e) {
            throw new ResponseStatusException(resolveStatus(e.statusCode()), e.getMessage(), e);
        } catch (MinioStorageException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "文件向量化失败: " + rootMessage(e), e);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<VectorSearchResponse> search(
            @RequestParam("q") String query,
            @RequestParam(value = "topK", required = false) Integer topK,
            @RequestParam(value = "fileId", required = false) List<String> fileIds) {
        try {
            return ResponseEntity.ok(fileEmbeddingService.search(query, topK, fileIds));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "向量检索失败: " + e.getMessage(), e);
        }
    }

    @GetMapping
    public ResponseEntity<List<VectorFileSummary>> listFiles() {
        try {
            return ResponseEntity.ok(fileEmbeddingService.listFiles());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "查询向量文件失败: " + e.getMessage(), e);
        }
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Map<String, Object>> deleteByFileId(@PathVariable("fileId") String fileId) {
        try {
            FileIngestionCleanupService.CleanupResult result = fileEmbeddingService.deleteByFileId(fileId);
            return ResponseEntity.ok(Map.of(
                    "fileId", fileId,
                    "deletedChunks", result.deletedChunks(),
                    "deletedCleaningLogs", result.deletedCleaningLogs(),
                    "deletedStorageLogs", result.deletedStorageLogs(),
                    "deletedMinioObjects", result.deletedMinioObjects()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除向量文件失败: " + rootMessage(e), e);
        }
    }

    /** 查询文件最近一笔 Neo4j 构图或删除任务。 */
    @GetMapping("/{fileId}/graph-status")
    public ResponseEntity<GraphIndexTaskResponse> graphStatus(@PathVariable("fileId") String fileId) {
        return graphIndexTaskService.findLatest(fileId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 手工重试文件最近一笔失败、死信或已完成的图任务。 */
    @PostMapping("/{fileId}/graph-retry")
    public ResponseEntity<GraphIndexTaskResponse> retryGraphTask(@PathVariable("fileId") String fileId) {
        try {
            return ResponseEntity.ok(graphIndexTaskService.retry(fileId));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        String message = error.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
        }
        return message == null ? "未知错误" : message;
    }

    private static HttpStatus resolveStatus(int statusCode) {
        HttpStatus status = HttpStatus.resolve(statusCode);
        return status == null ? HttpStatus.BAD_GATEWAY : status;
    }
}
