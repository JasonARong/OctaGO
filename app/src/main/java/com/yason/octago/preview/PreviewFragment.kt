package com.yason.octago.preview

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.core.graphics.scale
import androidx.navigation.fragment.findNavController
import com.google.android.filament.Colors
import com.google.android.material.card.MaterialCardView
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Material
import com.google.android.filament.MaterialInstance
import com.google.android.filament.Renderer
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import com.google.android.filament.TextureSampler
import com.google.android.filament.utils.ModelViewer
import com.yason.octago.R
import com.yason.octago.databinding.FragmentPreviewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.yason.octago.process.ProcessFragmentDirections
import kotlin.math.max
import kotlin.math.floor
import java.io.File
import java.nio.ByteBuffer


class PreviewFragment: Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!
    private val args: PreviewFragmentArgs by navArgs()

    // UI overlay
    private var uiOverlayVisibility = true

    // 3 Material Maps
    private val processedImages = mutableListOf<Bitmap>()

    // For toggle between maps and 3d preview
    private var selectedView: View? = null

    // 3D rendering
    lateinit var modelViewer: ModelViewer
    lateinit var engine: Engine
    private var rotationAngle = 0.0f
    val originalTransform = FloatArray(16)

    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private val touchSlop = 2f   // For detecting drag vs tap
//    private val touchSlop by lazy {
//        ViewConfiguration.get(requireContext()).scaledTouchSlop.toFloat()
//    }
    private var currentRotationY = 0f
    private val sphereScale = 1.3f

    companion object {
        init {
            System.loadLibrary("filament-jni")
            System.loadLibrary("filament-utils-jni")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPreviewBinding.inflate(inflater, container, false)
        return binding.root
    }

    @SuppressLint("ClickableViewAccessibility")
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



        // Load, scale, populate processedImages, and update UI
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


        // Initialize with mapsButton selected
        view.post {
            animateToggle(binding.mapsButton!!, initial = true)
        }

        // Toggle Maps and 3D preview buttons
        binding.mapsButton?.setOnClickListener { // View 3 generated maps
            if(selectedView != it){
                animateToggle(it)
                setViewVisible(binding.photoView!!, true) // show photoView
                setViewVisible(binding.surfaceView!!, false) // hide 3d rendering
            }
        }
        binding.previewButton?.setOnClickListener { // 3D rendering preview
            if(selectedView != binding.previewButton){
                animateToggle(it)
                setViewVisible(binding.surfaceView!!, true) // show 3d rendering
                setViewVisible(binding.photoView!!, false) // hide photoView
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



        // Setting UI overlay visibility
        binding.photoView?.setOnClickListener {
            Log.d("PreviewFragment", "Photo view surface clicked")
            if (uiOverlayVisibility == true) {
                setOverlayVisible(false)
                uiOverlayVisibility = false
            } else {
                setOverlayVisible(true)
                uiOverlayVisibility = true
            }
        }

        binding.surfaceView?.setOnClickListener {
            Log.d("PreviewFragment", "3d rendering surface clicked")
            if (uiOverlayVisibility == true) {
                setOverlayVisible(false)
                uiOverlayVisibility = false
            } else {
                setOverlayVisible(true)
                uiOverlayVisibility = true
            }
        }



        // Download Maps Button
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







        /* ============================ 3D Rendering  ============================ */

        // UI surface view that allow low-level rendering
        val surfaceView = binding.surfaceView!!
        // Model Viewer: Utility class from Utils sets up: Engine, Scene, Camera, View, Renderer
        modelViewer = ModelViewer(surfaceView)
        engine = modelViewer.engine




        // Drag to rotate 3d model
        surfaceView.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    lastTouchX = event.x

                    // Rotate Y based on horizontal drag
                    currentRotationY += dx * 0.2f  // tune 0.2f for sensitivity

                    // Apply rotation
                    modelViewer.asset?.let { asset ->
                        val root = asset.root
                        val transformManager = engine.transformManager
                        val instance = transformManager.getInstance(root)

                        // Scale
                        val scaleMatrix = float4x4Scale(sphereScale)
                        // Rotation
                        val radians = Math.toRadians(currentRotationY.toDouble()).toFloat()
                        val rotationMatrix = float4x4RotationY(radians)

                        // Combine scale and rotation
                        val scaledRotation = multiplyMatrices(scaleMatrix, rotationMatrix)
                        val finalTransform = multiplyMatrices(scaledRotation, originalTransform)

                        transformManager.setTransform(instance, finalTransform)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val distanceSq = dx * dx + dy * dy
                    Log.d("PreviewFragment", "Distance squared: $distanceSq")
                    Log.d("PreviewFragment", "Touch slop: ${touchSlop * touchSlop}")

                    if (distanceSq < touchSlop * touchSlop) {
                        v.performClick()  // Real tap, not drag
                    }
                }
            }
            true
        }



        /* ============ Lighting ============ */
        // Direct Lighting
        val sun = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 1.0f, 1.0f)
            .intensity(100_000.0f)
            .direction(0.6f, -0.4f, -1.0f)
            .castShadows(false)
            .build(engine, sun)
        modelViewer.scene.addEntity(sun)


        // Indirect Lighting
        val iblTexture = Texture.Builder()
            .width(1)
            .height(1)
            .levels(1)
            .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
            .format(Texture.InternalFormat.RGB8)
            .build(engine)
        // Create a buffer for 6 faces (RGB each)
        val faceColors = ByteBuffer.allocateDirect(6 * 3)
        repeat(6) {
            faceColors.put(127.toByte()) // R
            faceColors.put(127.toByte()) // G
            faceColors.put(127.toByte()) // B
        }
        faceColors.flip()

        iblTexture.setImage(engine, 0,
            Texture.PixelBufferDescriptor(faceColors, Texture.Format.RGB, Texture.Type.UBYTE)
        )

        val indirectLight = IndirectLight.Builder()
            .reflections(iblTexture)
            .intensity(4_000.0f)  // Tune this → 10k~50k good starting range
            .build(engine)

        modelViewer.scene.indirectLight = indirectLight


        // Background Color
        val sr = 0x11 / 255f  // 17
        val sg = 0x13 / 255f  // 19
        val sb = 0x1E / 255f  // 30

        val linear = Colors.toLinear(Colors.RgbType.SRGB, sr, sg, sb)
        modelViewer.scene.skybox = Skybox.Builder().color(linear[0], linear[1], linear[2], 1f).build(engine)


        /* ============ Scene setup ============ */
        try {
            // Load my precompiled filamat file
            val matPackage = requireContext().assets.open("defaultFilpped.filamat").readBytes()
            val matBuffer = ByteBuffer.allocateDirect(matPackage.size)
            matBuffer.put(matPackage)
            matBuffer.flip()

            // Material: describes how surface looks (PBR shader)
            val defaultMaterial: Material = Material.Builder().payload(matBuffer, matBuffer.remaining()).build(engine)
            // Material Instance: allows dynamic parameters (textures, floats, colors)
            val defaultMaterialInstance: MaterialInstance = defaultMaterial.createInstance()

            // Load my 3d model (glb)
            val byteArray = requireContext().assets.open("sphere.glb").readBytes()
            val buffer = ByteBuffer.allocateDirect(byteArray.size)
            buffer.put(byteArray)
            buffer.flip()

            // Load the model
            modelViewer.loadModelGlb(buffer)
            modelViewer.transformToUnitCube() // rescales the model → to fit in the scene/view

            // Save the unit cube transform (contains scale)
            val transformManager = engine.transformManager
            val rootInstance = transformManager.getInstance(modelViewer.asset!!.root)
            transformManager.getTransform(rootInstance, originalTransform)

            // Apply extra 1.3× scale to the sphere
            val scaleMatrix = float4x4Scale(sphereScale)
            val enlargedTransform = multiplyMatrices(scaleMatrix, originalTransform)
            transformManager.setTransform(rootInstance, enlargedTransform)


            // Access the loaded asset
            val asset = modelViewer.asset

            // Check if the asset is loaded
            if (asset == null) {
                Log.e("MainActivity", "Failed to load model")
                return
            }

            val entities = asset.getEntities()
            Log.d("MainActivity", "Model loaded successfully with ${entities.size} entities")


            // Load albedo and normal maps as bitmaps
            val albedoBitmap = BitmapFactory.decodeFile(processedImagePaths[0])
            val normalBitmap = BitmapFactory.decodeFile(processedImagePaths[1])

            if (albedoBitmap == null || albedoBitmap.isRecycled) {
                Log.e("MainActivity", "Failed to load albedo bitmap")
                return
            }
            if (normalBitmap == null || normalBitmap.isRecycled) {
                Log.e("MainActivity", "Failed to load normal bitmap")
                return
            }

            val albedoTexture = createTextureFromBitmap(albedoBitmap, isSRGB = true) // color accurate
            val normalTexture = createTextureFromBitmap(normalBitmap, isSRGB = false) // Linear → mathematical values

            // Set the Material Instance parameters
            defaultMaterialInstance.setParameter("baseColor", albedoTexture, TextureSampler())
            defaultMaterialInstance.setParameter("normal", normalTexture, TextureSampler())
            defaultMaterialInstance.setParameter("roughness", 0.5f)
            defaultMaterialInstance.setParameter("metallic", 0.0f)
            defaultMaterialInstance.setParameter("reflectance", 0.8f)


            val renderableManager = engine.renderableManager
            // Apply the material to all entities/models
            // Iterate through the entities obtained from the asset
            for (entity in entities) {
                if (!renderableManager.hasComponent(entity)) continue

                val renderableInstance = renderableManager.getInstance(entity)
                val primCount = renderableManager.getPrimitiveCount(renderableInstance)

                // Setting defaultMaterialInstance to renderableInstance
                for (primIndex in 0 until primCount) {
                    renderableManager.setMaterialInstanceAt(renderableInstance, primIndex, defaultMaterialInstance)
                }
            }

        }catch (e: Exception) {
            Log.e("MainActivity", "Error setting up model and materials: ${e.message}")
            e.printStackTrace()
        }
    }



    // sey UI overlay visibility
    private fun setOverlayVisible(visible: Boolean) {
        binding.uiOverlay!!.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(300)
            .withStartAction { // if fade in overlay, set view to visible in the beginning
                if (visible) binding.uiOverlay!!.visibility = View.VISIBLE
            }
            .withEndAction { // if fade out overlay, set view to gone at the end
                if (!visible) binding.uiOverlay!!.visibility = View.GONE
            }
            .start()
    }


    /** ============================ Toggle Maps and 3D Render  ============================ **/
    // Animate toggle button
    private fun animateToggle(target: View, initial: Boolean = false) {
        selectedView = target

        val highlight = binding.highlightView!!
        val toX = target.x
        val toWidth = target.width


        if (!initial) {
            // Animate movement
            highlight.animate().x(toX).setDuration(200).start()

            // Animate resizing
            val widthAnimator = ValueAnimator.ofInt(highlight.width, toWidth)
            widthAnimator.duration = 250
            widthAnimator.addUpdateListener { valueAnimator ->
                val params = highlight.layoutParams
                params.width = valueAnimator.animatedValue as Int
                highlight.layoutParams = params
            }
            widthAnimator.start()
        }else {
            // First time setup, default mapsButton selected
            highlight.x = toX
            highlight.layoutParams.width = toWidth
            highlight.requestLayout()
        }

        // Update text color
        val selectedColor = ContextCompat.getColor(requireContext(), R.color.green)
        val unselectedColor = ContextCompat.getColor(requireContext(), R.color.white)
        if (target == binding.mapsButton){ // select mapButton
            animateTextColor(binding.mapsButton!!, unselectedColor, selectedColor)
            animateTextColor(binding.previewButton!!, selectedColor, unselectedColor)
        }
        else if (target == binding.previewButton){ // select previewButton
            animateTextColor(binding.previewButton!!, unselectedColor, selectedColor)
            animateTextColor(binding.mapsButton!!, selectedColor, unselectedColor)
        }
    }
    fun animateTextColor(textView: TextView, fromColor: Int, toColor: Int) {
        val colorAnimator = ValueAnimator.ofObject(ArgbEvaluator(), fromColor, toColor)
        colorAnimator.duration = 200
        colorAnimator.addUpdateListener { animator ->
            textView.setTextColor(animator.animatedValue as Int)
        }
        colorAnimator.start()
    }

    // Toggle section view visibility
    private fun setViewVisible(view: View, visible: Boolean) {
        view.animate()
            .alpha(if (visible) 1f else 0f)
            .setDuration(200)
            .withStartAction { // if fade in overlay, set view to visible in the beginning
                if (visible) view.visibility = View.VISIBLE
            }
            .withEndAction { // if fade out overlay, set view to gone at the end
                if (!visible) view.visibility = View.INVISIBLE
            }
            .start()
    }



    /** ============================ Save Texture Maps  ============================ **/
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



    /** ============================ 3D rendering  ============================ **/
    fun Bitmap.rotateBy180(): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(180f)
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }
    // Texture creation for albedo, normal, and height maps
    private fun createTextureFromBitmap(bitmap: Bitmap, isSRGB: Boolean): Texture {
        val maxDimension = max(bitmap.width, bitmap.height)
        val numLevels = if (maxDimension <= 1) 1 else (floor(log(maxDimension.toDouble(), 2.0)) + 1).toInt()

        val texture = Texture.Builder()
            .width(bitmap.width)
            .height(bitmap.height)
            .levels(numLevels)
            .sampler(Texture.Sampler.SAMPLER_2D)
            .format(if (isSRGB) Texture.InternalFormat.SRGB8_A8 else Texture.InternalFormat.RGBA8)
            .build(engine)

        val bmpToUpload = if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bitmap
        }

        val buffer = ByteBuffer.allocateDirect(bmpToUpload.byteCount)
        bmpToUpload.copyPixelsToBuffer(buffer)
        buffer.flip()

        texture.setImage(engine, 0,
            Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE))

        texture.generateMipmaps(engine)

        if (bmpToUpload != bitmap) {
            bmpToUpload.recycle()
        }

        return texture
    }
    private fun log(x: Double, base: Double): Double {
        return kotlin.math.ln(x) / kotlin.math.ln(base)
    }


    // Scale the sphere
    fun float4x4Scale(s: Float): FloatArray {
        return floatArrayOf(
            s, 0f, 0f, 0f,
            0f, s, 0f, 0f,
            0f, 0f, s, 0f,
            0f, 0f, 0f, 1f
        )
    }

    // Rotation
    fun float4x4RotationY(angleRad: Float): FloatArray {
        val c = kotlin.math.cos(angleRad)
        val s = kotlin.math.sin(angleRad)

        return floatArrayOf(
            c, 0f, -s, 0f,
            0f, 1f,  0f, 0f,
            s, 0f,  c, 0f,
            0f, 0f, 0f, 1f
        )
    }
    fun multiplyMatrices(a: FloatArray, b: FloatArray): FloatArray {
        val result = FloatArray(16)
        for (i in 0..3) {
            for (j in 0..3) {
                result[i * 4 + j] =
                    a[i * 4 + 0] * b[0 * 4 + j] +
                            a[i * 4 + 1] * b[1 * 4 + j] +
                            a[i * 4 + 2] * b[2 * 4 + j] +
                            a[i * 4 + 3] * b[3 * 4 + j]
            }
        }
        return result
    }


    // Render Loop, called every frame
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Render the frame
            modelViewer.render(frameTimeNanos)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onPause() {
        super.onPause()
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        modelViewer.destroyModel()
        modelViewer.engine.destroy()
        engine.destroy()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


