package com.example.screengrab

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import java.io.File

/** Opened by the service when "Crop" is tapped. Reads the pending image from private cache. */
class CropActivity : Activity() {

    private val file by lazy { File(cacheDir, "pending.png") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val cropView = findViewById<CropView>(R.id.cropView)
        val bmp = BitmapFactory.decodeFile(file.path)
        if (bmp == null) { finish(); return }
        cropView.setBitmap(bmp)

        findViewById<Button>(R.id.btnCancel).setOnClickListener { file.delete(); finish() }
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val out = cropView.getCropped() ?: return@setOnClickListener
            Thread {
                val ok = Storage.saveToGallery(applicationContext, out) != null
                runOnUiThread {
                    Toast.makeText(this, if (ok) R.string.saved else R.string.capture_failed, Toast.LENGTH_SHORT).show()
                    file.delete()
                    finish()
                }
            }.start()
        }
    }
}
