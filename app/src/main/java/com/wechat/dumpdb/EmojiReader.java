package com.wechat.dumpdb;

import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class EmojiReader {
    private static final String TAG = "EmojiReader";
    private static final String DEFAULT_EMOJI_CACHE = "emoji.cache";

    private Path emojiDir;
    private WeChatDBParser parser;
    private Map<String, EmojiInfo> emojiInfo;
    private String cacheFile;
    private WxgfAndroidDecoder wxgfDecoder;
    private Map<String, CacheEntry> cache;
    private int cacheSize;
    private byte[] encryptionKey;
    private OkHttpClient httpClient;

    public EmojiReader(String resourceDir, WeChatDBParser parser, WxgfAndroidDecoder wxgfDecoder, String cacheFile) {
        this.emojiDir = Paths.get(resourceDir, "emoji");
        if (!Files.isDirectory(this.emojiDir)) {
            throw new IllegalArgumentException("Emoji directory not found: " + this.emojiDir);
        }

        this.parser = parser;
        this.emojiInfo = parser.getEmojiInfo();
        if (this.emojiInfo == null) {
            this.emojiInfo = new HashMap<>();
        }

        this.cacheFile = cacheFile != null ? cacheFile : DEFAULT_EMOJI_CACHE;
        this.wxgfDecoder = wxgfDecoder;
        this.httpClient = new OkHttpClient();

        // Load cache
        loadCache();

        // Set up encryption key
        String encKey = parser.getEmojiEncryptionKey();
        if (encKey != null) {
            this.encryptionKey = getAesKey(encKey);
        }
    }

    private byte[] getAesKey(String md5) {
        // ASCII representation of the first half of md5 is used as AES key
        if (md5.length() != 32) {
            throw new IllegalArgumentException("MD5 must be 32 characters long");
        }
        return md5.substring(0, 16).getBytes();
    }

    public EmojiResult getEmoji(String md5) {
        if (md5 == null || md5.isEmpty()) {
            throw new IllegalArgumentException("Invalid md5: " + md5);
        }

        // Check cache
        EmojiResult cached = cacheQuery(md5);
        if (cached.format != null) {
            return cached;
        }

        // Check resource directory
        String subdir = parser.getEmojiGroups().getOrDefault(md5, "");
        Path dirToSearch = emojiDir.resolve(subdir);
        EmojiResult result = searchInRes(dirToSearch, md5, false);
        if (result.format != null) {
            return result;
        }

        // Try to fetch from URL
        EmojiInfo info = emojiInfo.get(md5);
        if (info != null) {
            result = fetch(md5, info.getCdnUrl(), info.getEncryptUrl(), info.getAesKey());
            if (result.format != null) {
                return result;
            }
        }

        // Fallback search
        result = searchInRes(dirToSearch, md5, true);
        if (result.format != null) {
            Log.i(TAG, "Using fallback for emoji " + md5);
            return result;
        } else {
            boolean emojiInTable = info != null;
            String msg = emojiInTable ? "group='" + subdir + "'" : "not in database";
            Log.w(TAG, "Cannot find emoji " + md5 + ": " + msg);
            return new EmojiResult(null, null);
        }
    }

    private EmojiResult cacheQuery(String md5) {
        CacheEntry entry = cache.get(md5);
        if (entry != null) {
            return new EmojiResult(entry.data, entry.format);
        }
        return new EmojiResult(null, null);
    }

    private void cacheAdd(String md5, String data, String format) {
        cache.put(md5, new CacheEntry(data, format));
        if (cache.size() >= cacheSize + 15) {
            flushCache();
        }
    }

    public void flushCache() {
        if (cache.size() > cacheSize) {
            cacheSize = cache.size();
            saveCache();
        }
    }

    private void loadCache() {
        cache = new HashMap<>();
        cacheSize = 0;

        File file = new File(cacheFile);
        if (file.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
                cache = (Map<String, CacheEntry>) ois.readObject();
                cacheSize = cache.size();
            } catch (Exception e) {
                Log.e(TAG, "Error loading cache", e);
                cache = new HashMap<>();
            }
        }
    }

    private void saveCache() {
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(cacheFile))) {
            oos.writeObject(cache);
        } catch (IOException e) {
            Log.e(TAG, "Error saving cache", e);
        }
    }

    private EmojiResult searchInRes(Path dir, String md5, boolean allowFallback) {
        List<Path> candidates = new ArrayList<>();

        if (allowFallback) {
            try (Stream<Path> stream = Files.list(dir)) {
                candidates = stream.filter(p -> p.getFileName().toString().startsWith(md5))
                        .collect(ArrayList::new, (list, item) -> list.add(item), ArrayList::addAll);
            } catch (IOException e) {
                Log.e(TAG, "Error listing directory " + dir, e);
                return new EmojiResult(null, null);
            }
        } else {
            Path exactMatch = dir.resolve(md5);
            if (Files.isRegularFile(exactMatch)) {
                candidates.add(exactMatch);
            }
        }

        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }

            try {
                EmojiResult result;
                if (allowFallback) {
                    result = getDataFallback(candidate);
                } else {
                    result = getDataNoFallback(candidate, md5);
                }

                if (result.format != null) {
                    return result;
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing candidate " + candidate, e);
            }
        }

        return new EmojiResult(null, null);
    }

    private EmojiResult getDataNoFallback(Path fname, String expectedMd5) throws Exception {
        // Try as regular image first
        String imageFormat = getImageFormat(fname);
        if (imageFormat != null) {
            String fileMd5 = getFileMd5(fname);
            if (expectedMd5.equals(fileMd5)) {
                String b64 = getFileB64(fname);
                return new EmojiResult(b64, imageFormat);
            }
        }

        // Try decryption
        byte[] content = decryptEmoji(fname);
        String dataMd5 = getMd5Hex(content);

        if (!dataMd5.equals(expectedMd5)) {
            if (WxgfAndroidDecoder.isWxgfBuffer(content)) {
                content = wxgfDecoder.decodeWithCache(fname.toString(), content);
                if (content == null) {
                    if (!wxgfDecoder.hasServer()) {
                        Log.w(TAG, "wxgf decoder server is not provided. Cannot decode wxgf emojis.");
                    }
                    throw new Exception("Failed to decrypt wxgf file.");
                }
            } else {
                throw new Exception("Decrypted data mismatch md5!");
            }
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(content, 0, content.length, options);

        String format = getFormatFromMimeType(options.outMimeType);
        String b64 = Base64.encodeToString(content, Base64.NO_WRAP);
        return new EmojiResult(b64, format);
    }

    private EmojiResult getDataFallback(Path fname) throws Exception {
        String imageFormat = getImageFormat(fname);
        if (imageFormat == null) {
            return new EmojiResult(null, null); // Fallback files are not encrypted
        }

        String b64 = getFileB64(fname);
        return new EmojiResult(b64, imageFormat);
    }

    private byte[] decryptEmoji(Path fname) throws Exception {
        if (encryptionKey == null) {
            throw new Exception("No encryption key available");
        }

        Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
        SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
        cipher.init(Cipher.DECRYPT_MODE, keySpec);

        try (FileInputStream fis = new FileInputStream(fname.toFile())) {
            byte[] head = new byte[1024];
            int headLen = fis.read(head);
            byte[] plainHead = cipher.doFinal(head, 0, headLen);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(plainHead);

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }

            return baos.toByteArray();
        }
    }

    private EmojiResult fetch(String md5, String cdnUrl, String encryptUrl, String aesKey) {
        EmojiResult result = null;

        // Try CDN URL first
        if (cdnUrl != null && !cdnUrl.isEmpty()) {
            try {
                Log.i(TAG, "Requesting emoji " + md5 + " from " + cdnUrl + " ...");
                byte[] content = downloadUrl(cdnUrl);
                String emojiMd5 = getMd5Hex(content);
                String format = getFormatFromBytes(content);
                String b64 = Base64.encodeToString(content, Base64.NO_WRAP);
                result = new EmojiResult(b64, format);

                if (emojiMd5.equals(md5)) {
                    cacheAdd(md5, b64, format);
                    return result;
                } else {
                    throw new Exception("Emoji MD5 from CDNURL does not match");
                }
            } catch (Exception e) {
                Log.d(TAG, "Error processing cdnurl " + cdnUrl, e);
            }
        }

        // Try encrypted URL
        if (encryptUrl != null && !encryptUrl.isEmpty()) {
            try {
                Log.i(TAG, "Requesting encrypted emoji " + md5 + " from " + encryptUrl + " ...");
                byte[] buf = downloadUrl(encryptUrl);
                if (buf.length == 0) {
                    Log.e(TAG, "Failed to download emoji " + md5);
                    return new EmojiResult(null, null);
                }

                byte[] aesKeyBytes = hexStringToByteArray(aesKey);
                Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                SecretKeySpec keySpec = new SecretKeySpec(aesKeyBytes, "AES");
                IvParameterSpec ivSpec = new IvParameterSpec(aesKeyBytes);
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

                byte[] decryptedBuf = cipher.doFinal(buf);
                String format = getFormatFromBytes(decryptedBuf);
                String b64 = Base64.encodeToString(decryptedBuf, Base64.NO_WRAP);
                result = new EmojiResult(b64, format);

                cacheAdd(md5, b64, format);
                return result;
            } catch (Exception e) {
                Log.e(TAG, "Error processing encrypturl " + encryptUrl, e);
            }
        }

        // Return result with wrong md5 if available, but don't cache
        if (result != null) {
            return result;
        }

        return new EmojiResult(null, null);
    }

    private byte[] downloadUrl(String urlString) throws Exception {
        Request request = new Request.Builder()
                .url(urlString)
                .build();
        Response response = httpClient.newCall(request).execute();
        return response.body().bytes();
    }

    // Helper methods
    private String getMd5Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private String getFileMd5(Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        return getMd5Hex(data);
    }

    private String getFileB64(Path path) throws Exception {
        byte[] data = Files.readAllBytes(path);
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private String getImageFormat(Path path) {
        try {
            byte[] header = new byte[12];
            try (FileInputStream fis = new FileInputStream(path.toFile())) {
                fis.read(header);
            }
            return detectImageFormat(header);
        } catch (Exception e) {
            return null;
        }
    }

    private String detectImageFormat(byte[] header) {
        if (header.length >= 4) {
            // PNG
            if (header[0] == (byte) 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
                return "png";
            }
            // JPEG
            if (header[0] == (byte) 0xFF && header[1] == (byte) 0xD8) {
                return "jpeg";
            }
            // GIF
            if (header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46) {
                return "gif";
            }
            // WebP
            if (header.length >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46 &&
                    header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
                return "webp";
            }
        }
        return null;
    }

    private String getFormatFromBytes(byte[] data) {
        return detectImageFormat(data);
    }

    private String getFormatFromMimeType(String mimeType) {
        if (mimeType == null) return null;
        if (mimeType.equals("image/png")) return "png";
        if (mimeType.equals("image/jpeg")) return "jpeg";
        if (mimeType.equals("image/gif")) return "gif";
        if (mimeType.equals("image/webp")) return "webp";
        return null;
    }

    private byte[] hexStringToByteArray(String s) {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    // Data classes
    public static class EmojiResult {
        public final String data;
        public final String format;

        public EmojiResult(String data, String format) {
            this.data = data;
            this.format = format;
        }
    }

    private static class CacheEntry implements Serializable {
        final String data;
        final String format;

        CacheEntry(String data, String format) {
            this.data = data;
            this.format = format;
        }
    }
}