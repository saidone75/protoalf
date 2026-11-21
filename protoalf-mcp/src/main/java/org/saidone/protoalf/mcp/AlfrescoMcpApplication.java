package org.saidone.protoalf.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AlfrescoMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlfrescoMcpApplication.class, args);
    }
}
