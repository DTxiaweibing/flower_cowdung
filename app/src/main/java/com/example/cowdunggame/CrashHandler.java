package com.example.cowdunggame;

import android.content.Context;
import android.content.Intent;
import android.os.Process;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 全局崩溃捕获：将崩溃堆栈写入 filesDir/crash_logs/crash_*.log，
 * 并跳转 CrashReportActivity 供用户查看/分享。
 */
public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String CRASH_DIR = "crash_logs";
    private final Context context;
    private final Thread.UncaughtExceptionHandler defaultHandler;

    public CrashHandler(Context context) {
        this.context = context.getApplicationContext();
        this.defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
    }

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        try {
            saveCrashLog(throwable);
        } catch (Exception ignored) { /* ignored */ }

        try {
            Intent intent = new Intent(context, CrashReportActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            context.startActivity(intent);
        } catch (Exception ignored) { /* ignored */ }

        Process.killProcess(Process.myPid());
    }

    private void saveCrashLog(Throwable throwable) {
        File dir = new File(context.getFilesDir(), CRASH_DIR);
        if (!dir.exists()) dir.mkdirs();

        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        File file = new File(dir, "crash_" + time + ".log");

        try (PrintWriter pw = new PrintWriter(new FileWriter(file))) {
            pw.println(context.getString(R.string.crash_log_header));
            pw.println(context.getString(R.string.crash_log_time)
                    + new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(new Date()));
            pw.println("Android API: " + android.os.Build.VERSION.SDK_INT);
            pw.println(context.getString(R.string.crash_log_device)
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            pw.println();
            throwable.printStackTrace(pw);
            pw.flush();
        } catch (Exception ignored) { /* ignored */ }
    }

    public static boolean hasCrashLog(Context context) {
        File dir = new File(context.getFilesDir(), CRASH_DIR);
        if (!dir.exists()) return false;
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash_") && name.endsWith(".log"));
        return files != null && files.length > 0;
    }

    public static String getLatestCrashLog(Context context) {
        File dir = new File(context.getFilesDir(), CRASH_DIR);
        if (!dir.exists()) return null;
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash_") && name.endsWith(".log"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) {
            if (f.lastModified() > latest.lastModified()) latest = f;
        }
        try {
            FileInputStream fis = new FileInputStream(latest);
            byte[] data = new byte[(int) latest.length()];
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public static void clearCrashLogs(Context context) {
        File dir = new File(context.getFilesDir(), CRASH_DIR);
        if (!dir.exists()) return;
        File[] files = dir.listFiles((d, name) -> name.startsWith("crash_") && name.endsWith(".log"));
        if (files != null) {
            for (File f : files) f.delete();
        }
    }
}