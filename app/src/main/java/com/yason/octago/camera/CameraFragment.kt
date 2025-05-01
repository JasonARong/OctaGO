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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.navigation.fragment.findNavController
import com.yason.octago.databinding.FragmentCameraBinding
import java.io.InputStream
import java.io.OutputStream


class CameraFragment : Fragment() {
    // View binding
    private var _binding: FragmentCameraBinding? = null // FragmentCameraBinding generated from fragment_camera.xml
    private val binding get() = _binding!! // non-nullable version of _binding

    // Camera
    private val viewModel: CameraViewModel by viewModels()
    private var imageCapture: ImageCapture? = null // CameraX ImageCapture object

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


        // Connection Button
        binding.connectionBtn.setOnClickListener {
//            if (allBluetoothPermissionsGranted()) {
//                if (!isBluetoothSetup){
//                    setupBluetooth()
//                }
//                if (!isEspConnected){
//                    connectToEspLightingSystem()
//                }
//            } else {
//                requestPermissionLauncher.launch(REQUIRED_BLUETOOTH_PERMISSIONS)
//            }
        }


        // Camera Shutter
        binding.captureButton.setOnClickListener {
            if (allCameraPermissionsGranted()) {
                //capture8PhotosWithSimulatedLighting()
                capture8PhotosWithLightingSystem()
            }else {
                requestPermissionLauncher.launch(REQUIRED_CAMERA_PERMISSIONS)
            }
        }


    }




    /** ============================ Permissions ============================ **/
    // All Required Permissions: camera, bluetooth
    private val REQUIRED_CAMERA_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    private val REQUIRED_BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH
        )
    }
    private val REQUIRED_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S){
        arrayOf( // For Android 12+ (API 31+)
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
    }else{ // For Android < 12
        arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.BLUETOOTH
        )
    }

    // Check Granted Permissions
    private fun allCameraPermissionsGranted() = REQUIRED_CAMERA_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    private fun allBluetoothPermissionsGranted() = REQUIRED_BLUETOOTH_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { // loop all required permissions
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
        // call capture in viewmodel
        // ::captureToFile & {imagePaths -> ...} callback functions
        viewModel.simulateLightSyncCapture(requireContext(), ::captureToFile) { imagePaths ->
            Log.d("CaptureLoop", "Captured ${imagePaths.size} photos")
            imagePaths.forEachIndexed { i, path ->
                Log.d("CaptureLoop", "Photo $i: $path")
            }

            val action = CameraFragmentDirections.actionCameraFragmentToProcessFragment(imagePaths.toTypedArray())
            findNavController().navigate(action)
        }
    }

    private fun capture8PhotosWithLightingSystem() {
        viewModel.captureWithLightingSystem(requireContext(), ::captureToFile) { imagePaths ->
            Log.d("CaptureResults", "Captured ${imagePaths.size} photos")
            imagePaths.forEachIndexed { i, path ->
                Log.d("CaptureResults", "Photo $i: $path")
            }

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


    // Force landscape orientation when this fragment is visible
    override fun onResume() {
        super.onResume()
        // Force landscape orientation when this fragment is visible
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}