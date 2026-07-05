package com.example.myllm.service;

import com.example.myllm.dto.ParseEstimateResponse;
import com.example.myllm.repository.DocumentUploadTaskRepository;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;
import org.springframework.stereotype.Service;

/** 基于历史任务与启发式规则估算解析耗时。 */
@Service
public class ParseEstimateService {

    private final DocumentUploadTaskRepository uploadTaskRepository;

    public ParseEstimateService(DocumentUploadTaskRepository uploadTaskRepository) {
        this.uploadTaskRepository = uploadTaskRepository;
    }

    public ParseEstimateResponse estimate(String parseMode, long fileSizeBytes) {
        String mode = normalizeParseMode(parseMode);
        long sizeMb = Math.max(1, (fileSizeBytes + 1024 * 1024 - 1) / (1024 * 1024));

        Long historical = uploadTaskRepository.findAll().stream()
                .filter(task -> task.getStatus() == com.example.myllm.entity.UploadTaskStatus.SUCCEEDED)
                .filter(task -> mode.equals(normalizeParseMode(task.getParseMode())))
                .filter(task -> task.getStartedAt() != null && task.getFinishedAt() != null)
                .filter(task -> task.getFileSizeBytes() != null && task.getFileSizeBytes() > 0)
                .map(task -> Duration.between(
                                task.getStartedAt().atZone(ZoneId.systemDefault()),
                                task.getFinishedAt().atZone(ZoneId.systemDefault()))
                        .getSeconds())
                .filter(seconds -> seconds > 0)
                .sorted()
                .skip(Math.max(0, countSucceeded(mode) / 10))
                .findFirst()
                .orElse(null);

        if (historical != null && historical > 0) {
            return new ParseEstimateResponse(
                    mode,
                    fileSizeBytes,
                    historical,
                    "historical_p90",
                    "基于近期成功任务的耗时估算");
        }

        long heuristicSeconds = switch (mode) {
            case "maker" -> 30L + sizeMb * 90L;
            case "docling" -> 10L + sizeMb * 20L;
            case "local" -> 3L + sizeMb * 2L;
            default -> 15L + sizeMb * 40L;
        };
        return new ParseEstimateResponse(
                mode,
                fileSizeBytes,
                heuristicSeconds,
                "heuristic",
                "基于文件大小与解析模式的默认估算");
    }

    private long countSucceeded(String mode) {
        return uploadTaskRepository.findAll().stream()
                .filter(task -> task.getStatus() == com.example.myllm.entity.UploadTaskStatus.SUCCEEDED)
                .filter(task -> mode.equals(normalizeParseMode(task.getParseMode())))
                .count();
    }

    private static String normalizeParseMode(String parseMode) {
        if (parseMode == null || parseMode.isBlank()) {
            return "auto";
        }
        String normalized = parseMode.trim().toLowerCase(Locale.ROOT);
        return "marker".equals(normalized) ? "maker" : normalized;
    }
}
