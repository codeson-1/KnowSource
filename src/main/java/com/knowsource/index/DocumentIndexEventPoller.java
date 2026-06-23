package com.knowsource.index;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DocumentIndexEventPoller {

    private static final int MAX_EVENTS_PER_TICK = 5;

    private final DocumentIndexOutboxService outboxService;

    public DocumentIndexEventPoller(DocumentIndexOutboxService outboxService) {
        this.outboxService = outboxService;
    }

    @Scheduled(fixedDelayString = "${knowsource.index.poll-delay-ms:5000}")
    public void poll() {
        for (int i = 0; i < MAX_EVENTS_PER_TICK; i++) {
            if (!outboxService.processNextPendingEvent()) {
                return;
            }
        }
    }
}
