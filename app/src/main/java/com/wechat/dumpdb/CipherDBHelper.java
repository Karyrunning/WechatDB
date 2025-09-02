package com.wechat.dumpdb;

import android.os.CancellationSignal;
import android.util.Log;

import net.zetetic.database.sqlcipher.SQLiteConnection;
import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook;

import java.io.File;

public class CipherDBHelper {
    public static SQLiteDatabase openDatabase(String dbPath, String password) {
        File dbFile = new File(dbPath);
        System.loadLibrary("sqlcipher");
        return SQLiteDatabase.openOrCreateDatabase(dbFile, password, null, null, new MigrateHook());
    }

    static class MigrateHook implements SQLiteDatabaseHook {

        @Override
        public void preKey(SQLiteConnection connection) {
        }

        @Override
        public void postKey(SQLiteConnection connection) {
            CancellationSignal signal = new CancellationSignal();
            try {
                connection.execute(
                        "PRAGMA cipher_migrate;",
                        new Object[0],
                        signal
                );
            } catch (Exception e) {
                Log.e("MigrateHook", "Cipher migration failed: " + e.getMessage());
            } finally {
                signal.cancel();
            }
        }
    }

}
