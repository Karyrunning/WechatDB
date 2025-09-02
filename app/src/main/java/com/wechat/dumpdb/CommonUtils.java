package com.wechat.dumpdb;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;

public class CommonUtils {
    public static boolean executeRootCommand(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();
            os.close();

            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public static String executeRootCommandWithResult(String command) {
        try {
            Process process = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(process.getOutputStream());
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            os.writeBytes(command + "\n");
            os.writeBytes("exit\n");
            os.flush();

            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }

            os.close();
            reader.close();
            process.waitFor();

            return result.toString();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean copyToSdcard(String sourcePath, String destPath) {
        return executeRootCommand("cp " + sourcePath + " " + destPath);
    }
}
