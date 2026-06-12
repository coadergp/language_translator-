package com.eartranslator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eartranslator.databinding.ActivityLicensesBinding

/**
 * Displays the privacy policy (from res/raw/privacy_policy.txt) inside the app, so users
 * can read it without leaving for a browser. Reuses the licenses screen layout.
 *
 * Note: Google Play still requires a publicly hosted privacy-policy URL in the Console;
 * this in-app screen is a convenience/transparency addition, not a replacement.
 */
class PrivacyActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.privacy_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.txtLicenses.text = try {
            resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            getString(R.string.privacy_load_error)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
