# MinIO Object Key Integrity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make every newly uploaded original and parsed MinIO object readable by filename and unique by full `fileId`, without migrating or rewriting historical objects.

**Architecture:** `MinioObjectPaths` owns filename parsing, identifier validation, and object-key construction. `MinioStorageService` passes the identity into that pure path layer and returns the exact stored name, while `DocumentStorageService` validates and normalizes the identity once before either object write and persists the returned paths unchanged.

**Tech Stack:** Java 17, Spring Boot 3.4.1, MinIO Java SDK 8.5.17, JUnit 5, Mockito, Maven.

## Global Constraints

- New keys use `{prefix}/{yyyy-MM-dd}/{sanitized-stem}__{validated-file-id}{extension}`.
- Parsed keys use `{prefix}/{yyyy-MM-dd}/{sanitized-stem}__{validated-file-id}.parsed.md`.
- The complete `fileId` is retained; it is never shortened or probabilistically hashed.
- A normalized `fileId` must match `[A-Za-z0-9._-]+`; invalid values fail before any MinIO write.
- Existing database rows and MinIO objects are not migrated, renamed, copied, or deleted.
- Date folders, bucket names, configured prefixes, database schema, and cleanup-by-stored-path behavior remain unchanged.
- Do not stage or commit unrelated pre-existing workspace changes.
- Follow red-green-refactor for every behavior change.

---

### Task 1: Add the readable unique path API

**Files:**
- Modify: `src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java:3-71`
- Test: `src/test/java/com/example/myllm/support/minio/MinioObjectPathsTests.java`

**Interfaces:**
- Consumes: `uploadsPrefix`, `parsedPrefix`, `LocalDate`, caller-provided `fileId`, and original filename.
- Produces: `requireValidFileId(String)`, `originalFileName(String, String)`, `parsedFileName(String, String)`, `originalObjectKey(String, LocalDate, String, String)`, and `parsedObjectKey(String, LocalDate, String, String)`.
- Transitional constraint: keep the current three-argument path methods only until Task 2 updates all production callers; Task 2 must remove them.

- [ ] **Step 1: Replace the path tests with failing uniqueness and validation examples**

Add the following behaviors to `MinioObjectPathsTests` while retaining the existing Unicode sanitization test:

```java
private static final LocalDate STORAGE_DATE = LocalDate.of(2026, Month.JULY, 2);
private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";

@Test
void buildsReadableUniqueOriginalAndParsedPaths() {
    String original = MinioObjectPaths.originalObjectKey(
            "uploads", STORAGE_DATE, FILE_ID, "docs/report.pdf");
    String parsed = MinioObjectPaths.parsedObjectKey(
            "parsed", STORAGE_DATE, FILE_ID, "docs/report.pdf");

    assertEquals(
            "uploads/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.pdf",
            original);
    assertEquals(
            "parsed/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
            parsed);
}

@Test
void sameDayAndFilenameRemainUniqueAcrossFileIds() {
    String first = MinioObjectPaths.originalObjectKey(
            "uploads", STORAGE_DATE, "11111111-1111-1111-1111-111111111111", "report.pdf");
    String second = MinioObjectPaths.originalObjectKey(
            "uploads", STORAGE_DATE, "22222222-2222-2222-2222-222222222222", "report.pdf");

    assertNotEquals(first, second);
}

@Test
void preservesLastExtensionAndHandlesMissingExtension() {
    assertEquals(
            "archive.tar__550e8400-e29b-41d4-a716-446655440000.gz",
            MinioObjectPaths.originalFileName(FILE_ID, "archive.tar.gz"));
    assertEquals(
            "README__550e8400-e29b-41d4-a716-446655440000",
            MinioObjectPaths.originalFileName(FILE_ID, "README"));
    assertEquals(
            "unknown__550e8400-e29b-41d4-a716-446655440000",
            MinioObjectPaths.originalFileName(FILE_ID, " "));
}

@Test
void rejectsBlankOrUnsafeFileIds() {
    assertThrows(
            IllegalArgumentException.class,
            () -> MinioObjectPaths.originalFileName(" ", "report.pdf"));
    assertThrows(
            IllegalArgumentException.class,
            () -> MinioObjectPaths.originalFileName("../shared", "report.pdf"));
}
```

Add static imports for `assertNotEquals` and `assertThrows`.

- [ ] **Step 2: Run the path tests and verify RED**

Run:

```bash
mvn -q -Dtest=MinioObjectPathsTests test
```

Expected: test compilation fails because the four-argument object-key methods and `originalFileName(String, String)` do not exist.

- [ ] **Step 3: Implement the minimal unique naming API**

Add `Pattern` validation and a single filename-splitting path in `MinioObjectPaths`:

```java
private static final Pattern FILE_ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");
private static final String UNIQUE_SEPARATOR = "__";

public static String requireValidFileId(String fileId) {
    if (fileId == null || fileId.isBlank()) {
        throw new IllegalArgumentException("fileId 不能为空");
    }
    String normalized = fileId.trim();
    if (!FILE_ID_PATTERN.matcher(normalized).matches()) {
        throw new IllegalArgumentException("fileId 包含非法字符");
    }
    return normalized;
}

public static String originalFileName(String fileId, String originalFileName) {
    FileNameParts parts = splitFileName(originalFileName);
    return parts.stem() + UNIQUE_SEPARATOR + requireValidFileId(fileId) + parts.extension();
}

public static String parsedFileName(String fileId, String originalFileName) {
    FileNameParts parts = splitFileName(originalFileName);
    return parts.stem() + UNIQUE_SEPARATOR + requireValidFileId(fileId) + ".parsed.md";
}

public static String originalObjectKey(
        String uploadsPrefix, LocalDate date, String fileId, String originalFileName) {
    return objectKey(uploadsPrefix, date, originalFileName(fileId, originalFileName));
}

public static String parsedObjectKey(
        String parsedPrefix, LocalDate date, String fileId, String originalFileName) {
    return objectKey(parsedPrefix, date, parsedFileName(fileId, originalFileName));
}

private static String objectKey(String prefix, LocalDate date, String storedFileName) {
    return normalizePrefix(prefix) + "/" + dateFolder(date) + "/" + storedFileName;
}

private static FileNameParts splitFileName(String fileName) {
    String sanitized = sanitizeFileName(fileName);
    int dot = sanitized.lastIndexOf('.');
    if (dot <= 0) {
        return new FileNameParts(sanitized, "");
    }
    return new FileNameParts(sanitized.substring(0, dot), sanitized.substring(dot));
}

private record FileNameParts(String stem, String extension) {
}
```

Import `java.util.regex.Pattern`. Retain the old three-argument `originalObjectKey`, `parsedObjectKey`, and one-argument `parsedFileName` methods temporarily so the production source still compiles before Task 2.

- [ ] **Step 4: Run the path tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MinioObjectPathsTests test
```

Expected: all `MinioObjectPathsTests` pass with zero failures and zero errors.

- [ ] **Step 5: Commit only Task 1 files**

```bash
git add src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java src/test/java/com/example/myllm/support/minio/MinioObjectPathsTests.java
git commit --only src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java src/test/java/com/example/myllm/support/minio/MinioObjectPathsTests.java -m "fix: add unique MinIO object names"
```

---

### Task 2: Propagate `fileId` through MinIO writes

**Files:**
- Modify: `src/main/java/com/example/myllm/support/minio/MinioStorageService.java:47-84`
- Modify: `src/main/java/com/example/myllm/service/DocumentStorageService.java:52-64`
- Modify: `src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java:17-42`
- Create: `src/test/java/com/example/myllm/support/minio/MinioStorageServiceTests.java`

**Interfaces:**
- Consumes: Task 1 path methods.
- Produces: `storeOriginal(String fileId, MultipartFile file, LocalDate storageDate)` and `storeParsed(String fileId, String originalFileName, String parsedContent, LocalDate storageDate)`.
- Updates `DocumentStorageService.store(...)` to pass its existing `fileId` to both methods.
- Removes every unsafe path/storage overload that can write an object without a `fileId`.

- [ ] **Step 1: Add failing MinIO SDK argument tests**

Create `MinioStorageServiceTests`:

```java
package com.example.myllm.support.minio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.myllm.config.MinioProperties;
import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.Month;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class MinioStorageServiceTests {

    private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final LocalDate STORAGE_DATE = LocalDate.of(2026, Month.JULY, 2);

    private MinioClient minioClient;
    private MinioStorageService service;

    @BeforeEach
    void setUp() throws Exception {
        minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        MinioProperties properties = new MinioProperties(
                true,
                "http://127.0.0.1:9000",
                "access",
                "secret",
                "myllm",
                "uploads",
                "parsed",
                false);
        service = new MinioStorageService(minioClient, properties);
    }

    @Test
    void originalUploadUsesAndReturnsUniqueReadableName() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "docs/report.pdf", "application/pdf", "pdf".getBytes(StandardCharsets.UTF_8));

        MinioStoredObject stored = service.storeOriginal(FILE_ID, file, STORAGE_DATE);

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertEquals(
                "uploads/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.pdf",
                args.getValue().object());
        assertEquals("report__550e8400-e29b-41d4-a716-446655440000.pdf", stored.fileName());
    }

    @Test
    void parsedUploadUsesMatchingUniqueReadableName() throws Exception {
        MinioStoredObject stored = service.storeParsed(FILE_ID, "docs/report.pdf", "body", STORAGE_DATE);

        ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(args.capture());
        assertEquals(
                "parsed/2026-07-02/report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
                args.getValue().object());
        assertEquals(
                "report__550e8400-e29b-41d4-a716-446655440000.parsed.md",
                stored.fileName());
    }
}
```

- [ ] **Step 2: Run the storage test and verify RED**

Run:

```bash
mvn -q -Dtest=MinioStorageServiceTests test
```

Expected: test compilation fails because `storeOriginal` and `storeParsed` do not accept `fileId`.

- [ ] **Step 3: Change the storage signatures and returned names**

Update the two methods in `MinioStorageService`:

```java
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
        log.info("原始文件已写入 MinIO bucket={} object={} size={}",
                properties.bucket(), objectPath, bytes.length);
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
        log.info("解析文件已写入 MinIO bucket={} object={} size={}",
                properties.bucket(), objectPath, bytes.length);
        return new MinioStoredObject(
                properties.bucket(), objectPath, parsedFileName,
                "text/markdown; charset=utf-8", bytes.length);
    } catch (Exception e) {
        throw new MinioStorageException("解析文件写入 MinIO 失败: " + originalFileName, e);
    }
}
```

Update `DocumentStorageService` so the codebase compiles and the identity reaches both calls:

```java
MinioStoredObject originalObject = storageService.storeOriginal(fileId, file, storageDate);
MinioStoredObject parsedObject = storageService.storeParsed(
        fileId, fileName, parsedContent, storageDate);
```

Remove the old three-argument object-key methods, one-argument `parsedFileName`, and storage methods without `fileId`. List every remaining call and verify that each one supplies `fileId`:

```bash
rg -n "MinioObjectPaths\.(originalObjectKey|parsedObjectKey|parsedFileName)|storeOriginal\(|storeParsed\(" src/main/java src/test/java
```

Expected: each object-key call has four arguments, `parsedFileName` has two arguments, `storeOriginal` begins with `fileId`, and `storeParsed` begins with `fileId`.

- [ ] **Step 4: Run path and storage tests and verify GREEN**

Run:

```bash
mvn -q -Dtest=MinioObjectPathsTests,MinioStorageServiceTests test
```

Expected: both test classes pass with zero failures and zero errors.

- [ ] **Step 5: Compile all tests to catch stale signatures**

Run:

```bash
mvn -q -DskipTests test-compile
```

Expected: exit code 0; no production or test caller uses a removed signature.

- [ ] **Step 6: Commit only Task 2 files**

```bash
git add src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java src/main/java/com/example/myllm/support/minio/MinioStorageService.java src/main/java/com/example/myllm/service/DocumentStorageService.java src/test/java/com/example/myllm/support/minio/MinioStorageServiceTests.java
git commit --only src/main/java/com/example/myllm/support/minio/MinioObjectPaths.java src/main/java/com/example/myllm/support/minio/MinioStorageService.java src/main/java/com/example/myllm/service/DocumentStorageService.java src/test/java/com/example/myllm/support/minio/MinioStorageServiceTests.java -m "fix: propagate file ids to MinIO writes"
```

---

### Task 3: Enforce the identity boundary before object writes

**Files:**
- Modify: `src/main/java/com/example/myllm/service/DocumentStorageService.java:39-78`
- Create: `src/test/java/com/example/myllm/service/DocumentStorageServiceTests.java`

**Interfaces:**
- Consumes: `MinioObjectPaths.requireValidFileId(String)` from Task 1 and the Task 2 storage signatures.
- Produces: one normalized `fileId` used for both MinIO writes and `DocumentStorageLog.fileId`.
- Guarantees: an invalid identity produces zero calls to `MinioStorageService` and `DocumentStorageLogRepository`.

- [ ] **Step 1: Add failing service-boundary tests**

Create `DocumentStorageServiceTests` with Mockito:

```java
package com.example.myllm.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.myllm.config.MinioProperties;
import com.example.myllm.entity.DocumentStorageLog;
import com.example.myllm.repository.DocumentStorageLogRepository;
import com.example.myllm.support.document.DocumentParseResult;
import com.example.myllm.support.document.ParsedDocument;
import com.example.myllm.support.minio.MinioStorageService;
import com.example.myllm.support.minio.MinioStoredObject;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DocumentStorageServiceTests {

    private static final String FILE_ID = "550e8400-e29b-41d4-a716-446655440000";

    private DocumentStorageLogRepository repository;
    private MinioStorageService minioStorageService;
    private DocumentStorageService service;

    @BeforeEach
    void setUp() {
        repository = mock(DocumentStorageLogRepository.class);
        minioStorageService = mock(MinioStorageService.class);
        MinioProperties properties = new MinioProperties(
                true,
                "http://127.0.0.1:9000",
                "access",
                "secret",
                "myllm",
                "uploads",
                "parsed",
                false);
        service = new DocumentStorageService(repository, Optional.of(minioStorageService), properties);
    }

    @Test
    void normalizesOneFileIdForBothWritesAndAudit() {
        MockMultipartFile file = sampleFile();
        DocumentParseResult parseResult = sampleParseResult();
        MinioStoredObject original = new MinioStoredObject(
                "myllm", "uploads/2026-07-02/report__" + FILE_ID + ".pdf",
                "report__" + FILE_ID + ".pdf", "application/pdf", 3);
        MinioStoredObject parsed = new MinioStoredObject(
                "myllm", "parsed/2026-07-02/report__" + FILE_ID + ".parsed.md",
                "report__" + FILE_ID + ".parsed.md", "text/markdown; charset=utf-8", 0);
        when(minioStorageService.storeOriginal(eq(FILE_ID), eq(file), any(LocalDate.class)))
                .thenReturn(original);
        when(minioStorageService.storeParsed(
                eq(FILE_ID), eq("report.pdf"), eq(""), any(LocalDate.class)))
                .thenReturn(parsed);
        when(repository.save(any(DocumentStorageLog.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        DocumentStorageLog saved = service.store(
                "  " + FILE_ID + "  ", "report.pdf", file, parseResult.document(), parseResult);

        verify(minioStorageService).storeOriginal(eq(FILE_ID), eq(file), any(LocalDate.class));
        verify(minioStorageService).storeParsed(
                eq(FILE_ID), eq("report.pdf"), eq(""), any(LocalDate.class));
        assertEquals(FILE_ID, saved.getFileId());
        assertEquals(original.objectPath(), saved.getOriginalObjectPath());
        assertEquals(parsed.objectPath(), saved.getParsedObjectPath());
    }

    @Test
    void rejectsUnsafeFileIdBeforeAnyStorageCall() {
        MockMultipartFile file = sampleFile();
        DocumentParseResult parseResult = sampleParseResult();

        assertThrows(
                IllegalArgumentException.class,
                () -> service.store("../shared", "report.pdf", file, parseResult.document(), parseResult));

        verifyNoInteractions(minioStorageService, repository);
    }

    private static MockMultipartFile sampleFile() {
        return new MockMultipartFile("file", "report.pdf", "application/pdf", new byte[] {1, 2, 3});
    }

    private static DocumentParseResult sampleParseResult() {
        ParsedDocument document = new ParsedDocument("report.pdf", List.of(), Map.of());
        return new DocumentParseResult(
                document, "auto", "local", "local", 1, 5L, List.of("local"), null);
    }
}
```

- [ ] **Step 2: Run the service test and verify RED**

Run:

```bash
mvn -q -Dtest=DocumentStorageServiceTests test
```

Expected: `normalizesOneFileIdForBothWritesAndAudit` fails because the untrimmed value is passed to storage, or `rejectsUnsafeFileIdBeforeAnyStorageCall` fails because storage is invoked before the service boundary validates the ID.

- [ ] **Step 3: Validate and normalize once at the service boundary**

Import `MinioObjectPaths` and update the beginning of `DocumentStorageService.store` after the MinIO availability checks:

```java
String normalizedFileId = MinioObjectPaths.requireValidFileId(fileId);
LocalDate storageDate = LocalDate.now(ZoneId.systemDefault());
String parsedContent = DocumentTextRenderer.renderRaw(parsedDocument.blocks());
MinioStoredObject originalObject = storageService.storeOriginal(
        normalizedFileId, file, storageDate);
MinioStoredObject parsedObject = storageService.storeParsed(
        normalizedFileId, fileName, parsedContent, storageDate);
```

Persist the same value:

```java
storageLog.setFileId(normalizedFileId);
```

Do not add historical-path migration or compensation logic in this task.

- [ ] **Step 4: Run all three targeted test classes and verify GREEN**

Run:

```bash
mvn -q -Dtest=MinioObjectPathsTests,MinioStorageServiceTests,DocumentStorageServiceTests test
```

Expected: all targeted tests pass with zero failures and zero errors.

- [ ] **Step 5: Run the repository verification gate**

Run:

```bash
make verify
```

Expected: Maven tests, test count checks, and documentation link checks all exit successfully.

- [ ] **Step 6: Check whitespace and exact change scope**

Run:

```bash
git diff --check
git status --short
```

Expected: `git diff --check` exits 0. The status includes only the planned MinIO implementation/test files plus the user's pre-existing unrelated changes.

- [ ] **Step 7: Commit only Task 3 files**

```bash
git add src/main/java/com/example/myllm/service/DocumentStorageService.java src/test/java/com/example/myllm/service/DocumentStorageServiceTests.java
git commit --only src/main/java/com/example/myllm/service/DocumentStorageService.java src/test/java/com/example/myllm/service/DocumentStorageServiceTests.java -m "fix: validate storage file ids before upload"
```

- [ ] **Step 8: Record final evidence**

Run:

```bash
git log -4 --oneline
git diff HEAD~3..HEAD --stat
```

Expected: the three implementation commits contain only the planned production and test files; no historical object migration or deletion is present.
