package com.edischema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EdiSchemaGeneratorApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdiSchemaGeneratorApplication.class, args);
    }
}
