package com.wechat.dumpdb;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.Log;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class Resource {
    private static final String TAG = "Resource";
    private static final String VOICE_DIRNAME = "voice2";
    private static final String IMG_DIRNAME = "image2";
    private static final String EMOJI_DIRNAME = "emoji";
    private static final String VIDEO_DIRNAME = "video";
    private static final int JPEG_QUALITY = 50;

    private String resDir;
    private WeChatDBParser parser;
    private Map<String, Integer> voiceCacheIdx;
    private List<Future<VoiceResult>> voiceCache;
    private String imgDir;
    private String voiceDir;
    private String videoDir;
    private AvatarReader avtReader;
    private WxgfAndroidDecoder wxgfDecoder;
    private EmojiReader emojiReader;
    private ExecutorService executorService;
    private OkHttpClient httpClient;
    private AudioParser audioParser;
    private Context androidContext; // Android context for audio parsing

    public Resource(WeChatDBParser parser, String resDir, String wxgfServer, String avtDb, Context context) {
        // Check required directories
        checkDirectory(resDir, "");
        checkDirectory(resDir, IMG_DIRNAME);
        checkDirectory(resDir, EMOJI_DIRNAME);
        checkDirectory(resDir, VOICE_DIRNAME);

        this.resDir = resDir;
        this.parser = parser;
        this.androidContext = context;
        this.voiceCacheIdx = new HashMap<>();
        this.voiceCache = new ArrayList<>();
        this.imgDir = resDir + File.separator + IMG_DIRNAME;
        this.voiceDir = resDir + File.separator + VOICE_DIRNAME;
        this.videoDir = resDir + File.separator + VIDEO_DIRNAME;
        this.avtReader = new AvatarReader(resDir, avtDb);
        this.wxgfDecoder = new WxgfAndroidDecoder(wxgfServer);
        this.emojiReader = new EmojiReader(resDir, parser, wxgfDecoder, null);
        this.executorService = Executors.newFixedThreadPool(3);
        this.httpClient = new OkHttpClient();
        this.audioParser = new AudioParser(context);

        // Register shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (executorService != null) {
                executorService.shutdown();
            }
        }));
    }

    private void checkDirectory(String resDir, String subdir) {
        String dirToCheck = subdir.isEmpty() ? resDir : resDir + File.separator + subdir;
        File dir = new File(dirToCheck);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("No such directory: " + dirToCheck);
        }
    }

    private String getVoiceFilename(String imgPath) {
        String fname = getMd5Hex(imgPath);
        String dir1 = fname.substring(0, 2);
        String dir2 = fname.substring(2, 4);
        String ret = voiceDir + File.separator + dir1 + File.separator + dir2 +
                File.separator + "msg_" + imgPath + ".amr";

        File file = new File(ret);
        if (!file.isFile()) {
            Log.e(TAG, "Cannot find voice file " + imgPath + ", " + fname);
            return "";
        }
        return ret;
    }

    public VoiceResult getVoiceMp3(String imgPath) {
        Integer idx = voiceCacheIdx.get(imgPath);
        if (idx == null) {
            return parseWechatAudioFile(getVoiceFilename(imgPath));
        }

        try {
            return voiceCache.get(idx).get();
        } catch (Exception e) {
            Log.e(TAG, "Error getting cached voice", e);
            return new VoiceResult("", 0);
        }
    }

    public void cacheVoiceMp3(List<WeChatMsg> msgs) {
        List<String> voicePaths = new ArrayList<>();
        for (WeChatMsg msg : msgs) {
            if (msg.getType() == WeChatMsg.TYPE_SPEAK) {
                voicePaths.add(msg.getImgPath());
            }
        }

        voiceCacheIdx = new HashMap<>();
        for (int i = 0; i < voicePaths.size(); i++) {
            voiceCacheIdx.put(voicePaths.get(i), i);
        }

        voiceCache = new ArrayList<>();
        for (String voicePath : voicePaths) {
            String filename = getVoiceFilename(voicePath);
            Future<VoiceResult> future = executorService.submit(() -> parseWechatAudioFile(filename));
            voiceCache.add(future);
        }
    }

    public String getAvatar(String username) {
        Bitmap im = avtReader.getAvatar(username);

        if (im == null) {
            // Try downloading the avatar directly
            String avatarUrl = parser.getAvatarUrls().get(username);
            if (avatarUrl == null) {
                return "";
            }

            Log.i(TAG, "Requesting avatar of " + username + " from " + avatarUrl + " ...");
            try {
                byte[] imageData = downloadUrl(avatarUrl);
                im = BitmapFactory.decodeByteArray(imageData, 0, imageData.length);
                if (im == null) {
                    throw new Exception("Failed to decode downloaded image");
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to fetch avatar of " + username, e);
                return "";
            }

            // Save to cache
            avtReader.saveAvatarToAvtDir(username, im);
        }

        // Convert to JPEG and encode to base64
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            im.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
        } catch (Exception e) {
            try {
                // Sometimes it works the second time...
                im.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
            } catch (Exception e2) {
                Log.e(TAG, "Failed to compress image to JPEG", e2);
                return "";
            }
        }

        byte[] jpegBytes = baos.toByteArray();
        return Base64.encodeToString(jpegBytes, Base64.NO_WRAP);
    }

    private ImageFiles getImgFile(List<String> fnames) {
        List<FileSize> cands = new ArrayList<>();

        for (String fname : fnames) {
            String dir1 = fname.substring(0, 2);
            String dir2 = fname.substring(2, 4);
            String dirname = imgDir + File.separator + dir1 + File.separator + dir2;

            File dir = new File(dirname);
            if (!dir.isDirectory()) {
                Log.w(TAG, "Directory not found: " + dirname);
                continue;
            }

            File[] files = dir.listFiles();
            if (files == null) continue;

            for (File file : files) {
                if (file.getName().contains(fname)) {
                    long size = file.length();
                    if (size > 0) {
                        cands.add(new FileSize(file.getAbsolutePath(), size));
                    }
                }
            }
        }

        if (cands.isEmpty()) {
            return new ImageFiles("", "");
        }

        // Sort by size
        cands.sort(Comparator.comparingLong(fs -> fs.size));

        if (cands.size() == 1) {
            String name = cands.get(0).name;
            if (nameIsThumbnail(name)) {
                return new ImageFiles("", name); // thumbnail
            } else {
                Log.w(TAG, "Found big image but not thumbnail: " + fnames.get(0));
                return new ImageFiles(name, "");
            }
        }

        String big = cands.get(cands.size() - 1).name;
        String thumbnail = "";

        for (FileSize fs : cands) {
            if (nameIsThumbnail(fs.name)) {
                thumbnail = fs.name;
                break; // Use first thumbnail found
            }
        }

        return new ImageFiles(big, thumbnail);
    }

    private boolean nameIsThumbnail(String name) {
        String basename = new File(name).getName();
        return basename.startsWith("th_") && !name.endsWith("hd");
    }

    public String getImg(List<String> fnames) {
        // Filter out empty strings
        List<String> filteredFnames = new ArrayList<>();
        for (String fname : fnames) {
            if (fname != null && !fname.isEmpty()) {
                filteredFnames.add(fname);
            }
        }

        if (filteredFnames.isEmpty()) {
            return null;
        }

        ImageFiles imageFiles = getImgFile(filteredFnames);

        // Try big file first
        String result = getJpgB64(imageFiles.big);
        if (result != null) {
            return result;
        }

        // Try small file
        return getJpgB64(imageFiles.small);
    }

    private String getJpgB64(String imgFile) {
        if (imgFile == null || imgFile.isEmpty()) {
            return null;
        }

        try {
            // True jpeg. Simplest case.
            if (imgFile.endsWith("jpg") && getImageFormat(imgFile).equals("jpeg")) {
                return getFileB64(imgFile);
            }

            byte[] buf;
            if (WxgfAndroidDecoder.isWxgfFile(imgFile)) {
                long start = System.currentTimeMillis();
                buf = wxgfDecoder.decodeWithCache(imgFile, null);
                if (buf == null) {
                    if (!wxgfDecoder.hasServer()) {
                        Log.w(TAG, "wxgf decoder server is not provided. Cannot decode wxgf images. " +
                                "Please follow instructions to create wxgf decoder server if these images need to be decoded.");
                    } else {
                        Log.e(TAG, "Failed to decode wxgf file: " + imgFile);
                    }
                    return null;
                } else {
                    long elapsed = System.currentTimeMillis() - start;
                    if (elapsed > 10 && wxgfDecoder.hasServer()) {
                        Log.i(TAG, String.format("Decoded %s in %.2f seconds", imgFile, elapsed / 1000.0));
                    }
                }
            } else {
                buf = Files.readAllBytes(Paths.get(imgFile));
            }

            // File is not actually jpeg. Convert.
            String format = detectImageFormat(buf);
            if (!"jpeg".equals(format)) {
                try {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(buf, 0, buf.length);
                    if (bitmap == null) {
                        return null;
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos);
                    buf = baos.toByteArray();
                } catch (Exception e) {
                    Log.e(TAG, "Error converting image to JPEG", e);
                    return null;
                }
            }

            return Base64.encodeToString(buf, Base64.NO_WRAP);

        } catch (Exception e) {
            Log.e(TAG, "Error processing image file: " + imgFile, e);
            return null;
        }
    }

    public EmojiReader.EmojiResult getEmojiByMd5(String md5) {
        return emojiReader.getEmoji(md5);
    }

    public String getVideo(String videoId) {
        String videoFile = videoDir + File.separator + videoId + ".mp4";
        String videoThumbnailFile = videoDir + File.separator + videoId + ".jpg";

        File video = new File(videoFile);
        File thumbnail = new File(videoThumbnailFile);

        if (video.exists()) {
            return videoFile;
        } else if (thumbnail.exists()) {
            return videoThumbnailFile;
        }
        return null;
    }

    // Helper methods
    private String getMd5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes("ASCII"));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            Log.e(TAG, "Error computing MD5", e);
            return input;
        }
    }

    private String getFileB64(String filename) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(filename));
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private String getImageFormat(String filename) {
        try {
            byte[] header = new byte[12];
            try (FileInputStream fis = new FileInputStream(filename)) {
                fis.read(header);
            }
            return detectImageFormat(header);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private String detectImageFormat(byte[] header) {
        if (header.length >= 4) {
            // JPEG
            if (header[0] == (byte)0xFF && header[1] == (byte)0xD8) {
                return "jpeg";
            }
            // PNG
            if (header[0] == (byte)0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47) {
                return "png";
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
        return "unknown";
    }

    private byte[] downloadUrl(String urlString) throws Exception {
        Request request = new Request.Builder()
                .url(urlString)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new Exception("HTTP error: " + response.code());
            }
            return response.body().bytes();
        }
    }

    // Implement audio file parsing using AudioParser
    private VoiceResult parseWechatAudioFile(String filename) {
        if (filename == null || filename.isEmpty()) {
            return new VoiceResult("", 0);
        }

        // Initialize audio parser if not already done
        if (audioParser == null) {
            // Note: You need to pass Android Context to Resource constructor
            // and store it as a field to use here
            if (androidContext == null) {
                Log.e(TAG, "Android Context not available for audio parsing");
                return new VoiceResult("", 0);
            }
            audioParser = new AudioParser(androidContext);
        }

        try {
            AudioParser.AudioResult result = audioParser.parseWechatAudioFile(filename);
            return new VoiceResult(result.mp3Base64, (int) Math.ceil(result.duration));
        } catch (Exception e) {
            Log.e(TAG, "Error parsing audio file: " + filename, e);
            return new VoiceResult("", 0);
        }
    }

    public void close() {
        if (executorService != null) {
            executorService.shutdown();
        }
        if (wxgfDecoder != null) {
            wxgfDecoder.close();
        }
        if (avtReader != null) {
            // avtReader.close() if needed
        }
        if (emojiReader != null) {
            emojiReader.flushCache();
        }
        if (audioParser != null) {
            // audioParser.close()； if needed - clean up any resources
        }
    }

    // Data classes
    public static class VoiceResult {
        public final String mp3Data;
        public final int duration;

        public VoiceResult(String mp3Data, int duration) {
            this.mp3Data = mp3Data;
            this.duration = duration;
        }
    }

    private static class FileSize {
        final String name;
        final long size;

        FileSize(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static class ImageFiles {
        final String big;
        final String small;

        ImageFiles(String big, String small) {
            this.big = big;
            this.small = small;
        }
    }
}