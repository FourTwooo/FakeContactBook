package com.fourtwo.fakecontactbook.http;

import android.content.Context;

import java.io.IOException;

public final class HttpApiServerManager {
    public static final int DEFAULT_PORT = 9420;

    private static FakeContactHttpServer server;
    private static int currentPort = DEFAULT_PORT;

    private HttpApiServerManager() {
    }

    public static synchronized void start(Context context, int port) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("context == null");
        }

        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("端口必须在 1-65535 之间");
        }

        stop();

        currentPort = port;
        server = new FakeContactHttpServer(context.getApplicationContext(), port);
        server.start();
    }

    public static synchronized void stop() {
        if (server != null) {
            try {
                server.stop();
            } catch (Throwable ignored) {
            }

            server = null;
        }
    }

    public static synchronized boolean isRunning() {
        return server != null && server.wasStarted();
    }

    public static synchronized int getCurrentPort() {
        return currentPort;
    }

    public static synchronized String getBaseUrl() {
        return "http://127.0.0.1:" + currentPort;
    }
}