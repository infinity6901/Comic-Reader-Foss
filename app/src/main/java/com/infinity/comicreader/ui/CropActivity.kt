package com.infinity.comicreader.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.infinity.comicreader.R
import com.infinity.comicreader.util.AppDispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** Crops an image (or a rendered page) and writes the result as a PNG. */
class CropActivity : AppCompatActivity() {

    private lateinit var cropView: CropView
    private lateinit var progress: ProgressBar
    private var bitmap: Bitmap? = null
    private var sourcePath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crop)

        val toolbar = findViewById<MaterialToolbar>(R.id.crop_toolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        cropView = findViewById(R.id.crop_view)
        progress = findViewById(R.id.crop_progress)

        findViewById<View>(R.id.crop_reset)?.setOnClickListener { cropView.resetSelection() }
        findViewById<View>(R.id.crop_rotate).setOnClickListener { rotate() }
        findViewById<View>(R.id.crop_save).setOnClickListener { save() }

        sourcePath = intent.getStringExtra(EXTRA_PATH)
        if (sourcePath == null) {
            finish()
            return
        }
        load()
    }

    override fun onDestroy() {
        super.onDestroy()
        bitmap?.let { if (!it.isRecycled) it.recycle() }
        bitmap = null
    }

    private fun load() {
        val path = sourcePath ?: return
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val decoded = withContext(AppDispatchers.io) { decodeCapped(path, 3000) }
            progress.visibility = View.GONE
            if (decoded == null) {
                toast(R.string.error_open)
                finish()
            } else {
                bitmap = decoded
                cropView.setBitmap(decoded)
            }
        }
    }

    private fun rotate() {
        val current = bitmap ?: return
        val matrix = Matrix().apply { postRotate(90f) }
        val rotated = Bitmap.createBitmap(current, 0, 0, current.width, current.height, matrix, true)
        bitmap = rotated
        cropView.setBitmap(rotated)
        if (rotated !== current && !current.isRecycled) current.recycle()
    }

    private fun save() {
        val current = bitmap ?: return
        val rect: Rect = cropView.bitmapCropRect()
        val snapshot = runCatching { current.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: run {
            toast(R.string.crop_failed)
            return
        }
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val out = try {
                withContext(AppDispatchers.io) { cropAndWrite(snapshot, rect) }
            } finally {
                if (!snapshot.isRecycled) snapshot.recycle()
            }
            progress.visibility = View.GONE
            if (out != null) {
                toast(getString(R.string.crop_saved, out.absolutePath))
                setResult(RESULT_OK, android.content.Intent().putExtra(EXTRA_RESULT, out.absolutePath))
                finish()
            } else {
                toast(R.string.crop_failed)
            }
        }
    }

    private fun cropAndWrite(source: Bitmap, rect: Rect): File? = runCatching {
        val left = rect.left.coerceIn(0, source.width - 1)
        val top = rect.top.coerceIn(0, source.height - 1)
        val right = rect.right.coerceIn(left + 1, source.width)
        val bottom = rect.bottom.coerceIn(top + 1, source.height)
        val safe = Rect(left, top, right, bottom)
        val cropped = Bitmap.createBitmap(source, safe.left, safe.top, safe.width(), safe.height())
        val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(cacheDir, "cropped").apply { mkdirs() }
        val file = File(dir, "crop_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(file).use { stream ->
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw java.io.IOException("Could not encode cropped image")
                }
            }
            file
        } finally {
            if (cropped !== source && !cropped.isRecycled) cropped.recycle()
        }
    }.getOrNull()

    private fun decodeCapped(path: String, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxDim && sample <= (1 shl 29)) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, opts)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_RESULT = "extra_result"
    }
}
