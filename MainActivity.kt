package com.screenai

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var backendField: EditText
    private lateinit var tokenField: EditText
    private lateinit var statusText: TextView
    private val prefs by lazy { getSharedPreferences("screen_ai", Context.MODE_PRIVATE) }

    companion object {
        private const val REQUEST_CAPTURE = 401
        private const val REQUEST_NOTIFICATIONS = 402
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(24), dp(22), dp(24))
            setBackgroundColor(0xFF10111A.toInt())
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val logo = TextView(this).apply {
            text = "◉  SCREEN AI"
            textSize = 27f
            setTextColor(0xFFF7F5FF.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        content.addView(logo)
        content.addView(text("Ask about anything on your screen—without switching apps.", 15f, 0xFFB9B8C8.toInt()).also { it.setPadding(0, dp(10), 0, dp(18)) })
        content.addView(section("1  ·  Connect your AI backend"))
        content.addView(text("Run the included backend and paste its public HTTPS URL here. For local Android Emulator testing, use http://10.0.2.2:3000. A physical phone needs a reachable server URL.", 13f, 0xFFB9B8C8.toInt()).also { it.setPadding(0, 0, 0, dp(10)) })
        backendField = EditText(this).apply {
            hint = "https://your-screen-ai-server.example"
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFFF7F5FF.toInt())
            setHintTextColor(0xFF79798D.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF202130.toInt())
            setText(prefs.getString("backend_url", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        content.addView(backendField, matchWrap())
        tokenField = EditText(this).apply {
            hint = "Optional shared backend token"
            setSingleLine(true)
            textSize = 14f
            setTextColor(0xFFF7F5FF.toInt())
            setHintTextColor(0xFF79798D.toInt())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(0xFF202130.toInt())
            setText(prefs.getString("shared_token", "") ?: "")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }.also { it.setMarginsTop(dp(8)) }
        content.addView(tokenField, matchWrap())
        content.addView(actionButton("Save backend settings") { saveBackend() }.also { it.setMarginsTop(dp(10)) }, matchWrap())

        content.addView(section("2  ·  Give Android permissions" ).also { it.setMarginsTop(dp(20)) })
        content.addView(text("Screen AI must be allowed to capture the screen and display its floating answer panel. Android will always show its own screen-capture consent controls.", 13f, 0xFFB9B8C8.toInt()).also { it.setPadding(0, 0, 0, dp(10)) })
        content.addView(actionButton("Grant display-over-other-apps permission") {
            if (Settings.canDrawOverlays(this)) toast("Overlay permission is already enabled")
            else startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }, matchWrap())
        content.addView(actionButton("Start / authorize screen capture") {
            saveBackend()
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }.also { it.setMarginsTop(dp(8)) }, matchWrap())

        content.addView(section("3  ·  Use it over other apps").also { it.setMarginsTop(dp(20)) })
        content.addView(text("After capture permission is granted, launch the bubble below, then open your question in another app. Tap the Screen AI bubble to analyze the visible screen. On Android's capture chooser, select the entire screen if you want to use it across apps.", 13f, 0xFFB9B8C8.toInt()).also { it.setPadding(0, 0, 0, dp(10)) })
        content.addView(actionButton("Show floating Screen AI bubble") {
            saveBackend()
            if (!Settings.canDrawOverlays(this)) {
                toast("First grant display-over-other-apps permission")
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else if (!ScreenCaptureService.isRunning()) {
                toast("Authorize screen capture first")
            } else {
                startService(Intent(this, OverlayService::class.java))
                toast("Screen AI bubble is ready. Open another app.")
            }
        }, matchWrap())
        content.addView(actionButton("Stop Screen AI") {
            stopService(Intent(this, OverlayService::class.java))
            stopService(Intent(this, ScreenCaptureService::class.java))
            statusText.text = "Stopped. Re-authorize screen capture to use it again."
            toast("Screen AI stopped")
        }.also { it.setMarginsTop(dp(8)) }, matchWrap())

        statusText = text("Status: ${if (ScreenCaptureService.isRunning()) "screen capture active" else "screen capture not started"}", 13f, 0xFFB9B8C8.toInt()).also { it.setPadding(0, dp(16), 0, dp(6)) }
        content.addView(statusText)
        content.addView(text("Privacy: the visible screenshot is sent to the backend you configure for AI analysis. Avoid capturing passwords, private chats, payment details, or other sensitive screens.", 12f, 0xFF9B9AAF.toInt()).also { it.setPadding(0, dp(8), 0, dp(12)) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized) {
            statusText.text = "Status: ${if (ScreenCaptureService.isRunning()) "screen capture active" else "screen capture not started"} · ${if (Settings.canDrawOverlays(this)) "overlay allowed" else "overlay permission needed"}"
        }
    }

    @Deprecated("Android activity result API retained for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                val intent = Intent(this, ScreenCaptureService::class.java)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                    .putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
                statusText.text = "Status: starting screen capture. Grant overlay permission, then show the bubble."
                toast("Screen capture authorized")
            } else {
                toast("Screen capture permission was not granted")
            }
        }
    }

    private fun saveBackend() {
        val url = backendField.text.toString().trim().trimEnd('/')
        val token = tokenField.text.toString().trim()
        prefs.edit().putString("backend_url", url).putString("shared_token", token).apply()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(backendField.windowToken, 0)
        toast("Backend settings saved")
    }

    private fun section(value: String) = TextView(this).apply {
        text = value
        textSize = 17f
        setTextColor(0xFFE4DEFF.toInt())
        setTypeface(null, android.graphics.Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(10))
    }

    private fun text(value: String, size: Float, color: Int) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setLineSpacing(dp(2).toFloat(), 1.02f)
    }

    private fun actionButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setTextColor(0xFFFFFFFF.toInt())
        setBackgroundColor(0xFF6653D9.toInt())
        setOnClickListener { action() }
        minimumHeight = dp(48)
    }

    private fun matchWrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    private fun View.setMarginsTop(value: Int) { (layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = value }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
