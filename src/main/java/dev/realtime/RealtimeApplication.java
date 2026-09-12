package dev.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// @EnableScheduling added in M4, for ArchiveWriter's flush and RetentionTrimmer's sweep.
@EnableScheduling
@SpringBootApplication
public class RealtimeApplication {

    public static void main(String[] args) {
        SpringApplication.run(RealtimeApplication.class, args);
    }
}
