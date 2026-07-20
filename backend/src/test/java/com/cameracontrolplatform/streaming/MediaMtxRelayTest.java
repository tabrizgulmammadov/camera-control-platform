package com.cameracontrolplatform.streaming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

class MediaMtxRelayTest {

    @Test
    void probeApiReturnsTrueForSuccessfulResponse() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v3/config/global/get", exchange -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();

        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/config/global/get");
            assertTrue(MediaMtxRelay.probeApi(HttpClient.newHttpClient(), uri, Duration.ofSeconds(2)));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void probeApiReturnsFalseForUnavailableServer() throws IOException {
        URI uri = URI.create("http://127.0.0.1:1/v3/config/global/get");
        assertFalse(MediaMtxRelay.probeApi(HttpClient.newHttpClient(), uri, Duration.ofMillis(200)));
    }
}
