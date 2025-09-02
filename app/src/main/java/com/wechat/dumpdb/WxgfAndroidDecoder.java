package com.wechat.dumpdb;

import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class WxgfAndroidDecoder {
    private static final String TAG = "WxgfAndroidDecoder";
    private static final byte[] WXGF_HEADER = "wxgf".getBytes();
    private static final byte[] FAILURE_MESSAGE = "FAILED".getBytes();

    private String serverUrl;
    private WebSocketClient webSocketClient;
    private boolean isConnected = false;
    private CountDownLatch connectionLatch;
    private byte[] lastResponse;
    private boolean responseReceived;

    public WxgfAndroidDecoder(String server) {
        if (server != null) {
            if (!server.contains("://")) {
                server = "ws://" + server;
            }
            Log.i(TAG, "Connecting to " + server + " ...");
            this.serverUrl = server;
            connect();
        }
    }

    private void connect() {
        try {
            URI serverUri = URI.create(serverUrl);
            connectionLatch = new CountDownLatch(1);

            webSocketClient = new WebSocketClient(serverUri) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    Log.i(TAG, "WebSocket connection opened");
                    isConnected = true;
                    connectionLatch.countDown();
                }

                @Override
                public void onMessage(String message) {
                    // Handle text messages if needed
                }

                @Override
                public void onMessage(ByteBuffer bytes) {
                    byte[] data = new byte[bytes.remaining()];
                    bytes.get(data);
                    lastResponse = data;
                    responseReceived = true;
                    synchronized (WxgfAndroidDecoder.this) {
                        WxgfAndroidDecoder.this.notifyAll();
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "WebSocket connection closed: " + reason);
                    isConnected = false;
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket error: " + ex.getMessage(), ex);
                    isConnected = false;
                    connectionLatch.countDown();
                }
            };

            webSocketClient.connect();

            // Wait for connection with timeout
            if (!connectionLatch.await(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Connection timeout");
                isConnected = false;
            }

        } catch (Exception e) {
            Log.e(TAG, "Failed to connect to WebSocket server", e);
            isConnected = false;
        }
    }

    public void close() {
        if (hasServer() && webSocketClient != null) {
            webSocketClient.close();
            isConnected = false;
        }
    }

    public boolean hasServer() {
        return webSocketClient != null && isConnected;
    }

    public byte[] decode(byte[] data) throws Exception {
        // Verify WXGF header
        if (data.length < 4) {
            throw new IllegalArgumentException("Data too short");
        }

        for (int i = 0; i < 4; i++) {
            if (data[i] != WXGF_HEADER[i]) {
                byte[] header = new byte[Math.min(20, data.length)];
                System.arraycopy(data, 0, header, 0, header.length);
                throw new IllegalArgumentException("Invalid WXGF header: " + bytesToHex(header));
            }
        }

        Exception lastException = null;

        // Try to send data, reconnect on failure
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (!hasServer()) {
                    connect();
                    if (!hasServer()) {
                        throw new Exception("Failed to connect to server");
                    }
                }

                responseReceived = false;
                lastResponse = null;

                webSocketClient.send(data);

                // Wait for response with timeout
                synchronized (this) {
                    long startTime = System.currentTimeMillis();
                    while (!responseReceived && System.currentTimeMillis() - startTime < 30000) {
                        try {
                            wait(1000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new Exception("Interrupted while waiting for response");
                        }
                    }
                }

                if (!responseReceived || lastResponse == null) {
                    throw new Exception("No response received from server");
                }

                if (java.util.Arrays.equals(lastResponse, FAILURE_MESSAGE)) {
                    return null;
                }

                return lastResponse;

            } catch (Exception e) {
                lastException = e;
                Log.w(TAG, "Attempt " + (attempt + 1) + " failed: " + e.getMessage() + ". Reconnecting...");

                // Close and reconnect
                if (webSocketClient != null) {
                    webSocketClient.close();
                }
                isConnected = false;

                if (attempt == 0) { // Only reconnect on first failure
                    connect();
                }
            }
        }

        throw new Exception("Failed to decode after multiple attempts", lastException);
    }

    public byte[] decodeWithCache(String fname, byte[] data) throws Exception {
        // Read data from file if not provided
        if (data == null) {
            data = readFile(fname);
        }

        // Generate output filename
        String outFname = getFileWithoutExtension(fname) + ".dec";

        // Check if cached file exists
        File outFile = new File(outFname);
        if (outFile.exists()) {
            return readFile(outFname);
        }

        if (!hasServer()) {
            return null;
        }

        byte[] result = decode(data);

        if (result != null) {
            writeFile(outFname, result);
        }

        return result;
    }

    public static boolean isWxgfFile(String fname) throws IOException {
        try (FileInputStream fis = new FileInputStream(fname)) {
            byte[] header = new byte[4];
            int bytesRead = fis.read(header);
            if (bytesRead < 4) {
                return false;
            }
            return java.util.Arrays.equals(header, WXGF_HEADER);
        }
    }

    public static boolean isWxgfBuffer(byte[] buf) {
        if (buf.length < 4) {
            return false;
        }
        for (int i = 0; i < 4; i++) {
            if (buf[i] != WXGF_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    // Helper methods
    private byte[] readFile(String filename) throws IOException {
        try (FileInputStream fis = new FileInputStream(filename);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    private void writeFile(String filename, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(filename)) {
            fos.write(data);
        }
    }

    private String getFileWithoutExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return filename;
        }
        return filename.substring(0, lastDotIndex);
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
