package com.example.myllm.controller;

import com.example.myllm.dto.RequestLogItemResponse;
import com.example.myllm.service.RequestLogQueryService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/logs")
public class RequestLogController {

    private final RequestLogQueryService requestLogQueryService;

    public RequestLogController(RequestLogQueryService requestLogQueryService) {
        this.requestLogQueryService = requestLogQueryService;
    }

    @GetMapping("/requests")
    public ResponseEntity<List<RequestLogItemResponse>> queryRequestLogs(
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        try {
            return ResponseEntity.ok(requestLogQueryService.queryRecentLogs(limit, from, to));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "查询请求日志失败: " + e.getMessage(), e);
        }
    }
}
