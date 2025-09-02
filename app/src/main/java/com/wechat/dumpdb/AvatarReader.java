package com.wechat.dumpdb;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import com.wechat.dumpdb.common.TextUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class AvatarReader {
    private static final String TAG = "AvatarReader";

    private String sfsDir;
    private String avtDir;
    private String avtDb;
    private boolean useAvt;

    public AvatarReader(String resDir, String avtDb) {
        this.sfsDir = resDir + File.separator + "sfs";

        // New location of avatar, see #50
        this.avtDir = resDir + File.separator + "avatar";
        File avtDirFile = new File(this.avtDir);
        if (!avtDirFile.isDirectory() || avtDirFile.listFiles() == null || avtDirFile.listFiles().length == 0) {
            this.avtDir = null;
        }

        this.avtDb = avtDb;
        this.useAvt = true;

        if (this.avtDb != null) {
            // Check if sfs/avatar* files exist
            File sfsFile = new File(this.sfsDir);
            if (sfsFile.exists()) {
                File[] avatarFiles = sfsFile.listFiles((dir, name) -> name.startsWith("avatar"));
                if (avatarFiles == null || avatarFiles.length == 0) {
                    this.avtDb = null;
                }
            }
        }

        if (this.avtDir == null && this.avtDb == null) {
            Log.w(TAG, "Cannot find avatar storage. Will not use avatar!");
            this.useAvt = false;
        }
    }

    private int getFilenamePriority(String filename) {
        if (filename.contains("_hd") && filename.endsWith(".png")) {
            return 10;
        } else {
            return 1;
        }
    }

    public Bitmap getAvatarFromAvtDb(String avtId) {
        try {
            List<AvatarCandidate> candidates = searchAvtDb(avtId);
            candidates.sort((a, b) -> Integer.compare(getFilenamePriority(b.path), getFilenamePriority(a.path)));

            for (AvatarCandidate c : candidates) {
                Bitmap bitmap = readImgFromBlock(c.path, c.offset, c.size);
                if (bitmap != null) {
                    return bitmap;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting avatar from database", e);
        }
        return null;
    }

    public Bitmap getAvatarFromAvtDir(String avtId) {
        String dir1 = avtId.substring(0, 2);
        String dir2 = avtId.substring(2, 4);

        List<String> candidates = new ArrayList<>();
        String searchPath = avtDir + File.separator + dir1 + File.separator + dir2;
        File searchDir = new File(searchPath);

        if (searchDir.exists() && searchDir.isDirectory()) {
            File[] files = searchDir.listFiles((dir, name) -> name.contains(avtId));
            if (files != null) {
                for (File file : files) {
                    candidates.add(file.getAbsolutePath());
                }
            }
        }

        // Remove duplicates and sort by priority
        candidates = new ArrayList<>(new LinkedHashSet<>(candidates));
        candidates.sort((a, b) -> Integer.compare(getFilenamePriority(b), getFilenamePriority(a)));

        // Expand directories
        List<String> expandedCandidates = new ArrayList<>();
        for (String cand : candidates) {
            File candFile = new File(cand);
            if (candFile.isDirectory()) {
                File[] subFiles = candFile.listFiles();
                if (subFiles != null) {
                    for (File subFile : subFiles) {
                        expandedCandidates.add(subFile.getAbsolutePath());
                    }
                }
            } else {
                expandedCandidates.add(cand);
            }
        }

        for (String cand : expandedCandidates) {
            File candFile = new File(cand);
            if (candFile.isDirectory()) {
                continue;
            }

            try {
                if (cand.endsWith(".bm")) {
                    return readBmFile(cand);
                } else {
                    return BitmapFactory.decodeFile(cand);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error reading candidate file: " + cand, e);
            }
        }
        return null;
    }

    public Bitmap getAvatar(String username) {
        if (!useAvt) {
            return null;
        }

        String avtId = TextUtil.md5(username.getBytes(StandardCharsets.UTF_8));

        if (avtDb != null) {
            Bitmap bitmap = getAvatarFromAvtDb(avtId);
            if (bitmap != null) {
                return bitmap;
            }
        }

        if (avtDir != null) {
            Bitmap bitmap = getAvatarFromAvtDir(avtId);
            if (bitmap != null) {
                return bitmap;
            }
        }

        Log.w(TAG, "Avatar file for " + username + " not found.");
        return null;
    }

    public void saveAvatarToAvtDir(String username, Bitmap bitmap) {
        if (avtDir == null) {
            Log.w(TAG, "Avatar directory not available for saving");
            return;
        }

        String avtId = TextUtil.md5(username.getBytes(StandardCharsets.UTF_8));
        String dir1 = avtId.substring(0, 2);
        String dir2 = avtId.substring(2, 4);
        String fname = avtDir + File.separator + dir1 + File.separator + dir2 + File.separator + "user_" + avtId + ".png";

        try {
            File file = new File(fname);
            file.getParentFile().mkdirs();

            try (FileOutputStream fos = new FileOutputStream(file)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
                Log.i(TAG, "Caching downloaded avatar for " + username + " to " + fname);
            }
        } catch (IOException e) {
            Log.e(TAG, "Error saving avatar to " + fname, e);
        }
    }

    public Bitmap readImgFromBlock(String filename, long pos, int size) {
        long fileIdx = pos >> 32;
        String fname = sfsDir + File.separator + "avatar.block." + String.format("%05d", fileIdx);
        long startPos = pos - fileIdx * (1L << 32) + 16 + filename.length() + 1;

        try (RandomAccessFile file = new RandomAccessFile(fname, "r")) {
            file.seek(startPos);
            byte[] data = new byte[size];
            file.readFully(data);
            return BitmapFactory.decodeByteArray(data, 0, data.length);
        } catch (IOException e) {
            Log.w(TAG, "Cannot read avatar from " + fname + ": " + e.getMessage());
            return null;
        }
    }

    public Bitmap readBmFile(String fname) {
        try (FileInputStream fis = new FileInputStream(fname)) {
            // filesize is 36880=96x96x4+16
            int width = 96, height = 96;
            int[] pixels = new int[width * height];

            for (int i = 0; i < height; i++) {
                for (int j = 0; j < width; j++) {
                    int r = fis.read();
                    int g = fis.read();
                    int b = fis.read();
                    int a = fis.read(); // alpha, not used

                    if (r == -1 || g == -1 || b == -1 || a == -1) {
                        throw new IOException("Unexpected end of file");
                    }

                    // Convert to ARGB format
                    pixels[i * width + j] = (0xFF << 24) | (r << 16) | (g << 8) | b;
                }
            }

            return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        } catch (IOException e) {
            Log.e(TAG, "Error reading BM file: " + fname, e);
            return null;
        }
    }

    private List<AvatarCandidate> searchAvtDb(String avtId) {
        List<AvatarCandidate> candidates = new ArrayList<>();

        try {
            String url = "jdbc:sqlite:" + avtDb;
            try (Connection conn = DriverManager.getConnection(url);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT FileName, Offset, Size FROM Index_avatar")) {

                while (rs.next()) {
                    String path = rs.getString("FileName");
                    long offset = rs.getLong("Offset");
                    int size = rs.getInt("Size");

                    if (path.contains(avtId)) {
                        candidates.add(new AvatarCandidate(path, offset, size));
                    }
                }
            }
        } catch (SQLException e) {
            Log.e(TAG, "Error searching avatar database", e);
        }

        return candidates;
    }

    // Helper class to store avatar candidate information
    private static class AvatarCandidate {
        final String path;
        final long offset;
        final int size;

        AvatarCandidate(String path, long offset, int size) {
            this.path = path;
            this.offset = offset;
            this.size = size;
        }
    }
}