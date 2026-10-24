package com.bluesounds.nasquire.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NasquireApplication {
    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--recover-admin")) { AdminRecovery.run(args); return; }
        SpringApplication.run(NasquireApplication.class, args);
    }
}
