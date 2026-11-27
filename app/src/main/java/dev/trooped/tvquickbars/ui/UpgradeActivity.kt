package dev.trooped.tvquickbars.ui

import android.os.Bundle
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import dev.trooped.tvquickbars.R

/**
 * UpgradeActivity displays QuickBars Plus status on community/sideloaded TV builds.
 */
class UpgradeActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upgrade)

        findViewById<ImageButton>(R.id.btn_close).setOnClickListener { finish() }

        val priceText: TextView = findViewById(R.id.price_text)
        val purchaseButton: Button = findViewById(R.id.btn_purchase)
        val restoreButton: TextView = findViewById(R.id.btn_restore)

        priceText.text = "Plus Status Unlocked"
        purchaseButton.isEnabled = false
        purchaseButton.text = "Active"
        restoreButton.setOnClickListener {
            Toast.makeText(this, "Plus features are already active.", Toast.LENGTH_SHORT).show()
        }
    }
}
