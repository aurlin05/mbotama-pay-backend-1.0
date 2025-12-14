package com.mbotamapay.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduler Configuration
 * Enables Spring's scheduled task execution and async support
 */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulerConfig {
    // Configuration is handled through annotations
    // Thread pool can be customized via application.yml:
    // spring.task.scheduling.pool.size=2
    // spring.task.execution.pool.core-size=2
}
