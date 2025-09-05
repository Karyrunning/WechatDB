package com.wechat.dumpdb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 微信CDN视频解码器
 * 解析 reserved 字段中的加密视频信息
 */
public class WeChatCDNVideoDecoder {
    private static final String TAG = "CDNVideoDecoder";

    // 微信CDN服务器地址
    private static final String[] CDN_SERVERS = {
            "https://vweixinf.tc.qq.com",
            "https://szminorshort.weixin.qq.com",
            "https://finder.video.qq.com",
            "https://mpvideo.qpic.cn"
    };

    /**
     * 解析reserved字段中的视频信息
     */
    public static WeChatVideoInfo parseReservedContent(String reservedXml) {
        if (reservedXml == null || reservedXml.isEmpty()) {
            return null;
        }

        try {
            WeChatVideoInfo videoInfo = new WeChatVideoInfo();

            // 解析XML内容
            Map<String, String> attributes = parseXmlAttributes(reservedXml, "videomsg");

            if (attributes.isEmpty()) {
                Log.w(TAG, "No videomsg attributes found");
                return null;
            }

            // 提取基本信息
            videoInfo.aesKey = attributes.get("aeskey");
            videoInfo.cdnVideoUrl = attributes.get("cdnvideourl");
            videoInfo.cdnThumbAesKey = attributes.get("cdnthumbaeskey");
            videoInfo.cdnThumbUrl = attributes.get("cdnthumburl");
            videoInfo.length = parseLong(attributes.get("length"));
            videoInfo.playLength = parseInt(attributes.get("playlength"));
            videoInfo.cdnThumbLength = parseInt(attributes.get("cdnthumblength"));
            videoInfo.cdnThumbWidth = parseInt(attributes.get("cdnthumbwidth"));
            videoInfo.cdnThumbHeight = parseInt(attributes.get("cdnthumbheight"));
            videoInfo.fromUsername = attributes.get("fromusername");
            videoInfo.md5 = attributes.get("md5");
            videoInfo.newMd5 = attributes.get("newmd5");

            Log.d(TAG, "Parsed video info: " + videoInfo.toString());
            return videoInfo;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing reserved content", e);
            return null;
        }
    }

    /**
     * 从CDN URL数据构建实际的下载链接
     */
    public static String buildCdnDownloadUrl(String cdnUrlData, String aesKey) {
        if (cdnUrlData == null || cdnUrlData.isEmpty()) {
            return null;
        }

        try {
            // CDN URL数据是十六进制编码的
            byte[] urlBytes = hexToBytes(cdnUrlData);

            if (urlBytes == null || urlBytes.length < 20) {
                Log.w(TAG, "Invalid CDN URL data length");
                return null;
            }

            // 解析CDN URL结构
            CDNUrlInfo urlInfo = parseCdnUrlStructure(urlBytes);

            if (urlInfo != null) {
                // 构建最终的下载URL
                String finalUrl = buildFinalDownloadUrl(urlInfo, aesKey);
                Log.d(TAG, "Built download URL: " + finalUrl);
                return finalUrl;
            }

        } catch (Exception e) {
            Log.e(TAG, "Error building CDN download URL", e);
        }

        return null;
    }

    /**
     * 解析CDN URL的二进制结构
     */
    private static CDNUrlInfo parseCdnUrlStructure(byte[] data) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            CDNUrlInfo info = new CDNUrlInfo();

            // 跳过前面的头部信息 (根据微信协议结构)
            buffer.position(4); // 版本信息

            // 读取文件ID长度
            int fileIdLength = buffer.get() & 0xFF;
            if (fileIdLength > 0 && buffer.remaining() >= fileIdLength) {
                byte[] fileIdBytes = new byte[fileIdLength];
                buffer.get(fileIdBytes);
                info.fileId = new String(fileIdBytes);
            }

            // 读取其他字段...
            if (buffer.remaining() >= 8) {
                info.fileSize = buffer.getLong();
            }

            // 跳过到UUID部分 (通常在固定位置)
            if (data.length >= 52) {
                // UUID通常在特定位置
                byte[] uuidBytes = new byte[36];
                System.arraycopy(data, 13, uuidBytes, 0, Math.min(36, data.length - 13));
                info.uuid = new String(uuidBytes);

                // 清理UUID (移除非打印字符)
                info.uuid = info.uuid.replaceAll("[^a-fA-F0-9-]", "");
            }

            Log.d(TAG, "Parsed CDN URL info: " + info.toString());
            return info;

        } catch (Exception e) {
            Log.e(TAG, "Error parsing CDN URL structure", e);
            return null;
        }
    }

    /**
     * 构建最终的下载URL
     */
    private static String buildFinalDownloadUrl(CDNUrlInfo urlInfo, String aesKey) {
        if (urlInfo.uuid == null || urlInfo.uuid.isEmpty()) {
            return null;
        }

        // 微信视频下载URL的常见格式
        String[] urlTemplates = {
                "%s/1007_%s.mp4",
                "%s/110/20304/%s.mp4",
                "%s/sz_mmbiz_mp4/%s/640",
                "%s/mmvideo/%s.mp4"
        };

        // 尝试不同的CDN服务器和URL格式
        for (String server : CDN_SERVERS) {
            for (String template : urlTemplates) {
                String url = String.format(template, server, urlInfo.uuid);

                // 添加必要的参数
                if (aesKey != null && !aesKey.isEmpty()) {
                    url += "?aeskey=" + aesKey;
                }

                Log.d(TAG, "Generated URL: " + url);
                return url; // 返回第一个生成的URL用于测试
            }
        }

        return null;
    }

    /**
     * 下载并解密视频内容
     */
    public static byte[] downloadAndDecryptVideo(WeChatVideoInfo videoInfo) {
        String downloadUrl = buildCdnDownloadUrl(videoInfo.cdnVideoUrl, videoInfo.aesKey);

        if (downloadUrl == null) {
            Log.e(TAG, "Cannot build download URL");
            return null;
        }

        try {
            // 下载加密的视频数据
            byte[] encryptedData = downloadFromUrl(downloadUrl);

            if (encryptedData == null) {
                Log.e(TAG, "Failed to download video data");
                return null;
            }

            // 解密视频数据
            if (videoInfo.aesKey != null && !videoInfo.aesKey.isEmpty()) {
                return decryptVideoData(encryptedData, videoInfo.aesKey);
            } else {
                return encryptedData; // 如果没有AES密钥，可能是未加密的
            }

        } catch (Exception e) {
            Log.e(TAG, "Error downloading and decrypting video", e);
            return null;
        }
    }

    /**
     * 从URL下载数据
     */
    private static byte[] downloadFromUrl(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();

            // 设置微信相关的请求头
            connection.setRequestProperty("User-Agent", "MicroMessenger Client");
            connection.setRequestProperty("Accept", "*/*");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP response code: " + responseCode);
                return null;
            }

            InputStream inputStream = connection.getInputStream();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            inputStream.close();
            connection.disconnect();

            return outputStream.toByteArray();

        } catch (Exception e) {
            Log.e(TAG, "Error downloading from URL: " + urlString, e);
            return null;
        }
    }

    /**
     * 使用AES解密视频数据
     */
    private static byte[] decryptVideoData(byte[] encryptedData, String aesKeyHex) {
        try {
            // 将十六进制AES密钥转换为字节数组
            byte[] aesKey = hexToBytes(aesKeyHex);

            if (aesKey == null || aesKey.length != 16) {
                Log.e(TAG, "Invalid AES key length");
                return null;
            }

            // 创建AES解密器
            SecretKeySpec keySpec = new SecretKeySpec(aesKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);

            // 解密数据
            return cipher.doFinal(encryptedData);

        } catch (Exception e) {
            Log.e(TAG, "Error decrypting video data", e);
            return null;
        }
    }

    /**
     * 获取视频缩略图
     */
    public static byte[] getVideoThumbnail(WeChatVideoInfo videoInfo) {
        String thumbUrl = buildCdnDownloadUrl(videoInfo.cdnThumbUrl, videoInfo.cdnThumbAesKey);

        if (thumbUrl == null) {
            return null;
        }

        try {
            byte[] encryptedThumb = downloadFromUrl(thumbUrl);

            if (encryptedThumb != null && videoInfo.cdnThumbAesKey != null) {
                return decryptVideoData(encryptedThumb, videoInfo.cdnThumbAesKey);
            }

            return encryptedThumb;

        } catch (Exception e) {
            Log.e(TAG, "Error getting video thumbnail", e);
            return null;
        }
    }

    // 辅助方法

    private static Map<String, String> parseXmlAttributes(String xml, String tagName) {
        Map<String, String> attributes = new HashMap<>();

        try {
            Pattern tagPattern = Pattern.compile("<" + tagName + "([^>]*)>");
            Matcher tagMatcher = tagPattern.matcher(xml);

            if (tagMatcher.find()) {
                String attributeString = tagMatcher.group(1);

                Pattern attrPattern = Pattern.compile("(\\w+)=\"([^\"]+)\"");
                Matcher attrMatcher = attrPattern.matcher(attributeString);

                while (attrMatcher.find()) {
                    attributes.put(attrMatcher.group(1), attrMatcher.group(2));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing XML attributes", e);
        }

        return attributes;
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            return null;
        }

        try {
            byte[] bytes = new byte[hex.length() / 2];
            for (int i = 0; i < bytes.length; i++) {
                bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
            return bytes;
        } catch (Exception e) {
            Log.e(TAG, "Error converting hex to bytes", e);
            return null;
        }
    }

    private static long parseLong(String value) {
        try {
            return value != null ? Long.parseLong(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static int parseInt(String value) {
        try {
            return value != null ? Integer.parseInt(value) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // 数据类

    public static class WeChatVideoInfo {
        public String aesKey;
        public String cdnVideoUrl;
        public String cdnThumbAesKey;
        public String cdnThumbUrl;
        public long length;
        public int playLength;
        public int cdnThumbLength;
        public int cdnThumbWidth;
        public int cdnThumbHeight;
        public String fromUsername;
        public String md5;
        public String newMd5;

        @Override
        public String toString() {
            return String.format("WeChatVideoInfo{length=%d, playLength=%d, md5='%s'}",
                    length, playLength, md5);
        }
    }

    public static class CDNUrlInfo {
        public String fileId;
        public String uuid;
        public long fileSize;

        @Override
        public String toString() {
            return String.format("CDNUrlInfo{fileId='%s', uuid='%s', size=%d}",
                    fileId, uuid, fileSize);
        }
    }
}
