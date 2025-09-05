package com.wechat.dumpdb;

import android.util.Log;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 WxFileIndex.db 中 wcf:// 路径解析器
 * 将虚拟路径映射到实际文件系统路径
 */
public class WeChatFilePathResolver {
    private static final String TAG = "WeChatFileResolver";

    // 基础目录路径
    private String basePath;

    // wcf协议到实际路径的映射规则
    private final Map<String, PathResolver> pathResolvers;

    public WeChatFilePathResolver(String basePath) {
        this.basePath = basePath;
        this.pathResolvers = initializePathResolvers();
    }

    /**
     * 初始化路径解析器
     */
    private Map<String, PathResolver> initializePathResolvers() {
        Map<String, PathResolver> resolvers = new HashMap<>();

        // attachment: 附件文件
        resolvers.put("attachment", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://attachment/filename -> /data/data/com.tencent.mm/MicroMsg/{userId}/attachment/filename
                return basePath + "/attachment/" + fileName;
            }
        });

        // openapi/thumb: API缩略图
        resolvers.put("openapi", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://openapi/thumb/a3/7f/msgth_xxx -> /data/data/com.tencent.mm/MicroMsg/{userId}/openapi/thumb/a3/7f/msgth_xxx
                String subPath = virtualPath.substring("wcf://openapi/".length());
                return basePath + "/openapi/" + subPath;
            }
        });

        // video: 视频文件
        resolvers.put("video", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://video/xxx.mp4 -> /data/data/com.tencent.mm/MicroMsg/{userId}/video/xxx.mp4
                // wcf://video/xxx.jpg -> /data/data/com.tencent.mm/MicroMsg/{userId}/video/xxx.jpg (缩略图)
                return basePath + "/video/" + fileName;
            }
        });

        // image2: 图片文件 (新版本微信)
        resolvers.put("image2", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://image2/88/2a/th_xxx -> /data/data/com.tencent.mm/MicroMsg/{userId}/image2/88/2a/th_xxx
                String subPath = virtualPath.substring("wcf://image2/".length());
                return basePath + "/image2/" + subPath;
            }
        });

        // image: 图片文件 (旧版本微信)
        resolvers.put("image", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://image/xx/xx/xxx -> /data/data/com.tencent.mm/MicroMsg/{userId}/image/xx/xx/xxx
                String subPath = virtualPath.substring("wcf://image/".length());
                return basePath + "/image/" + subPath;
            }
        });

        // voice: 语音文件
        resolvers.put("voice", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                // wcf://voice/xxx.amr -> /data/data/com.tencent.mm/MicroMsg/{userId}/voice2/xx/xx/msg_xxx.amr
                return resolveVoicePath(fileName);
            }
        });

        // voice2: 语音文件 (新版本)
        resolvers.put("voice2", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                String subPath = virtualPath.substring("wcf://voice2/".length());
                return basePath + "/voice2/" + subPath;
            }
        });

        // emoji: 表情文件
        resolvers.put("emoji", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                String subPath = virtualPath.substring("wcf://emoji/".length());
                return basePath + "/emoji/" + subPath;
            }
        });

        // sfs: 文件系统相关
        resolvers.put("sfs", new PathResolver() {
            @Override
            public String resolve(String virtualPath, String fileName) {
                String subPath = virtualPath.substring("wcf://sfs/".length());
                return basePath + "/sfs/" + subPath;
            }
        });

        return resolvers;
    }

    /**
     * 解析 wcf:// 虚拟路径到实际文件系统路径
     *
     * @param wcfPath wcf:// 格式的虚拟路径
     * @return 实际文件系统路径，如果无法解析则返回null
     */
    public String resolvePath(String wcfPath) {
        if (wcfPath == null || !wcfPath.startsWith("wcf://")) {
            return null;
        }

        try {
            // 解析协议类型
            Pattern pattern = Pattern.compile("wcf://([^/]+)/(.*)");
            Matcher matcher = pattern.matcher(wcfPath);

            if (!matcher.matches()) {
                Log.w(TAG, "Invalid wcf path format: " + wcfPath);
                return null;
            }

            String protocol = matcher.group(1);
            String fileName = matcher.group(2);

            // 查找对应的解析器
            PathResolver resolver = pathResolvers.get(protocol);
            if (resolver == null) {
                Log.w(TAG, "Unknown wcf protocol: " + protocol);
                return null;
            }

            String realPath = resolver.resolve(wcfPath, fileName);
            Log.d(TAG, "Resolved: " + wcfPath + " -> " + realPath);

            return realPath;

        } catch (Exception e) {
            Log.e(TAG, "Error resolving wcf path: " + wcfPath, e);
            return null;
        }
    }

    /**
     * 特殊处理语音文件路径
     * 语音文件通常存储在 voice2/xx/xx/ 目录下
     */
    private String resolveVoicePath(String fileName) {
        if (fileName == null || fileName.length() < 4) {
            return basePath + "/voice2/" + fileName;
        }

        // 提取文件名的前几位作为子目录
        String subDir1 = fileName.substring(0, 2);
        String subDir2 = fileName.substring(2, 4);

        return basePath + "/voice2/" + subDir1 + "/" + subDir2 + "/msg_" + fileName;
    }

    /**
     * 检查文件是否存在
     */
    public boolean fileExists(String wcfPath) {
        String realPath = resolvePath(wcfPath);
        if (realPath == null) {
            return false;
        }

        File file = new File(realPath);
        return file.exists() && file.isFile();
    }

    /**
     * 获取文件信息
     */
    public FileInfo getFileInfo(String wcfPath) {
        String realPath = resolvePath(wcfPath);
        if (realPath == null) {
            return null;
        }

        File file = new File(realPath);
        if (!file.exists()) {
            return null;
        }

        FileInfo info = new FileInfo();
        info.wcfPath = wcfPath;
        info.realPath = realPath;
        info.fileName = file.getName();
        info.size = file.length();
        info.lastModified = file.lastModified();
        info.isDirectory = file.isDirectory();

        return info;
    }

    /**
     * 批量解析路径
     */
    public Map<String, String> resolvePaths(String[] wcfPaths) {
        Map<String, String> results = new HashMap<>();

        for (String wcfPath : wcfPaths) {
            String realPath = resolvePath(wcfPath);
            if (realPath != null) {
                results.put(wcfPath, realPath);
            }
        }

        return results;
    }

    /**
     * 获取可能的备用路径
     * 有些文件可能存在多个位置
     */
    public String[] getAlternativePaths(String wcfPath) {
        String mainPath = resolvePath(wcfPath);
        if (mainPath == null) {
            return new String[0];
        }

        // 根据文件类型提供备用路径
        if (wcfPath.contains("/image2/")) {
            // 图片可能也在 image 目录
            String altPath = mainPath.replace("/image2/", "/image/");
            return new String[]{mainPath, altPath};
        } else if (wcfPath.contains("/video/")) {
            // 视频缩略图和视频文件
            if (wcfPath.endsWith(".mp4")) {
                String thumbPath = mainPath.replace(".mp4", ".jpg");
                return new String[]{mainPath, thumbPath};
            }
        }

        return new String[]{mainPath};
    }

    // 内部接口
    private interface PathResolver {
        String resolve(String virtualPath, String fileName);
    }

    // 文件信息类
    public static class FileInfo {
        public String wcfPath;
        public String realPath;
        public String fileName;
        public long size;
        public long lastModified;
        public boolean isDirectory;

        @Override
        public String toString() {
            return String.format("FileInfo{wcf='%s', real='%s', size=%d}", wcfPath, realPath, size);
        }
    }

    // 使用示例
    public static void main(String[] args) {
        // 示例用法
        String basePath = "/storage/emulated/0/Download/com.tencent.mm/MicroMsg/3d7f491a654552450d1352e52d0891a1";
        WeChatFilePathResolver resolver = new WeChatFilePathResolver(basePath);

        // 测试路径解析
        String[] testPaths = {
                "wcf://attachment/the-bath-song-flashcards.pdf",
                "wcf://openapi/thumb/a3/7f/msgth_a37fd3f5355c2e670da774a4af9ae9c0",
                "wcf://video/2504181043492841.mp4",
                "wcf://video/2504181043492841.jpg",
                "wcf://image2/88/2a/th_882a576c6183808258948920e8f9f266"
        };

        for (String wcfPath : testPaths) {
            String realPath = resolver.resolvePath(wcfPath);
            System.out.println(wcfPath + " -> " + realPath);

            // 检查文件是否存在
            boolean exists = resolver.fileExists(wcfPath);
            System.out.println("  Exists: " + exists);
        }
    }
}
