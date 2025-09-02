package com.wechat.dumpdb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.wechat.dumpdb.common.TextUtil;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AvatarReader {
    private static final String TAG = "AvatarReader";

    private final File sfsDir;
    private File avtDir;
    private final String avtDb;
    private boolean useAvt = true;

    public AvatarReader(String resDir, String avtDb) {
        this.sfsDir = new File(resDir, "sfs");

        // new location of avatar
        this.avtDir = new File(resDir, "avatar");
        if (!avtDir.isDirectory() || avtDir.list().length == 0) {
            avtDir = null;
        }

        this.avtDb = avtDb;

        if (avtDb != null) {
            File[] files = sfsDir.listFiles((dir, name) -> name.startsWith("avatar"));
            if (files == null || files.length == 0) {
                // no sfs/avatar*
                this.useAvt = false;
            }
        }

        if (avtDir == null && avtDb == null) {
            Log.w(TAG, "Cannot find avatar storage. Will not use avatar!");
            this.useAvt = false;
        }
    }

    private int filenamePriority(String s) {
        if (s.contains("_hd") && s.endsWith(".png")) {
            return 10;
        } else {
            return 1;
        }
    }

    public Bitmap getAvatar(String username) {
        if (!useAvt) {
            return null;
        }
        String avtid = TextUtil.md5(username.getBytes(StandardCharsets.UTF_8));

        if (avtDb != null) {
            Bitmap bmp = getAvatarFromAvtDb(avtid);
            if (bmp != null) return bmp;
        }

        if (avtDir != null) {
            Bitmap bmp = getAvatarFromAvtDir(avtid);
            if (bmp != null) return bmp;
        }

        Log.w(TAG, "Avatar file for " + username + " not found.");
        return null;
    }

    private Bitmap getAvatarFromAvtDb(String avtid) {
        try {
            List<String[]> candidates = searchAvtDb(avtid);
            candidates.sort((a, b) -> filenamePriority(b[0]) - filenamePriority(a[0]));
            for (String[] c : candidates) {
                String path = c[0];
                long offset = Long.parseLong(c[1]);
                int size = Integer.parseInt(c[2]);
                return readImgFromBlock(path, offset, size);
            }
        } catch (Exception e) {
            Log.e(TAG, "getAvatarFromAvtDb failed", e);
        }
        return null;
    }

    private Bitmap getAvatarFromAvtDir(String avtid) {
        File dir1 = new File(avtDir, avtid.substring(0, 2));
        File dir2 = new File(dir1, avtid.substring(2, 4));
        File[] candidates = dir2.listFiles((dir, name) -> name.contains(avtid));
        if (candidates == null) return null;

        List<File> candList = new ArrayList<>();
        Collections.addAll(candList, candidates);
        candList.sort((a, b) -> filenamePriority(b.getName()) - filenamePriority(a.getName()));

        for (File cand : candList) {
            if (cand.isDirectory()) continue;
            try {
                if (cand.getName().endsWith(".bm")) {
                    return readBmFile(cand);
                } else {
                    return BitmapFactory.decodeFile(cand.getAbsolutePath());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading avatar from " + cand, e);
            }
        }
        return null;
    }

    private Bitmap readImgFromBlock(String filename, long pos, int size) {
        try {
            long fileIdx = pos >> 32;
            File fname = new File(sfsDir, String.format("avatar.block.%05d", fileIdx));
            long startPos = pos - fileIdx * (1L << 32) + 16 + filename.length() + 1;

            RandomAccessFile raf = new RandomAccessFile(fname, "r");
            raf.seek(startPos);
            byte[] data = new byte[size];
            raf.readFully(data);
            raf.close();

            return BitmapFactory.decodeStream(new ByteArrayInputStream(data));
        } catch (IOException e) {
            Log.w(TAG, "Cannot read avatar block: " + e.getMessage());
            return null;
        }
    }

    private Bitmap readBmFile(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            int size = 96;
            int[] pixels = new int[size * size];
            byte[] buf = new byte[4];
            for (int i = 0; i < size; i++) {
                for (int j = 0; j < size; j++) {
                    int read = fis.read(buf);
                    if (read != 4) continue;
                    int r = buf[0] & 0xff;
                    int g = buf[1] & 0xff;
                    int b = buf[2] & 0xff;
                    pixels[i * size + j] = 0xff000000 | (r << 16) | (g << 8) | b;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (IOException e) {
            Log.e(TAG, "readBmFile failed", e);
            return null;
        }
    }

    private List<String[]> searchAvtDb(String avtid) throws Exception {
        List<String[]> candidates = new ArrayList<>();
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + avtDb);
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("select FileName, Offset, Size from Index_avatar");
        while (rs.next()) {
            String path = rs.getString(1);
            if (path.contains(avtid)) {
                candidates.add(new String[]{path, rs.getString(2), rs.getString(3)});
            }
        }
        rs.close();
        stmt.close();
        conn.close();
        return candidates;
    }
}
