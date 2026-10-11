package com.lyricflow.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class LyricsOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var root: LinearLayout? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var controlsRow: LinearLayout? = null
    private var controlsHideButton: TextView? = null

    private var lastRenderedCurrent = ""
    private var lastTrackKey = ""
    private var transitionRunning = false

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferences: SharedPreferences
    private lateinit var uiPreferences: SharedPreferences

    private val uiPreferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            applyAppearancePreferences()
        }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startWindowX = 0
    private var startWindowY = 0
    private var dragging = false
    private var downTime = 0L
    private var controlsVisible = false

    private val hideControlsRunnable = Runnable {
        setControlsVisible(false)
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 100L)
        }
    }

    override fun onCreate() {
        super.onCreate()

        preferences = getSharedPreferences("overlay_preferences", MODE_PRIVATE)
        uiPreferences = getSharedPreferences("lyricflow_preferences", MODE_PRIVATE)
        uiPreferences.edit().putBoolean("overlay_enabled", true).apply()
        uiPreferences.registerOnSharedPreferenceChangeListener(uiPreferenceListener)
        createChannel()
        startForeground(42, notification())

        showOverlay()
        applyAppearancePreferences()
        handler.post(ticker)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(10))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(Color.argb(214, 12, 13, 18))
                setStroke(dp(1), Color.argb(55, 255, 255, 255))
            }
            elevation = dp(10).toFloat()
        }

        val header = TextView(this).apply {
            text = "♪  LYRICFLOW"
            setTextColor(Color.argb(150, 255, 255, 255))
            textSize = 9f
            letterSpacing = 0.14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(5))
        }

        val previous = TextView(this).apply {
            setTextColor(Color.argb(120, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        val current = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, dp(5))
        }

        val next = TextView(this).apply {
            setTextColor(Color.argb(120, 255, 255, 255))
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        box.addView(header)
        box.addView(previous, matchWrapParams())
        box.addView(current, matchWrapParams())
        box.addView(next, matchWrapParams())

        // Minimal controls: hidden by default and shown temporarily after a tap.
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(0, dp(5), 0, 0)
        }

        val resetButton = controlButton("↺")
        val hideButton = controlButton("×")

        controls.addView(resetButton, controlParams())
        controls.addView(hideButton, controlParams())

        box.addView(controls, matchWrapParams())

        applyAppearanceToViews(box, previous, current, next)

        resetButton.setOnClickListener {
            resetOverlayPosition()
            showControlsTemporarily()
        }

        hideButton.setOnClickListener {
            setControlsVisible(false)
            box.visibility = View.GONE
        }

        controlsRow = controls
        controlsHideButton = hideButton

        val params = WindowManager.LayoutParams(
            dp(340),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = preferences.getInt(KEY_X, defaultX())
            y = preferences.getInt(KEY_Y, defaultY())
        }

        windowParams = params
        root = box

        installTouchHandling(box)
        windowManager?.addView(box, params)
    }

    private fun matchWrapParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun controlParams() = LinearLayout.LayoutParams(
        dp(42),
        dp(32)
    ).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun controlButton(symbol: String): TextView = TextView(this).apply {
        text = symbol
        textSize = 18f
        setTextColor(Color.argb(210, 255, 255, 255))
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(45, 255, 255, 255))
            setStroke(dp(1), Color.argb(35, 255, 255, 255))
        }
    }

    private fun applyAppearancePreferences() {
        val box = root ?: return
        val previous = box.getChildAt(1) as? TextView ?: return
        val current = box.getChildAt(2) as? TextView ?: return
        val next = box.getChildAt(3) as? TextView ?: return
        applyAppearanceToViews(box, previous, current, next)
        val params = windowParams
        if (params != null && box.isAttachedToWindow) {
            windowManager?.updateViewLayout(box, params)
        }
    }

    private fun applyAppearanceToViews(
        box: LinearLayout,
        previous: TextView,
        current: TextView,
        next: TextView
    ) {
        val fontSize = uiPreferences.getFloat("font_size", 18f)
        val currentSize = uiPreferences.getFloat("current_size", 22f)
        val opacity = uiPreferences.getFloat("opacity", 0.84f).coerceIn(0.35f, 0.95f)
        val radius = uiPreferences.getFloat("radius", 16f).coerceIn(8f, 28f)
        val alignment = uiPreferences.getInt("alignment", 1)
        val gravity = when (alignment) {
            0 -> Gravity.START
            2 -> Gravity.END
            else -> Gravity.CENTER_HORIZONTAL
        }

        previous.textSize = fontSize
        next.textSize = fontSize
        current.textSize = currentSize
        previous.gravity = gravity
        current.gravity = gravity
        next.gravity = gravity

        previous.setTextColor(Color.argb((120 * opacity).toInt().coerceIn(30, 120), 255, 255, 255))
        next.setTextColor(Color.argb((120 * opacity).toInt().coerceIn(30, 120), 255, 255, 255))
        current.setTextColor(Color.argb(255, 255, 255, 255))

        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radius.toInt()).toFloat()
            setColor(Color.argb((214 * opacity).toInt().coerceIn(90, 242), 12, 13, 18))
            setStroke(dp(1), Color.argb(55, 255, 255, 255))
        }
        box.background = background
    }

    private fun defaultX(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        return ((screenWidth - dp(340)) / 2).coerceAtLeast(0)
    }

    private fun defaultY(): Int = dp(520)

    private fun installTouchHandling(view: View) {
        view.setOnTouchListener { _, event -> handleOverlayTouch(event) }

        // Child TextViews can receive the touch first, so forward their gestures to
        // the same drag/tap handler. Actual control buttons keep their click listeners.
        val box = view as? LinearLayout ?: return
        for (i in 0 until box.childCount) {
            val child = box.getChildAt(i)
            if (child === controlsRow) continue
            child.setOnTouchListener { _, event -> handleOverlayTouch(event) }
        }
    }

    private fun handleOverlayTouch(event: MotionEvent): Boolean {
        val params = windowParams ?: return false
        val currentRoot = root ?: return false

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startWindowX = params.x
                startWindowY = params.y
                downTime = System.currentTimeMillis()
                dragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = (event.rawX - downRawX).toInt()
                val dy = (event.rawY - downRawY).toInt()

                if (!dragging && (abs(dx) > dp(6) || abs(dy) > dp(6))) {
                    dragging = true
                }

                if (dragging) {
                    params.x = startWindowX + dx
                    params.y = startWindowY + dy
                    clampPosition(params, currentRoot)
                    windowManager?.updateViewLayout(currentRoot, params)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val elapsed = System.currentTimeMillis() - downTime

                if (event.actionMasked == MotionEvent.ACTION_UP && !dragging && elapsed < 450L) {
                    showControlsTemporarily()
                }

                if (dragging) {
                    savePosition(params)
                }

                dragging = false
                return true
            }
        }

        return true
    }

    private fun clampPosition(
        params: WindowManager.LayoutParams,
        view: View
    ) {
        val metrics = resources.displayMetrics
        val maxX = (metrics.widthPixels - view.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - view.height).coerceAtLeast(0)

        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun savePosition(params: WindowManager.LayoutParams) {
        preferences.edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun resetOverlayPosition() {
        val params = windowParams ?: return
        val view = root ?: return

        params.x = defaultX()
        params.y = defaultY().coerceAtMost(
            (resources.displayMetrics.heightPixels - view.height).coerceAtLeast(0)
        )

        windowManager?.updateViewLayout(view, params)
        savePosition(params)
    }

    private fun showControlsTemporarily() {
        setControlsVisible(true)
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, 3000L)
    }

    private fun setControlsVisible(visible: Boolean) {
        controlsVisible = visible
        controlsRow?.animate()?.cancel()
        controlsRow?.let { row ->
            if (visible) {
                row.visibility = View.VISIBLE
                row.alpha = 0f
                row.animate().alpha(1f).setDuration(150L).start()
            } else {
                row.animate()
                    .alpha(0f)
                    .setDuration(120L)
                    .withEndAction { row.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun render() {
        val box = root ?: return

        val header = box.getChildAt(0) as TextView
        val previous = box.getChildAt(1) as TextView
        val current = box.getChildAt(2) as TextView
        val next = box.getChildAt(3) as TextView

        val track = PlaybackStore.track
        val trackKey = "${track.title}|${track.artist}|${track.album}"

        if (!track.isPlaying || track.title == "—") {
            header.visibility = View.GONE
            previous.text = ""
            current.text = ""
            next.text = ""
            box.visibility = View.GONE
            lastRenderedCurrent = ""
            lastTrackKey = ""
            return
        }

        box.visibility = View.VISIBLE
        header.visibility = View.VISIBLE

        if (LyricsStore.loading) {
            previous.text = ""
            current.text = "Finding synchronized lyrics…"
            next.text = ""
            return
        }

        val lines = LyricsStore.current?.synced.orEmpty()

        if (lines.isEmpty()) {
            previous.text = ""
            current.text = LyricsStore.error ?: "No synchronized lyrics available"
            next.text = ""
            return
        }

        val index = lines.indexOfLast {
            it.startMs <= track.positionMs
        }.coerceAtLeast(0)

        val previousText = lines.getOrNull(index - 1)?.text.orEmpty()
        val currentText = lines.getOrNull(index)?.text.orEmpty()
        val nextText = lines.getOrNull(index + 1)?.text.orEmpty()

        header.text = "♪  LYRICFLOW"

        if (currentText != lastRenderedCurrent || trackKey != lastTrackKey) {
            animateLyricChange(previous, current, next, previousText, currentText, nextText)
            lastRenderedCurrent = currentText
            lastTrackKey = trackKey
        }
    }

    private fun animateLyricChange(
        previous: TextView,
        current: TextView,
        next: TextView,
        previousText: String,
        currentText: String,
        nextText: String
    ) {
        if (transitionRunning) {
            previous.animate().cancel()
            current.animate().cancel()
            next.animate().cancel()
        }

        transitionRunning = true
        val distance = dp(8).toFloat()
        val duration = 240L

        current.animate()
            .alpha(0f)
            .translationY(-distance)
            .setDuration(120L)
            .withEndAction {
                previous.text = previousText
                current.text = currentText
                next.text = nextText

                previous.alpha = 0.35f
                previous.translationY = distance
                current.alpha = 0f
                current.translationY = distance
                next.alpha = 0.35f
                next.translationY = 0f

                previous.animate()
                    .alpha(0.60f)
                    .translationY(0f)
                    .setDuration(duration)
                    .start()

                current.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(duration)
                    .withEndAction { transitionRunning = false }
                    .start()

                next.animate()
                    .alpha(0.60f)
                    .translationY(0f)
                    .setDuration(duration)
                    .start()
            }
            .start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                "lyricflow",
                "LyricFlow",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun notification(): Notification =
        Notification.Builder(this, "lyricflow")
            .setContentTitle("LyricFlow is active")
            .setContentText("Synchronized lyrics overlay")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(hideControlsRunnable)
        if (::uiPreferences.isInitialized) {
            uiPreferences.unregisterOnSharedPreferenceChangeListener(uiPreferenceListener)
        }
        root?.let {
            if (it.isAttachedToWindow) {
                windowManager?.removeView(it)
            }
        }
        root = null
        if (::uiPreferences.isInitialized) {
            uiPreferences.edit().putBoolean("overlay_enabled", false).apply()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val KEY_X = "overlay_x"
        private const val KEY_Y = "overlay_y"
    }
}
