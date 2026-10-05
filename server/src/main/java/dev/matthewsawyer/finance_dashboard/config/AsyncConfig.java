package dev.matthewsawyer.finance_dashboard.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class AsyncConfig {

    /** Keeps webhook responses fast by moving Plaid syncs off the request thread. */
    @Bean("plaidSyncExecutor")
    ThreadPoolTaskExecutor plaidSyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("plaid-sync-");
        return executor;
    }

    /** Runs delayed re-checks of Plaid data that wasn't ready yet. */
    @Bean("plaidRetryScheduler")
    ThreadPoolTaskScheduler plaidRetryScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("plaid-retry-");
        return scheduler;
    }

    /**
     * Runs sorting jobs. One thread, so jobs never overlap and a later job always
     * stores its answers after an earlier one's.
     */
    @Bean("sortingJobExecutor")
    ThreadPoolTaskExecutor sortingJobExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("sorting-job-");
        return executor;
    }

    /** Asks TypeSafe about several transactions at once while a sorting job runs. */
    @Bean("sortingClassifyExecutor")
    ThreadPoolTaskExecutor sortingClassifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("sorting-classify-");
        return executor;
    }
}
