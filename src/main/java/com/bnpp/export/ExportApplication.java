package com.bnpp.export;

import com.bnpp.export.config.MockProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
@EnableConfigurationProperties(MockProperties.class)
public class ExportApplication {

    private static final Logger log = LoggerFactory.getLogger(ExportApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ExportApplication.class, args);
    }

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("export-job-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    @Bean
    public CommandLineRunner startup(MockProperties mockProperties, Environment environment) {
        return args -> log.info(
                "REGLISS Export Module ready on port {} (mock={}, profiles={})",
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8082")),
                mockProperties.isEnabled(),
                Arrays.toString(environment.getActiveProfiles()));
    }
}
