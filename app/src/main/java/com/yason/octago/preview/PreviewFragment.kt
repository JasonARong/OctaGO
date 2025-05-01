package com.yason.octago.preview

import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
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

class PreviewFragment: Fragment() {

    private var _binding: FragmentPreviewBinding? = null
    private val binding get() = _binding!!

    private val args: PreviewFragmentArgs by navArgs()
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

        // Get all IMAGE PATHS from safe arguments
        val processedImagePaths = args.processedImagePaths?.toList()
        if (processedImagePaths.isNullOrEmpty()) {
            Toast.makeText(requireContext(), "No images provided", Toast.LENGTH_SHORT).show()
            return
        }else if (processedImagePaths.size != 3) {
            Toast.makeText(requireContext(), "Expected 8 images, got ${processedImagePaths.size}", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.async(Dispatchers.Default) {
            for (i in processedImagePaths.indices) {
                // Load bit map from path
                val bitmap = BitmapFactory.decodeFile(processedImagePaths[i]) ?: throw IllegalArgumentException("Failed to decode bitmap")

                // Set thumbnail for each option
                val thumbnail = bitmap.scale(200, 200, false)
                when (i) {
                    0 -> binding.albedoImage?.setImageBitmap(thumbnail)
                    1 -> binding.normalImage?.setImageBitmap(thumbnail)
                    2 -> binding.heightImage?.setImageBitmap(thumbnail)
                }

                // Add bitmap to global list for easy access
                processedImages.add(bitmap)
            }
        }


        val mapOptions = listOf(binding.albedoMap, binding.normalMap, binding.heightMap)
        // Set albedo map as default selected option
        var selectedMapOption: MaterialCardView? = binding.albedoMap
        selectedMapOption?.strokeWidth = 4

        // Handle map option clicks
        for (mapOption in mapOptions) {
            mapOption?.setOnClickListener {

                // If user clicks the same card → do nothing
                if (mapOption == selectedMapOption) return@setOnClickListener

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
                        mapOption.strokeWidth = value
                    }
                    start()
                }

                selectedMapOption = mapOption
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}


