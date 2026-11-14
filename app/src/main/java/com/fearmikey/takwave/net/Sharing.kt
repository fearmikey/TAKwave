// SPDX-License-Identifier: GPL-3.0-or-later
package com.fearmikey.takwave.net

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.fearmikey.takwave.core.datapackage.DataPackage
import java.io.File
import java.util.UUID

/** Writes generated files to the cache and hands them to the Android share sheet. */
object Sharing {
    private fun exportDir(context: Context): File = File(context.cacheDir, "export").apply { mkdirs() }

    private fun share(context: Context, file: File, mime: String, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Builds the ATAK data package for the server on [port] and opens the share sheet.
     * Call from a background thread; the file write is small but it is still disk I/O.
     */
    fun shareDataPackage(context: Context, port: Int) {
        val file = File(exportDir(context), "TAKwave.zip")
        file.writeBytes(DataPackage.build(UUID.randomUUID().toString(), port))
        share(context, file, "application/zip", "Send TAKwave data package to ATAK")
    }

    /** Shares the packet log as a text file. Call from a background thread. */
    fun shareLog(context: Context, text: String) {
        val file = File(exportDir(context), "takwave-log.txt")
        file.writeText(text)
        share(context, file, "text/plain", "Export TAKwave log")
    }
}
