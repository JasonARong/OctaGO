package com.yason.octago.camera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.ImageCapture
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.navigation.fragment.findNavController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.yason.octago.R
import com.yason.octago.databinding.FragmentCameraBinding
import com.yason.octago.camera.LightingWebSocketManager
import com.yason.octago.preview.PreviewFragmentDirections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL


class CameraFragment : Fragment() {
    // View binding
    private var _binding: FragmentCameraBinding? = null // FragmentCameraBinding generated from fragment_camera.xml
    private val binding get() = _binding!! // non-nullable version of _binding

    // Camera
    private val viewModel: CameraViewModel by viewModels()
    private var imageCapture: ImageCapture? = null // CameraX ImageCapture object

    // ESP
    // lighting websocket manager that listens to shutter press
    private lateinit var lightingWebSocketManager: LightingWebSocketManager
    private var isConnectedToESP = false
    private var connectionMonitorJob: Job? = null // Job to monitor connection status
    private var shutterClickedCount = 0


    // Called when create the user interface view.
    override fun onCreateView(
        inflater: LayoutInflater,   // System service to inflate (create) views from XML layout files.
        container: ViewGroup?,      // Parent view that the fragment's UI should be attached to.
        savedInstanceState: Bundle? // A bundle that contains the saved state of the fragment.
    ): View {
        // Setup view binding, inflate the layout for this fragment
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root // returns the root view of the inflated layout, displayed on the screen
    }


    // Called when view is created but before it's attached to its parent
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // Permissions
        var missingPermissions = false
        // Start camera if the permissions are granted
        if (allCameraPermissionsGranted()) {
            startCamera()
        } else {
            Log.d("CameraFragment", "Missing Camera permissions")
            missingPermissions = true

        }
        if (missingPermissions) {
            requestPermissionLauncher.launch(REQUIRED_CAMERA_PERMISSIONS)
        }


        // Start ESP connection monitor, no needed, switch to use websocket for monitor
        startConnectionMonitor()


        // Lighting WebSocket, Listens to shutter button click
        lightingWebSocketManager = LightingWebSocketManager(
            serverUrl = "ws://192.168.4.1:81",
            onShutterPress = { // shutter button pressed, trigger UI button
                requireActivity().runOnUiThread {
                    binding.captureButton.performClick()
                }
            },
        )
        lightingWebSocketManager.connect()


        // Connection Button
        binding.connectionBtn.setOnClickListener {
            if(isConnectedToESP){
                val builder = AlertDialog.Builder(requireContext())
                builder.setTitle("OctaGO Lighting System is connected")
                builder.setPositiveButton("Let's Scan Material!", null)
                val dialog = builder.create()
                dialog.show()
            }else{
                val builder = AlertDialog.Builder(requireContext())
                builder.setTitle("Connect to OctaGO Lighting System")
                builder.setMessage("Set your Wi-Fi to \"ESP_Lighting\" ")
                builder.setPositiveButton("WI-FI Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                }
                builder.setNegativeButton("Cancel", null)
                val dialog = builder.create()
                dialog.show()
            }
        }

        // Gallery Button
        binding.galleryBtn.setOnClickListener {
            val action = CameraFragmentDirections.actionCameraFragmentToGalleryFragment()
            findNavController().navigate(action)
        }


        // Camera Shutter
        binding.captureButton.setOnClickListener {
            if (allCameraPermissionsGranted()) {
                if (isConnectedToESP){ // if connected to ESP, use lighting system
                    if (shutterClickedCount == 0) { // if first click, light up the first light for correcting exposure and monitor
                        viewModel.readyToCapture()
                        shutterClickedCount++

                        // wait for a while, if no second click, reset the capture
                        lifecycleScope.launch {
                            delay(20_000) // wait for 20 seconds
                            if (shutterClickedCount == 1) {
                                viewModel.resetCapture()
                                shutterClickedCount = 0
                            }
                        }
                    }
                    else if (shutterClickedCount == 1){ // actually take the 8 photos
                        capture8PhotosWithLightingSystem()
                        shutterClickedCount = 0
                    }
                }
                else{ // if not connected to ESP, use simulated lighting
                    val builder = AlertDialog.Builder(requireContext())
                    builder.setTitle("Scan material without lighting system?")

                    builder.setPositiveButton("Scan Material") { dialog, which ->
                        capture8PhotosWithSimulatedLighting()
                    }
                    builder.setNegativeButton("Cancel") { dialog, which ->
                        // User clicked Cancel
                        dialog.dismiss()
                    }

                    val dialog = builder.create()
                    dialog.show()
                }
            }else {
                requestPermissionLauncher.launch(REQUIRED_CAMERA_PERMISSIONS)
            }
        }
    }




    /** ============================ Permissions ============================ **/
    // All Required Permissions: camera, bluetooth
    private val REQUIRED_CAMERA_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)


    // Check Granted Permissions
    private fun allCameraPermissionsGranted() = REQUIRED_CAMERA_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }


    /* ============= Request Permissions ============= */
    @SuppressLint("MissingPermission")
    @RequiresApi(Build.VERSION_CODES.R)
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions() // request multiple permissions
    ) { permissions -> // permissions results

        Log.d("CameraFragment", "request permissions")

        // Process camera permission result
        if(permissions[Manifest.permission.CAMERA] == true){
            Log.d("CameraFragment", "Camera permission granted")
            // Force recreation of camera preview, refresh
            binding.previewView.visibility = View.GONE // Temporarily hides the previewView
            binding.root.postDelayed({
                binding.previewView.visibility = View.VISIBLE
                startCamera()
            }, 100)
        }
        else if( permissions[Manifest.permission.CAMERA] == false){
            Log.d("CameraFragment", "Camera permission denied")
            Toast.makeText(requireContext(), "Camera permission is required", Toast.LENGTH_LONG).show()
        }

    }



    /** ============================ ESP Lighting Connection Status ============================ **/
    private fun updateConnectionIndicator(isConnected: Boolean) {
        val colorResId = if (isConnected) R.color.green else R.color.red // select color
        // Apply color
        binding.connectionIndicator.backgroundTintList = ContextCompat.getColorStateList(requireContext(), colorResId)
    }

    @SuppressLint("MissingPermission")
    private fun startConnectionMonitor() {
        connectionMonitorJob?.cancel() // Prevent duplicate jobs

        connectionMonitorJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
//                val connectionStatus = isConnectedToEsp(requireContext()) // possible missing permission
                val connectionStatus = pingEsp()

                // Update UI if connection status has changed
                if (connectionStatus != isConnectedToESP) {
                    withContext(Dispatchers.Main) {
                        updateConnectionIndicator(connectionStatus)
                    }
                }

                isConnectedToESP = connectionStatus

                delay(2000) // Check every 2 seconds
            }
        }
    }
    suspend fun pingEsp(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = URL("http://192.168.4.1/ping")
            (url.openConnection() as HttpURLConnection).run {
                requestMethod  = "GET"
                setRequestProperty("Connection", "close")
                connectTimeout = 1000
                readTimeout    = 1000
                connect()
                val ok = responseCode == 200
                disconnect()
                ok
            }
        } catch (e: Exception) {
            false   // any exception → not reachable
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_NETWORK_STATE)
    fun isConnectedToEsp(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        // Check if connected via Wi-Fi
        if (networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo

            val bssid = connectionInfo.bssid

            // If BSSID is not null/unknown, we are connected to some Wi-Fi
            if (bssid != null && bssid != "00:00:00:00:00:00") {
                // Optional: if you want to validate it is ESP AP specifically
                // you can hardcode your ESP BSSID if needed or just return true

                return true
            }
        }

        return false
    }







    /** ============================ Setup Camera ============================ **/
    // Initializing and starting the camera using CameraX.
    @RequiresApi(Build.VERSION_CODES.R)
    private fun startCamera() {
        // First, make sure any previous camera instance is released
        // cameraProviderFuture will hold the singleton ProcessCameraProvider(manages the lifecycle of CameraX)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext()) // aync initialization of the instance
        cameraProviderFuture.addListener({ // runs lambda when the instance is available
            try {
                // Get the camera provider
                val cameraProvider = cameraProviderFuture.get()
                // unbinds all previously bound CameraX use cases from the lifecycle
                cameraProvider.unbindAll()

                // Build the preview use case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(binding.previewView.surfaceProvider)
                    }

                // Set a maximum resolution for the picture
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1440, 1440), // preferred upper bound
                            // ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()

                // Setup image capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setTargetRotation(Surface.ROTATION_90)
                    .setTargetRotation(requireActivity().display?.rotation ?: Surface.ROTATION_0)
                    .setResolutionSelector(resolutionSelector)
                    .build()

                // Select back camera
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                // Bind use cases to camera
                cameraProvider.bindToLifecycle(
                    viewLifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                // Log that camera is started
                Log.d("CameraFragment", "Camera setup complete")

            } catch (e: Exception) {
                Log.e("CameraFragment", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(requireContext()))// executes the lambda on the main thread
    }




    /** ============================ Capture Images ============================ **/
    private fun capture8PhotosWithSimulatedLighting() {
        // Set shutter UI to loading
        setShutterButtonLoading(true)

        // call capture in viewmodel
        // ::captureToFile & {imagePaths -> ...} callback functions
        viewModel.simulateLightSyncCapture(requireContext(), ::captureToFile) { imagePaths ->
            Log.d("CaptureLoop", "Captured ${imagePaths.size} photos")
            imagePaths.forEachIndexed { i, path ->
                Log.d("CaptureLoop", "Photo $i: $path")
            }


            setShutterButtonLoading(false)
            val action = CameraFragmentDirections.actionCameraFragmentToProcessFragment(imagePaths.toTypedArray())
            findNavController().navigate(action)
        }
    }

    private fun capture8PhotosWithLightingSystem() {
        // Set shutter UI to loading
        setShutterButtonLoading(true)

        viewModel.captureWithLightingSystem(requireContext(), ::captureToFile) { imagePaths ->
            Log.d("CaptureResults", "Captured ${imagePaths.size} photos")
            imagePaths.forEachIndexed { i, path ->
                Log.d("CaptureResults", "Photo $i: $path")
            }

            setShutterButtonLoading(false)
            val action = CameraFragmentDirections.actionCameraFragmentToProcessFragment(imagePaths.toTypedArray())
            findNavController().navigate(action)
        }
    }


    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun captureToFile(path: String): Boolean {
        val imageCapture = imageCapture ?: return false
        val file = File(path)

        // suspendCancellableCoroutine will only continue when resume is called
        // Wrap callback-based takePicture inside a suspending block
        // Returns true when the coroutine resumes
        return suspendCancellableCoroutine { continuation ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(requireContext()), // gives executor that runs tasks on the main thread
                object : ImageCapture.OnImageSavedCallback { // anonymous obj implementation of OnImageSavedCallback interface
                    override fun onError(exc: ImageCaptureException) {
                        Log.e("Camera", "Capture failed: ${exc.message}")
                        if (continuation.isActive) continuation.resume(false,onCancellation = null) // return false
                    }

                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        Log.d("Camera", "Captured to ${file.absolutePath}")
                        if (continuation.isActive) continuation.resume(true,onCancellation = null) // return true
                    }
                }
            )
        }
    }

    // Set the UI shutter button while capturing 8 images
    private fun setShutterButtonLoading(isLoading: Boolean) {
        binding.captureButton.isEnabled = !isLoading

        if (isLoading) {
//            binding.captureButton.alpha = 0.4f
            ValueAnimator.ofFloat(1.0f, 0.4f).apply {
                duration = 100
                addUpdateListener { animator ->
                    val value = animator.animatedValue as Float
                    binding.captureButton.alpha = value
                }
                start()
            }
        } else {
            binding.captureButton.alpha = 1f
//            ValueAnimator.ofFloat(0.4f, 1.0f).apply {
//                duration = 100
//                addUpdateListener { animator ->
//                    val value = animator.animatedValue as Float
//                    binding.captureButton.alpha = value
//                }
//                start()
//            }
        }
    }


    // Force landscape orientation when this fragment is visible
    override fun onResume() {
        super.onResume()
        // Force landscape orientation when this fragment is visible
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null

        // Discount lighting websocket connections
        lightingWebSocketManager.disconnect()

        // Allow all orientation when fragment is destroyed
        //activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}







