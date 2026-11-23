package com.example.forcavendas

import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.appcompat.app.AppCompatDelegate
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import java.util.Calendar
import androidx.navigation.fragment.findNavController
import com.example.forcavendas.databinding.FragmentSecondBinding
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.concurrent.thread
import java.text.NumberFormat
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.card.MaterialCardView
import android.widget.ImageView
import android.widget.TextView

/**
 * A simple [Fragment] subclass as the second destination in the navigation.
 */
class SecondFragment : Fragment() {

    private var _binding: FragmentSecondBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!
    private fun avatarPreferenceKey(userId: String?, license: String): String =
        "avatar_uri_${userId.orEmpty()}_${license}"

    private val avatarPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && isAdded) {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
            val userId = preferences.getString("user_id", null)
            val license = preferences.getString("license", "") ?: ""
            preferences.edit()
                .putString(avatarPreferenceKey(userId, license), uri.toString())
                .apply()
            showAvatar(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentSecondBinding.inflate(inflater, container, false)
        return binding.root

    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val preferences = requireContext().getSharedPreferences("forca_vendas", 0)
        var darkMenu = preferences.getBoolean("dashboard_dark_mode", false)
        applyMenuAppearance(darkMenu)
        binding.menuThemeButton.setOnClickListener {
            darkMenu = !darkMenu
            preferences.edit().putBoolean("dashboard_dark_mode", darkMenu).apply()
            applyMenuAppearance(darkMenu)
            AppCompatDelegate.setDefaultNightMode(
                if (darkMenu) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
        }
        val username = preferences.getString("username", "Usuário")
        val userId = preferences.getString("user_id", null)
        val license = preferences.getString("license", "") ?: ""

        AutoSyncManager.syncOncePerDay(requireContext())

        binding.menuUserName.text = username
        binding.menuUserDetails.text = buildString {
            append("Licença: ").append(license)
            if (!userId.isNullOrBlank()) append("  •  ID: ").append(userId)
            append("  •  ").append(getString(R.string.app_version, BuildConfig.VERSION_NAME))
        }
        binding.menuAvatarInitials.text = username
            ?.trim()
            ?.split("\\s+".toRegex())
            ?.take(2)
            ?.mapNotNull { part -> part.firstOrNull()?.uppercaseChar()?.toString() }
            ?.joinToString("")
            ?: "UV"

        var savedAvatar = preferences.getString(avatarPreferenceKey(userId, license), null)
        if (savedAvatar.isNullOrBlank()) {
            val legacyAvatar = preferences.getString("avatar_uri", null)
            if (!legacyAvatar.isNullOrBlank()) {
                savedAvatar = legacyAvatar
                preferences.edit()
                    .putString(avatarPreferenceKey(userId, license), legacyAvatar)
                    .remove("avatar_uri")
                    .apply()
            }
        }
        if (!savedAvatar.isNullOrBlank()) {
            showAvatar(Uri.parse(savedAvatar))
        } else {
            binding.menuAvatarPhoto.visibility = View.GONE
            binding.menuAvatarInitials.visibility = View.VISIBLE
        }

        binding.menuAvatarCard.setOnClickListener {
            avatarPicker.launch("image/*")
        }

        binding.menuExitButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Encerrar sessão")
                .setMessage("Deseja sair da conta ou finalizar o aplicativo?")
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Deslogar") { _, _ ->
                    SessionManager.logout(requireContext())
                    ProductLocalRepository(requireContext()).replaceAll(emptyList())
                    findNavController().popBackStack(R.id.FirstFragment, false)
                }
                .setPositiveButton("Fechar app") { _, _ ->
                    requireActivity().finishAffinity()
                }
                .show()
        }

        val calendar = Calendar.getInstance()
        val elapsedDays = calendar.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
        val month = SimpleDateFormat("yyyyMM", Locale.US).format(calendar.time)

        if (userId.isNullOrBlank()) {
            binding.achievedValue.text = "Indisponível"
            binding.projectionValue.text = "Indisponível"
            binding.projectionStatus.text = "Código do representante não disponível."
        } else {
            binding.achievedValue.text = "Consultando..."
            binding.projectionValue.text = "Consultando..."
            binding.projectionStatus.text = "Consultando dados da meta..."
            binding.targetValue.text = "Consultando..."

            TargetService.loadCached(requireContext(), userId, month)?.let { cached ->
                showTarget(cached, elapsedDays, daysInMonth, isLocal = true)
            }

            thread {
                try {
                    val targetData = TargetService.load(userId, month)
                    TargetService.save(requireContext(), userId, month, targetData)
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        showTarget(targetData, elapsedDays, daysInMonth, isLocal = false)
                    }
                } catch (_: Exception) {
                    activity?.runOnUiThread {
                        if (!isAdded) return@runOnUiThread
                        val cached = TargetService.loadCached(requireContext(), userId, month)
                        if (cached == null) {
                            binding.achievedValue.text = "Indisponível"
                            binding.projectionValue.text = "Indisponível"
                            binding.achievedProgress.progress = 0
                            binding.projectionProgress.progress = 0
                            binding.projectionStatus.text = "Não foi possível consultar os dados da meta."
                        } else {
                            showTarget(cached, elapsedDays, daysInMonth, isLocal = true)
                        }
                    }
                }
            }
        }

        binding.menuOrders.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_OrdersFragment)
        }
        binding.menuProducts.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_ProductsFragment)
        }
        binding.menuClients.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_ClientsFragment)
        }
        binding.menuLoads.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_LoadsFragment)
        }
        binding.menuSync.setOnClickListener {
            findNavController().navigate(R.id.action_SecondFragment_to_SyncFragment)
        }
    }

    private fun applyMenuAppearance(dark: Boolean) {
        fun color(light: Int, darkColor: Int): Int =
            resources.getColor(if (dark) darkColor else light, requireContext().theme)

        val background = color(R.color.dashboard_background_light, R.color.dashboard_background_dark)
        val surface = color(R.color.dashboard_surface_light, R.color.dashboard_surface_dark)
        val primary = color(R.color.dashboard_text_light, R.color.dashboard_text_dark)
        val secondary = color(R.color.dashboard_secondary_light, R.color.dashboard_secondary_dark)
        val accent = color(R.color.dashboard_accent_light, R.color.dashboard_accent_dark)
        val stroke = color(R.color.dashboard_stroke_light, R.color.dashboard_stroke_dark)

        binding.root.setBackgroundColor(background)
        binding.menuThemeButton.text = if (dark) "Claro" else "Escuro"
        binding.menuThemeButton.contentDescription =
            if (dark) "Ativar aparência clara do aplicativo" else "Ativar aparência escura do aplicativo"

        fun applyTo(view: View) {
            when (view.tag) {
                "dashboard_surface" -> (view as MaterialCardView).apply {
                    setCardBackgroundColor(surface)
                    strokeColor = stroke
                }
                "dashboard_primary" -> (view as TextView).setTextColor(primary)
                "dashboard_secondary" -> (view as TextView).setTextColor(secondary)
                "dashboard_accent" -> when (view) {
                    is TextView -> view.setTextColor(accent)
                    is ImageView -> view.imageTintList = android.content.res.ColorStateList.valueOf(accent)
                }
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) applyTo(view.getChildAt(index))
            }
        }
        applyTo(binding.root)
    }

    private fun showAvatar(uri: Uri) {
        try {
            binding.menuAvatarPhoto.setImageURI(uri)
            binding.menuAvatarPhoto.visibility = View.VISIBLE
            binding.menuAvatarInitials.visibility = View.GONE
        } catch (_: Exception) {
            binding.menuAvatarPhoto.visibility = View.GONE
            binding.menuAvatarInitials.visibility = View.VISIBLE
        }
    }

    private fun openPlaceholder(title: String) {
        val arguments = Bundle().apply { putString("screen_title", title) }
        findNavController().navigate(R.id.action_SecondFragment_to_PlaceholderFragment, arguments)
    }

    private fun showTarget(
        targetData: TargetService.TargetData,
        elapsedDays: Int,
        daysInMonth: Int,
        isLocal: Boolean
    ) {
        val achievedPercent = if (targetData.target > 0.0) {
            kotlin.math.round(targetData.achieved * 100.0 / targetData.target).toInt()
        } else {
            0
        }
        val projectedPercent = if (elapsedDays > 0) {
            kotlin.math.round(achievedPercent.toDouble() * daysInMonth / elapsedDays).toInt()
        } else {
            achievedPercent
        }
        val formatter = NumberFormat.getInstance(Locale("pt", "BR")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 3
            isGroupingUsed = true
        }

        binding.targetProgress.progress = 100
        binding.targetValue.text = "${formatter.format(targetData.target)} kg"
        binding.achievedProgress.progress = achievedPercent.coerceAtMost(100)
        binding.achievedValue.text =
            "$achievedPercent%  •  ${formatter.format(targetData.achieved)} kg"
        binding.projectionProgress.progress = projectedPercent.coerceAtMost(100)
        binding.projectionValue.text = "$projectedPercent%"

        if (projectedPercent >= 100) {
            binding.projectionProgress.progressTintList =
                android.content.res.ColorStateList.valueOf(
                    resources.getColor(R.color.achieved_blue, requireContext().theme)
                )
            binding.projectionStatus.text =
                "Previsão positiva: o ritmo atual pode atingir ou superar a meta." +
                    if (isLocal) " Dados sincronizados localmente." else ""
        } else {
            binding.projectionProgress.progressTintList =
                android.content.res.ColorStateList.valueOf(0xFFE53935.toInt())
            binding.projectionStatus.text =
                "Previsão de não atingir a meta pelo ritmo atual (${elapsedDays}/${daysInMonth} dias do mês)." +
                    if (isLocal) " Dados sincronizados localmente." else ""
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}