package com.example.screengrab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Setup screen: collects the three permissions, then starts [CaptureService]. */
class MainActivity : ComponentActivity() {

    private var askedNotifications = false

    // Step 3: system dialog "Start recording or casting?"
    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, CaptureService::class.java)
                    .setAction(CaptureService.ACTION_START)
                    .putExtra(CaptureService.EXTRA_RESULT_CODE, res.resultCode)
                    .putExtra(CaptureService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, intent)
                moveTaskToBack(true) // get out of the way so the user can open another app
            } else {
                Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show()
            }
        }

    // Step 2: notification permission (Android 13+)
    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { proceed() }

    // Step 1: "Display over other apps" special-access screen
    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Settings.canDrawOverlays(this)) proceed()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.btnStart).setOnClickListener { proceed() }
        findViewById<Button>(R.id.btnStop).setOnClickListener {
            stopService(Intent(this, CaptureService::class.java))
            updateStatus(false)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus(CaptureService.running)
    }

    private fun updateStatus(on: Boolean) {
        findViewById<TextView>(R.id.status).setText(if (on) R.string.status_on else R.string.status_off)
    }

    /** Walks through the permission chain; each step re-enters here when it completes. */
    private fun proceed() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.perm_overlay, Toast.LENGTH_LONG).show()
            overlayLauncher.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && !askedNotifications &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
