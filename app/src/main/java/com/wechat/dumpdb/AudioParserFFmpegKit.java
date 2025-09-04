package com.wechat.dumpdb;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.util.Base64;
import android.util.Log;

import com.antonkarpenko.ffmpegkit.FFmpegKit;
import com.antonkarpenko.ffmpegkit.FFmpegSession;
import com.antonkarpenko.ffmpegkit.ReturnCode;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

import cc.imorning.silk.AudioConfig;
import cc.imorning.silk.SilkDecoder;

public class AudioParserFFmpegKit {
    private static final String TAG = "AudioParserFFmpegKit";

    private Context context;

    public AudioParserFFmpegKit(Context context) {
        this.context = context;
    }

    public AudioParser.AudioResult parseWechatAudioFile(String fileName) {
        try {
            return doParseWechatAudioFile(fileName);
        } catch (Exception e) {
            Log.e(TAG, "Error when parsing audio file " + fileName + ": " + e.getMessage(), e);
            return new AudioParser.AudioResult("", 0);
        }
    }

    private AudioParser.AudioResult doParseWechatAudioFile(String fileName) throws Exception {
        if (fileName == null || fileName.isEmpty()) {
            return new AudioParser.AudioResult("", 0);
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
            float duration;

            if (headerStr.contains("AMR") || headerStr.contains("SILK")) {
                // Use FFmpegKit for both AMR and SILK
//                duration = convertToMp3WithFFmpegKit(fileName, mp3File);
                duration = convertToMp3WithFFmpegKit2(fileName, mp3File);
            } else {
                throw new UnsupportedOperationException("Audio file format cannot be recognized.");
            }

            // Read MP3 file and encode to base64
            byte[] mp3Data = Files.readAllBytes(Paths.get(mp3File));
            String mp3String = Base64.encodeToString(mp3Data, Base64.NO_WRAP);

            return new AudioParser.AudioResult(mp3String, duration);

        } finally {
            // Clean up temporary directory
            deleteDirectory(tempDir);
        }
    }

    private float convertToMp3WithFFmpegKit(String inputFile, String outputFile) throws Exception {
        // Using FFmpegKit library (add to dependencies)
        String command = String.format("-i %s -acodec libmp3lame -ar 16000 -ac 1 -y %s", inputFile, outputFile);


        // 方案1: 尝试强制指定输入格式
//        String command = "-f amr -i \"" + inputFile + "\" -acodec libmp3lame -ar 16000 -ac 1 -y \"" + outputFile + "\"";

//        // 方案2: 尝试使用不同的解码器
//        String command = "-i \"" + inputFile + "\" -acodec mp3 -ar 16000 -ac 1 -y \"" + outputFile + "\"";
//
//        // 方案3: 增加错误容忍参数
//        String command = "-err_detect ignore_err -i \"" + inputFile + "\" -acodec libmp3lame -ar 16000 -ac 1 -y \"" + outputFile + "\"";

        FFmpegSession session = FFmpegKit.execute(command);
        ReturnCode returnCode = session.getReturnCode();
        Log.i(TAG, "语音转结果:" + ReturnCode.isSuccess(returnCode));
        if (ReturnCode.isSuccess(returnCode)) {
            // Get duration
            return getAudioDuration(outputFile);
        } else {
            String failureMessage = session.getFailStackTrace();
            throw new RuntimeException("FFmpeg conversion failed: " + failureMessage);
        }
    }

    private float convertToMp3WithFFmpegKit2(String inputFile, String outputFile) throws Exception {
        try {
            // 第一步：SILK 解码
            String pcmPath = inputFile.replace(".amr", ".pcm");

            // 使用 SILK 库解码
            String result = SilkDecoder.INSTANCE.doDecode(inputFile, pcmPath, AudioConfig.AudioSampleRate.SAMPLE_RATE_16K);

            if (result != null) {
                // 第二步：PCM 转 MP3
                String command = "-f s16le -ar 24000 -ac 1 -i \"" + pcmPath + "\" " +
                        "-acodec libmp3lame -ar 16000 -ac 1 -y \"" + outputFile + "\"";

                FFmpegSession session = FFmpegKit.execute(command);

                if (ReturnCode.isSuccess(session.getReturnCode())) {
                    Log.d("Convert", "转换成功");
                    return getAudioDuration(outputFile);
                } else {
                    Log.e("Convert", "FFmpeg 转换失败: " + session.getAllLogsAsString());
                }

                // 清理临时文件
                new File(pcmPath).delete();
            } else {
                Log.e("Convert", "SILK 解码失败");
            }

        } catch (Exception e) {
            Log.e("Convert", "转换异常", e);
        }
        return 0;
    }

    private float getAudioDuration(String audioFile) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(audioFile);
            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                return Long.parseLong(durationStr) / 1000.0f;
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
        return 0;
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