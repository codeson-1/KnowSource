package com.knowsource.index;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "knowsource.index.poller", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DocumentIndexEventPoller {

    private static final int MAX_EVENTS_PER_TICK = 5;

    private final DocumentIndexOutboxService outboxService;
    private final AsyncTaskExecutor indexExecutor;
    private final AtomicBoolean drainRunning = new AtomicBoolean();

    public DocumentIndexEventPoller(
            DocumentIndexOutboxService outboxService,
            @Qualifier("indexExecutor") AsyncTaskExecutor indexExecutor) {
        this.outboxService = outboxService;
        this.indexExecutor = indexExecutor;
    }

    @Scheduled(fixedDelayString = "${knowsource.index.poll-delay-ms:5000}")
    public void poll() {
        if (!drainRunning.compareAndSet(false, true)) {
            return;
        }
        indexExecutor.execute(this::drainPendingEvents);
    }

    private void drainPendingEvents() {
        try {
            processPendingEvents();
        } finally {
            drainRunning.set(false);
        }
    }

    private void processPendingEvents() {
        for (int i = 0; i < MAX_EVENTS_PER_TICK; i++) {
            if (!outboxService.processNextPendingEvent()) {
                return;
            }
        }
    }
}
