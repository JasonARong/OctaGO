package com.yason.feature_process

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.navArgs
import com.yason.feature_process.databinding.FragmentProcessBinding

class ProcessFragment : Fragment() {
    private var _binding: FragmentProcessBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ProcessViewModel by viewModels()
//    private val args: ProcessFragmentArgs by navArgs()

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

        val imagePaths = arguments?.getStringArray("imagePaths")?.toList() ?: emptyList()
        viewModel.setImagePaths(imagePaths)

        // TODO: Bind to RecyclerView or UI here
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}