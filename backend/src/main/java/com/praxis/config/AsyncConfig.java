package com.praxis.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AsyncConfig {

    @Bean(name = "analysisExecutor")
    public Executor analysisExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("analysis-");
        executor.initialize();
        return executor;
    }

    /**
     * Prax answering a question, so the browser can watch it work.
     *
     * A question takes 15-40s. Held in the request thread that is a blank
     * workspace for half a minute; run here instead, and the client polls for
     * tool calls and diagrams as they appear.
     *
     * Two threads, not one: the point is that a player is WAITING, and queueing
     * a second question behind a first would defeat the exercise. Not more than
     * two, because they compete for the same Ollama process and the same VRAM —
     * three concurrent runs would make all three slower than one.
     */
    @Bean(name = "praxExecutor")
    public Executor praxExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("prax-");
        executor.initialize();
        return executor;
    }

    /**
     * Post-game analysis of practice games.
     *
     * Separate from analysisExecutor on purpose. That one is single-threaded and
     * may be an hour deep in a Re-analyze All run; a player who has just finished
     * a game and is waiting for their report must not queue behind it, and must
     * not disturb the shared progress tracker that run is using.
     *
     * Still single-threaded: it shares one Stockfish process with the bulk run,
     * so running two of these concurrently would only move the contention.
     */
    @Bean(name = "practiceExecutor")
    public Executor practiceExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(20);
        executor.setThreadNamePrefix("practice-");
        executor.initialize();
        return executor;
    }
}
