package com.wechat.dumpdb;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.wechat.dumpdb.common.TextUtil;

import org.json.JSONException;
import org.json.JSONObject;
import org.sqlite.date.DateFormatUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class HTMLRender {
    private static final String TAG = "WeChatHTMLRenderer";

    // Template mappings
    private static final Map<Integer, String> TEMPLATE_FILES = new HashMap<Integer, String>() {{
        put(WeChatMsg.TYPE_MSG, "TP_MSG");
        put(WeChatMsg.TYPE_IMG, "TP_IMG");
        put(WeChatMsg.TYPE_SPEAK, "TP_SPEAK");
        put(WeChatMsg.TYPE_EMOJI, "TP_EMOJI");
        put(WeChatMsg.TYPE_CUSTOM_EMOJI, "TP_EMOJI");
        put(WeChatMsg.TYPE_LINK, "TP_MSG");
        put(WeChatMsg.TYPE_VIDEO_FILE, "TP_VIDEO_FILE");
        put(WeChatMsg.TYPE_QQMUSIC, "TP_QQMUSIC");
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
        String sender = msg.getIsSend() == 1 ? "me" : (msg.getTalker());
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
            case WeChatMsg.TYPE_SPEAK:
                renderVoiceMessage(msg, formatDict);
            case WeChatMsg.TYPE_IMG:
                renderImageMessage(msg, formatDict);
            case WeChatMsg.TYPE_QQMUSIC:
                renderMusicMessage(msg, formatDict);
            case WeChatMsg.TYPE_EMOJI:
            case WeChatMsg.TYPE_CUSTOM_EMOJI:
                renderEmojiMessage(msg, formatDict);
            case WeChatMsg.TYPE_LINK:
                renderLinkMessage(msg, formatDict);
            case WeChatMsg.TYPE_VIDEO_FILE:
                renderVideoMessage(msg, formatDict);
            case WeChatMsg.TYPE_WX_VIDEO:
                // TODO: implement WeChat video rendering
                renderFallbackMessage(msg, formatDict);
            default:
                renderFallbackMessage(msg, formatDict);
        }
        Log.i(TAG, new Gson().toJson(formatDict));
    }

    private void renderFallbackMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        String content = msg.getMsgStr();
        formatDict.put("content", content);
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

    private void renderMusicMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        try {
            JSONObject musicData = new JSONObject(msg.getMsgStr());
            String content = musicData.getString("title") + " - " + musicData.getString("singer");

            if (msg.getImgPath() != null) {
                String imgPath = extractImagePath(msg.getImgPath());
                String img = resourceManager.getImg(Collections.singletonList(imgPath));
                if (img != null) {
                    formatDict.put("img", img);
                }
            }
            formatDict.put("url", musicData.getString("url"));
            formatDict.put("content", content);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse music message", e);
        }
    }

    private void renderEmojiMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        String md5 = extractEmojiMd5(msg);
        if (md5 != null && !md5.isEmpty()) {
            EmojiReader.EmojiResult emoji = resourceManager.getEmojiByMd5(md5);
            if (emoji != null) {
                formatDict.put("emoji_format", emoji.format);
                formatDict.put("emoji_img", emoji.data);
            }
        }
    }

    private void renderLinkMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        LinkInfo linkInfo = parseXmlForLink(msg.getContentXmlReady());
        if (linkInfo != null && linkInfo.getUrl() != null) {
            String content = String.format("<a target=\"_blank\" href=\"%s\">%s</a>",
                    linkInfo.getUrl(),
                    linkInfo.getTitle() != null ? linkInfo.getTitle() : linkInfo.getUrl());
            formatDict.put("content", content);
        }
    }

    private void renderVideoMessage(WeChatMsg msg, Map<String, Object> formatDict) {
        String videoPath = resourceManager.getVideo(msg.getImgPath());
        if (videoPath == null) {
            Log.w(TAG, "Cannot find video: " + msg.getImgPath());
            formatDict.put("content", "VIDEO FILE " + msg.getImgPath());
            return;
        }
        if (videoPath.endsWith(".mp4")) {
            String videoBase64 = TextUtil.getFileB64(videoPath);
            formatDict.put("video_str", videoBase64);
        } else if (videoPath.endsWith(".jpg")) {
            // Only has thumbnail
            String imageBase64 = TextUtil.getFileB64(videoPath);
            ImageData imageData = new ImageData(imageBase64, "jpeg");
            formatDict.put("img", imageData);
        }
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