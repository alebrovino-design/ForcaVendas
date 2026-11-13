package com.example.forcavendas

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.findNavController
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.example.forcavendas.databinding.FragmentFirstBinding
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * A simple [Fragment] subclass as the default destination in the navigation.
 */
class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val preferences = requireContext().getSharedPreferences(PREFERENCES_NAME, 0)
        val isLoggedIn = preferences.getBoolean("logged_in", false)
        val cachedUserId = preferences.getString("user_id", null)
        if (isLoggedIn && !cachedUserId.isNullOrBlank()) {
            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
            return
        }

        binding.buttonFirst.setOnClickListener {
            val username = binding.editUsername.text?.toString()?.trim().orEmpty()
            val password = binding.editPassword.text?.toString().orEmpty()
            val license = binding.editLicense.text?.toString()?.trim().orEmpty()

            var isValid = true
            binding.usernameLayout.error = null
            binding.passwordLayout.error = null
            binding.licenseLayout.error = null

            if (username.isEmpty()) {
                binding.usernameLayout.error = getString(R.string.login_required_username)
                isValid = false
            }
            if (password.isEmpty()) {
                binding.passwordLayout.error = getString(R.string.login_required_password)
                isValid = false
            }
            if (license.isEmpty()) {
                binding.licenseLayout.error = getString(R.string.login_required_license)
                isValid = false
            }
            if (!isValid) return@setOnClickListener

            setLoading(true)
            Thread {
                try {
                    val result = AuthService.authenticate(username, password)
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        if (result.success) {
                            cacheAuthenticatedUser(username, password, license, result.userId)
                            SessionManager.bindCurrentSession(requireContext())
                            binding.editPassword.text?.clear()
                            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
                        } else {
                            setLoading(false)
                            binding.passwordLayout.error = result.message
                                .ifBlank { getString(R.string.login_invalid_credentials) }
                        }
                    }
                } catch (_: IOException) {
                    val offlineAuthorized = authenticateOffline(username, password, license)
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        setLoading(false)
                        if (offlineAuthorized) {
                            SessionManager.bindCurrentSession(requireContext())
                            binding.editPassword.text?.clear()
                            findNavController().navigate(R.id.action_FirstFragment_to_SecondFragment)
                        } else {
                            binding.passwordLayout.error =
                                getString(R.string.login_offline_not_authorized)
                        }
                    }
                } catch (_: Exception) {
                    Handler(Looper.getMainLooper()).post {
                        if (!isAdded) return@post
                        setLoading(false)
                        binding.passwordLayout.error = getString(R.string.login_connection_error)
                    }
                }
            }.start()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.buttonFirst.isEnabled = !loading
        binding.buttonFirst.text = getString(
            if (loading) R.string.login_loading else R.string.login_button
        )
        binding.usernameLayout.isEnabled = !loading
        binding.passwordLayout.isEnabled = !loading
        binding.licenseLayout.isEnabled = !loading
    }

    private fun cacheAuthenticatedUser(
        username: String,
        password: String,
        license: String,
        userId: String?
    ) {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val preferences = requireContext().getSharedPreferences(PREFERENCES_NAME, 0)
        preferences.edit()
            .putString("username", username)
            .putString("license", license)
            .putString("user_id", userId)
            .putBoolean("logged_in", true)
            .putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("password_hash", hashPassword(password, salt))
            .apply()
    }

    private fun authenticateOffline(username: String, password: String, license: String): Boolean {
        val preferences = requireContext().getSharedPreferences(PREFERENCES_NAME, 0)
        val cachedUsername = preferences.getString("username", null) ?: return false
        val cachedLicense = preferences.getString("license", null) ?: return false
        val saltEncoded = preferences.getString("password_salt", null) ?: return false
        val expectedHash = preferences.getString("password_hash", null) ?: return false
        val salt = Base64.decode(saltEncoded, Base64.NO_WRAP)

        return cachedUsername == username &&
            cachedLicense == license &&
            MessageDigest.isEqual(
                Base64.decode(expectedHash, Base64.NO_WRAP),
                Base64.decode(hashPassword(password, salt), Base64.NO_WRAP)
            )
    }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        return Base64.encodeToString(digest.digest(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val PREFERENCES_NAME = "forca_vendas"
    }
}