package com.eartranslator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eartranslator.databinding.ActivityLicensesBinding

/**
 * Displays open-source license notices and model attributions (from res/raw/licenses.txt).
 *
 * This screen is what satisfies the CC-BY 4.0 attribution requirement for the Helsinki-NLP
 * opus-mt translation models, and surfaces the MIT/Apache notices for everything else.
 */
class LicensesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.licenses_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.txtLicenses.text = readLicenses()
    }

    private fun readLicenses(): String = try {
        resources.openRawResource(R.raw.licenses).bufferedReader().use { it.readText() }
    } catch (e: Exception) {
        getString(R.string.licenses_load_error)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
