package com.rkh.callisto.core;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

final class ProxyProbe {
    private ProxyProbe() {}

    static boolean waitForSocks(String host, int port, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (connectThroughSocks(host, port)) return true;
            Thread.sleep(200L);
        }
        return false;
    }

    static void waitUntilClosed(int port, int timeoutSeconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        do {
            if (!isListening(port)) return;
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < deadline);
    }

    static boolean waitUntilListening(int port, int timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        do {
            if (isListening(port)) return true;
            Thread.sleep(50L);
        } while (System.currentTimeMillis() < deadline);
        return false;
    }

    static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 150);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean connectThroughSocks(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1500);
            socket.setSoTimeout(4000);
            InputStream input = socket.getInputStream();
            OutputStream output = socket.getOutputStream();

            output.write(new byte[]{5, 1, 0});
            output.flush();
            byte[] greeting = readExact(input, 2);
            if (greeting[0] != 5 || greeting[1] != 0) return false;

            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            byte[] request = new byte[7 + name.length];
            request[0] = 5;
            request[1] = 1;
            request[2] = 0;
            request[3] = 3;
            request[4] = (byte) name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[request.length - 2] = 1;
            request[request.length - 1] = (byte) 187; // 443
            output.write(request);
            output.flush();
            byte[] response = readExact(input, 4);
            return response[0] == 5 && response[1] == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] readExact(InputStream input, int size) throws Exception {
        byte[] data = new byte[size];
        int offset = 0;
        while (offset < size) {
            int count = input.read(data, offset, size - offset);
            if (count < 0) throw new Exception("unexpected end of stream");
            offset += count;
        }
        return data;
    }
}
