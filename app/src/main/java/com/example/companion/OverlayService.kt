package com.example.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var root: LinearLayout
    private lateinit var panel: LinearLayout
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var params: WindowManager.LayoutParams
    private val history = JSONArray()
    private val transcript = StringBuilder()
    private val ui = Handler(Looper.getMainLooper())
    private var savedY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ov", "Companion", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "ov")
            .setContentTitle("Companion is running")
            .setSmallIcon(R.drawable.ic_character)
            .build()
        startForeground(1, n)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        buildOverlay()
    }

    private fun buildOverlay() {
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val character = ImageView(this).apply {
            setImageResource(R.drawable.ic_character)
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
        }
        log = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 14f
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        scroll = ScrollView(this).apply {
            addView(log)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(220))
        }
        input = EditText(this).apply {
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            hint = "Message..."; maxLines = 3
        }
        val send = Button(this).apply { text = "Send"; setOnClickListener { sendMessage() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(0xEE14141C.toInt()); cornerRadius = dp(16).toFloat()
                setStroke(dp(2), 0xFF7B2FFF.toInt())
            }
            addView(scroll); addView(row)
            layoutParams = LinearLayout.LayoutParams(dp(300), WRAP_CONTENT)
        }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(character); addView(panel)
        }

        params = WindowManager.LayoutParams(
            WRAP_CONTENT, WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16); y = dp(300)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        character.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = params.x; oy = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 12 || abs(dy) > 12) moved = true
                    if (moved) {
                        params.x = ox + dx.toInt(); params.y = oy + dy.toInt()
                        wm.updateViewLayout(root, params)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            true
        }
        wm.addView(root, params)
    }

    private fun togglePanel() {
        val open = panel.visibility != View.VISIBLE
        if (open) {
            panel.visibility = View.VISIBLE
            savedY = params.y
            params.y = (40 * resources.displayMetrics.density).toInt()
            params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            wm.updateViewLayout(root, params)
            input.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            input.post { imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
            panel.visibility = View.GONE
            params.y = savedY
            params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            wm.updateViewLayout(root, params)
        }
    }

    private fun render(pending: Boolean = false) {
        log.text = transcript.toString() + if (pending) "\nNeon: ..." else ""
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendMessage() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        input.setText("")
        val key = getSharedPreferences("prefs", MODE_PRIVATE).getString("api_key", "") ?: ""
        if (key.isEmpty()) {
            transcript.append("\n[Set your API key in the app first]"); render(); return
        }
        transcript.append("\nYou: ").append(text)
        history.put(JSONObject().put("role", "user").put("content", text))
        while (history.length() > 20) { history.remove(0); history.remove(0) }
        render(pending = true)
        val snapshot = JSONArray(history.toString())
        Thread {
            val result = ClaudeClient.chat(key, snapshot)
            ui.post {
                result.onSuccess {
                    history.put(JSONObject().put("role", "assistant").put("content", it))
                    transcript.append("\nNeon: ").append(it)
                }.onFailure {
                    history.remove(history.length() - 1)
                    transcript.append("\n[Error: ").append(it.message?.take(200)).append("]")
                }
                render()
            }
        }.start()
    }

    override fun onDestroy() {
        if (::root.isInitialized) wm.removeView(root)
        super.onDestroy()
    }
}
