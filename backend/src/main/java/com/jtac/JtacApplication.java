package com.jtac;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * JTAC application entry point: a modular monolith. Each direct sub-package of
 * {@code com.jtac} is an application module whose boundaries are verified by
 * Spring Modulith (see {@code ModularityTests}).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class JtacApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(JtacApplication.class, args);
        // One-shot administration commands (e.g. ./gradlew createUser) run without a
        // web server and must terminate once their runner has finished.
        if (context.getEnvironment().containsProperty("jtac.admin.user-action")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
