package com.systemguideforge.backend.application;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;

/** Legacy TCP-only probe retained for focused diagnostics; it is not the application access adapter. */
public class SocketAccessProbe implements AccessProbe {
    @Override public AccessResult test(AccessRequest request) {
        try {
            URI uri = URI.create(request.baseUrl());
            int port = uri.getPort() > 0 ? uri.getPort() : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), 2000);
            }
            return new AccessResult(true, false, "Host reachable; authentication requires browser adapter");
        } catch (Exception ex) { return new AccessResult(false, false, "Host unreachable"); }
    }
}
