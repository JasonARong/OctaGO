package com.yason.octago.camera

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraViewModel : ViewModel() {
    private val _capturedImages = mutableListOf<String>()
    val capturedImages: List<String> get() = _capturedImages

    fun resetImages() {
        _capturedImages.clear()
    }
    fun simulateLightSyncCapture(
        context: Context,
        imageCaptureFunc: suspend (String) -> Boolean,
        onComplete: (List<String>) -> Unit
    ){
        resetImages()

        viewModelScope.launch { // CoroutineScope tied to the lifecycle of the ViewModel
            for (i in 0 until 8) {
                Log.d("LightSim", "Simulating light $i ON")

                // create directory
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PhotoCapture")
                if (!directory.exists()) {
                    directory.mkdirs()
                }

                val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())}_$i.png"
                val file = File(context.cacheDir, fileName)
//                val file = File(directory, fileName)

                val success = imageCaptureFunc(file.absolutePath)
                if (success) {
                    _capturedImages.add(file.absolutePath)
                } else {
                    Log.e("LightSim", "Failed to capture image $i")
                }

                delay(200) // wait for light to stabilize (simulate)
            }
            onComplete(_capturedImages)
        }
    }
}