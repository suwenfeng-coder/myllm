package com.example.myllm.support.minio;

import com.example.myllm.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnBean(MinioClient.class)
public class MinioStorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public MinioStorageService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    @PostConstruct
    void initializeBucket() {
        if (!properties.enabled() || !properties.ensureBucketOnStartup()) {
            return;
        }
        try {
            ensureBucketExists();
            log.info("MinIO bucket 已就绪: {}", properties.bucket());
        } catch (Exception e) {
            log.warn("MinIO bucket 初始化失败，文件存储可能不可用: {}", e.getMessage());
        }
    }

    public MinioStoredObject storeOriginal(
            String fileId, MultipartFile file, LocalDate storageDate) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        String fileName = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        String storedFileName = MinioObjectPaths.originalFileName(fileId, fileName);
        String objectPath = MinioObjectPaths.originalObjectKey(
                properties.uploadsPrefix(), storageDate, fileId, fileName);
        try {
            byte[] bytes = file.getBytes();
            String contentType = file.getContentType() == null
                    ? "application/octet-stream"
                    : file.getContentType();
            uploadBytes(objectPath, bytes, contentType);
            log.info(
                    "原始文件已写入 MinIO bucket={} object={}",
                    properties.bucket(),
                    objectPath);
            return new MinioStoredObject(
                    properties.bucket(), objectPath, storedFileName, contentType, bytes.length);
        } catch (Exception e) {
            throw new MinioStorageException("原始文件写入 MinIO 失败: " + fileName, e);
        }
    }

    public MinioStoredObject storeParsed(
            String fileId,
            String originalFileName,
            String parsedContent,
            LocalDate storageDate) {
        String normalizedContent = parsedContent == null ? "" : parsedContent;
        String parsedFileName = MinioObjectPaths.parsedFileName(fileId, originalFileName);
        String objectPath = MinioObjectPaths.parsedObjectKey(
                properties.parsedPrefix(), storageDate, fileId, originalFileName);
        try {
            byte[] bytes = normalizedContent.getBytes(StandardCharsets.UTF_8);
            uploadBytes(objectPath, bytes, "text/markdown; charset=utf-8");
            log.info(
                    "解析文件已写入 MinIO bucket={} object={}",
                    properties.bucket(),
                    objectPath);
            return new MinioStoredObject(
                    properties.bucket(), objectPath, parsedFileName,
                    "text/markdown; charset=utf-8", bytes.length);
        } catch (Exception e) {
            throw new MinioStorageException("解析文件写入 MinIO 失败: " + originalFileName, e);
        }
    }

    public int deleteObject(String bucket, String objectPath) {
        if (bucket == null || bucket.isBlank() || objectPath == null || objectPath.isBlank()) {
            return 0;
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(objectPath)
                    .build());
            log.info("MinIO 对象已删除 bucket={} object={}", bucket, objectPath);
            return 1;
        } catch (Exception e) {
            log.warn("MinIO 对象删除失败 bucket={} object={} error={}", bucket, objectPath, e.getMessage());
            return 0;
        }
    }

    private void uploadBytes(String objectPath, byte[] bytes, String contentType) throws Exception {
        ensureBucketExists();
        try (InputStream inputStream = new ByteArrayInputStream(bytes)) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.bucket())
                    .object(objectPath)
                    .stream(inputStream, bytes.length, -1)
                    .contentType(contentType)
                    .build());
        }
    }

    @SuppressWarnings("java:S112") // MinIO SDK exposes several checked transport and protocol exceptions.
    private void ensureBucketExists() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.bucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.bucket())
                    .build());
        }
    }
}
