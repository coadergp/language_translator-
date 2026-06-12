package com.eartranslator

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.eartranslator.databinding.ActivityLicensesBinding

/** Displays the Terms of Service (from res/raw/terms.txt). Reuses the text-viewer layout. */
class TermsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityLicensesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            title = getString(R.string.terms_title)
            setDisplayHomeAsUpEnabled(true)
        }

        binding.txtLicenses.text = try {
            resources.openRawResource(R.raw.terms).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            getString(R.string.terms_load_error)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
