package com.wechat.dumpdb.common;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;

public class TextUtil {

    public static String md5(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(data);
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("MD5 error", e);
        }
    }

    public static String getFileB64(String fname) {
        try {
            byte[] data = readAllBytes(fname);
            return Base64.encodeToString(data, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new RuntimeException("Error reading file", e);
        }
    }

    public static String getFileMd5(String fname) {
        try {
            byte[] data = readAllBytes(fname);
            return md5(data);
        } catch (Exception e) {
            throw new RuntimeException("Error reading file", e);
        }
    }

    public static String safeFilename(String fname) {
        return fname.replaceAll("[^a-zA-Z0-9 ]", "").trim();
    }

    private static byte[] readAllBytes(String fname) throws IOException {
        File file = new File(fname);
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, len);
            }
            return bos.toByteArray();
        }
    }
}
