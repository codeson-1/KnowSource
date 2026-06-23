package com.knowsource.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class DocumentIndexEventPollerTest {

    @Test
    void pollerProcessesOutboxEventsOnIndexExecutor() throws Exception {
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<String> threadName = new AtomicReference<>();
        DocumentIndexOutboxService outboxService = mock(DocumentIndexOutboxService.class);
        when(outboxService.processNextPendingEvent()).thenAnswer(invocation -> {
            threadName.set(Thread.currentThread().getName());
            processed.countDown();
            return false;
        });

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("index-");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.initialize();

        try {
            new DocumentIndexEventPoller(outboxService, executor).poll();

            assertThat(processed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(threadName.get()).startsWith("index-");
        } finally {
            executor.shutdown();
        }
    }
}
