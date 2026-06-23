package ru.abs7.leadprosvet;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LeadProsvetApplication {

    public static void main(String[] args) {
        SpringApplication.run(LeadProsvetApplication.class, args);
    }
}
