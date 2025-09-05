package com.wechat.dumpdb;

import com.tencent.mm.plugin.gif.MMWXGFJNI;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;

public class WxgfAndroidDecoder {
    private static final String TAG = "WxgfAndroidDecoder";
    private static final byte[] WXGF_HEADER = "wxgf".getBytes();
    private static final byte[] FAILURE_MESSAGE = "FAILED".getBytes();

    private CountDownLatch connectionLatch;

    public WxgfAndroidDecoder() {
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
        return MMWXGFJNI.nativeWxam2PicBuf(data);
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
