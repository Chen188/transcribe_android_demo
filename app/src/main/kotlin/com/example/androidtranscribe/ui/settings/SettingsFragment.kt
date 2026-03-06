package com.example.androidtranscribe.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.androidtranscribe.R
import com.example.androidtranscribe.aws.AwsPrefs
import com.example.androidtranscribe.databinding.FragmentSettingsBinding

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadCurrentValues()
        updateStatus()

        binding.btnSave.setOnClickListener { saveCredentials() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private fun loadCurrentValues() {
        val ctx = requireContext()
        binding.edtRegion.setText(AwsPrefs.getRegion(ctx))
        binding.edtAccessKey.setText(AwsPrefs.getAccessKey(ctx))
        binding.edtSecretKey.setText(AwsPrefs.getSecretKey(ctx))
    }

    private fun saveCredentials() {
        val region = binding.edtRegion.text.toString().trim()
        val accessKey = binding.edtAccessKey.text.toString().trim()
        val secretKey = binding.edtSecretKey.text.toString().trim()

        if (region.isBlank()) {
            showToast(getString(R.string.msg_region_required))
            return
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            showToast(getString(R.string.msg_keys_required))
            return
        }

        AwsPrefs.save(requireContext(), region, accessKey, secretKey)
        showToast(getString(R.string.msg_credentials_saved))
        updateStatus()
    }

    private fun updateStatus() {
        val configured = AwsPrefs.isConfigured(requireContext())
        val region = AwsPrefs.getRegion(requireContext())
        val accessKey = AwsPrefs.getAccessKey(requireContext())

        if (configured) {
            val masked = if (accessKey.length > 4) {
                "${"*".repeat(accessKey.length - 4)}${accessKey.takeLast(4)}"
            } else {
                "****"
            }
            binding.txtStatus.text = getString(R.string.msg_credentials_configured, region, masked)
        } else {
            binding.txtStatus.text = getString(R.string.msg_credentials_not_configured)
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
