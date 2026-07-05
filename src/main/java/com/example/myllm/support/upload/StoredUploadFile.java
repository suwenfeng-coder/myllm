package com.example.myllm.support.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.web.multipart.MultipartFile;

/** 将磁盘临时文件包装为 MultipartFile，供后台 worker 消费。 */
public class StoredUploadFile implements MultipartFile {

    private final Path path;
    private final String originalFilename;
    private final String contentType;

    public StoredUploadFile(Path path, String originalFilename, String contentType) {
        this.path = path;
        this.originalFilename = originalFilename == null || originalFilename.isBlank()
                ? "unknown"
                : originalFilename;
        this.contentType = contentType;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalFilename;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        try {
            return Files.size(path) <= 0;
        } catch (IOException e) {
            return true;
        }
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalStateException("读取临时文件大小失败: " + path, e);
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public void transferTo(java.io.File dest) throws IOException {
        Files.copy(path, dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    public Path path() {
        return path;
    }
}
