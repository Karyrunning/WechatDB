package com.wechat.dumpdb;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import xyz.xxin.silkdecoder.SilkDecoder;

public class AudioParserFFmpegKit {
    private static final String TAG = "AudioParserFFmpegKit";

    private Context context;

    public AudioParserFFmpegKit(Context context) {
        this.context = context;
    }

    public AudioResult doParseWechatAudioFile(String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()) {
            return new AudioResult("", 0L);
        }

        // Create temporary directory
        File tempDir = createTempDirectory();
        try {
            String baseName = new File(fileName).getName();
            if (baseName.endsWith(".amr")) {
                baseName = baseName.substring(0, baseName.length() - 4);
            }
            String mp3File = new File(tempDir, baseName + ".mp3").getAbsolutePath();

            // Read header to determine format
            byte[] header = new byte[10];
            try (FileInputStream fis = new FileInputStream(fileName)) {
                fis.read(header);
            }

            String headerStr = new String(header);
            boolean result = false;
            if (headerStr.contains("AMR") || headerStr.contains("SILK")) {
                // Use FFmpegKit for both AMR and SILK
//                duration = convertToMp3WithFFmpegKit(fileName, mp3File);
//                decodeAudioToMp3(fileName, tempDir.getAbsolutePath(), baseName);
                result = SilkDecoder.decodeToMp3(fileName, mp3File);
//                decodeAudioToMp3Alternative(fileName, tempDir.getAbsolutePath(), baseName);
            } else {
                throw new UnsupportedOperationException("Audio file format cannot be recognized.");
            }

            // Read MP3 file and encode to base64
//            byte[] mp3Data = Files.readAllBytes(Paths.get(mp3File));
//            String mp3String = Base64.encodeToString(mp3Data, Base64.NO_WRAP);
            if (result) {
                return new AudioResult(mp3File, getAudioDuration(mp3File));
            }
            return new AudioResult("", 0L);
        } finally {
            // Clean up temporary directory
//            deleteDirectory(tempDir);
        }
    }

    /**
     * 清理临时文件
     */
    private static void cleanupTempFiles(String... paths) {
        for (String path : paths) {
            File file = new File(path);
            if (file.exists()) {
                boolean deleted = file.delete();
                Log.d(TAG, "删除临时文件 " + path + ": " + deleted);
            }
        }
    }

    private Long getAudioDuration(String audioFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audioFile);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get audio duration", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // Ignore
            }
        }
        return 0L;
    }

    private File createTempDirectory() throws IOException {
        File tempDir = new File(context.getExternalCacheDir(), "wechatdump_audio_" + System.currentTimeMillis());
        if (!tempDir.mkdirs()) {
            throw new IOException("Failed to create temporary directory: " + tempDir);
        }
        return tempDir;
    }

    private void deleteDirectory(File directory) {
        if (directory == null || !directory.exists()) {
            return;
        }

        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }
}