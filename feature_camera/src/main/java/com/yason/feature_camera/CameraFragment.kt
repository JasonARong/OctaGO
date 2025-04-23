package com.yason.feature_camera

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.camera.core.ImageCapture
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.yason.feature_camera.databinding.FragmentCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import androidx.navigation.fragment.findNavController

class CameraFragment : Fragment() {
    // View binding
    private var _binding: FragmentCameraBinding? = null // FragmentCameraBinding generated from fragment_camera.xml
    private val binding get() = _binding!! // non-nullable version of _binding

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
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (allPermissionsGranted()) {
            Log.d("CameraFragment", "Permissions already granted, starting camera")
            startCamera()
        } else {
            Log.d("CameraFragment", "Requesting permissions")
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }


        // Button Interactions
        binding.captureButton.setOnClickListener {
//            capturePhoto()
            capture8PhotosWithSimulatedLighting()
        }
    }

    /* ============================ Permissions ============================ */
    // Required Permissions
    private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA) // [Camera Permission]

    // Check if all camera permissions are granted
    // Expression Body Function for Boolean returns
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all { // loop all required permissions
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    // Request Camera Permissions
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions() // request multiple permissions
    ) { permissions -> // permissions results
        Log.d("CameraFragment", "Permission result received")
        val allGranted = permissions.entries.all { it.value } // Get whether all permissions granted
        if (allGranted) {
            Log.d("CameraFragment", "All permissions granted, starting camera")
            // Force recreation of camera preview, refresh
            binding.previewView.visibility = View.GONE // Temporarily hides the previewView
            binding.root.postDelayed({
                binding.previewView.visibility = View.VISIBLE
                startCamera()
            }, 100)
        } else {
            Log.d("CameraFragment", "Permissions denied")
            Toast.makeText(
                requireContext(),
                "Camera permission is required to use this feature",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /* ============================ Setup Camera ============================ */
    // Initializing and starting the camera using CameraX.
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

                // Setup image capture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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



    /* ============================ Capture Images ============================ */
    private fun capture8PhotosWithSimulatedLighting() {
        // call capture in viewmodel
        // ::captureToFile & {imagePaths -> ...} callback functions
        viewModel.simulateLightSyncCapture(requireContext(), ::captureToFile) { imagePaths ->
            Log.d("CaptureLoop", "Captured ${imagePaths.size} photos")
            imagePaths.forEachIndexed { i, path ->
                Log.d("CaptureLoop", "Photo $i: $path")
            }

            val bundle = Bundle().apply {
                putStringArray("imagePaths", imagePaths.toTypedArray())
            } // bundle.putStringArray("imagePaths", imagePaths.toTypedArray())


            Toast.makeText(requireContext(), "All 8 images captured!", Toast.LENGTH_SHORT).show()
            // TODO: Navigate to ProcessFragment with imagePaths
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


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}