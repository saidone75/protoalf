package org.saidone.protoalf.example;

import lombok.val;
import org.jspecify.annotations.NonNull;
import org.saidone.protoalf.api.Node;
import org.saidone.protoalf.api.SortField;
import org.saidone.protoalf.client.AlfrescoGrpcChannels;
import org.saidone.protoalf.client.AuthenticationServiceClient;
import org.saidone.protoalf.client.NodeServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.time.Duration;
import java.util.List;

/**
 * PoC of protoalf-client, gRPC only: logs in through AuthenticationService, lists the children of
 * Company Home (-root-), then the 10 documents modified most recently, and exits.
 */
@SpringBootApplication
public class ExampleApplication implements CommandLineRunner {

    private static final Duration DEADLINE = Duration.ofSeconds(30);

    @Value("${alfresco.host}")
    String host;

    @Value("${alfresco.port}")
    int port;

    @Value("${alfresco.trust-certificate}")
    String trustCertificate;

    @Value("${alfresco.username}")
    String username;

    @Value("${alfresco.password}")
    String password;

    public static void main(String[] args) {
        SpringApplication.run(ExampleApplication.class, args);
    }

    @Override public void run(String@NonNull ... args) throws Exception {
        val channel = AlfrescoGrpcChannels.create(host, port, trustCertificate, null);
        try {
            // Logs in on the first call, and again if the ticket expires
            val tickets = new AuthenticationServiceClient(channel, username, password, DEADLINE);
            val client = new NodeServiceClient(channel, tickets, DEADLINE);
            System.out.println("Company Home:");
            client.listChildren("-root-", 0, 100).getEntriesList().forEach(ExampleApplication::print);
            System.out.println("Last modified documents:");
            val newestFirst = List.of(SortField.newBuilder().setField("cm:modified").setDescending(true).build());
            client.search("TYPE:\"cm:content\"", "afts", 0, 10, newestFirst).getEntriesList().forEach(ExampleApplication::print);
        } finally {
            channel.shutdownNow();
        }
    }

    private static void print(Node node) {
        System.out.printf("  %-40s %s (parent %s)%n", node.getName(), node.getNodeRef(), node.getParentRef().isEmpty() ? "-" : node.getParentRef());
    }
}
