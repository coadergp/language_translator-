package com.eartranslator

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.eartranslator.bluetooth.BluetoothAudioManager
import com.eartranslator.bluetooth.BluetoothAudioManager.Slot
import com.eartranslator.config.Language
import com.eartranslator.databinding.ActivityMainBinding
import com.eartranslator.nlp.ModelDownloader
import com.eartranslator.nlp.ModelManifest
import com.eartranslator.service.TranslatorService
import kotlinx.coroutines.launch

/**
 * UI: pick a language and a BT earbud for each of the two people, then start/stop the
 * translation service. Shows a live status line.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        /**
         * Base URL for first-run model download (e.g. "https://cdn.example.com/eartranslator").
         * Leave BLANK to ship models in the APK / via Play Asset Delivery (no INTERNET
         * permission needed). If you set this, you MUST add the INTERNET permission and
         * update the privacy docs — see [ModelDownloader].
         */
        private const val MODEL_BASE_URL = ""
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var btManager: BluetoothAudioManager

    private var devices: List<BluetoothDevice> = emptyList()
    private var running = false

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.MODIFY_AUDIO_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            setupBluetooth()
        } else {
            setStatus("Permissions denied — cannot run")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguageSpinners()
        binding.btnStart.setOnClickListener { startTranslation() }
        binding.btnStop.setOnClickListener { stopTranslation() }
        binding.btnRefresh.setOnClickListener { refreshDevices() }
        binding.btnLicenses.setOnClickListener {
            startActivity(Intent(this, LicensesActivity::class.java))
        }
        binding.btnPrivacy.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        binding.btnStop.isEnabled = false

        if (hasAllPermissions()) setupBluetooth()
        else showDisclosureThenRequest()
    }

    /**
     * Prominent disclosure (Google Play User Data / Permissions policy): before requesting
     * the microphone we must clearly tell the user what is accessed, why, and that it
     * stays on the device. We only proceed to the system permission prompt after the user
     * acknowledges. Audio is processed in-memory for live translation and never recorded,
     * stored, or transmitted.
     */
    private fun showDisclosureThenRequest() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.disclosure_title)
            .setMessage(R.string.disclosure_body)
            .setCancelable(false)
            .setPositiveButton(R.string.disclosure_agree) { _, _ ->
                permissionLauncher.launch(requiredPermissions)
            }
            .setNegativeButton(R.string.disclosure_decline) { _, _ ->
                setStatus("Microphone access is required to translate")
            }
            .show()
    }

    private fun hasAllPermissions(): Boolean =
        requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun setupLanguageSpinners() {
        val names = Language.displayNames()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerLangA.adapter = adapter
        binding.spinnerLangB.adapter = adapter
        // Sensible defaults: A = English, B = Spanish.
        binding.spinnerLangA.setSelection(Language.ENGLISH.ordinal)
        binding.spinnerLangB.setSelection(Language.SPANISH.ordinal)
    }

    private fun setupBluetooth() {
        btManager = BluetoothAudioManager(this)
        btManager.openProfiles { runOnUiThread { refreshDevices() } }
        setStatus("Ready")
    }

    private fun refreshDevices() {
        if (!::btManager.isInitialized) return
        devices = btManager.connectedDevices()
        val labels = if (devices.isEmpty()) listOf("(no BT devices connected)")
                     else devices.map { btManager.deviceLabel(it) }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerDeviceA.adapter = adapter
        binding.spinnerDeviceB.adapter = adapter
        if (devices.size >= 2) binding.spinnerDeviceB.setSelection(1)
        setStatus("Found ${devices.size} BT device(s)")
    }

    private fun startTranslation() {
        if (running) return
        if (devices.isEmpty()) {
            setStatus("Connect two BT earbuds first")
            return
        }

        // Assign devices to slots.
        val idxA = binding.spinnerDeviceA.selectedItemPosition.coerceIn(0, devices.size - 1)
        val idxB = binding.spinnerDeviceB.selectedItemPosition.coerceIn(0, devices.size - 1)
        btManager.assignSlot(Slot.PERSON_A, devices[idxA])
        btManager.assignSlot(Slot.PERSON_B, devices[idxB])

        val langA = Language.byDisplay(binding.spinnerLangA.selectedItem as String)
        val langB = Language.byDisplay(binding.spinnerLangB.selectedItem as String)

        // Ensure the models for this language pair are present (downloaded or bundled).
        val missing = ModelManifest.missing(this, listOf(langA, langB))
        if (missing.isEmpty()) {
            launchService(langA, langB)
            return
        }

        val downloader = ModelDownloader(this, MODEL_BASE_URL)
        if (!downloader.isConfigured()) {
            // No download source set → models must be bundled / installed manually.
            setStatus("${missing.size} model files missing for ${langA.display}/${langB.display} — install the model pack (see README)")
            return
        }

        // First-run download, then start.
        binding.btnStart.isEnabled = false
        lifecycleScope.launch {
            val paths = ModelManifest.requiredPaths(listOf(langA, langB))
            val failed = downloader.download(paths) { p ->
                runOnUiThread { setStatus("Downloading models ${p.completed}/${p.total}…") }
            }
            binding.btnStart.isEnabled = true
            if (failed.isEmpty()) launchService(langA, langB)
            else setStatus("Model download failed (${failed.size} files) — check connection")
        }
    }

    private fun launchService(langA: Language, langB: Language) {
        val intent = Intent(this, TranslatorService::class.java).apply {
            putExtra(TranslatorService.EXTRA_LANG_A, langA.code)
            putExtra(TranslatorService.EXTRA_LANG_B, langB.code)
        }
        ContextCompat.startForegroundService(this, intent)

        running = true
        binding.btnStart.isEnabled = false
        binding.btnStop.isEnabled = true
        setStatus("Translating ${langA.display} ⇄ ${langB.display}")
    }

    private fun stopTranslation() {
        if (!running) return
        stopService(Intent(this, TranslatorService::class.java))
        running = false
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled = false
        setStatus("Stopped")
    }

    private fun setStatus(text: String) {
        binding.txtStatus.text = getString(R.string.status_fmt, text)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::btManager.isInitialized) btManager.closeProfiles()
    }
}
