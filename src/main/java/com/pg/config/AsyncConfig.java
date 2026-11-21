package com.pg.config;

import java.util.concurrent.Executor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

	@Bean(name = "notificationExecutor")
	public Executor notificationExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(5);
		executor.setMaxPoolSize(20);
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("async-notify-");
		executor.initialize();
		return executor;
	}

	@Bean(name = "dashboardExecutor")
	public Executor dashboardExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(8);
		executor.setMaxPoolSize(32);
		executor.setQueueCapacity(200);
		executor.setThreadNamePrefix("async-dash-");
		executor.initialize();
		return executor;
	}
}
