package com.enigmacast.data.tvhome

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Channel logos (picons) for the Android TV home-screen rows. The launcher cannot fetch picons
 * from the receiver (that needs the receiver login), so the publisher copies them to
 * `files/tvhome/` and this read-only provider hands them out. Only `<hex>.png` files in that
 * folder are served; nothing else in the app is reachable through it.
 */
class TvHomeArtProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read only")
        val context = context ?: throw FileNotFoundException()
        val name = uri.lastPathSegment.orEmpty()
        if (uri.pathSegments.size != 1 || !NAME.matches(name)) throw FileNotFoundException()
        val file = File(dir(context), name)
        if (!file.isFile) throw FileNotFoundException()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private val NAME = Regex("[a-f0-9]{8,64}\\.png")

        fun dir(context: Context): File = File(context.filesDir, "tvhome")

        fun uri(context: Context, name: String): Uri =
            Uri.Builder().scheme("content").authority("${context.packageName}.tvhomeart").appendPath(name).build()

        fun isName(name: String): Boolean = NAME.matches(name)
    }
}
