package com.jersnet.wtfview;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class FileLogger {
    private static File logFile;
    private static final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static volatile boolean isDebugMode = BuildConfig.DEBUG;
    private static final int MAX_BUFFER_LINES = 150;
    private static final Deque<String> memoryLogs = new ArrayDeque<>();
    private static LogListener logListener;

    private static final BlockingQueue<String> diskQueue = new LinkedBlockingQueue<>(1000);
    private static Thread writerThread;
    private static volatile boolean running = false;

    public interface LogListener {
        void onLog(String line);
    }

    public static synchronized void setLogListener(LogListener listener) {
        logListener = listener;
        if (logListener != null) {
            for (String line : memoryLogs) {
                try {
                    logListener.onLog(line);
                } catch (Exception ignored) {}
            }
        }
    }

    public static synchronized void setDebugEnabled(boolean enabled) {
        isDebugMode = enabled;
        if (enabled) {
            startWriterThread();
        } else {
            stopWriterThread();
            clearLogs();
        }
    }

    public static boolean isDebugEnabled() {
        return isDebugMode;
    }

    public static synchronized void clearLogs() {
        memoryLogs.clear();
        diskQueue.clear();
    }

    public static synchronized List<String> getRecentLogs() {
        return new ArrayList<>(memoryLogs);
    }

    public static void init(Context context) {
        if (context != null) {
            boolean appDebuggable = (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
            isDebugMode = BuildConfig.DEBUG || appDebuggable;
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) dir = context.getFilesDir();
                logFile = new File(dir, "wtfview.log");
            } catch (Exception ignored) {}
        }

        if (isDebugMode) {
            startWriterThread();
            log("FileLogger", "=== WTFView Logging Initialized (Debug Mode) ===");
        } else {

            if (logFile != null && logFile.exists()) {
                try { logFile.delete(); } catch (Exception ignored) {}
            }
        }
    }

    private static synchronized void startWriterThread() {
        if (!isDebugMode) return;
        if (running && writerThread != null && writerThread.isAlive()) return;
        running = true;
        writerThread = new Thread(() -> {
            BufferedWriter writer = null;
            try {
                while (running && isDebugMode) {
                    String line = diskQueue.take();
                    if (logFile != null) {
                        if (writer == null) {
                            writer = new BufferedWriter(new FileWriter(logFile, true), 8192);
                        }
                        writer.write(line);
                        writer.newLine();

                        String next;
                        int drained = 0;
                        while ((next = diskQueue.poll()) != null && drained < 50) {
                            writer.write(next);
                            writer.newLine();
                            drained++;
                        }
                        writer.flush();
                    }
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.e("FileLogger", "Async disk log writer error", e);
            } finally {
                if (writer != null) {
                    try { writer.close(); } catch (Exception ignored) {}
                }
            }
        }, "AsyncFileLoggerThread");
        writerThread.setDaemon(true);
        writerThread.setPriority(Thread.MIN_PRIORITY);
        writerThread.start();
    }

    private static synchronized void stopWriterThread() {
        running = false;
        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }
        diskQueue.clear();
    }

    public static void log(String tag, String message) {
        Log.i(tag, message);

        if (!isDebugMode && logListener == null) {
            return;
        }

        String line;
        synchronized (sdf) {
            line = sdf.format(new Date()) + " [" + tag + "] " + message;
        }

        LogListener listener;
        synchronized (FileLogger.class) {
            if (memoryLogs.size() >= MAX_BUFFER_LINES) {
                memoryLogs.pollFirst();
            }
            memoryLogs.addLast(line);
            listener = logListener;
        }

        if (listener != null) {
            try {
                listener.onLog(line);
            } catch (Exception ignored) {}
        }

        if (isDebugMode) {
            if (!diskQueue.offer(line)) {
                diskQueue.poll();
                diskQueue.offer(line);
            }
        }
    }

    public static void logError(String tag, String message, Throwable t) {
        Log.e(tag, message, t);

        if (!isDebugMode && logListener == null) {
            return;
        }

        String line;
        synchronized (sdf) {
            line = sdf.format(new Date()) + " [" + tag + " ERR] " + message + (t != null ? ": " + t.getMessage() : "");
        }

        LogListener listener;
        synchronized (FileLogger.class) {
            if (memoryLogs.size() >= MAX_BUFFER_LINES) {
                memoryLogs.pollFirst();
            }
            memoryLogs.addLast(line);
            listener = logListener;
        }

        if (listener != null) {
            try {
                listener.onLog(line);
            } catch (Exception ignored) {}
        }

        if (isDebugMode) {
            diskQueue.offer(line);
            if (t != null) {
                java.io.StringWriter sw = new java.io.StringWriter();
                t.printStackTrace(new PrintWriter(sw));
                diskQueue.offer(sw.toString());
            }
        }
    }
}
