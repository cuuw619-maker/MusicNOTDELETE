package com.example.util

import android.content.Context
import android.os.Build
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Global uncaught exception catcher that synchronously persists crash stacktraces
 * before process termination so the user can inspect and copy the exact error on next launch.
 */
object CrashHandler {

    private const val PREFS_NAME = "imux_crash_reporter"
    private const val KEY_LAST_CRASH = "last_crash_report"

    @Volatile
    private var isInstalled = false

    fun install(context: Context) {
        if (isInstalled) return
        isInstalled = true
        val appContext = context.applicationContext
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val report = formatReport(appContext, thread, throwable)
                appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, report)
                    .commit()
            }
            if (previousHandler != null && previousHandler !== Thread.getDefaultUncaughtExceptionHandler()) {
                previousHandler.uncaughtException(thread, throwable)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun getLastCrashReport(context: Context): String? {
        return runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_CRASH, null)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun clearLastCrashReport(context: Context) {
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }

    internal fun formatReport(context: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val versionName = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "unknown"
        }.getOrDefault("unknown")
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        return buildString {
            appendLine("=== Отчёт о сбое imux player ===")
            appendLine("Время: $time")
            appendLine("Версия: $versionName")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT}, Android ${Build.VERSION.RELEASE})")
            appendLine("Поток: ${thread.name}")
            appendLine("Ошибка: ${throwable::class.java.name}: ${throwable.message ?: "без описания"}")
            appendLine("--- Стек вызовов ---")
            append(sw.toString().take(8000))
        }.trim()
    }
}
