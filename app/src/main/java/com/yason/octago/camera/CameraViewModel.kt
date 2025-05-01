package com.yason.octago.camera

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class CameraViewModel : ViewModel() {
    private val _capturedImages = mutableListOf<String>()
    val capturedImages: List<String> get() = _capturedImages

    private val espIp = "192.168.4.1"

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

    fun captureWithLightingSystem(
        context: Context,
        imageCaptureFunc: suspend (String) -> Boolean,
        onComplete: (List<String>) -> Unit
    ) {
        resetImages()
        viewModelScope.launch {
            try{
                // Tell ESP to start lighting capture
                sendPostRequest("start_capture")

                // Repeat 8 times to take 8 pictures
                for (i in 0 until 8) {
                    //Prepare file for saving image
                    val fileName = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(System.currentTimeMillis())}_$i.png"
                    val file = File(context.cacheDir, fileName)

                    // Capture photo under current light
                    val success = imageCaptureFunc(file.absolutePath)
                    if (success) {
                        _capturedImages.add(file.absolutePath)
                    } else {
                        Log.e("Capture", "Failed to capture image $i")
                    }

                    sendPostRequest("next_light")
                    delay(200)
                }
                onComplete(_capturedImages)
            }catch (e: Exception){
                e.printStackTrace()
            }
        }
    }

    private suspend fun sendPostRequest(path: String) {
        Log.d("CameraViewModel", "Sending Request: POST $path")
        withContext(Dispatchers.IO){
            try {
                val url = URL("http://$espIp/$path")
                val conn = url.openConnection() as HttpURLConnection // open connection
                conn.requestMethod = "POST"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.doOutput = true
                conn.connect()

                val responseCode = conn.responseCode
                Log.d("WiFi", "POST $path responded: $responseCode")

                conn.disconnect()
            }catch (e: Exception){
                e.printStackTrace()
            }
        }
    }
}