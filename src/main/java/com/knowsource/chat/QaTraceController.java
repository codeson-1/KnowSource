package com.knowsource.chat;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/kbs/{kbId}/qa-traces")
public class QaTraceController {

    private final QaTraceService qaTraceService;

    public QaTraceController(QaTraceService qaTraceService) {
        this.qaTraceService = qaTraceService;
    }

    @GetMapping
    public List<QaTraceSummaryResponse> listRecent(
            @PathVariable String kbId,
            @RequestParam(defaultValue = "20") Integer limit) {
        return qaTraceService.listRecent(kbId, limit);
    }

    @GetMapping("/{traceId}")
    public QaTraceDetailResponse getTrace(@PathVariable String kbId, @PathVariable String traceId) {
        return qaTraceService.getTrace(kbId, traceId);
    }
}
