package com.yason.octago.preview

import android.animation.ValueAnimator
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.google.android.material.card.MaterialCardView
import com.yason.octago.databinding.FragmentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import androidx.core.graphics.scale
import androidx.navigation.fragment.findNavController
import com.yason.octago.process.ProcessFragmentDirections
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File


class PreviewFragment: Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private val args: PreviewFragmentArgs by navArgs()

    // 3 Processed material map images
    private val processedImages = mutableListOf<Bitmap>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)


        // Get all IMAGE PATHS from safe args
        val processedImagePaths = args.processedImagePaths?.toList()
        if (processedImagePaths.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No images provided", Toast.LENGTH_SHORT).show()
            return
        }else if (processedImagePaths.size != 3) {
            Toast.makeText(requireContext(), "Expected 3 images, got ${processedImagePaths.size}", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.async(Dispatchers.Default) {
            val thumbnails = mutableListOf<Bitmap>()

            // Load and scale all processed map images
            for (i in processedImagePaths.indices) {
                val bitmap = BitmapFactory.decodeFile(processedImagePaths[i]) ?: throw IllegalArgumentException("Failed to decode bitmap")
                thumbnails.add(bitmap.scale(400, 400, false))
                processedImages.add(bitmap) // Add bitmap to global list for easy access
                Log.d("PreviewFragment", "Added bitmap $i to processedImages list")
            }

            // Update UI on main thread
            withContext(Dispatchers.Main) {

                // For 2D PhotoView
                for (i in processedImages.indices) {
                    when (i) {
                        0 -> {
                            binding.albedoImage?.setImageBitmap(thumbnails[i])
                            // Set default image for Photoview
                            binding.photoView?.setImageBitmap(processedImages[i])
                        }
                        1 -> binding.normalImage?.setImageBitmap(thumbnails[i])
                        2 -> binding.heightImage?.setImageBitmap(thumbnails[i])
                    }
                }
            }
        }


        // Choose among 3 Material Map Card Options
        val mapOptions = listOf(binding.albedoMap, binding.normalMap, binding.heightMap)
        // Set albedo map as default selected option
        var selectedMapOption: MaterialCardView? = binding.albedoMap
        selectedMapOption?.strokeWidth = 4

        // Handle map option clicks
        for (i in mapOptions.indices) {
            mapOptions[i]?.setOnClickListener {
                Log.d("PreviewFragment", "Processed Images length: ${processedImages.size}")

                // If user clicks the same card → do nothing
                if (mapOptions[i] == selectedMapOption) return@setOnClickListener

                // Unselect previous
                selectedMapOption?.let { prev ->
                    ValueAnimator.ofInt(4,0).apply {
                        duration = 200
                        addUpdateListener { animator ->
                            val value = animator.animatedValue as Int
                            prev.strokeWidth = value
                        }
                        start()
                    }
                }

                // Select new
                ValueAnimator.ofInt(0, 4).apply {
                    duration = 200
                    addUpdateListener { animator ->
                        val value = animator.animatedValue as Int
                        mapOptions[i]?.strokeWidth = value
                    }
                    start()
                }

                // Change the PhotoView image based on the selected option
                if(processedImages.size == 3){
                    Log.d("PreviewFragment", "Changing image to ${mapOptions[i]}")
                    binding.photoView?.setImageBitmap(processedImages[i])
                }

                // Update selected option
                selectedMapOption = mapOptions[i]
            }
        }


        // Download all 3 material maps to phone's photo gallery
        binding.downloadBtn?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    processedImagePaths.forEach { path ->
                        val bitmap = BitmapFactory.decodeFile(path)

                        val originalName = File(path).nameWithoutExtension
                        val filename = "${originalName}_${System.currentTimeMillis()}.png" // make sure file name is unique
                        saveImageToGallery(requireContext(), bitmap, filename)

                        bitmap.recycle()
                    }
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Export complete!", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        // Back to Gallery Button
        binding.backBtn?.setOnClickListener {
            val action = PreviewFragmentDirections.actionPreviewFragmentToGalleryFragment()
            findNavController().navigate(action)
        }
    }


    // Save the bitmap to phone's photo gallery
    fun saveImageToGallery(context: Context, bitmap: Bitmap, filename: String) {
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES  + "/OctaGO") // folder name
            put(MediaStore.Images.Media.IS_PENDING, 1) // To make sure incomplete file is not shown
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        uri?.let {
            val outputStream = resolver.openOutputStream(it)
            outputStream?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


