package com.wechat.dumpdb;

import android.database.Cursor;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WeChatDBParser {
    private static final String TAG = "WeChatDBParser";

    // 数据库字段定义
    private static final String[] FIELDS = {
            "msgSvrId", "type", "isSend", "createTime", "talker", "content", "imgPath", "msgId"
    };

    private SQLiteDatabase database;
    private SQLiteDatabase filedb;
    private Map<String, String> contacts = new HashMap<>();           // username -> nickname
    private Map<String, List<String>> contactsRev = new HashMap<>();  // nickname -> List<username>
    private Map<String, List<WeChatMsg>> msgsByChat = new HashMap<>(); // chat -> List<messages>
    private Map<String, String> emojiGroups = new HashMap<>();
    private Map<String, EmojiInfo> emojiInfo = new HashMap<>();
    private Map<String, String> imgInfo = new HashMap<>();
    private Map<String, String> avatarUrls = new HashMap<>();
    private String username;

    public WeChatDBParser(String dbRoot, String password) {
        try {
            String dbPath = dbRoot + "/EnMicroMsg.db";
            database = CipherDBHelper.openDatabase(dbPath, password);
            String fileDbPath = dbRoot + "/WxFileIndex.db";
            filedb = CipherDBHelper.openDatabase(fileDbPath, password);
            parse();
        } catch (Exception e) {
            Log.e(TAG, "Failed to open database: " + e.getMessage());
        }
    }

    /**
     * 解析数据库的主方法
     */
    private void parse() {
        parseContact();
        parseUserInfo();
        parseMsg();
        parseImgInfo();
        parseEmoji();
        parseImgFlag();
    }

    /**
     * 解析联系人表
     */
    private void parseContact() {
        String query = "SELECT username, conRemark, nickname FROM rcontact";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(query, null);
            while (cursor.moveToNext()) {
                String username = cursor.getString(0);
                String remark = cursor.getString(1);
                String nickname = cursor.getString(2);

                String displayName = (remark != null && !remark.isEmpty()) ? remark : nickname;
                contacts.put(username, displayName);

                // 建立反向索引
                List<String> userList = contactsRev.get(displayName);
                if (userList == null) {
                    userList = new ArrayList<>();
                    contactsRev.put(displayName, userList);
                }
                userList.add(username);
            }
            Log.i(TAG, "Found " + contacts.size() + " contacts");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing contacts: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 解析用户信息表
     */
    private void parseUserInfo() {
        String query = "SELECT id, value FROM userinfo";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(query, null);
            Map<Integer, String> userInfo = new HashMap<>();

            while (cursor.moveToNext()) {
                int id = cursor.getInt(0);
                String value = cursor.getString(1);
                userInfo.put(id, value);
            }

            // 获取用户名
            username = userInfo.get(2);
            if (username == null) {
                String nickname = userInfo.get(4);
                if (nickname != null) {
                    List<String> users = contactsRev.get(nickname);
                    if (users != null && !users.isEmpty()) {
                        username = users.get(0);
                    }
                }
            }

            Log.i(TAG, "Username: " + username);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing user info: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 解析消息表
     */
    private void parseMsg() {
        String query = "SELECT " + String.join(",", FIELDS) + " FROM message";
        Cursor cursor = null;
        int totalMsgCount = 0;

        try {
            cursor = database.rawQuery(query, null);
            while (cursor.moveToNext()) {
                WeChatMsg msg = parseMsgRow(cursor);
                if (msg != null && !WeChatMsg.shouldFilterType(msg.getType())) {
                    List<WeChatMsg> chatMsgs = msgsByChat.get(msg.getChat());
                    if (chatMsgs == null) {
                        chatMsgs = new ArrayList<>();
                        msgsByChat.put(msg.getChat(), chatMsgs);
                    }
                    chatMsgs.add(msg);
                    totalMsgCount++;
                }
            }

            // 按时间排序每个聊天的消息
            for (List<WeChatMsg> msgs : msgsByChat.values()) {
                Collections.sort(msgs, new Comparator<WeChatMsg>() {
                    @Override
                    public int compare(WeChatMsg o1, WeChatMsg o2) {
                        return Long.compare(o1.getCreateTime(), o2.getCreateTime());
                    }
                });
            }

            Log.i(TAG, "Found " + totalMsgCount + " messages");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing messages: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 解析单条消息记录
     */
    private WeChatMsg parseMsgRow(Cursor cursor) {
        try {
            long msgSvrId = cursor.getLong(0);
            int type = cursor.getInt(1);
            int isSend = cursor.getInt(2);
            long createTime = cursor.getLong(3);
            String talker = cursor.getString(4);
            String content = cursor.getString(5);
            String imgPath = cursor.getString(6);
            String msgId = cursor.getString(7);

            if (content == null) content = "";

            String chat = talker;
            String chatNickname = null;
            String talkerNickname = null;

            if (talker.endsWith("@chatroom")) {
                // 群聊消息
                chatNickname = contacts.get(talker);

                if (isSend == 1) {
                    talker = username;
//                    talkerNickname = "me";
                } else if (type == WeChatMsg.TYPE_SYSTEM) {
                    talker = "SYSTEM";
//                    talkerNickname = "SYSTEM";
                } else {
                    int colonIndex = content.indexOf(':');
                    if (colonIndex > 0) {
                        talker = content.substring(0, colonIndex);
                        talkerNickname = contacts.get(talker);
                        if (talkerNickname == null) talkerNickname = talker;
                    }
                }
                int newlineIndex = content.indexOf('\n');
                if (newlineIndex > 0) {
                    content = content.substring(newlineIndex + 1);
                }
            } else {
                // 单聊消息
                chatNickname = contacts.get(talker);
                talkerNickname = contacts.get(talker);
            }

            if (chatNickname == null) {
                Log.w(TAG, "Unknown contact: " + talker);
                return null;
            }

            return new WeChatMsg(msgSvrId, type, isSend, createTime, talker,
                    content, imgPath, chat, chatNickname, talkerNickname, msgId);

        } catch (Exception e) {
            Log.e(TAG, "Error parsing message row: " + e.getMessage());
            return null;
        }
    }

    /**
     * 解析图片信息表
     */
    private void parseImgInfo() {
        String query = "SELECT msgSvrId, bigImgPath FROM ImgInfo2";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(query, null);
            while (cursor.moveToNext()) {
                String msgSvrId = cursor.getString(0);
                String bigImgPath = cursor.getString(1);

                if (bigImgPath != null && !bigImgPath.startsWith("SERVERID://")) {
                    imgInfo.put(msgSvrId, bigImgPath);
                }
            }
            Log.i(TAG, "Found " + imgInfo.size() + " image records");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing image info: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 解析表情信息
     */
    private void parseEmoji() {
        // 解析表情分组
        String groupQuery = "SELECT md5, groupid FROM EmojiInfoDesc";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(groupQuery, null);
            while (cursor.moveToNext()) {
                String md5 = cursor.getString(0);
                String groupId = cursor.getString(1);
                emojiGroups.put(md5, groupId);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing emoji groups: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }

        // 解析表情详细信息
        String infoQuery = "SELECT md5, catalog, name, cdnUrl, encrypturl, aeskey FROM EmojiInfo";
        cursor = null;

        try {
            cursor = database.rawQuery(infoQuery, null);
            while (cursor.moveToNext()) {
                String md5 = cursor.getString(0);
                String catalog = cursor.getString(1);
                String name = cursor.getString(2);
                String cdnUrl = cursor.getString(3);
                String encryptUrl = cursor.getString(4);
                String aesKey = cursor.getString(5);

                if (cdnUrl != null || encryptUrl != null) {
                    emojiInfo.put(md5, new EmojiInfo(catalog, name, cdnUrl, encryptUrl, aesKey));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error parsing emoji info: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 解析头像标记表
     */
    private void parseImgFlag() {
        String query = "SELECT username, reserved1 FROM img_flag";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(query, null);
            while (cursor.moveToNext()) {
                String username = cursor.getString(0);
                String url = cursor.getString(1);

                if (url != null && !url.isEmpty()) {
                    avatarUrls.put(username, url);
                }
            }
            Log.i(TAG, "Found " + avatarUrls.size() + " avatar URLs");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing img flag: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /**
     * 获取表情加密密钥
     */
    public String getEmojiEncryptionKey() {
        String query = "SELECT md5 FROM EmojiInfo WHERE catalog = 153";
        Cursor cursor = null;

        try {
            cursor = database.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting emoji encryption key: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    public FileInfo getFileInfo(String msgId) {
        String query = "SELECT msgId, username, msgType,msgSubType,path,size,msgtime,diskSpace FROM WxFileIndex3 where msgId='" + msgId + "'";
        Cursor cursor = null;
        try {
            cursor = filedb.rawQuery(query, null);
            if (cursor.moveToFirst()) {
                FileInfo fileInfo = new FileInfo();
                fileInfo.msgId = cursor.getString(0);
                fileInfo.username = cursor.getString(1);
                fileInfo.msgType = cursor.getString(2);
                fileInfo.msgSubType = cursor.getString(3);
                fileInfo.path = cursor.getString(4);
                fileInfo.size = cursor.getString(5);
                fileInfo.msgtime = cursor.getString(6);
                fileInfo.diskSpace = cursor.getString(7);
                return fileInfo;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting emoji encryption key: " + e.getMessage());
        } finally {
            if (cursor != null) cursor.close();
        }
        return null;
    }

    /**
     * 根据昵称获取聊天ID
     */
    public String getIdByNickname(String nickname) {
        List<String> ids = contactsRev.get(nickname);
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("No contacts have nickname " + nickname);
        }
        if (ids.size() > 1) {
            Log.w(TAG, "More than one contacts have nickname " + nickname + "! Using the first contact");
        }
        return ids.get(0);
    }

    /**
     * 获取聊天ID（通过昵称或ID本身）
     */
    public String getChatId(String nickNameOrId) {
        if (contacts.containsKey(nickNameOrId)) {
            return nickNameOrId;
        } else {
            return getIdByNickname(nickNameOrId);
        }
    }

    // Getters
    public List<String> getAllChatIds() {
        return new ArrayList<>(msgsByChat.keySet());
    }

    public List<String> getAllChatNicknames() {
        List<String> nicknames = new ArrayList<>();
        for (String chatId : msgsByChat.keySet()) {
            String nickname = contacts.get(chatId);
            if (nickname != null && !nickname.isEmpty()) {
                nicknames.add(nickname);
            }
        }
        return nicknames;
    }

    public List<WeChatMsg> getMessagesByChat(String chatId) {
        return msgsByChat.get(chatId);
    }

    public Map<String, String> getContacts() {
        return new HashMap<>(contacts);
    }

    public String getUsername() {
        return username;
    }

    /**
     * 关闭数据库连接
     */
    public void close() {
        if (database != null && database.isOpen()) {
            database.close();
        }
    }

    public Map<String, String> getAvatarUrls() {
        return avatarUrls;
    }

    public Map<String, EmojiInfo> getEmojiInfo() {
        return emojiInfo;
    }

    public Map<String, String> getEmojiGroups() {
        return emojiGroups;
    }

    public Map<String, String> getImgInfo() {
        return imgInfo;
    }
}

