package com.bnpp.export;

import com.bnpp.export.config.DatabaseSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.task.DelegatingSecurityContextAsyncTaskExecutor;

import java.util.Arrays;
import java.util.concurrent.Executor;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableAsync
public class ExportApplication {

    private static final Logger log = LoggerFactory.getLogger(ExportApplication.class);

    private static String[] startArgs = new String[0];
    private static volatile ConfigurableApplicationContext context;

    /** Starts on the database chosen with the Database switch (H2 unless PostgreSQL was chosen), or on an explicit profile. */
    public static void main(String[] args) {
        startArgs = args;
        context = SpringApplication.run(ExportApplication.class, DatabaseSettings.load().arguments(args));
    }

    /** Whether the Database switch can restart the module: it was started from {@link #main} without an explicit profile. */
    public static boolean canRestart() {
        return context != null && !DatabaseSettings.profileChosenExplicitly(startArgs);
    }

    /**
     * Restarts the module on the database now saved in {@link DatabaseSettings}, after the current request has been
     * answered. If PostgreSQL does not start (wrong password, missing tables, failed import), the module starts on H2
     * again and keeps the reason for the screen.
     */
    public static void restart() {
        if (!canRestart()) {
            throw new IllegalStateException("The module was not started from ExportApplication.main without a profile");
        }
        Thread thread = new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            context.close();
            DatabaseSettings settings = DatabaseSettings.load();
            try {
                context = SpringApplication.run(ExportApplication.class, settings.arguments(startArgs));
            } catch (RuntimeException ex) {
                String reason = rootMessage(ex);
                log.error("Could not start on {}: {}. Starting on H2 instead.", settings.mode(), reason, ex);
                settings.withMode(DatabaseSettings.H2, "PostgreSQL did not start: " + reason).save();
                context = SpringApplication.run(ExportApplication.class, startArgs);
            }
        }, "database-switch");
        thread.setDaemon(false);
        thread.start();
    }

    private static String rootMessage(Throwable ex) {
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
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
        // Carry the signed-in user into background export runs so audit rows name who started them.
        return new DelegatingSecurityContextAsyncTaskExecutor(executor);
    }

    @Bean
    public CommandLineRunner startup(Environment environment) {
        return args -> log.info("REGLISS Export Module ready on port {} (profiles={})",
                environment.getProperty("local.server.port", environment.getProperty("server.port", "8082")),
                Arrays.toString(environment.getActiveProfiles()));
    }
}
