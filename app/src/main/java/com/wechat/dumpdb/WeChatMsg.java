package com.wechat.dumpdb;

import android.util.Log;

import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

public class WeChatMsg {
    private static final String TAG = "WeChatMsg";

    // 消息类型常量
    public static final int TYPE_MSG = 1;
    public static final int TYPE_IMG = 3;
    public static final int TYPE_SPEAK = 34;
    public static final int TYPE_NAMECARD = 42;
    public static final int TYPE_VIDEO_FILE = 43;
    public static final int TYPE_EMOJI = 47;
    public static final int TYPE_LOCATION = 48;
    public static final int TYPE_LINK = 49;
    public static final int TYPE_VOIP = 50;
    public static final int TYPE_WX_VIDEO = 62;
    public static final int TYPE_SYSTEM = 10000;
    public static final int TYPE_CUSTOM_EMOJI = 1048625;
    public static final int TYPE_REDENVELOPE = 436207665;
    public static final int TYPE_MONEY_TRANSFER = 419430449;
    public static final int TYPE_LOCATION_SHARING = -1879048186;
    public static final int TYPE_REPLY = 822083633;
    public static final int TYPE_FILE = 1090519089;
    public static final int TYPE_QQMUSIC = 1040187441;
    public static final int TYPE_APP_MSG = 16777265;

    // 已知消息类型数组
    private static final int[] KNOWN_TYPES = {
            TYPE_MSG, TYPE_IMG, TYPE_SPEAK, TYPE_NAMECARD, TYPE_VIDEO_FILE,
            TYPE_EMOJI, TYPE_LOCATION, TYPE_LINK, TYPE_VOIP, TYPE_WX_VIDEO,
            TYPE_SYSTEM, TYPE_CUSTOM_EMOJI, TYPE_REDENVELOPE, TYPE_MONEY_TRANSFER,
            TYPE_LOCATION_SHARING, TYPE_REPLY, TYPE_FILE, TYPE_QQMUSIC, TYPE_APP_MSG
    };

    private String msgId;
    private long msgSvrId;
    private int type;
    private int isSend;
    private long createTime;
    private String talker;
    private String content;
    private String imgPath;
    private String chat;
    private String chatNickname;
    private String talkerNickname;
    private boolean knownType;

    private String reserved;

    public WeChatMsg(long msgSvrId, int type, int isSend, long createTime,
                     String talker, String content, String imgPath, String chat,
                     String chatNickname, String talkerNickname, String msgId, String reserved) {
        this.msgSvrId = msgSvrId;
        this.type = type;
        this.isSend = isSend;
        this.createTime = createTime;
        this.talker = talker;
        this.content = content != null ? content : "";
        this.imgPath = imgPath;
        this.chat = chat;
        this.chatNickname = chatNickname;
        this.talkerNickname = talkerNickname;
        this.knownType = isKnownType(type);
        this.msgId = msgId;
        this.reserved = reserved;
    }

    /**
     * 检查是否为已知类型
     */
    private boolean isKnownType(int type) {
        for (int knownType : KNOWN_TYPES) {
            if (knownType == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断是否应该过滤该类型的消息
     */
    public static boolean shouldFilterType(int type) {
        return type == TYPE_SYSTEM;
    }

    /**
     * 获取消息内容字符串
     */
    public String getMsgStr() {
        try {
            switch (type) {
                case TYPE_LOCATION:
                    return parseLocationMsg();
                case TYPE_LINK:
                    return parseLinkMsg();
                case TYPE_NAMECARD:
                    return parseNameCardMsg();
                case TYPE_APP_MSG:
                    return parseAppMsg();
                case TYPE_VIDEO_FILE:
                    return "VIDEO FILE";
                case TYPE_WX_VIDEO:
                    return "WeChat VIDEO";
                case TYPE_VOIP:
                    return "REQUEST VIDEO CHAT";
                case TYPE_LOCATION_SHARING:
                    return "LOCATION SHARING";
                case TYPE_EMOJI:
                    return content;
                case TYPE_REDENVELOPE:
                    return parseRedEnvelopeMsg();
                case TYPE_MONEY_TRANSFER:
                    return parseMoneyTransferMsg();
                case TYPE_REPLY:
                    return parseReplyMsg();
                case TYPE_FILE:
                    return parseFileMsg();
                case TYPE_QQMUSIC:
                    return parseQQMusicMsg();
                default:
                    return content;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing message content: " + e.getMessage());
            return content;
        }
    }

    /**
     * 解析位置消息
     */
    private String parseLocationMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady(), "", org.jsoup.parser.Parser.xmlParser());
            Element location = doc.selectFirst("location");
            if (location != null) {
                String label = location.attr("label");
                String poiname = location.attr("poiname");
                String x = location.attr("x");
                String y = location.attr("y");

                if (poiname != null && !poiname.isEmpty()) {
                    label = poiname;
                }
                return "LOCATION:" + label + " (" + x + "," + y + ")";
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing location: " + e.getMessage());
        }
        return "LOCATION: unknown";
    }

    /**
     * 解析链接消息
     */
    private String parseLinkMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady());
            Element urlElement = doc.selectFirst("url");
            if (urlElement != null && !urlElement.text().isEmpty()) {
                return "URL:" + urlElement.text();
            } else {
                Element titleElement = doc.selectFirst("title");
                if (titleElement != null && !titleElement.text().isEmpty()) {
                    return "FILE:" + titleElement.text();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing link: " + e.getMessage());
        }
        return "NOT IMPLEMENTED: " + getContentXmlReady();
    }

    /**
     * 解析名片消息
     */
    private String parseNameCardMsg() {
        return "NAMECARD: " + getContentXmlReady();
    }

    /**
     * 解析应用消息
     */
    private String parseAppMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady(), "", org.jsoup.parser.Parser.xmlParser());
            Element title = doc.selectFirst("title");
            if (title != null) {
                return title.text();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing app message: " + e.getMessage());
        }
        return content;
    }

    /**
     * 解析红包消息
     */
    private String parseRedEnvelopeMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady(), "", org.jsoup.parser.Parser.xmlParser());
            Element sendertitle = doc.selectFirst("sendertitle");
            if (sendertitle != null && !sendertitle.text().isEmpty()) {
                return "[RED ENVELOPE]\n" + sendertitle.text();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing red envelope: " + e.getMessage());
        }
        return "[RED ENVELOPE]";
    }

    /**
     * 解析转账消息
     */
    private String parseMoneyTransferMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady(), "", org.jsoup.parser.Parser.xmlParser());
            Element des = doc.selectFirst("des");
            if (des != null && !des.text().isEmpty()) {
                return "[Money Transfer]\n" + des.text();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing money transfer: " + e.getMessage());
        }
        return "[Money Transfer]";
    }

    /**
     * 解析回复消息
     */
    private String parseReplyMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady());
            Elements titles = doc.select("title");
            if (!titles.isEmpty()) {
                return titles.first().text();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing reply message: " + e.getMessage());
        }
        return getContentXmlReady();
    }

    /**
     * 解析文件消息
     */
    private String parseFileMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady());
            Elements titles = doc.select("title");
            if (!titles.isEmpty()) {
                return "FILE:" + titles.first().text();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing file message: " + e.getMessage());
        }
        return getContentXmlReady();
    }

    /**
     * 解析QQ音乐消息
     */
    private String parseQQMusicMsg() {
        try {
            Document doc = Jsoup.parse(getContentXmlReady());
            Elements titles = doc.select("title");
            Elements descriptions = doc.select("des");
            Elements urls = doc.select("url");

            if (!titles.isEmpty() && !descriptions.isEmpty() && !urls.isEmpty()) {
                JSONObject musicInfo = new JSONObject();
                musicInfo.put("title", titles.first().text());
                musicInfo.put("singer", descriptions.first().text());
                musicInfo.put("url", urls.first().text());
                return musicInfo.toString();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing QQ music: " + e.getMessage());
        }
        return content;
    }

    /**
     * 获取处理过的XML内容（移除XML头）
     */
    public String getContentXmlReady() {
        if (content == null) return "";
        // 移除XML头部以避免可能的错误
        Pattern headerPattern = Pattern.compile("<\\?.*\\?>");
        return headerPattern.matcher(content).replaceAll("");
    }

    /**
     * 判断是否为群聊消息
     */
    public boolean isChatroom() {
        return !talker.equals(chat);
    }

    /**
     * 获取群聊ID
     */
    public String getChatroom() {
        return isChatroom() ? chat : "";
    }

    /**
     * 获取表情产品ID
     */
    public String getEmojiProductId() {
        if (type != TYPE_EMOJI) {
            throw new IllegalStateException("Wrong call to getEmojiProductId()!");
        }

        try {
            Document doc = Jsoup.parse(getContentXmlReady(), "", org.jsoup.parser.Parser.xmlParser());
            Element emoji = doc.selectFirst("emoji");
            if (emoji != null) {
                return emoji.attr("productid");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting emoji product id: " + e.getMessage());
        }
        return null;
    }

    @Override
    public String toString() {
        String senderName = (isSend == 1) ? "me" : talkerNickname;
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        String timeStr = sdf.format(new Date(createTime));

        String result = type + "|" + senderName + ":" + timeStr + ":" + getMsgStr();

        if (imgPath != null && !imgPath.isEmpty()) {
            result += "|img:" + imgPath;
        }

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        WeChatMsg other = (WeChatMsg) obj;
        return createTime == other.createTime &&
                talker.equals(other.talker) &&
                isSend == other.isSend;
    }

    public String getMsgId() {
        return msgId;
    }

    public long getMsgSvrId() {
        return msgSvrId;
    }

    public int getType() {
        return type;
    }

    public int getIsSend() {
        return isSend;
    }

    public long getCreateTime() {
        return createTime;
    }

    public String getTalker() {
        return talker;
    }

    public String getContent() {
        return content;
    }

    public String getImgPath() {
        return imgPath;
    }

    public String getChat() {
        return chat;
    }

    public String getChatNickname() {
        return chatNickname;
    }

    public String getTalkerNickname() {
        return talkerNickname;
    }

    public boolean isKnownType() {
        return knownType;
    }

    public void setMsgSvrId(long msgSvrId) {
        this.msgSvrId = msgSvrId;
    }

    public void setType(int type) {
        this.type = type;
    }

    public void setIsSend(int isSend) {
        this.isSend = isSend;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public void setTalker(String talker) {
        this.talker = talker;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void setImgPath(String imgPath) {
        this.imgPath = imgPath;
    }

    public void setChat(String chat) {
        this.chat = chat;
    }

    public void setChatNickname(String chatNickname) {
        this.chatNickname = chatNickname;
    }

    public void setTalkerNickname(String talkerNickname) {
        this.talkerNickname = talkerNickname;
    }

    public String getReserved() {
        return reserved;
    }
}
