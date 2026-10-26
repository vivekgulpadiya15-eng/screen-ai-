package com.screenai

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var bubble: TextView? = null
    private var panel: LinearLayout? = null
    private var status: TextView? = null
    private var answer: TextView? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val solveId = AtomicLong(0)
    private var panelVisible = false

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    private fun createBubble() {
        if (bubble != null) return
        bubble = TextView(this).apply {
            text = "AI"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = rounded(0xFF6D5BEA.toInt(), dp(28).toFloat(), 0xFFBEB4FF.toInt())
            elevation = dp(8).toFloat()
        }
        val p = WindowManager.LayoutParams(dp(58), dp(58), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(12)
            y = dp(230)
        }
        bubble?.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var downX = 0f
            private var downY = 0f
            private var moved = false
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { startX = p.x; startY = p.y; downX = event.rawX; downY = event.rawY; moved = false; return true }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(5)) moved = true
                        p.x = (startX - dx).coerceAtLeast(0)
                        p.y = (startY + dy).coerceAtLeast(0)
                        try { wm.updateViewLayout(bubble, p) } catch (_: Exception) {}
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) { solveVisibleScreen() }
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(bubble, p)
    }

    private fun buildPanel(): LinearLayout {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(0xFF171825.toInt(), dp(20).toFloat(), 0xFF514A80.toInt())
            elevation = dp(12).toFloat()
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "SCREEN AI"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFFE9E5FF.toInt())
        }, LinearLayout.LayoutParams(0, dp(34), 1f))
        header.addView(smallButton("×") { hidePanel() })
        outer.addView(header)
        status = TextView(this).apply {
            text = "Tap solve to analyze the visible screen."
            textSize = 12f
            setTextColor(0xFFB9B8C8.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        outer.addView(status)
        val scroller = ScrollView(this)
        answer = TextView(this).apply {
            text = "Your answer will appear here."
            textSize = 14f
            setTextColor(0xFFF2F1FA.toInt())
            setLineSpacing(dp(3).toFloat(), 1.02f)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xFF202130.toInt(), dp(12).toFloat(), 0xFF333349.toInt())
        }
        scroller.addView(answer, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        outer.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(270), 1f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        row.addView(smallButton("Solve again") { solveVisibleScreen() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(4) })
        row.addView(smallButton("Next screen") { hidePanel() }, LinearLayout.LayoutParams(0, dp(42), 1f).apply { leftMargin = dp(4) })
        outer.addView(row)
        outer.addView(TextView(this).apply {
            text = "Next screen hides this panel. Navigate to the next question, then tap the AI bubble again."
            textSize = 11f
            setTextColor(0xFF9291A5.toInt())
            setPadding(0, dp(6), 0, 0)
        })
        return outer
    }

    private fun showPanel() {
        if (panelVisible) return
        if (panel == null) panel = buildPanel()
        val displayW = resources.displayMetrics.widthPixels
        val width = minOf(dp(380), (displayW * 0.92f).toInt())
        panelParams = WindowManager.LayoutParams(width, dp(440), overlayType(), WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(90)
        }
        try { wm.addView(panel, panelParams); panelVisible = true } catch (_: Exception) {}
    }

    private fun hidePanel() {
        if (panelVisible) {
            try { wm.removeView(panel) } catch (_: Exception) {}
            panelVisible = false
        }
    }

    private fun solveVisibleScreen() {
        val id = solveId.incrementAndGet()
        val backend = getSharedPreferences("screen_ai", Context.MODE_PRIVATE).getString("backend_url", "")?.trimEnd('/') ?: ""
        val token = getSharedPreferences("screen_ai", Context.MODE_PRIVATE).getString("shared_token", "") ?: ""
        if (backend.isBlank()) {
            showPanel()
            update("Backend URL missing", "Open Screen AI and save your backend URL first. The OpenAI API key belongs on the backend, not in the APK.")
            return
        }
        // Remove Screen AI's own UI briefly so it does not cover the question in the screenshot.
        hidePanel()
        bubble?.visibility = View.INVISIBLE
        mainHandler.postDelayed({
            if (solveId.get() != id) return@postDelayed
            val accepted = ScreenCaptureService.requestCapture { imageBytes ->
                if (imageBytes == null) {
                    mainHandler.post {
                        if (solveId.get() == id) {
                            bubble?.visibility = View.VISIBLE
                            showPanel()
                            update("Capture failed", "Screen capture is unavailable. Re-authorize capture in Screen AI and try again.")
                        }
                    }
                } else {
                    mainHandler.post {
                        if (solveId.get() == id) {
                            bubble?.visibility = View.VISIBLE
                            showPanel()
                            update("Thinking…", "Sending this screenshot to your configured AI backend.")
                        }
                    }
                    Thread {
                        try {
                            val connection = (URL(backend + "/solve").openConnection() as HttpURLConnection).apply {
                                requestMethod = "POST"
                                connectTimeout = 15000
                                readTimeout = 90000
                                doOutput = true
                                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                                setRequestProperty("Accept", "application/json")
                                if (token.isNotBlank()) setRequestProperty("X-Screen-AI-Token", token)
                            }
                            val body = JSONObject()
                                .put("imageBase64", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
                                .put("mimeType", "image/jpeg")
                                .put("prompt", "Read the visible question from this screenshot. Explain the key steps briefly and provide the final answer clearly. If several questions are visible, answer the most prominent one. If the screen is not a question, describe what is visible and ask for clarification.")
                            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                            val code = connection.responseCode
                            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                            val responseText = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { it.readText() }
                            connection.disconnect()
                            if (code !in 200..299) throw IllegalStateException("Server returned HTTP $code: ${responseText.take(500)}")
                            val result = JSONObject(responseText)
                            val answerText = result.optString("answer", "No answer was returned.")
                            mainHandler.post { if (solveId.get() == id) update("Answer", answerText) }
                        } catch (e: Exception) {
                            val msg = when {
                                e.message?.contains("Unable to resolve host", true) == true -> "Server not found. Check the backend URL and that the server is reachable."
                                e.message?.contains("timed out", true) == true -> "The request timed out. Check your connection and try again."
                                else -> "Could not get an answer: ${e.message ?: "network error"}. Check backend logs and settings."
                            }
                            mainHandler.post { if (solveId.get() == id) update("Request failed", msg) }
                        }
                    }.start()
                }
            }
            if (!accepted) {
                bubble?.visibility = View.VISIBLE
                showPanel()
                update("Screen capture is off", "Return to Screen AI and tap Start / authorize screen capture first.")
            }
        }, 180)
    }

    private fun update(title: String, body: String) {
        status?.text = title
        answer?.text = body
    }

    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        setTextColor(Color.WHITE)
        setBackgroundColor(0xFF6653D9.toInt())
        minimumHeight = dp(40)
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Float, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(dp(1), stroke)
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hidePanel()
        bubble?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        bubble = null
        panel = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
