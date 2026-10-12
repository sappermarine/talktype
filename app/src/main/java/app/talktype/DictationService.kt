package app.talktype

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private const val MAX_RECORDING_SECONDS = 600
private const val UNLOAD_MODEL_AFTER_MS = 10 * 60 * 1000L
private const val NOTIFICATION_ID = 1
private const val TAG = "TalkType"
private const val CHANNEL_ID = "recording"

/**
 * Draws the floating mic bubble over other apps and types the dictated text into the focused field.
 * It is an accessibility service because that is the only way to insert text into another app
 * without replacing the user's keyboard.
 */
class DictationService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val recorder = AudioRecorder()
    private lateinit var wm: WindowManager
    private lateinit var lp: WindowManager.LayoutParams
    private var bubble: BubbleView? = null
    /** Bumped when a dictation is cancelled so its late result is ignored. */
    private var generation = 0
    private var refreshPending = false
    private var dragging = false
    private var lastVisibility = ""
    private var lastField: AccessibilityNodeInfo? = null
    private val refresh = Runnable {
        refreshPending = false
        updateVisibility()
    }
    private val settle = Runnable { updateVisibility() }
    private val unloadModel = Runnable { scope.launch(Dispatchers.Default) { LocalTranscriber.unload() } }

    override fun onServiceConnected() {
        Prefs.init(this)
        ModelStore.init(this)
        History.init(this)
        wm = getSystemService(WindowManager::class.java)
        addBubble()
        updateVisibility()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED || event?.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
            event.source?.takeIf { it.isEditable }?.let { lastField = it }
        }
        // The keyboard window can show up in the window list a beat after the last event about it,
        // so look again once things have settled.
        handler.removeCallbacks(settle)
        handler.postDelayed(settle, 700)
        if (refreshPending) return
        refreshPending = true
        handler.postDelayed(refresh, 120)
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        placeBubble()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (recorder.isRecording) recorder.stop()
        scope.cancel()
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
        LocalTranscriber.unload()
        super.onDestroy()
    }

    // ---- Bubble ----

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val size = dp(60)
        lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        placeBubble()

        val view = BubbleView(this)
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressed = false
                    handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        clampToScreen()
                        wm.updateViewLayout(view, lp)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (dragging) {
                        dragging = false
                        if (landscape()) {
                            Prefs.bubbleLandX = lp.x
                            Prefs.bubbleLandY = lp.y
                        } else {
                            Prefs.bubbleX = lp.x
                            Prefs.bubbleY = lp.y
                        }
                        Log.i(TAG, "bubble: position saved ${lp.x},${lp.y} landscape=${landscape()}")
                    } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP) {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        onTap()
                    }
                }
            }
            true
        }
        wm.addView(view, lp)
        bubble = view
    }

    /** The real screen size right now. The service's own resources can lag behind a rotation. */
    private fun screenSize(): Point =
        if (Build.VERSION.SDK_INT >= 30) {
            wm.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else {
            resources.displayMetrics.let { Point(it.widthPixels, it.heightPixels) }
        }

    private fun landscape() = screenSize().let { it.x > it.y }

    private fun clampToScreen() {
        val screen = screenSize()
        lp.x = lp.x.coerceIn(0, max(0, screen.x - lp.width))
        lp.y = lp.y.coerceIn(0, max(0, screen.y - lp.height))
    }

    /**
     * Puts the bubble where the user last dragged it for this orientation. Always starts from the
     * saved spot, so squeezing it onto a smaller screen (landscape) never overwrites that spot.
     */
    private fun placeBubble() {
        if (dragging) return
        val screen = screenSize()
        val savedX = if (landscape()) Prefs.bubbleLandX else Prefs.bubbleX
        val savedY = if (landscape()) Prefs.bubbleLandY else Prefs.bubbleY
        val oldX = lp.x
        val oldY = lp.y
        lp.x = if (savedX >= 0) savedX else screen.x - lp.width - dp(8)
        lp.y = if (savedY >= 0) savedY else (screen.y * 0.42f).toInt()
        clampToScreen()
        if (lp.x == oldX && lp.y == oldY) return
        Log.i(TAG, "bubble: placed at ${lp.x},${lp.y} (was $oldX,$oldY) saved=$savedX,$savedY screen=${screen.x}x${screen.y}")
        bubble?.let { wm.updateViewLayout(it, lp) }
    }

    /** The bubble is shown while the keyboard is open, and always while a dictation is in flight. */
    private fun updateVisibility() {
        val view = bubble ?: return
        view.cloud = Prefs.engine == ENGINE_CLOUD
        placeBubble()
        val types = runCatching { windows.map { it.type } }.getOrElse { Log.w(TAG, "windows failed: $it"); emptyList() }
        val keyboard = AccessibilityWindowInfo.TYPE_INPUT_METHOD in types
        val show = view.state != BubbleView.State.IDLE || Prefs.bubbleAlways || keyboard
        val summary = "show=$show keyboard=$keyboard windows=$types at=${lp.x},${lp.y}"
        if (summary != lastVisibility) {
            lastVisibility = summary
            Log.i(TAG, "bubble: $summary app=${runCatching { rootInActiveWindow?.packageName }.getOrNull()}")
        }
        view.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun setState(s: BubbleView.State) {
        bubble?.state = s
        updateVisibility()
    }

    // ---- Dictation ----

    private fun onTap() {
        when (bubble?.state) {
            BubbleView.State.IDLE -> startDictation()
            BubbleView.State.RECORDING -> finishDictation()
            BubbleView.State.PROCESSING -> {
                generation++
                setState(BubbleView.State.IDLE)
                toast("Cancelled")
            }
            null -> {}
        }
    }

    /** Long-press discards a recording in progress; otherwise it switches between on-device and cloud. */
    private fun onLongPress() {
        if (bubble?.state == BubbleView.State.RECORDING) {
            recorder.stop()
            stopForegroundCompat()
            setState(BubbleView.State.IDLE)
            toast("Recording discarded")
        } else if (bubble?.state == BubbleView.State.IDLE) {
            switchEngine()
        }
    }

    private fun switchEngine() {
        val target = if (Prefs.engine == ENGINE_CLOUD) ENGINE_LOCAL else ENGINE_CLOUD
        if (target == ENGINE_CLOUD && Prefs.apiKey.isBlank()) {
            toast("Add an API key in TalkType to use the cloud engine")
            return
        }
        if (target == ENGINE_LOCAL && ModelStore.active() == null) {
            toast("Download a speech model in TalkType to use on-device")
            return
        }
        Prefs.engine = target
        bubble?.cloud = target == ENGINE_CLOUD
        Toast.makeText(this, if (target == ENGINE_CLOUD) "Cloud engine" else "On-device engine", Toast.LENGTH_SHORT).show()
    }

    private fun startDictation() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            toast("TalkType needs microphone permission")
            openApp()
            return
        }
        Pipeline.readinessProblem()?.let {
            toast(it)
            openApp()
            return
        }
        handler.removeCallbacks(unloadModel)
        startForegroundCompat()
        val started = recorder.start(
            MAX_RECORDING_SECONDS,
            onLevel = { level -> handler.post { bubble?.level = level } },
            onLimit = { handler.post { if (bubble?.state == BubbleView.State.RECORDING) finishDictation() } },
        )
        if (!started) {
            stopForegroundCompat()
            toast("Couldn't start the microphone. Is another app using it?")
            return
        }
        setState(BubbleView.State.RECORDING)
        scope.launch(Dispatchers.Default) { runCatching { Pipeline.warmUp() } }
    }

    private fun finishDictation() {
        val pcm = recorder.stop()
        stopForegroundCompat()
        if (pcm.size < SAMPLE_RATE * 3 / 10) {
            setState(BubbleView.State.IDLE)
            return
        }
        setState(BubbleView.State.PROCESSING)
        val mine = ++generation
        scope.launch {
            try {
                val out = withContext(Dispatchers.Default) { Pipeline.run(pcm) }
                if (mine != generation) return@launch
                if (out.text.isBlank()) {
                    toast("Didn't catch anything")
                } else {
                    History.add(out.text)
                    insert(out.text)
                    out.note?.let { toast(it) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) toast("Dictation failed: ${e.message}")
            } finally {
                if (mine == generation) setState(BubbleView.State.IDLE)
                handler.postDelayed(unloadModel, UNLOAD_MODEL_AFTER_MS)
            }
        }
    }

    // ---- Text insertion ----

    private fun insert(text: String) {
        val node = findTarget()
        if (node == null) {
            Log.w(TAG, "insert: no editable node found")
            copyToClipboard(text)
            toast("No text field focused - copied to clipboard")
            return
        }
        Log.i(TAG, "insert: target ${node.className} in ${node.packageName} editable=${node.isEditable}")
        if (!Prefs.pasteInsert && !node.isPassword && insertAtCursor(node, text)) return
        copyToClipboard(text)
        val pasted = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        Log.i(TAG, "insert: paste=$pasted")
        if (!pasted) toast("Couldn't type here - copied to clipboard, long-press to paste")
    }

    private fun AccessibilityNodeInfo.acceptsText() =
        isEditable || actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT }

    /**
     * The text field the user is typing in. Tapping the bubble makes our own overlay the "active"
     * window, so the plain lookup can come back empty; fall back to searching every window, then to
     * the last field that reported focus.
     */
    private fun findTarget(): AccessibilityNodeInfo? {
        findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.acceptsText() }?.let { return it }
        val roots = runCatching { windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root } }
            .getOrDefault(emptyList())
        for (root in roots) {
            if (root.packageName == packageName && bubble?.state != BubbleView.State.IDLE && roots.size > 1) continue
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.acceptsText() }?.let { return it }
        }
        for (root in roots) findFocusedEditable(root, 0)?.let { return it }
        return lastField?.takeIf { it.refresh() && it.acceptsText() }
    }

    private fun findFocusedEditable(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (node.isFocused && node.acceptsText()) return node
        if (depth > 60) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findFocusedEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Rewrites the field's text with [text] spliced in at the cursor (replacing any selection). */
    private fun insertAtCursor(node: AccessibilityNodeInfo, text: String): Boolean {
        val existing = if (node.isShowingHintText) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > existing.length || end > existing.length) {
            start = existing.length
            end = existing.length
        }
        val before = existing.substring(0, min(start, end))
        val after = existing.substring(max(start, end))
        val spacer = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
        val inserted = spacer + text
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, before + inserted + after)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        Log.i(TAG, "insert: setText=$ok selection=$start..$end existing=${existing.length} chars")
        if (!ok) return false
        val cursor = before.length + inserted.length
        node.refresh()
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
            },
        )
        return true
    }

    private fun copyToClipboard(text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Dictation", text))
    }

    // ---- Plumbing ----

    /**
     * Android only lets a background app use the mic from a "microphone" foreground service, so the
     * service promotes itself for the duration of a recording.
     */
    private fun startForegroundCompat() {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("TalkType is listening")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun openApp() {
        runCatching { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}
