package com.yason.octago.process

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.yason.octago.databinding.FragmentProcessBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.android.Utils
import org.opencv.android.OpenCVLoader
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import com.yason.core.MaterialMapGenerator
import androidx.exifinterface.media.ExifInterface
import org.opencv.core.Point
import androidx.core.graphics.scale
import androidx.navigation.findNavController
import androidx.navigation.fragment.findNavController
import com.yason.core.R
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlin.math.cos
import kotlin.math.sin


class ProcessFragment : Fragment() {
    private var _binding: FragmentProcessBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProcessViewModel by viewModels()
    private val args: ProcessFragmentArgs by navArgs()

    private lateinit var imageViews: List<ImageView>
    private lateinit var imageProcessingJob: Deferred<Pair<MutableList<Bitmap>, MutableList<Bitmap>>>

    private val processedImagePaths = mutableListOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProcessBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // UI ImageViews
        imageViews = listOfNotNull(
            binding.image01,
            binding.image02,
            binding.image03,
            binding.image04,
            binding.image05,
            binding.image06,
            binding.image07,
            binding.image08
        )

        // Get all IMAGE PATHS from safe arguments
        var imagePaths = args.imagePaths?.toList()
        if (imagePaths.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No images provided", Toast.LENGTH_SHORT).show()
            return
        }else if (imagePaths.size != 8) {
            Toast.makeText(requireContext(), "Expected 8 images, got ${imagePaths.size}", Toast.LENGTH_SHORT).show()
            return
        }

        // List of images in BITMAP format
        imageProcessingJob = viewLifecycleOwner.lifecycleScope.async(Dispatchers.Default) {
            val fullImages = mutableListOf<Bitmap>()
            val thumbnails = mutableListOf<Bitmap>()

            for (path in imagePaths) {
                val bitmap = BitmapFactory.decodeFile(path) ?: throw IllegalArgumentException("Failed to decode bitmap")

                // Rotate bitmap if necessary
                val rotation = getExifRotation(path)
                val rotatedBitmap = if (rotation != 0) {
                    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                // Crop bitmap to a square
                val side = minOf(rotatedBitmap.width, rotatedBitmap.height)
                val x = (rotatedBitmap.width - side) / 2
                val y = (rotatedBitmap.height - side) / 2
                val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, x, y, side, side)

                fullImages.add(croppedBitmap.scale(720, 720)) // change resolution
                thumbnails.add(croppedBitmap.scale(400, 400))
            }

            Pair(fullImages, thumbnails)
        }.also { job -> // after process set the thumbnail pictures
            viewLifecycleOwner.lifecycleScope.launch {
                val (_, thumbnails) = job.await()
                withContext(Dispatchers.Main) {
                    for (i in thumbnails.indices) {
                        imageViews[i].setImageBitmap(thumbnails[i])
                    }
//                    Log.d("ProcessFragment", "image view count: ${imageViews.size}")
//                    Log.d("ProcessFragment", "thumbnails count: ${thumbnails.size}")
                }
            }
        }

        // Image Processing Button
        binding.processBtn?.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                // Wait for the image processing job to complete
                var (fullImages, _) = imageProcessingJob.await()
//                var fullImages = loadImagesFromResources()
                var lightDirs = getLightDirections()

                withContext(Dispatchers.Main) {
                    setOverlayVisible(true)
                }

                when {
                    binding.fast!!.isChecked -> { // Fast mode with 4 pictures
                        fullImages = filterListByIndices(fullImages, listOf(0, 2, 4, 6)) as MutableList<Bitmap>
                        lightDirs = filterListByIndices(lightDirs, listOf(0, 2, 4, 6))

                    }
                    binding.balanced!!.isChecked -> { // Balanced mode with 6 pictures
                        fullImages = filterListByIndices(fullImages, listOf(1, 2, 3, 5, 6, 7)) as MutableList<Bitmap>
                        lightDirs = filterListByIndices(lightDirs, listOf(1, 2, 3, 5, 6, 7))
                    }
                }

                // Start Generate material maps Algorithm
                generateAndSaveMaterialMaps(fullImages, lightDirs)
            }
        }

        binding.exportToPhotoBtn?.setOnClickListener {
            Toast.makeText(requireContext(), "Exporting...", Toast.LENGTH_SHORT).show()
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                try {
                    imagePaths.forEach { path ->
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

        // Skip Button
        binding.skipBtn?.setOnClickListener {
            val action = ProcessFragmentDirections.actionProcessFragmentToGalleryFragment()
            findNavController().navigate(action)
        }

        // Back Button
        binding.backBtn?.setOnClickListener {
            val action = ProcessFragmentDirections.actionProcessFragmentToGalleryFragment()
            findNavController().navigate(action)
        }
    }

    // Save the bitmap to phone's photo gallery
    fun saveImageToGallery(context: Context, bitmap: Bitmap, filename: String) {
        // Set image metadata
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES  + "/OctaGO") // folder name
            put(MediaStore.Images.Media.IS_PENDING, 1) // To make sure file that is still writing is not shown in the Gallery
        }


        // Allow this app to interact with content providers (for files in public storage)
        val resolver = context.contentResolver
        // URI: unique address/ID for this file in MediaStore
        // Insert a file with public image storage location and the image's metadata
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        // Open OutputStream to that URI and save bitmap
        uri?.let {
            // Open a stream to write bytes into the file
            val outputStream = resolver.openOutputStream(it)
            outputStream?.use { stream ->
                // compresses and writes the bitmap into the stream with PNG format & full quality.
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }

            contentValues.clear() // just clears the ContentValues object in memory, does not affect the entry already inserted.
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0) // finished writing, update is_pending to 0
            resolver.update(uri, contentValues, null, null) // Updates image metadata, becomes visible in the Gallery
        }
    }

    /** ============================ Map generation preparation ============================ **/
    fun getExifRotation(path: String): Int {
        val exif = ExifInterface(path)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        Log.d("ExifDebug", "EXIF Orientation value: $orientation")
        return when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> -90
            ExifInterface.ORIENTATION_ROTATE_180 -> -180
            ExifInterface.ORIENTATION_ROTATE_270 -> -270
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
                Log.w("Exif", "Flip horizontal not handled — treating as 0")
                0
            }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                Log.w("Exif", "Flip vertical not handled — treating as 180")
                180
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                Log.w("Exif", "Transpose not handled — treating as 90")
                90
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                Log.w("Exif", "Transverse not handled — treating as 270")
                270
            }
            else -> 0
        }
    }

    private fun <T> filterListByIndices(
        originalList: List<T>,
        indicesToKeep: List<Int>
    ): List<T> {
        require(originalList.size == 8) { "The original list must have exactly 8 items" }

        return originalList.filterIndexed { index, _ ->
            index in indicesToKeep
        }.toMutableList()
    }

    /** ============================ Material Map Generation ============================ **/
    // load sample images for testing
    private fun loadImagesFromResources(): MutableList<Bitmap> {
        val imageResourceIds = arrayOf(
            R.drawable.fabric01,
            R.drawable.fabric02,
            R.drawable.fabric03,
            R.drawable.fabric04,
            R.drawable.fabric05,
            R.drawable.fabric06,
            R.drawable.fabric07,
            R.drawable.fabric08
        )
//        val imageResourceIds = arrayOf(
//            R.drawable.cropped_wall01,
////            R.drawable.cropped_wall02,
//            R.drawable.cropped_wall03,
////            R.drawable.cropped_wall04,
//            R.drawable.cropped_wall05,
////            R.drawable.cropped_wall06,
//            R.drawable.cropped_wall07,
////            R.drawable.cropped_wall08
//        )
        return imageResourceIds.map { resourceId ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeResource(resources, resourceId, options)
            bitmap.scale(720, 720)
        } as MutableList<Bitmap>
    }


/*    private fun getLightDirections(): List<FloatArray> = listOf(
        // For testing
        floatArrayOf(1.0f, 0.0f, 0.5f),
        floatArrayOf(1.0f, -1.0f, 0.5f),
        floatArrayOf(0.0f, -1.0f, 0.5f),
        floatArrayOf(-1.0f, -1.0f, 0.5f),
        floatArrayOf(-1.0f, 0.0f, 0.5f),
        floatArrayOf(-1.0f, 1.0f, 0.5f),
        floatArrayOf(0.0f, 1.0f, 0.5f),
        floatArrayOf(1.0f, 1.0f, 0.5f)

        // Actual light directions of the ESP lighting system
//        floatArrayOf(0.8165f, 0.3368f, 0.4695f),  // direction 0
//        floatArrayOf(0.6036f, 0.6036f, 0.4695f),  // direction 1
//        floatArrayOf(-0.3368f, 0.8165f, 0.4695f), // direction 2
//        floatArrayOf(-0.8165f, 0.3368f, 0.4695f), // direction 3
//        floatArrayOf(-0.8165f, -0.3368f, 0.4695f),// direction 4
//        floatArrayOf(-0.6036f, -0.6036f, 0.4695f),// direction 5
//        floatArrayOf(0.3368f, -0.8165f, 0.4695f), // direction 6
//        floatArrayOf(0.8165f, -0.3368f, 0.4695f)  // direction 7
    )*/

    private fun getLightDirections(): List<FloatArray> {
        val zComponent = 0.4695f  // sin(28°)
        val xyScale = 0.8829f     // cos(28°)

        return listOf(
            floatArrayOf(xyScale * cos(Math.toRadians(22.5)).toFloat(), xyScale * sin(Math.toRadians(22.5)).toFloat(), zComponent), // 22.5°
            floatArrayOf(xyScale * cos(Math.toRadians(67.5)).toFloat(), xyScale * sin(Math.toRadians(67.5)).toFloat(), zComponent), // 67.5°
            floatArrayOf(xyScale * cos(Math.toRadians(112.5)).toFloat(), xyScale * sin(Math.toRadians(112.5)).toFloat(), zComponent), // 112.5°
            floatArrayOf(xyScale * cos(Math.toRadians(157.5)).toFloat(), xyScale * sin(Math.toRadians(157.5)).toFloat(), zComponent), // 157.5°
            floatArrayOf(xyScale * cos(Math.toRadians(202.5)).toFloat(), xyScale * sin(Math.toRadians(202.5)).toFloat(), zComponent), // 202.5°
            floatArrayOf(xyScale * cos(Math.toRadians(247.5)).toFloat(), xyScale * sin(Math.toRadians(247.5)).toFloat(), zComponent), // 247.5°
            floatArrayOf(xyScale * cos(Math.toRadians(292.5)).toFloat(), xyScale * sin(Math.toRadians(292.5)).toFloat(), zComponent), // 292.5°
            floatArrayOf(xyScale * cos(Math.toRadians(337.5)).toFloat(), xyScale * sin(Math.toRadians(337.5)).toFloat(), zComponent) // 337.5°
        )
    }


    // Generate material maps from the 8 bitmap images
    private suspend fun generateAndSaveMaterialMaps(bitmapImages: List<Bitmap>, lightDirs: List<FloatArray>) {
        Log.d("ProcessFragment", "Generating material maps...")
        withContext(Dispatchers.Default) {
            try {
                val images = bitmapImages.map { bitmap ->
                    Log.d("BitmapSize", "Bitmap size: ${bitmap.width} x ${bitmap.height}")
                    val mat = Mat()
                    Log.d("MatSize", "Mat size: ${mat.cols()} x ${mat.rows()}")
                    Utils.bitmapToMat(bitmap, mat)
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                    mat
                }

                val generator = MaterialMapGenerator(images, lightDirs, images.size)
                val result = generator.generate()

                Log.d("ProcessFragment", "Material maps generated, trying to save...")
                withContext(Dispatchers.Main) {
                    try {
                        saveMatToPng(result.albedoMap, "albedo_map.png")
                        saveMatToPng(result.normalMap, "normal_map.png")
                        saveMatToPng(result.heightMap, "height_map.png")
                        Toast.makeText(requireContext(), "Maps generated!", Toast.LENGTH_SHORT).show()

                        // Saved all images Navigate to preview
                        val action = ProcessFragmentDirections.actionProcessFragmentToPreviewFragment(processedImagePaths.toTypedArray())
                        findNavController().navigate(action)

                    }catch (e: Exception){
                        Toast.makeText(requireContext(), "Failed to save maps, error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Dark overlay during map generation
    private fun setOverlayVisible(visible: Boolean) {
        binding.loadingOverlay!!.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(300)
            .withStartAction { // if fade in overlay, set view to visible in the beginning
                if (visible) binding.loadingOverlay!!.visibility = View.VISIBLE
            }
            .withEndAction { // if fade out overlay, set view to gone at the end
                if (!visible) binding.loadingOverlay!!.visibility = View.GONE
            }
            .start()
    }

    // Save generated material map as PNG
    private fun saveMatToPng(mat: Mat, filename: String) {
        val directory = File(requireContext().getExternalFilesDir(null), "MaterialMaps")
        if (!directory.exists()) directory.mkdirs()
        val file = File(requireContext().cacheDir, filename)
        Imgcodecs.imwrite(file.absolutePath, mat)
        Log.d("SaveMat", "Saved: ${file.absolutePath}")
        processedImagePaths.add(file.absolutePath)
    }



    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}