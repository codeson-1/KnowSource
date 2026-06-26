package com.knowsource.eval;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalRunnerService evalRunnerService;

    public EvalController(EvalRunnerService evalRunnerService) {
        this.evalRunnerService = evalRunnerService;
    }

    @PostMapping("/golden-set/run")
    public EvalRunResponse runGoldenSet() {
        return evalRunnerService.runGoldenSet();
    }

    @GetMapping("/golden-set/report")
    public EvalReportResponse latestReport() {
        return evalRunnerService.latestReport();
    }
}
