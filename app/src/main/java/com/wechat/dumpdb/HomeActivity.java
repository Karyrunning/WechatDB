package com.wechat.dumpdb;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.alibaba.fastjson2.util.DateUtils;
import com.tencent.mm.plugin.gif.MMWXGFJNI;

import net.zetetic.database.sqlcipher.SQLiteDatabase;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public class HomeActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 1001;

    // WeChat应用路径常量
    private static final String MM_DIR = "/storage/emulated/0/Download/com.tencent.mm";
    private static final String RES_DIR = "/mnt/sdcard/tencent/MicroMsg";

    private String USER_ROOT = MM_DIR + "/MicroMsg/3d7f491a654552450d1352e52d0891a1";

    // SharedPreferences文件路径
    private static final String SYSTEM_CONFIG_PREFS = MM_DIR + "/shared_prefs/system_config_prefs.xml";
    private static final String MM_PREFERENCES = MM_DIR + "/shared_prefs/com.tencent.mm_preferences.xml";
    private static final String AUTH_INFO_KEY_PREFS = MM_DIR + "/shared_prefs/auth_info_key_prefs.xml";
    private static final String SYSTEM_INFO_CFG = MM_DIR + "/MicroMsg/systemInfo.cfg";
    private static final String COMPATIBLE_INFO_CFG = MM_DIR + "/MicroMsg/CompatibleInfo.cfg";

    private static final String TAG = "HomeActivity";

    static {
        System.loadLibrary("wechatcommon");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        // 申请必要权限
        requestPermissions();

        MMWXGFJNI.initialize(getApplicationInfo().nativeLibraryDir);
    }

    public void onBtnClicked(View view) {
        new Handler().post(new Runnable() {
            @Override
            public void run() {
//                String dbRoot = "/storage/emulated/0/Download";
//                String passWord = "10efc55";

                String dbPath = USER_ROOT + "/EnMicroMsg.db";
                String passWord = "";

                List<String> uins = getUin();
                List<String> imeis = getImei();
                // 尝试所有UIN和IMEI的组合
                for (String uin : uins) {
                    for (String imei : imeis) {
                        passWord = generateKey(imei, uin);
                        if (passWord == null) continue;
                        Log.i(TAG, "尝试密钥: " + passWord + " (UIN: " + uin + ", IMEI: " + imei + ")");
                        if (decryptDatabase(dbPath, passWord)) {
                            break;
                        }
                    }
                }

//                SQLiteDatabase db = CipherDBHelper.openDatabase(dbPath, passWord);
//                // 查询
//                Cursor c = db.rawQuery("SELECT id,type,value FROM userinfo", null);
//                while (c.moveToNext()) {
//                    String name = c.getString(0);
//                    String type = c.getString(1);
//                    Log.d("SQLCipher", "表: " + name + " 类型: " + type);
//                }
//                c.close();
//                db.close();
                Long startTime = DateUtils.parseDate("2025-04-19 00:00:00", "yyyy-MM-dd HH:mm:ss").getTime();

                WeChatDBParser dbParser = new WeChatDBParser(USER_ROOT, passWord);
                dbParser.parse(startTime);

                WeChatFilePathResolver filePathResolver = new WeChatFilePathResolver(USER_ROOT);
                String chatId = dbParser.getChatId("karyrunning");
                List<WeChatMsg> msgList = dbParser.getMessagesByChat(chatId);
                if (msgList == null || msgList.size() == 0) {
                    return;
                }
                Resource resource = new Resource(dbParser, USER_ROOT, "avatar.index", getBaseContext());
                resource.cacheVoiceMp3(msgList);
                HTMLRender render = new HTMLRender(getBaseContext(), dbParser, resource, filePathResolver);
                List<Map<String, Object>> dataList = new ArrayList<>();
                for (WeChatMsg chatMsg : msgList) {
//                    if (!"190".equals(chatMsg.getMsgId())) {
//                        continue;
//                    }
                    dataList.add(render.renderMessage(chatMsg));
                }
                Log.d(TAG, "转换完成");
            }
        });
    }

    /**
     * 申请运行时权限
     */
    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), REQUEST_PERMISSIONS);
        }
    }

    public void testWxgfDecoder(View view) {
        byte[] wxgf = null;
        try {
            wxgf = readBinary(this);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("Unit test: wxgf size=" + wxgf.length);
        byte[] res = MMWXGFJNI.nativeWxam2PicBuf(wxgf);
        System.out.println("Unit test: res size=" + res.length + " -- " + Arrays.toString(Arrays.copyOfRange(res, 0, 10)));

        try {
            writeFile(this, "test.jpg", res);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        if (wxgf.length != 64367 || res.length != 705188) {
            throw new RuntimeException("test failed");
        }
    }

    public static byte[] readBinary(Context context) throws IOException {
        InputStream inputStream = context.getResources().openRawResource(R.raw.test_wxgf); // R.raw.image refers to your image.jpg file
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int len;
        while ((len = inputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, len);
        }
        inputStream.close(); // Close the input stream
        return byteArrayOutputStream.toByteArray();
    }

    public static void writeFile(Context context, String filename, byte[] data) throws IOException {
        File file = new File(context.getExternalFilesDir(null), filename); // Use null for the type argument to get the root external files directory
        System.out.println("Writing file to: " + file.getAbsolutePath());
        FileOutputStream outputStream = new FileOutputStream(file);
        outputStream.write(data);
        outputStream.close();
    }

    // 权限请求结果回调
    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        // 简单处理：如有拒绝，可提示用户
        for (int i = 0; i < perms.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "必要权限未授予：" + perms[i], Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * 从XML文件中解析UIN值
     */
    private String parseUinFromXml(String xmlContent, String tag) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            InputSource is = new InputSource(new StringReader(xmlContent));
            Document doc = builder.parse(is);

            NodeList list = doc.getElementsByTagName("*");
            for (int i = 0; i < list.getLength(); i++) {
                Element element = (Element) list.item(i);
                String nameAttr = element.getAttribute("name");

                // 精确匹配 或 忽略前导下划线
                if (tag.equals(nameAttr) || nameAttr.replaceFirst("^_", "").equals(tag)) {
                    if (element.hasAttribute("value")) {
                        return element.getAttribute("value");
                    }
                    return element.getTextContent();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 获取微信UIN列表
     */
    public List<String> getUin() {
        Set<String> candidates = new HashSet<>();

        // 1. 从system_config_prefs.xml获取
        try {
            String content = executeCommand("cat " + SYSTEM_CONFIG_PREFS);
            String uin = parseUinFromXml(content, "default_uin");
            if (uin != null && !uin.equals("0")) {
                candidates.add(uin);
                Log.i(TAG, "从system_config_prefs.xml找到uin: " + uin);
            }
        } catch (Exception e) {
            Log.w(TAG, "从system_config_prefs.xml获取uin失败", e);
        }

        // 2. 从com.tencent.mm_preferences.xml获取
        try {
            String content = executeCommand("cat " + MM_PREFERENCES);
            String uin = parseUinFromXml(content, "last_login_uin");
            if (uin != null && !uin.equals("0")) {
                candidates.add(uin);
                Log.i(TAG, "从com.tencent.mm_preferences.xml找到uin: " + uin);
            }
        } catch (Exception e) {
            Log.w(TAG, "从com.tencent.mm_preferences.xml获取uin失败", e);
        }

        // 3. 从auth_info_key_prefs.xml获取
        try {
            String content = executeCommand("cat " + AUTH_INFO_KEY_PREFS);
            String uin = parseUinFromXml(content, "auth_uin");
            if (uin != null && !uin.equals("0")) {
                candidates.add(uin);
                Log.i(TAG, "从auth_info_key_prefs.xml找到uin: " + uin);
            }
        } catch (Exception e) {
            Log.w(TAG, "从auth_info_key_prefs.xml获取uin失败", e);
        }

        // 4. 从systemInfo.cfg获取（Java对象反序列化，这里简化处理）
        try {
            // 注意：这里需要实现Java对象反序列化，或者跳过
            Log.w(TAG, "systemInfo.cfg解析暂未实现");
        } catch (Exception e) {
            Log.w(TAG, "从systemInfo.cfg获取uin失败", e);
        }

        List<String> result = new ArrayList<>(candidates);
        Log.i(TAG, "可能的UIN值: " + result);
        return result;
    }

    /**
     * 解析Parcel数据（用于获取IMEI）
     */
    private static class Parcel {
        private byte[] data;

        public Parcel(String text) throws Exception {
            if (text.startsWith("Result: Parcel(") && text.endsWith("')")) {
                Pattern pattern = Pattern.compile("([0-9a-f]{8}) ");
                Matcher matcher = pattern.matcher(text);
                List<Byte> bytes = new ArrayList<>();

                while (matcher.find()) {
                    int value = (int) Long.parseLong(matcher.group(1), 16);
                    ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
                    buffer.putInt(value);
                    for (byte b : buffer.array()) {
                        bytes.add(b);
                    }
                }

                data = new byte[bytes.size()];
                for (int i = 0; i < bytes.size(); i++) {
                    data[i] = bytes.get(i);
                }
            } else {
                throw new Exception("Unexpected input format");
            }
        }

        public int getInt(int offset) {
            return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        }

        public String getUtf16(int offset) {
            int length = getInt(offset);
            return new String(data, offset + 4, length * 2, java.nio.charset.StandardCharsets.UTF_16LE);
        }
    }

    /**
     * 获取IMEI列表
     */
    public List<String> getImei() {
        Set<String> candidates = new HashSet<>();

        // 1. 通过service call获取IMEI
        try {
            String result = executeCommand("service call iphonesubinfo 1");
            Parcel parcel = new Parcel(result.trim());
            String imei = parcel.getUtf16(4);
            candidates.add(imei);
            Log.i(TAG, "从iphonesubinfo获取IMEI: " + imei);
        } catch (Exception e) {
            Log.w(TAG, "通过service call获取IMEI失败", e);
        }

        // 2. 从CompatibleInfo.cfg获取IMEI（需要实现Java反序列化）
        try {
            String result = executeCommand("service call iphonesubinfo 1");
            Parcel parcel = new Parcel(result.trim());
            String imei = parcel.getUtf16(4);
            candidates.add(imei);
            Log.w(TAG, "CompatibleInfo.cfg解析暂未实现");
        } catch (Exception e) {
            Log.w(TAG, "从CompatibleInfo.cfg获取IMEI失败", e);
        }

        // 3. 通过TelephonyManager获取IMEI（需要权限）
        try {
            TelephonyManager telephonyManager = (TelephonyManager) getBaseContext().getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                String imei = telephonyManager.getImei();
                if (imei != null) {
                    candidates.add(imei);
                    Log.i(TAG, "通过TelephonyManager获取IMEI: " + imei);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "通过TelephonyManager获取IMEI失败", e);
        }
        // 4. 添加默认IMEI（fallback）
        candidates.add("1234567890ABCDEF");
        List<String> result = new ArrayList<>(candidates);
        Log.i(TAG, "可能的IMEI值: " + result);
        return result;
    }

    /**
     * 生成解密密钥
     */
    public String generateKey(String imei, String uin) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            String combined = imei + uin;
            byte[] digest = md.digest(combined.getBytes());

            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }

            return sb.toString().substring(0, 7);
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "MD5算法不可用", e);
            return null;
        }
    }

    /**
     * 执行Shell命令
     */
    private String executeCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su -c " + command);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
            process.waitFor();
            return output.toString();
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + command, e);
            return "";
        }
    }

    /**
     * 解密微信数据库
     */
    public boolean decryptDatabase(String dbPath, String password) {
        try {
            // 打开加密的数据库
            SQLiteDatabase db = CipherDBHelper.openDatabase(dbPath, password);
            // 检查SQLCipher版本
            Cursor cursor = db.rawQuery("PRAGMA cipher_version", null);
            if (cursor.moveToFirst()) {
                String version = cursor.getString(0);
                Log.i(TAG, "SQLCipher版本: " + version);
                String[] versionParts = version.split("\\.");
                int major = Integer.parseInt(versionParts[0]);
                int minor = Integer.parseInt(versionParts[1]);
                if (major < 4 || (major == 4 && minor < 1)) {
                    Log.e(TAG, "需要SQLCipher 4.1或更高版本");
                    cursor.close();
                    db.close();
                    return false;
                }
            }
            cursor.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "数据库解密失败: " + e.getMessage(), e);
            // 删除可能创建的不完整文件
            return false;
        }
    }

    /**
     * 检查设备是否已root
     */
    public static boolean isRooted() {
        try {
            Process process = Runtime.getRuntime().exec("su -c echo test");
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            return "test".equals(result);
        } catch (Exception e) {
            return false;
        }
    }
}