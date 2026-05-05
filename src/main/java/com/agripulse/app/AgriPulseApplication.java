package com.agripulse.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// @SpringBootApplication is a convenience annotation that bundles together
// configuration, component scanning, and auto-configuration for Spring Boot apps.
@SpringBootApplication
public class AgriPulseApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgriPulseApplication.class, args);
    }
}

