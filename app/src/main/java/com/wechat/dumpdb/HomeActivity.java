package com.wechat.dumpdb;

import static com.tencent.mm.plugin.gif.MMWXGFJNI.nativeInit;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.tencent.mm.plugin.gif.MMWXGFJNI;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class HomeActivity extends AppCompatActivity {
    private static final int REQUEST_PERMISSIONS = 1001;

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
                String dbRoot = "/storage/emulated/0/Download/com.tencent.mm/MicroMsg/3d7f491a654552450d1352e52d0891a1";
//                String dbRoot = "/storage/emulated/0/Download";
                String dbPath = dbRoot + "/EnMicroMsg.db";
                String passWord = "10efc55";
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

                WeChatDBParser dbParser = new WeChatDBParser(dbPath, passWord);
                String chatId = dbParser.getChatId("karyrunning");
                List<WeChatMsg> msgList = dbParser.getMessagesByChat(chatId);
                Resource resource = new Resource(dbParser, dbRoot, "avatar.index", getBaseContext());
                resource.cacheVoiceMp3(msgList);
                HTMLRender render = new HTMLRender(getBaseContext(), dbParser, resource);
                for (WeChatMsg chatMsg : msgList) {
                    render.renderMessage(chatMsg);
                }
            }
        });
    }

    /**
     * 申请运行时权限
     */
    private void requestPermissions() {
        List<String> needed = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(android.Manifest.permission.READ_PHONE_STATE);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(
                    this,
                    needed.toArray(new String[0]),
                    REQUEST_PERMISSIONS);
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
                Toast.makeText(this,
                        "必要权限未授予：" + perms[i],
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}