package com.systemguideforge.backend.application;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;

class SocketAccessProbeTest {
    @Test
    void reportsHostReachableWhenThePortAcceptsConnections() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            String base = "http://127.0.0.1:" + server.getLocalPort();

            AccessResult result = new SocketAccessProbe().test(new AccessRequest(base, base + "/login", "user", "pw"));

            assertThat(result.reachable()).isTrue();
            assertThat(result.authenticated()).isFalse();
            assertThat(result.code()).isEqualTo(AccessResultCode.HOST_REACHABLE);
        }
    }

    @Test
    void reportsHostUnreachableWhenThePortIsClosed() throws Exception {
        int closedPort;
        try (ServerSocket server = new ServerSocket(0)) { closedPort = server.getLocalPort(); }
        String base = "http://127.0.0.1:" + closedPort;

        AccessResult result = new SocketAccessProbe().test(new AccessRequest(base, base + "/login", "user", "pw"));

        assertThat(result.reachable()).isFalse();
        assertThat(result.code()).isEqualTo(AccessResultCode.HOST_UNREACHABLE);
    }
}
