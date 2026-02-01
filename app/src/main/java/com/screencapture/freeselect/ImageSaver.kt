package com.screencapture.freeselect

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object ImageSaver {

    fun saveImage(context: Context, bitmap: Bitmap, callback: (Boolean, String?) -> Unit) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "Screenshot_$timestamp.png"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 MediaStore
                saveWithMediaStore(context, bitmap, fileName, callback)
            } else {
                // 旧版本使用文件系统
                saveToFile(bitmap, fileName, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            callback(false, null)
        }
    }

    private fun saveWithMediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
        callback: (Boolean, String?) -> Unit
    ) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenCapture")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            resolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(it, contentValues, null, null)

            val path = "${Environment.DIRECTORY_PICTURES}/ScreenCapture/$fileName"
            callback(true, path)
        } ?: callback(false, null)
    }

    private fun saveToFile(bitmap: Bitmap, fileName: String, callback: (Boolean, String?) -> Unit) {
        val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val screenshotDir = File(picturesDir, "ScreenCapture")
        if (!screenshotDir.exists()) {
            screenshotDir.mkdirs()
        }

        val file = File(screenshotDir, fileName)
        FileOutputStream(file).use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        }

        callback(true, file.absolutePath)
    }
}
