package com.nullx11.official

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * NullX11 floating translator.
 *
 * Bubble: draggable orb (drag anywhere, snaps to the nearest edge).
 * Panel:  three tabs over any app —
 *   Translate : screen capture -> AI vision -> dismissible cards placed
 *               exactly over every detected text block; plus text mode.
 *   Chat      : multi-turn AI conversation (provider/model from tab 3).
 *   Settings  : provider, API key, model, base URL, target language.
 *
 * Screen capture uses MediaProjection (no root, no Shizuku). MainActivity
 * holds the consent dialog; this service owns the projection afterwards.
 */
class TransOverlayService : Service() {

    companion object {
        /** Live state for MainActivity. */
        @Volatile
        var isRunning: Boolean = false

        /** True once a MediaProjection session is wired up. */
        @Volatile
        var hasProjection: Boolean = false

        const val EXTRA_CODE = "projection_code"
        const val EXTRA_DATA = "projection_data"
        const val EXTRA_AUTORUN = "autorun_translate"

        /** A tap on TRANSLATE SCREEN is waiting for a fresh consent grant. */
        @Volatile
        var pendingScreenTranslate: Boolean = false
    }

    private val NOTIF_ID = 11
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var wm: WindowManager
    private var capThread: HandlerThread? = null
    private var capHandler: Handler? = null

    private var bubbleRoot: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panelRoot: ViewGroup? = null
    private var panelParams: WindowManager.LayoutParams? = null
    private var chatList: LinearLayout? = null
    private var chatScroll: ScrollView? = null
    private var chatInput: EditText? = null
    private var tvStatus: TextView? = null

    private val chatHistory = ArrayList<Pair<String, String>>()

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenW = 0
    private var screenH = 0
    private val capBusy = AtomicBoolean(false)

    private val chips = ArrayList<Pair<View, WindowManager.LayoutParams>>()
    private var clearAll: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        capThread = HandlerThread("nullx11-capture").also { it.start() }
        capHandler = Handler(capThread!!.looper)
        goToForeground(withProjection = false)
        try {
            buildBubble()
        } catch (e: Exception) {
            toast(e.message ?: "Overlay failed")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null && intent.hasExtra(EXTRA_CODE)) {
            val code = intent.getIntExtra(EXTRA_CODE, -1)
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
            if (code >= 0 && data != null) {
                try {
                    // Android 15 requires the mediaProjection FGS type to be
                    // active BEFORE getMediaProjection() is called.
                    goToForeground(withProjection = true)
                    setupProjection(code, data)
                    hasProjection = true
                    toast(getString(R.string.toast_capture_granted))
                    if (intent.getBooleanExtra(EXTRA_AUTORUN, false) && pendingScreenTranslate) {
                        pendingScreenTranslate = false
                        // small pause: the consent activity leaves, then we
                        // capture the app that sat underneath it
                        mainHandler.postDelayed({ captureScreen(0) }, 1200)
                    }
                } catch (e: Exception) {
                    toast(getString(R.string.toast_capture_denied))
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        hasProjection = false
        pendingScreenTranslate = false
        // remove every chip and the ✕ALL chip synchronously (main thread)
        clearChipsSync(removeClearAllChip = true)
        mainHandler.removeCallbacksAndMessages(null)
        disposeProjection()
        capThread?.quitSafely()
        capThread = null
        capHandler = null
        try { panelRoot?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { bubbleRoot?.let { wm.removeView(it) } } catch (_: Exception) {}
        panelRoot = null
        panelParams = null
        bubbleRoot = null
        bubbleParams = null
    }

    // ------------------------------------------------------------ foreground

    private fun buildNotif(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            "nx_overlay", "Floating translator", NotificationManager.IMPORTANCE_LOW
        )
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
        return Notification.Builder(this, "nx_overlay")
            .setSmallIcon(R.drawable.ic_bubble)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.status_on))
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 1, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    /**
     * API 34+ blocks startForeground(mediaprojection) unless the user just
     * accepted the capture dialog. So the bubble stage runs as `specialUse`,
     * upgraded with the mediaProjection bit only when a consent result lands.
     */
    private fun goToForeground(withProjection: Boolean) {
        val notif = buildNotif()
        if (Build.VERSION.SDK_INT >= 34) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (withProjection) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
            try {
                startForeground(NOTIF_ID, notif, types)
            } catch (e: Exception) {
                // picky OEM fallback: bare call uses the manifest types
                try {
                    startForeground(NOTIF_ID, notif)
                } catch (e2: Exception) {
                    toast(e2.message ?: "foreground failed")
                    stopSelf()
                }
            }
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ------------------------------------------------------------ bubble orb

    private val overlayType: Int get() = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val root = FrameLayout(this)
        root.background = getDrawable(R.drawable.bg_bubble)
        root.clipToOutline = true
        root.isClickable = true
        root.isFocusable = true
        val icon = ImageView(this)
        icon.setImageResource(R.drawable.gamespace_ring)
        val iconLp = FrameLayout.LayoutParams(dp(42), dp(42))
        iconLp.gravity = Gravity.CENTER
        root.addView(icon, iconLp)
        bubbleRoot = root

        val params = WindowManager.LayoutParams(
            dp(56), dp(56),
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenBounds().width() - dp(64)
            y = screenBounds().height() / 4
        }
        bubbleParams = params
        wm.addView(root, params)

        var initX = 0
        var initY = 0
        var tX = 0f
        var tY = 0f
        var dragging = false
        var downAt = 0L

        root.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    tX = e.rawX; tY = e.rawY
                    dragging = false
                    downAt = android.os.SystemClock.uptimeMillis()
                    root.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tX; val dy = e.rawY - tY
                    if (!dragging && (abs(dx) > 12 || abs(dy) > 12)) dragging = true
                    if (dragging) {
                        params.x = initX + dx.toInt()
                        params.y = initY + dy.toInt()
                        clampBubble(params)
                        updateView(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    root.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    if (!dragging && android.os.SystemClock.uptimeMillis() - downAt < 380) {
                        if (panelRoot != null && panelRoot!!.parent != null) collapsePanel() else expandPanel()
                    }
                    snapToEdge(params)
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    root.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------ panel tabs

    @SuppressLint("ClickableViewAccessibility")
    private fun expandPanel() {
        if (panelRoot != null && panelRoot!!.parent != null) return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = getDrawable(R.drawable.bg_panel)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }

        panelParams = WindowManager.LayoutParams(
            dp(292), WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            // FLAG_WATCH_OUTSIDE | FLAG_HARDWARE_ACCELERATED
            0x00040000 or 0x01000000,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        // header row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.gamespace_ring)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        header.addView(TextView(this).apply {
            text = getString(R.string.panel_title)
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(6)
        })
        header.addView(TextView(this).apply {
            text = getString(R.string.close_panel)
            textSize = 13f
            setTextColor(Color.parseColor("#9AA3B2"))
            gravity = Gravity.CENTER
            background = getDrawable(R.drawable.bg_btn_ghost)
            setOnClickListener { collapsePanel() }
        }, LinearLayout.LayoutParams(dp(30), dp(30)))
        panel.addView(header)

        // tab row
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabNames = intArrayOf(R.string.tab_translate, R.string.tab_chat, R.string.tab_settings)
        tabNames.forEachIndexed { idx, res ->
            tabs.addView(TextView(this).apply {
                text = getString(res)
                textSize = 11.5f
                gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.topMargin = dp(9)
            })
        }
        panel.addView(tabs)
        panel.addView(
            View(this).apply { background = ColorDrawable(0x1AFFFFFF) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
        )

        val content = FrameLayout(this)
        panel.addView(
            content, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        fun select(tab: Int) {
            for (i in 0 until tabs.childCount) {
                val tv = tabs.getChildAt(i) as TextView
                val sel = i == tab
                tv.setTextColor(if (sel) Color.parseColor("#22D3EE") else Color.parseColor("#98A2B3"))
                tv.setTypeface(tv.typeface, if (sel) Typeface.BOLD else Typeface.NORMAL)
            }
            content.removeAllViews()
            when (tab) {
                0 -> content.addView(buildTranslateColumn())
                1 -> content.addView(buildChatColumn())
                else -> content.addView(buildSettingsColumn())
            }
        }
        tabNames.forEachIndexed { idx, _ -> tabs.getChildAt(idx).setOnClickListener { select(idx) } }

        panelRoot = panel
        placePanel(panel)
        wm.addView(panel, panelParams)
        panel.post {
            placePanel(panel)
            panel.scaleX = 0.86f
            panel.scaleY = 0.86f
            panel.alpha = 0f
            panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(170)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f)).start()
        }
        panelRoot!!.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE) {
                collapsePanel()
                true
            } else false
        }
        select(0)
        refreshChatList()
    }

    private fun collapsePanel() {
        try {
            if (panelRoot != null && panelRoot!!.parent != null) {
                wm.removeView(panelRoot)
            }
        } catch (_: Exception) {}
    }

    private fun placePanel(panel: LinearLayout) {
        val bp = bubbleParams ?: return
        val pp = panelParams ?: return
        val scr = screenBounds()
        val guessW = if (panel.measuredWidth == 0) dp(292) else panel.measuredWidth
        val guessH = if (panel.measuredHeight == 0) dp(360) else panel.measuredHeight
        val left = bp.x < scr.width() / 2
        pp.x = if (left) {
            (bp.x + bpWidth() + dp(8)).coerceIn(0, scr.width() - guessW)
        } else {
            (bp.x - guessW - dp(8)).coerceIn(0, scr.width() - guessW)
        }
        val top = bp.y + bpHeight() / 2 - guessH / 2
        pp.y = top.coerceIn(dp(8), max(dp(8), scr.height() - guessH - dp(8)))
        updateView(panel, pp)
    }

    // ------------------------------------------------------ translate column

    private var screenMode = true

    private fun buildTranslateColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btnScreen = modeBtn(getString(R.string.mode_screen), screenMode)
        val btnText = modeBtn(getString(R.string.mode_text), !screenMode)
        modes.addView(
            btnScreen,
            LinearLayout.LayoutParams(0, dp(30), 1f).also { it.marginEnd = dp(4) }
        )
        modes.addView(
            btnText,
            LinearLayout.LayoutParams(0, dp(30), 1f)
        )
        col.addView(
            modes, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(9) })

        fun refreshModes() {
            restyleModeBtn(btnScreen, screenMode)
            restyleModeBtn(btnText, !screenMode)
        }
        btnScreen.setOnClickListener {
            screenMode = true
            refreshModes()
            swapTranslateBody(col, modes)
        }
        btnText.setOnClickListener {
            screenMode = false
            refreshModes()
            swapTranslateBody(col, modes)
        }

        tvStatus = TextView(this).apply {
            textSize = 10.5f
            setTextColor(Color.parseColor("#98A2B2"))
            text = idleText()
        }
        col.addView(
            tvStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) })

        addTranslateBody(col, modes)
        return col
    }

    private fun modeBtn(label: String, selected: Boolean): TextView = TextView(this).apply {
        text = label
        textSize = 11f
        gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        restyleModeBtn(this, selected)
    }

    private fun restyleModeBtn(tv: TextView, selected: Boolean) {
        tv.setTextColor(if (selected) Color.WHITE else Color.parseColor("#98A2B2"))
        tv.background = getDrawable(if (selected) R.drawable.bg_btn_primary else R.drawable.bg_btn_ghost)
    }

    /** children: [0]=modes, [1]=body, [2]=status — only the body swaps. */
    private fun swapTranslateBody(col: LinearLayout, keep: View) {
        if (col.childCount > 1) col.removeViewAt(1)
        addTranslateBody(col, keep)
    }

    private fun addTranslateBody(col: LinearLayout, keep: View) {
        val insertAt = col.indexOfChild(keep) + 1
        if (screenMode) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            body.addView(
                TextView(this).apply {
                    text = getString(R.string.hint_screen)
                    textSize = 10.5f
                    setTextColor(Color.parseColor("#98A2B2"))
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) })

            body.addView(
                TextView(this).apply {
                    text = getString(R.string.btn_translate_screen)
                    textSize = 14f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = getDrawable(R.drawable.bg_btn_primary)
                    setTypeface(typeface, Typeface.BOLD)
                    setOnClickListener { startScreenTranslate() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
                ).also { it.topMargin = dp(10) })

            col.addView(
                body, insertAt, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
        } else {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            val input = EditText(this).apply {
                hint = getString(R.string.input_hint)
                textSize = 12.5f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#68737F"))
                background = getDrawable(R.drawable.bg_input)
                gravity = Gravity.TOP or Gravity.START
                minLines = 3
                maxLines = 6
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            body.addView(
                input, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) })

            val go = TextView(this).apply {
                text = getString(R.string.btn_translate)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_btn_primary)
                setTypeface(typeface, Typeface.BOLD)
            }
            body.addView(
                go, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
                ).also { it.topMargin = dp(8) })

            val result = TextView(this).apply {
                textSize = 12.5f
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_input)
                minLines = 2
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }
            body.addView(
                result, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(8) })

            val copy = TextView(this).apply {
                text = getString(R.string.btn_copy)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_btn_ghost)
                visibility = View.GONE
            }
            body.addView(
                copy, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
                ).also { it.topMargin = dp(6) })

            go.setOnClickListener {
                val src = input.text?.toString()?.trim().orEmpty()
                if (src.isEmpty()) return@setOnClickListener
                if (Prefs.apiKey(this, Prefs.provider(this)).isBlank()) {
                    toast(getString(R.string.no_key))
                    return@setOnClickListener
                }
                go.isEnabled = false
                setStatus(getString(R.string.thinking))
                Thread {
                    try {
                        val (lang, tr) = LlmApi.translateText(this, src)
                        mainHandler.post {
                            go.isEnabled = true
                            result.text = if (lang.isNotBlank()) "[$lang] $tr" else tr
                            copy.visibility = View.VISIBLE
                            copy.setOnClickListener {
                                clip(tr)
                                toast(getString(R.string.copied))
                            }
                            setStatus(idleText())
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("nullx11", "translateText failed", e)
                        mainHandler.post {
                            go.isEnabled = true
                            copy.visibility = View.GONE
                            result.text = "⚠ ${e.message}"
                            setStatus(idleText())
                        }
                    }
                }.start()
            }

            col.addView(
                body, insertAt, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
        }
    }

    private fun startScreenTranslate() {
        if (Prefs.apiKey(this, Prefs.provider(this)).isBlank()) {
            toast(getString(R.string.no_key))
            return
        }
        if (!hasProjection || imageReader == null) {
            // permission lapsed or the projection died — recover in place
            // with a fresh consent dialog instead of a dead-end message
            collapsePanel()
            requestCaptureConsent()
            return
        }
        collapsePanel()
        toast(getString(R.string.toast_saving_screen))
        mainHandler.postDelayed({ captureScreen(0) }, 480)
    }

    /**
     * Self-healing re-consent: opens the system capture dialog right over
     * whatever app is on screen. The result relaunches this service with
     * EXTRA_AUTORUN and the translation continues by itself.
     */
    private fun requestCaptureConsent() {
        pendingScreenTranslate = true
        toast(getString(R.string.grant_capture_first))
        try {
            startActivity(
                Intent(this, CaptureConsentActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            android.util.Log.e("nullx11", "consent activity launch failed", e)
            pendingScreenTranslate = false
            toast(getString(R.string.no_capture))
        }
    }

    // ------------------------------------------------------- screen capture

    private fun setupProjection(code: Int, data: Intent) {
        // re-grant while a session is alive: tear the old one down first
        if (projection != null || imageReader != null || virtualDisplay != null) {
            disposeProjection()
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                disposeProjection()
                hasProjection = false
            }
        }, mainHandler)
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        screenW = m.widthPixels
        screenH = m.heightPixels
        val reader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader.setOnImageAvailableListener({ r ->
            if (capBusy.get()) {
                // discard frames arriving while a translation is already running
                try { r.acquireLatestImage()?.close() } catch (_: Exception) {}
            }
        }, capHandler)
        imageReader = reader
        virtualDisplay = projection?.createVirtualDisplay(
            "nullx11-capture", screenW, screenH, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, capHandler
        )
    }

    private fun disposeProjection() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null
        imageReader = null
        projection = null
    }

    /** attempt 0/1/2 — Samsung One UI can be slow to hand the first frame. */
    private fun captureScreen(attempt: Int) {
        val reader = imageReader
        if (reader == null) {
            requestCaptureConsent()
            return
        }
        if (!capBusy.compareAndSet(false, true)) return
        val image = try { reader.acquireLatestImage() } catch (e: Exception) {
            android.util.Log.e("nullx11", "acquireLatestImage failed", e)
            null
        }
        if (image == null) {
            capBusy.set(false)
            if (attempt < 2) {
                mainHandler.postDelayed({ captureScreen(attempt + 1) }, 900)
            } else {
                toast(getString(R.string.error_prefix, "no frame available"))
            }
            return
        }
        Thread {
            try {
                val bitmap = imageToBitmap(image)
                image.close()
                setStatus(getString(R.string.translating_frames))
                android.util.Log.d("nullx11", "captured ${bitmap.width}x${bitmap.height}, calling model")
                val found = LlmApi.screenTranslate(this, bitmap)
                android.util.Log.d("nullx11", "screenTranslate ok: ${found.size} blocks")
                mainHandler.post { placeChips(found) }
                setStatus(null)
            } catch (e: Exception) {
                android.util.Log.e("nullx11", "screenTranslate failed", e)
                mainHandler.post { toast(getString(R.string.error_prefix, e.message ?: "?")) }
                setStatus(null)
            } finally {
                try { image.close() } catch (_: Exception) {}
                capBusy.set(false)
            }
        }.start()
    }

    private fun imageToBitmap(image: android.media.Image): Bitmap {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val px = IntArray(w * h)
        var o = 0
        for (row in 0 until h) {
            var base = row * rowStride
            for (col in 0 until w) {
                val r = buf.get(base).toInt() and 0xFF
                val g = buf.get(base + 1).toInt() and 0xFF
                val b = buf.get(base + 2).toInt() and 0xFF
                val a = buf.get(base + 3).toInt() and 0xFF
                px[o++] = Color.argb(a, r, g, b)
                base += 4
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    // --------------------------------------------------------- overlay cards

    private class ChipBox(
        val x: Int, val y: Int, val w: Int, val h: Int, val data: LlmApi.Chip
    )

    private fun placeChips(found: List<LlmApi.Chip>) {
        clearChipsSync(removeClearAllChip = true)
        if (screenW == 0 || screenH == 0) return
        for (chip in found) {
            val x = (chip.x0 / 1000f * screenW).toInt()
            val y = (chip.y0 / 1000f * screenH).toInt()
            val w = ((chip.x1 - chip.x0) / 1000f * screenW).toInt().coerceAtLeast(dp(30))
            val h = ((chip.y1 - chip.y0) / 1000f * screenH).toInt().coerceAtLeast(dp(16))
            val tv = TextView(this).apply {
                text = chip.translated
                textSize = 10.8f
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_chip)
                maxLines = 7
                setPadding(dp(7), dp(4), dp(7), dp(4))
                isClickable = true
                isLongClickable = true
                setOnClickListener { removeChip(this) }
                setOnLongClickListener {
                    toast(chip.orig)
                    true
                }
            }
            val p = WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x.coerceIn(0, screenW - w)
                this.y = y.coerceIn(dp(18), screenH - dp(30))
            }
            try { wm.addView(tv, p) } catch (_: Exception) { continue }
            chips.add(tv to p)
            fitChipHeight(tv, p, h)
        }
        toggleClearAll(chips.isNotEmpty())
        toast(getString(R.string.toast_translated))
    }

    /** GROW tall boxes' windows so long translations are not cut off. */
    private fun fitChipHeight(tv: TextView, p: WindowManager.LayoutParams, boxH: Int) {
        tv.post {
            try {
                val mh = tv.measuredHeight
                if (mh > boxH + dp(60)) {
                    p.height = boxH + dp(60)
                    updateView(tv, p)
                } else if (mh in 1 until boxH && boxH - mh > dp(8)) {
                    p.height = boxH
                    updateView(tv, p)
                }
            } catch (_: Exception) {}
        }
    }

    private fun removeChip(tv: View) {
        val idx = chips.indexOfFirst { it.first === tv }
        if (idx >= 0) {
            val v = chips[idx].first
            chips.removeAt(idx)
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        toggleClearAll(chips.isNotEmpty())
    }

    /** Synchronous chip removal — call only from the main thread. */
    private fun clearChipsSync(removeClearAllChip: Boolean) {
        for ((v, _) in chips) {
            try { wm.removeView(v) } catch (_: Exception) {}
        }
        chips.clear()
        if (removeClearAllChip && clearAll != null) {
            try { wm.removeView(clearAll) } catch (_: Exception) {}
            clearAll = null
        }
    }

    private fun toggleClearAll(show: Boolean) {
        mainHandler.post { applyClearAll(show) }
    }

    private fun applyClearAll(show: Boolean) {
        try {
            if (show) {
                if (clearAll == null) {
                    val tv = TextView(this).apply {
                        text = getString(R.string.clear_all)
                        textSize = 10f
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                        background = getDrawable(R.drawable.bg_chip_clear)
                        setTypeface(typeface, Typeface.BOLD)
                    }
                    val layout = WindowManager.LayoutParams(
                        dp(58), dp(26),
                        overlayType,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.END
                        x = dp(10)
                        y = dp(64)
                    }
                    tv.setOnClickListener {
                        clearChipsSync(true)
                        toast(getString(R.string.toast_cleared))
                    }
                    try {
                        wm.addView(tv, layout)
                        clearAll = tv
                    } catch (_: Exception) {}
                }
            } else if (clearAll != null) {
                try { wm.removeView(clearAll) } catch (_: Exception) {}
                clearAll = null
            }
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------ chat column

    private fun buildChatColumn(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        col.addView(
            TextView(this).apply {
                text = getString(R.string.new_chat)
                textSize = 10f
                setTextColor(Color.parseColor("#98A2B2"))
                setPadding(dp(2), dp(2), dp(2), dp(2))
                setOnClickListener {
                    chatHistory.clear()
                    refreshChatList()
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        chatScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chatList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(7), dp(7), dp(7), dp(7))
            background = getDrawable(R.drawable.bg_input)
        }
        chatScroll!!.addView(
            chatList, ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        col.addView(
            chatScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            ).also { it.topMargin = dp(6) })

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val input = EditText(this).apply {
            hint = getString(R.string.chat_hint)
            textSize = 12f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#68737F"))
            background = getDrawable(R.drawable.bg_input)
            maxLines = 3
            setPadding(dp(10), dp(9), dp(10), dp(9))
        }
        chatInput = input
        row.addView(
            input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val send = ImageView(this).apply {
            setImageResource(R.drawable.ic_send)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = getDrawable(R.drawable.bg_btn_primary)
            setPadding(dp(9), dp(9), dp(9), dp(9))
        }
        row.addView(
            send, LinearLayout.LayoutParams(dp(40), dp(38)).also { it.marginStart = dp(6) }
        )
        col.addView(
            row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(6) })

        send.setOnClickListener { sendChat() }
        refreshChatList()
        return col
    }

    private fun sendChat() {
        val input = chatInput ?: return
        val txt = input.text?.toString()?.trim().orEmpty()
        if (txt.isEmpty()) return
        if (Prefs.apiKey(this, Prefs.provider(this)).isBlank()) {
            toast(getString(R.string.no_key))
            return
        }
        input.setText("")
        chatHistory.add("user" to txt)
        userBubble(txt)
        val tv = aiBubble(getString(R.string.thinking))
        Thread {
            try {
                val reply = LlmApi.chat(this, chatHistory.takeLast(16))
                chatHistory.add("assistant" to reply)
                mainHandler.post { tv.text = reply; autoScrollChat() }
            } catch (e: Exception) {
                android.util.Log.e("nullx11", "chat failed", e)
                val msg = "⚠ ${e.message}"
                chatHistory.add("assistant" to msg)
                mainHandler.post { tv.text = msg; autoScrollChat() }
            }
        }.start()
    }

    /** Adds an AI bubble; returns the TextView so async replies can update it. */
    private fun aiBubble(text: String): TextView {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val tv = TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.WHITE)
            background = getDrawable(R.drawable.bg_msg_ai)
            maxLines = 14
            minWidth = dp(60)
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        holder.addView(tv)
        chatList?.addView(
            holder, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2) }
        )
        autoScrollChat()
        return tv
    }

    private fun userBubble(text: String) {
        val holder = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        holder.addView(
            TextView(this).apply {
                this.text = text
                textSize = 12f
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_msg_user)
                maxLines = 14
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
        )
        chatList?.addView(
            holder, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(2) }
        )
        autoScrollChat()
    }

    private fun refreshChatList() {
        val list = chatList ?: return
        list.removeAllViews()
        for ((role, text) in chatHistory) {
            if (role == "user") userBubble(text) else aiBubble(text)
        }
        if (chatHistory.isEmpty()) {
            aiBubble(getString(R.string.idle))
        }
        autoScrollChat()
    }

    private fun autoScrollChat() {
        chatScroll?.post { chatScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    // ------------------------------------------------------- settings column

    private val provCodes = listOf("gemini", "openrouter", "groq", "custom")

    private fun buildSettingsColumn(): View {
        val ctx = this
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var current = Prefs.provider(ctx)
        if (!provCodes.contains(current)) current = "gemini"

        val provLabels = listOf("Gemini (AI Studio)", "OpenRouter :free", "Groq (free tier)", "Custom API")

        fun hintFor(prov: String): String = when (prov) {
            "gemini" -> getString(R.string.model_hint_gemini)
            "openrouter" -> getString(R.string.model_hint_openrouter)
            "groq" -> getString(R.string.model_hint_groq)
            else -> getString(R.string.model_hint_custom)
        }

        fun label(text: String): TextView = TextView(ctx).apply {
            this.text = text
            textSize = 10f
            setTextColor(Color.parseColor("#98A2B2"))
        }

        fun field(hint: String, value: String, password: Boolean): EditText =
            EditText(ctx).apply {
                setText(value)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    (if (password) android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    else android.text.InputType.TYPE_TEXT_VARIATION_NORMAL)
                textSize = 12f
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#68737F"))
                background = getDrawable(R.drawable.bg_input)
                setSingleLine(true)
                setPadding(dp(10), dp(9), dp(10), dp(9))
            }

        fun keyPrefOf(prov: String): String = when (prov) {
            "gemini" -> Prefs.KEY_GEMINI
            "openrouter" -> Prefs.KEY_OPENROUTER
            "groq" -> Prefs.KEY_GROQ
            else -> Prefs.KEY_CUSTOM
        }

        fun modelPrefOf(prov: String): String = when (prov) {
            "gemini" -> Prefs.MODEL_GEMINI
            "openrouter" -> Prefs.MODEL_OPENROUTER
            "groq" -> Prefs.MODEL_GROQ
            else -> Prefs.MODEL_CUSTOM
        }

        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, provLabels).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        val keyField = field("AIza… / sk-… / gsk_…", Prefs.apiKey(ctx, current), false)
        val modelField = field(Prefs.defaultModel(current), Prefs.model(ctx, current), false)
        val hintView = TextView(ctx).apply {
            text = hintFor(current)
            textSize = 9.5f
            setTextColor(Color.parseColor("#68737F"))
        }
        val targetField = field(Prefs.DEFAULT_TARGET_LANG, Prefs.targetLang(ctx), false)
        val baseField = field("https://api.provider.com/v1", Prefs.baseUrl(ctx, "custom"), false)
        val baseContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                // the auto-fired first callback just re-selects the same provider
                val code = provCodes[position]
                current = code
                hintView.text = hintFor(code)
                baseContainer.visibility = if (code == "custom") View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        spinner.setSelection(provCodes.indexOf(current))

        col.addView(label(getString(R.string.provider)), colParams())
        col.addView(spinner, colParams())

        col.addView(label(getString(R.string.api_key)), colParams())
        col.addView(keyField, colParams())

        col.addView(label(getString(R.string.model)), colParams())
        col.addView(modelField, colParams())

        col.addView(hintView, colParams())

        baseContainer.addView(label(getString(R.string.base_url)), colParams())
        baseContainer.addView(baseField, colParams())
        baseContainer.visibility = if (current == "custom") View.VISIBLE else View.GONE
        col.addView(baseContainer, colParams())

        col.addView(label(getString(R.string.target_lang)), colParams())
        col.addView(targetField, colParams())

        col.addView(
            TextView(ctx).apply {
                text = getString(R.string.save)
                textSize = 12.5f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_btn_primary)
                setTypeface(typeface, Typeface.BOLD)
                setOnClickListener {
                    Prefs.get(ctx).edit()
                        .putString(Prefs.PROVIDER, current)
                        .putString(keyPrefOf(current), keyField.text?.toString()?.trim().orEmpty())
                        .putString(modelPrefOf(current), modelField.text?.toString()?.trim().orEmpty())
                        .putString(Prefs.BASE_CUSTOM, baseField.text?.toString()?.trim().orEmpty())
                        .putString(Prefs.TARGET_LANG, targetField.text?.toString()?.trim().orEmpty())
                        .apply()
                    toast(getString(R.string.saved))
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38)
            ).also { it.topMargin = dp(10) })

        col.addView(
            TextView(ctx).apply {
                text = getString(R.string.open_github)
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                background = getDrawable(R.drawable.bg_btn_ghost)
                setOnClickListener {
                    try {
                        val i = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/Inkflow-Gab/nullx11/releases")
                        )
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(i)
                    } catch (_: Exception) {}
                }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
            ).also { it.topMargin = dp(6) })

        col.addView(
            TextView(ctx).apply {
                text = getString(R.string.privacy_note)
                textSize = 9.5f
                setTextColor(Color.parseColor("#68737F"))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.topMargin = dp(8) })

        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        scroll.addView(col)
        return scroll
    }

    private fun colParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).also { it.topMargin = dp(6) }

    // ------------------------------------------------------------------ misc

    private fun setStatus(text: String?) {
        mainHandler.post {
            tvStatus?.text = text ?: idleText()
        }
    }

    private fun idleText(): String =
        if (Prefs.apiKey(this, Prefs.provider(this)).isBlank()) getString(R.string.no_key)
        else getString(R.string.idle)

    private fun clip(text: String) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("nullx11", text))
        } catch (_: Exception) {}
    }

    private fun bpWidth(): Int = bubbleParams?.width ?: dp(50)
    private fun bpHeight(): Int = bubbleParams?.height ?: dp(50)

    private fun clampBubble(p: WindowManager.LayoutParams) {
        val s = screenBounds()
        p.x = p.x.coerceIn(0, max(0, s.width() - bpWidth()))
        p.y = p.y.coerceIn(0, max(0, s.height() - bpHeight()))
    }

    private fun snapToEdge(p: WindowManager.LayoutParams) {
        val target = if (p.x + bpWidth() / 2 < screenBounds().width() / 2) 0
        else screenBounds().width() - bpWidth()
        android.animation.ValueAnimator.ofInt(p.x, target).apply {
            duration = 230
            addUpdateListener { a ->
                p.x = a.animatedValue as Int
                updateView(bubbleRoot, p)
            }
            start()
        }
    }

    private fun screenBounds(): android.graphics.Rect = try {
        @Suppress("DEPRECATION")
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        android.graphics.Rect(0, 0, m.widthPixels, m.heightPixels)
    } catch (e: Exception) {
        android.graphics.Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun updateView(v: View?, p: WindowManager.LayoutParams) {
        try {
            if (v != null) wm.updateViewLayout(v, p)
        } catch (_: Exception) {}
    }

    private fun toast(msg: String) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
            } else {
                mainHandler.post {
                    Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Exception) {}
    }
}
