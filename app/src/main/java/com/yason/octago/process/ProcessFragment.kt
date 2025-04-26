package com.yason.octago.process

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Bundle
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


class ProcessFragment : Fragment() {
    private var _binding: FragmentProcessBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProcessViewModel by viewModels()
    private val args: ProcessFragmentArgs by navArgs()

    private lateinit var imageViews: List<ImageView>

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
        var bitmapImages = mutableListOf<Bitmap>()
        val thumbnailBitmapImages = mutableListOf<Bitmap>()
        for (path in imagePaths) {
            // Load
            val bitmap = BitmapFactory.decodeFile(path) ?: throw IllegalArgumentException("Failed to decode bitmap")

            // Rotate
            val rotation = getExifRotation(path)
            val rotatedBitmap = if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

            // Crop
            val side = minOf(rotatedBitmap.width, rotatedBitmap.height)
            val x = (rotatedBitmap.width - side) / 2
            val y = (rotatedBitmap.height - side) / 2

            val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, x, y, side, side)
            bitmapImages.add(croppedBitmap)

            // Downscaled images for the ImageView
            val thumbnailWidth = 200
            val thumbnailHeight = 200
            val scaledThumbnail = croppedBitmap.scale(thumbnailWidth, thumbnailHeight)
            thumbnailBitmapImages.add(scaledThumbnail)
        }

        // Load images into UI ImageViews
        for (i in thumbnailBitmapImages.indices) {
            imageViews[i].setImageBitmap(thumbnailBitmapImages[i])
        }



        // bitmapImages = filterListByIndices(bitmapImages, listOf(0, 2, 4, 6))

        binding.processBtn?.setOnClickListener {
            var lightDirs = getLightDirections()
            when {
                binding.fast!!.isChecked -> { // Fast mode with 4 pictures
                    bitmapImages = filterListByIndices(bitmapImages, listOf(0, 2, 4, 6)) as MutableList<Bitmap>
                    lightDirs = filterListByIndices(lightDirs, listOf(0, 2, 4, 6))

                }
                binding.balanced!!.isChecked -> { // Balanced mode with 6 pictures
                    bitmapImages = filterListByIndices(bitmapImages, listOf(1, 2, 3, 5, 6, 7)) as MutableList<Bitmap>
                    lightDirs = filterListByIndices(lightDirs, listOf(1, 2, 3, 5, 6, 7))
                }
            }
            viewLifecycleOwner.lifecycleScope.launch {
                generateAndSaveMaterialMaps(bitmapImages, lightDirs)
            }
        }

    }


    // Generate material maps from the 8 bitmap images
    private suspend fun generateAndSaveMaterialMaps(bitmapImages: List<Bitmap>, lightDirs: List<FloatArray>) {
        Log.d("ProcessFragment", "Generating material maps...")
        withContext(Dispatchers.Default) {
            try {
                val images = bitmapImages.map { bitmap ->
                    val mat = Mat()
                    Utils.bitmapToMat(bitmap, mat)
                    Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                    mat
                }

                val generator = MaterialMapGenerator(images, lightDirs, images.size)
                val result = generator.generate()

                Log.d("ProcessFragment", "Material maps generated, trying to save...")
                withContext(Dispatchers.Main) {
                    saveMatToPng(result.albedoMap, "albedo_map.png")
                    saveMatToPng(result.normalMap, "normal_map.png")
                    saveMatToPng(result.heightMap, "height_map.png")
                    Toast.makeText(requireContext(), "Maps generated!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    //how to wrap this part of my code into a function
    // Generate material maps from the 8 image file path
    /*
    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
        try{
            val images = bitmapImages.map { bitmap ->
                val mat = Mat()
                Utils.bitmapToMat(bitmap, mat)
                Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
                mat
            }
            val lightDirs = getLightDirections()

            // Create MaterialMapGenerator obj from core
            val generator = MaterialMapGenerator(images, lightDirs, images.size)
            val result = generator.generate() // start generating material maps

            withContext(Dispatchers.Main) {
                saveMatToPng(result.albedoMap, "albedo_map.png")
                saveMatToPng(result.normalMap, "normal_map.png")
                saveMatToPng(result.heightMap, "height_map.png")
                Toast.makeText(requireContext(), "Maps generated!", Toast.LENGTH_SHORT).show()
            }
        }catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }
    */

    fun loadAndCropMat(imagePath: String): Mat {
        // Load bitmap from imagePath
        val bitmap = BitmapFactory.decodeFile(imagePath)
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2BGR)
        bitmap.recycle()

        // Rotate image based on EXIF data
        val rotation = getExifRotation(imagePath)
        val rotatedMat =
        if (rotation != 0) {
            val center = Point((mat.cols() / 2).toDouble(), (mat.rows() / 2).toDouble())
            val rotMat = Imgproc.getRotationMatrix2D(center, rotation.toDouble(), 1.0)
            val result = Mat()
            Imgproc.warpAffine(mat, result, rotMat, mat.size())
            result
        } else {
            mat
        }

        // Temporary implementation to determine center square region
        // TODO: need to change the size to match the input size
        val width = rotatedMat.cols()
        val height = rotatedMat.rows()
        Log.d("loadAndCropMat", "og width: $width, og height: $height")
        val side = minOf(width, height)
        val x = (width - side) / 2
        val y = (height - side) / 2
        val croppedMat = Mat(rotatedMat, org.opencv.core.Rect(x, y, side, side))

        // Clean up temp mats
        if (rotation != 0) {
            mat.release()
            rotatedMat.release()
        }

        return croppedMat
    }


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


    private fun getLightDirections(): List<FloatArray> = listOf(
        floatArrayOf(1.0f, 0.0f, 0.5f),
        floatArrayOf(0.0f, -1.0f, 0.5f),
        floatArrayOf(-1.0f, 0.0f, 0.5f),
        floatArrayOf(0.0f, 1.0f, 0.5f),
        floatArrayOf(1.0f, 1.0f, 0.5f),
        floatArrayOf(-1.0f, 1.0f, 0.5f),
        floatArrayOf(-1.0f, -1.0f, 0.5f),
        floatArrayOf(1.0f, -1.0f, 0.5f)
    )

    private fun saveMatToPng(mat: Mat, filename: String) {
        val directory = File(requireContext().getExternalFilesDir(null), "MaterialMaps")
        if (!directory.exists()) directory.mkdirs()
        val file = File(directory, filename)
        Imgcodecs.imwrite(file.absolutePath, mat)
        Log.d("SaveMat", "Saved: ${file.absolutePath}")
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


    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}