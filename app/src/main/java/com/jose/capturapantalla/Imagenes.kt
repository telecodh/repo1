package com.jose.capturapantalla

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Utilidades para guardar y compartir imágenes. */
object Imagenes {

    private fun nombre() =
        "Captura_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"

    /** Guarda en Imágenes/Capturas. Devuelve la Uri o null si falla. */
    fun guardarEnGaleria(ctx: Context, bmp: Bitmap): Uri? {
        val cr = ctx.contentResolver
        val valores = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nombre())
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Capturas")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, valores) ?: return null
        return try {
            cr.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            valores.clear()
            valores.put(MediaStore.Images.Media.IS_PENDING, 0)
            cr.update(uri, valores, null, null)
            uri
        } catch (e: Exception) {
            cr.delete(uri, null, null)
            null
        }
    }

    /**
     * Escribe la imagen en la caché privada y devuelve una Uri content:// servida por
     * [ArchivosProvider], para copiar al portapapeles o compartir sin guardar en la galería.
     */
    fun uriTemporal(ctx: Context, bmp: Bitmap): Uri {
        val dir = File(ctx.cacheDir, ArchivosProvider.CARPETA).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // solo guardamos la última
        val archivo = File(dir, nombre())
        FileOutputStream(archivo).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return Uri.parse("content://${ctx.packageName}.archivos/${archivo.name}")
    }
}
