package com.jose.capturapantalla

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Proveedor mínimo (sin AndroidX) que sirve, solo en lectura, las imágenes temporales
 * de la caché para copiarlas o compartirlas con otras apps.
 */
class ArchivosProvider : ContentProvider() {

    override fun onCreate() = true

    private fun archivo(uri: Uri): File {
        val nombre = uri.lastPathSegment ?: throw FileNotFoundException()
        if (nombre.contains('/') || nombre.contains("..")) throw FileNotFoundException()
        val f = File(File(context!!.cacheDir, CARPETA), nombre)
        if (!f.exists()) throw FileNotFoundException(nombre)
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(archivo(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun getType(uri: Uri) = "image/png"

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val f = archivo(uri)
        val columnas = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columnas)
        cursor.addRow(columnas.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    companion object {
        const val CARPETA = "compartir"
    }
}
