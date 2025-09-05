package com.wechat.dumpdb;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AudioParser {
    private static final String TAG = "AudioParser";
    private static final String SILK_DECODER_NAME = "silk_decoder";

    private String silkDecoderPath;
    private Context context;

    public AudioParser(Context context) {
        this.context = context;
        // Initialize silk decoder path - you'll need to include the decoder binary in assets
        this.silkDecoderPath = extractSilkDecoder();
    }

    public AudioResult parseWechatAudioFile(String fileName) {
        try {
            return doParseWechatAudioFile(fileName);
        } catch (Exception e) {
            Log.e(TAG, "Error when parsing audio file " + fileName + ": " + e.getMessage(), e);
            return new AudioResult("", 0L);
        }
    }

    private AudioResult doParseWechatAudioFile(String fileName) throws Exception {
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
            Long duration;

            if (headerStr.contains("AMR")) {
                // Handle AMR format using FFmpeg
                duration = convertAmrToMp3(fileName, mp3File);
            } else if (headerStr.contains("SILK")) {
                // Handle SILK format
                duration = convertSilkToMp3(fileName, mp3File, tempDir);
            } else {
                throw new UnsupportedOperationException("Audio file format cannot be recognized.");
            }

            // Read MP3 file and encode to base64
            byte[] mp3Data = Files.readAllBytes(Paths.get(mp3File));
            String mp3String = Base64.encodeToString(mp3Data, Base64.NO_WRAP);

            return new AudioResult(mp3String, duration);

        } finally {
            // Clean up temporary directory
            deleteDirectory(tempDir);
        }
    }

    private Long convertAmrToMp3(String inputFile, String outputFile) throws Exception {
        // Use FFmpeg to convert AMR to MP3
        String[] ffmpegCmd = {
                getFFmpegPath(),
                "-i", inputFile,
                "-acodec", "libmp3lame",
                "-ar", "16000",
                "-ac", "1",
                "-y", // Overwrite output file
                outputFile
        };

        Process process = Runtime.getRuntime().exec(ffmpegCmd);
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            String error = readProcessError(process);
            throw new RuntimeException("FFmpeg conversion failed: " + error);
        }

        // Get duration using MediaMetadataRetriever
        return getAudioDuration(outputFile);
    }

    private Long convertSilkToMp3(String inputFile, String outputFile, File tempDir) throws Exception {
        if (silkDecoderPath == null || !new File(silkDecoderPath).exists()) {
            throw new RuntimeException("Silk decoder is not available. Please include silk decoder in assets.");
        }

        String baseName = new File(inputFile).getName();
        if (baseName.endsWith(".amr")) {
            baseName = baseName.substring(0, baseName.length() - 4);
        }
        String rawFile = new File(tempDir, baseName + ".raw").getAbsolutePath();

        // Decode SILK to raw PCM
        String[] silkCmd = {silkDecoderPath, inputFile, rawFile};
        Process silkProcess = Runtime.getRuntime().exec(silkCmd);

        // Read silk decoder output to get duration
        String silkOutput = readProcessOutput(silkProcess);
        int exitCode = silkProcess.waitFor();

        if (exitCode != 0) {
            throw new RuntimeException("Silk decoding failed: " + readProcessError(silkProcess));
        }

        // Parse duration from silk decoder output
        Long duration = parseSilkDuration(silkOutput);

        // Convert raw PCM to MP3 using FFmpeg
        String[] ffmpegCmd = {
                getFFmpegPath(),
                "-f", "s16le",    // 16-bit signed little endian
                "-ar", "24000",   // Sample rate 24kHz
                "-ac", "1",       // Mono
                "-i", rawFile,
                "-acodec", "libmp3lame",
                "-y",
                outputFile
        };

        Process ffmpegProcess = Runtime.getRuntime().exec(ffmpegCmd);
        int ffmpegExitCode = ffmpegProcess.waitFor();

        if (ffmpegExitCode != 0) {
            String error = readProcessError(ffmpegProcess);
            throw new RuntimeException("FFmpeg conversion failed: " + error);
        }

        return duration;
    }

    private Long parseSilkDuration(String silkOutput) throws Exception {
        // Look for "File length" in silk decoder output
        Pattern pattern = Pattern.compile("File length\\s*:\\s*([0-9.]+)\\s*ms");
        Matcher matcher = pattern.matcher(silkOutput);

        if (matcher.find()) {
            return Long.parseLong(matcher.group(1)); // Convert ms to seconds
        }

        throw new RuntimeException("Could not parse duration from silk decoder output: " + silkOutput);
    }

    private Long getAudioDuration(String audioFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audioFile);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr); // Convert ms to seconds
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get audio duration using MediaMetadataRetriever", e);
        } finally {
            try {
                retriever.release();
            } catch (Exception e) {
                // Ignore
            }
        }
        return 0L;
    }

    private String getFFmpegPath() {
        // You can use FFmpegKit library or include FFmpeg binary in assets
        // For FFmpegKit: return "ffmpeg" (it handles the path internally)
        // For custom binary: return path to your ffmpeg executable
        return "ffmpeg"; // Assuming FFmpegKit is used
    }

    private String extractSilkDecoder() {
        try {
            // Extract silk decoder from assets to internal storage
            File internalDir = new File(context.getFilesDir(), "native");
            internalDir.mkdirs();

            File silkDecoder = new File(internalDir, SILK_DECODER_NAME);
            if (!silkDecoder.exists()) {
                try (InputStream inputStream = context.getAssets().open("native/" + SILK_DECODER_NAME);
                     FileOutputStream outputStream = new FileOutputStream(silkDecoder)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                    }
                }

                // Make executable
                silkDecoder.setExecutable(true);
            }

            return silkDecoder.getAbsolutePath();
        } catch (Exception e) {
            Log.e(TAG, "Failed to extract silk decoder", e);
            return null;
        }
    }

    private File createTempDirectory() throws IOException {
        File tempDir = new File(context.getCacheDir(), "wechatdump_audio_" + System.currentTimeMillis());
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

    private String readProcessOutput(Process process) throws IOException {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private String readProcessError(Process process) throws IOException {
        StringBuilder error = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                error.append(line).append('\n');
            }
        }
        return error.toString();
    }
}