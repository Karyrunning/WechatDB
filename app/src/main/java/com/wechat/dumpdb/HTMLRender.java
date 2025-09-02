package com.wechat.dumpdb;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.sqlite.date.DateFormatUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HTMLRender {
    private static final String TAG = "WeChatHTMLRenderer";

    // Message types (from original Python code)
    public static final int TYPE_MSG = 1;
    public static final int TYPE_IMG = 3;
    public static final int TYPE_SPEAK = 34;
    public static final int TYPE_EMOJI = 47;
    public static final int TYPE_CUSTOM_EMOJI = 49;
    public static final int TYPE_LINK = 5;
    public static final int TYPE_VIDEO_FILE = 62;
    public static final int TYPE_QQMUSIC = 436207665;
    public static final int TYPE_WX_VIDEO = 43;

    // Template mappings
    private static final Map<Integer, String> TEMPLATE_FILES = new HashMap<Integer, String>() {{
        put(TYPE_MSG, "TP_MSG");
        put(TYPE_IMG, "TP_IMG");
        put(TYPE_SPEAK, "TP_SPEAK");
        put(TYPE_EMOJI, "TP_EMOJI");
        put(TYPE_CUSTOM_EMOJI, "TP_EMOJI");
        put(TYPE_LINK, "TP_MSG");
        put(TYPE_VIDEO_FILE, "TP_VIDEO_FILE");
        put(TYPE_QQMUSIC, "TP_QQMUSIC");
    }};

    private Context context;
    private WeChatDBParser parser;
    private Resource resourceManager;
    private Map<Integer, Integer> unknownTypeCounts;


    public HTMLRender(Context context, WeChatDBParser parser, Resource resourceManager) {
        this.context = context;
        this.parser = parser;
        this.resourceManager = resourceManager;
        this.unknownTypeCounts = new HashMap<>();
    }

    /**
     * Render a single WeChat message to HTML
     */
    public void renderMessage(WeChatMsg msg) {
        String sender = msg.getIsSend() == 1 ? "me" : ("you " + msg.getTalker());
        Map<String, Object> formatDict = new HashMap<>();
        formatDict.put("sender_label", sender);
        formatDict.put("time", DateFormatUtils.format(msg.getCreateTime(), "yyyy-MM-dd HH:mm:ss"));

        if (!msg.isKnownType()) {
            Integer count = unknownTypeCounts.get(msg.getType());
            unknownTypeCounts.put(msg.getType(), count == null ? 1 : count + 1);
        }

        // Handle chatroom nickname
        if (msg.getIsSend() == 0 && msg.isChatroom()) {
            formatDict.put("nickname", msg.getTalkerNickname());
        } else {
            formatDict.put("nickname", " ");
        }
        switch (msg.getType()) {
            case TYPE_SPEAK:
                renderVoiceMessage(msg, formatDict);
            case TYPE_IMG:
                renderImageMessage(msg, formatDict);
            case TYPE_QQMUSIC:
                renderMusicMessage(msg, formatDict);
            case TYPE_EMOJI:
            case TYPE_CUSTOM_EMOJI:
                renderEmojiMessage(msg, formatDict);
            case TYPE_LINK:
                renderLinkMessage(msg, formatDict);
            case TYPE_VIDEO_FILE:
                renderVideoMessage(msg, formatDict);
            case TYPE_WX_VIDEO:
                // TODO: implement WeChat video rendering
                renderFallbackMessage(msg, formatDict);
            default:
                renderFallbackMessage(msg, formatDict);
        }
    }

    private void renderVoiceMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        Resource.VoiceResult voiceData = resourceManager.getVoiceMp3(msg.getImgPath());
        if (voiceData != null) {
            formatDict.put("voice_duration", voiceData.duration);
            formatDict.put("voice_str", voiceData.mp3Data);
        }
    }

    private void renderImageMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        String imgPath = extractImagePath(msg.getImgPath());
        if (imgPath == null || imgPath.isEmpty()) {
            Log.w(TAG, "No imgpath in image message");
            return;
        }
        String bigImgPath = parser.getImgInfo().get(String.valueOf(msg.getMsgSvrId()));
        List<String> filenames = new ArrayList<>();
        filenames.add(imgPath);
        if (bigImgPath != null) filenames.add(bigImgPath);

        String img = resourceManager.getImg(filenames);
        if (img == null) {
            Log.w(TAG, "No image found for: " + imgPath);
            return;
        }
        formatDict.put("img", img);
    }

    private String renderMusicMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        try {
            JSONObject musicData = new JSONObject(msg.getMsgStr());
            String content = musicData.getString("title") + " - " + musicData.getString("singer");

            if (msg.getImgPath() != null) {
                String imgPath = extractImagePath(msg.getImgPath());
                ImageData img = resourceManager.getImage(Collections.singletonList(imgPath));
                if (img != null) {
                    formatDict.put("img", img);
                }
            } else {
                template = getTemplate("TP_QQMUSIC_NOIMG");
            }

            formatDict.put("url", musicData.getString("url"));
            formatDict.put("content", content);
            return formatTemplate(template, formatDict);

        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse music message", e);
            return renderFallbackMessage(msg, formatDict);
        }
    }

    private String renderEmojiMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        if (template == null) return renderFallbackMessage(msg, formatDict);

        String md5 = extractEmojiMd5(msg);
        if (md5 != null && !md5.isEmpty()) {
            EmojiData emoji = resourceManager.getEmojiByMd5(md5);
            if (emoji != null) {
                formatDict.put("emoji_format", emoji.getFormat());
                formatDict.put("emoji_img", emoji.getBase64Data());
                return formatTemplate(template, formatDict);
            }
        }

        return renderFallbackMessage(msg, formatDict);
    }

    private String renderLinkMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        if (template == null) return renderFallbackMessage(msg, formatDict);

        LinkInfo linkInfo = parseXmlForLink(msg.getContentXmlReady());
        if (linkInfo != null && linkInfo.getUrl() != null) {
            String content = String.format("<a target=\"_blank\" href=\"%s\">%s</a>",
                    linkInfo.getUrl(),
                    linkInfo.getTitle() != null ? linkInfo.getTitle() : linkInfo.getUrl());
            formatDict.put("content", content);
            return formatTemplate(template, formatDict);
        }

        return renderFallbackMessage(msg, formatDict);
    }

    private String renderVideoMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        if (template == null) return renderFallbackMessage(msg, formatDict);

        String videoPath = resourceManager.getVideo(msg.getImgPath());
        if (videoPath == null) {
            Log.w(TAG, "Cannot find video: " + msg.getImgPath());
            formatDict.put("content", "VIDEO FILE " + msg.getImgPath());
            return formatTemplate(getTemplate("TP_MSG"), formatDict);
        }

        if (videoPath.endsWith(".mp4")) {
            String videoBase64 = resourceManager.getFileBase64(videoPath);
            formatDict.put("video_str", videoBase64);
            return formatTemplate(template, formatDict);
        } else if (videoPath.endsWith(".jpg")) {
            // Only has thumbnail
            String imageBase64 = resourceManager.getFileBase64(videoPath);
            ImageData imageData = new ImageData(imageBase64, "jpeg");
            formatDict.put("img", imageData);
            return formatTemplate(getTemplate("TP_IMG"), formatDict);
        }

        return renderFallbackMessage(msg, formatDict);
    }

    private String extractImagePath(String imgPath) {
        if (imgPath == null) return null;
        String[] parts = imgPath.split("_");
        return parts.length > 0 ? parts[parts.length - 1] : null;
    }

    private String extractEmojiMd5(WeChatMsg msg) {
        // This would need XML parsing implementation
        // Simplified version - you'll need to implement proper XML parsing
        if (msg.getContent() != null && msg.getContent().contains("emoticonmd5")) {
            // Parse XML to extract md5 - implementation needed
            return parseXmlForEmojiMd5(msg.getContent());
        }
        return msg.getImgPath();
    }

    private Set<String> extractTalkers(List<WeChatMsg> messages) {
        Set<String> talkers = new HashSet<>();
        for (WeChatMsg msg : messages) {
            if (msg.isChatroom()) {
                talkers.add(msg.getTalker());
            } else {
                talkers.add(messages.get(0).getTalker());
                break;
            }
        }
        return talkers;
    }

    private void prepareAvatarCss(Set<String> talkers) {
        try {
            String avatarTemplate = loadAssetFile("avatar.css.tpl");
            StringBuilder avatarCss = new StringBuilder();

            // My avatar
            String myAvatar = resourceManager.getAvatar(parser.getUsername());
            avatarCss.append(formatTemplate(avatarTemplate,
                    Map.of("name", "me", "avatar", myAvatar)));

            // Other avatars
            for (String talker : talkers) {
                String avatar = resourceManager.getAvatar(talker);
                avatarCss.append(formatTemplate(avatarTemplate,
                        Map.of("name", talker, "avatar", avatar)));
            }

            cssStrings.add(avatarCss.toString());
        } catch (IOException e) {
            Log.e(TAG, "Failed to prepare avatar CSS", e);
        }
    }

    private String formatTemplate(Map<String, Object> params) {
        if (template == null) return "";

        String result = template;
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue().toString() : "";
            result = result.replace(placeholder, value);
        }
        return result;
    }

    private String compressCss(String css) {
        // Simple CSS compression - remove comments and extra whitespace
        return css.replaceAll("/\\*.*?\\*/", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // Placeholder methods - need implementation based on your XML parsing library
    private LinkInfo parseXmlForLink(String xml) {
        // TODO: Implement XML parsing to extract URL and title
        return null;
    }

    private String parseXmlForEmojiMd5(String xml) {
        // TODO: Implement XML parsing to extract emoji MD5
        return null;
    }
}

class ImageData {
    private String base64Data;
    private String format;

    public ImageData(String base64Data, String format) {
        this.base64Data = base64Data;
        this.format = format;
    }

    public String getBase64Data() {
        return base64Data;
    }

    public String getFormat() {
        return format;
    }
}

class EmojiData {
    private String base64Data;
    private String format;

    public String getBase64Data() {
        return base64Data;
    }

    public String getFormat() {
        return format;
    }
}

class LinkInfo {
    private String url;
    private String title;

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }
}

interface MessageSlicer {
    List<List<WeChatMessage>> slice(List<WeChatMessage> messages);
}

class MessageSlicerByTime implements MessageSlicer {
    public List<List<WeChatMessage>> slice(List<WeChatMessage> messages) {
        // Implementation needed
        return new ArrayList<>();
    }
}

class MessageSlicerBySize implements MessageSlicer {
    public List<List<WeChatMessage>> slice(List<WeChatMessage> messages) {
        // Implementation needed
        return new ArrayList<>();
    }
}