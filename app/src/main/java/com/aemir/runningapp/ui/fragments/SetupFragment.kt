package com.aemir.runningapp.ui.fragments

import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.aemir.runningapp.R
import com.aemir.runningapp.databinding.FragmentSetupBinding
import com.aemir.runningapp.other.Constants.KEY_FIRST_TIME_TOGGLE
import com.aemir.runningapp.other.Constants.KEY_NAME
import com.aemir.runningapp.other.Constants.KEY_WEIGHT
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SetupFragment : Fragment(R.layout.fragment_setup) {

    private lateinit var binding: FragmentSetupBinding

    @Inject
    lateinit var sharedPref: SharedPreferences

    @set:Inject
    var isFirstOpen = true

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!isFirstOpen) {
            navigateToRunFragment()
        }
        binding = FragmentSetupBinding.bind(view).apply {
            tvContinue.setOnClickListener {
                val success = writePersonalDataToSharedPref()
                if (success) {
                    navigateToRunFragment()
                } else {
                    Snackbar.make(
                        requireView(),
                        "Please enter all the fields",
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun navigateToRunFragment(savedInstanceState: Bundle? = null) {
        val navOptions = NavOptions.Builder()
            .setPopUpTo(R.id.setupFragment, true)
            .build()
        findNavController().navigate(
            R.id.action_setupFragment_to_runFragment,
            savedInstanceState,
            navOptions
        )
    }

    private fun writePersonalDataToSharedPref(): Boolean {
        val name = binding.etName.text.toString()
        val weight = binding.etWeight.text.toString()
        if (name.isEmpty() || weight.isEmpty()) return false
        sharedPref.edit()
            .putString(KEY_NAME, name)
            .putFloat(KEY_WEIGHT, weight.toFloat())
            .putBoolean(KEY_FIRST_TIME_TOGGLE, false)
            .apply()
        val toolbarText = "Let's go, $name!"
        requireActivity().findViewById<TextView>(R.id.tvToolbarTitle).text = toolbarText
        return true
    }
}