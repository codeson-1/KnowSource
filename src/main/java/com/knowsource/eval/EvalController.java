package com.knowsource.eval;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunnerService evalRunnerService;
    private final EvalHistoryService evalHistoryService;

    public EvalController(EvalRunnerService evalRunnerService, EvalHistoryService evalHistoryService) {
        this.evalRunnerService = evalRunnerService;
        this.evalHistoryService = evalHistoryService;
    }

    @PostMapping("/golden-set/run")
    public EvalRunResponse runGoldenSet() {
        return evalRunnerService.runGoldenSet();
    }

    @GetMapping("/golden-set/report")
    public EvalReportResponse latestReport() {
        return evalRunnerService.latestReport();
    }

    @GetMapping("/golden-set/history")
    public List<EvalHistoryItem> history() {
        return evalHistoryService.listHistory();
    }
}
