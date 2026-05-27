package com.rimdroid;

import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads logcat output filtered by com.rimdroid tag and forwards lines to callback.
 * Shows only rimdroid-main, rimdroid-emu, RimDroid/* tags — т.е. box64 и наш код.
 */
public class LogcatReader {

    private static final String TAG = "RimDroid/LogcatReader";

    public interface LineCallback {
        void onLine(String line);
    }

    private final LineCallback callback;
    private Process process;
    private Thread thread;
    private volatile boolean running;

    public LogcatReader(LineCallback callback) {
        this.callback = callback;
    }

    public void start() {
        running = true;
        thread = new Thread(() -> {
            try {
                // Очищаем старый logcat и читаем только новые строки
                // Фильтруем по нашим тегам
                process = new ProcessBuilder(
                        "logcat",
                        "-v", "tag",          // Формат: TAG: message
                        "-T", "1",            // Только с этого момента
                        "rimdroid-main:I",    // box64 вывод через наш pipe
                        "rimdroid-emu:I",     // emulation init
                        "RimDroid/*:I",       // наши Java теги
                        "*:S"                 // Остальное — silence
                ).start();

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while (running && (line = reader.readLine()) != null) {
                    // Убираем префикс тега для чистоты
                    String clean = stripTag(line);
                    if (!clean.isEmpty()) {
                        callback.onLine(clean);
                    }
                }
            } catch (Exception e) {
                if (running) Log.e(TAG, "LogcatReader error", e);
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (process != null) {
            process.destroy();
            process = null;
        }
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private String stripTag(String line) {
        // Формат logcat -v tag: "TAG: message" или "TAG : message"
        int colon = line.indexOf(": ");
        if (colon > 0) return line.substring(colon + 2).trim();
        return line.trim();
    }
}
