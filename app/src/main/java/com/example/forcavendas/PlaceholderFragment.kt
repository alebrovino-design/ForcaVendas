package com.example.forcavendas

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentPlaceholderBinding

class PlaceholderFragment : Fragment() {
    private var binding: FragmentPlaceholderBinding? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val viewBinding = FragmentPlaceholderBinding.inflate(inflater, container, false)
        binding = viewBinding
        return viewBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding?.placeholderTitle?.text = arguments?.getString("screen_title") ?: "Tela"
        binding?.placeholderBack?.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }
}
