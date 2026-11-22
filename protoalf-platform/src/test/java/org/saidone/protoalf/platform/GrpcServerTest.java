package org.saidone.protoalf.platform;

import lombok.val;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrpcServerTest {

    @Test
    void rejectsRemoteCleartext() {
        val server = new GrpcServer(null, true, "0.0.0.0", 9090, 2, 16777216, "", "");
        assertThrows(IllegalArgumentException.class, server::start);
        server.stop();
    }

    @Test
    void rejectsPartialTlsConfiguration() {
        val server = new GrpcServer(null, true, "127.0.0.1", 9090, 2, 16777216, "cert.pem", "");
        assertThrows(IllegalArgumentException.class, server::start);
    }

    @Test
    void disabledServerDoesNotOpenSocket() throws Exception {
        val server = new GrpcServer(null, false, "127.0.0.1", 9090, 2, 16777216, "", "");
        server.start();
        server.stop();
    }
}
