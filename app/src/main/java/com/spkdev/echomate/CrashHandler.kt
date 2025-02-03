package com.spkdev.echomate

import android.app.Application
import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*

class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler: Thread.UncaughtExceptionHandler =
        Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        // Handle the exception and log it to a file
        saveErrorToFile(e)

        // Let the default handler take care of the rest (crash the app)
        defaultHandler.uncaughtException(t, e)
    }

    private fun saveErrorToFile(e: Throwable) {
        try {
            // Get the error message and stack trace as a string
            val errorDetails = getStackTraceAsString(e)

            // Get the file path
            val logFile = getLogFile()

            // Open the file and append the error details
            val fileOutputStream = FileOutputStream(logFile, true)
            val writer = PrintWriter(fileOutputStream)
            writer.println("------ New Crash Log: ${getCurrentTime()} ------")
            writer.println(errorDetails)
            writer.println("\n\n")
            writer.close()

        } catch (exception: Exception) {
            // Log any errors in saving the error log (optional)
            exception.printStackTrace()
        }
    }

    private fun getLogFile(): File {
        // Get the app's log file in external storage (make sure to request permissions for writing to storage)
        val logDir = File(context.getExternalFilesDir(null), "crash_logs")
        if (!logDir.exists()) {
            logDir.mkdir()  // Create the directory if it doesn't exist
        }

        // Create the log file (appending)
        return File(logDir, "crash_log.txt")
    }

    private fun getStackTraceAsString(e: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        e.printStackTrace(printWriter)
        return stringWriter.toString()
    }

    private fun getCurrentTime(): String {
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return dateFormat.format(Date())
    }
}



class MyApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Set the custom crash handler
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(this))
    }
}
