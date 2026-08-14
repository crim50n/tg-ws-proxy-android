package dev.minios.tgwsproxy

import android.app.Application
import android.os.Process
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlin.system.exitProcess

class TgWsProxyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.initialize(this)
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                DiagnosticLogger.recordCrash(thread, error)
            } finally {
                if (previousHandler != null) {
                    previousHandler.uncaughtException(thread, error)
                } else {
                    Process.killProcess(Process.myPid())
                    exitProcess(10)
                }
            }
        }
    }
}
