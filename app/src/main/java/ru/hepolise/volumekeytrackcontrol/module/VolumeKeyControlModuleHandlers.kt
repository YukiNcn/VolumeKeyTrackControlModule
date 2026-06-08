package ru.hepolise.volumekeytrackcontrol.module

import android.content.Context
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.PowerManager
import android.os.Vibrator
import android.view.Display
import android.view.KeyEvent
import androidx.core.content.edit
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodHook.MethodHookParam
import de.robv.android.xposed.XposedHelpers
import kotlinx.coroutines.Runnable
import ru.hepolise.volumekeytrackcontrol.module.util.LogHelper
import ru.hepolise.volumekeytrackcontrol.module.util.RemotePrefsHelper
import ru.hepolise.volumekeytrackcontrol.module.util.State
import ru.hepolise.volumekeytrackcontrol.module.util.StatusHelper
import ru.hepolise.volumekeytrackcontrol.util.AppFilterType
import ru.hepolise.volumekeytrackcontrol.util.RewindActionType
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.LAUNCHED_COUNT
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getAppFilterType
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getApps
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getLaunchedCount
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getLongPressDuration
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getRewindActionType
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.getRewindDuration
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.isAddSecondaryAction
import ru.hepolise.volumekeytrackcontrol.util.SharedPreferencesUtil.isSwapButtons
import ru.hepolise.volumekeytrackcontrol.util.VibratorUtil.getVibrator
import ru.hepolise.volumekeytrackcontrol.util.VibratorUtil.triggerVibration
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min


object VolumeKeyControlModuleHandlers {
    private const val CLASS_MEDIA_SESSION_LEGACY_HELPER =
        "android.media.session.MediaSessionLegacyHelper"
    private const val CLASS_COMPONENT_NAME = "android.content.ComponentName"

    @Volatile
    private var state = State()

    private lateinit var audioManager: AudioManager
    private lateinit var powerManager: PowerManager
    private lateinit var displayManager: DisplayManager
    private lateinit var vibrator: Vibrator
    private lateinit var sessionHelper: Any

    private var prefs: SharedPreferences? = null

    private var mediaControllers: List<MediaController>? = null

    private val logBuffer = StringBuilder()

    private fun bufferedLog(text: String) {
        logBuffer
            .append("[${Thread.currentThread().name}] ")
            .append(text)
            .append("\n")
    }

    private fun endLog() {
        if (logBuffer.isEmpty()) return
        LogHelper.log(VolumeControlModule::class.java.simpleName, logBuffer.toString())
        logBuffer.clear()
    }

    private fun log(text: String) = LogHelper.log(VolumeControlModule::class.java.simpleName, text)

    val handleInterceptKeyBeforeQueueing: XC_MethodHook = object : XC_MethodHook() {
        override fun beforeHookedMethod(param: MethodHookParam) {
            val handler = param.getHandler()
            handler.postDelayed(::endLog, 1_000)
            initPrefs()
            with(param) {
                val event = args[0] as KeyEvent
                try {
                    with(getContext()) {
                        initManagers()
                        initControllers()
                    }
                } catch (t: Throwable) {
                    log("init failed")
                    t.printStackTrace()
                    return
                }
                updateFlags(event)
                if (needHook(event)) {
                    doHook(event)
                }
            }
        }
    }

    val handleConstructPhoneWindowManager: XC_MethodHook = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            log("handleConstructPhoneWindowManager: initialized")
            val context = param.getContext()
            MediaEvent.entries.forEach { event ->
                val runnable = Runnable { event.handle(param) }
                XposedHelpers.setAdditionalInstanceField(
                    param.thisObject,
                    event.fieldName,
                    runnable
                )
            }

            StatusHelper.handleSuccessHook(context)
        }
    }

    private fun needHook(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        bufferedLog("========")
        bufferedLog("audioManager mode: ${audioManager.mode}, required: ${AudioManager.MODE_NORMAL}")
        bufferedLog("keyCode: ${keyCode}, required: ${KeyEvent.KEYCODE_VOLUME_DOWN} or ${KeyEvent.KEYCODE_VOLUME_UP}")
        bufferedLog("displayInteractive: ${isDisplayInteractive()}, required: false")
        bufferedLog("isDownPressed: ${state.isDownPressed}")
        bufferedLog("isUpPressed: ${state.isUpPressed}")
        bufferedLog("hasPendingEvent: ${state.pendingEvent != null}")
        bufferedLog("hasActiveMediaController: ${hasActiveMediaController()}, required: true")
        val needHook =
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                    && event.flags and KeyEvent.FLAG_FROM_SYSTEM != 0
                    && (!isDisplayInteractive()/* || isDownPressed || isUpPressed*/)
                    && audioManager.mode == AudioManager.MODE_NORMAL
                    && hasActiveMediaController()
        bufferedLog("needHook: $needHook")
        return needHook
    }

    private fun isDisplayInteractive(): Boolean {
        bufferedLog("powerManager.isInteractive: ${powerManager.isInteractive}")
        if (!powerManager.isInteractive) {
            return false
        }
        bufferedLog("Displays count: ${displayManager.displays.size}")
        // TODO
        if (displayManager.displays.size > 1) {
            return true
        }
        val display = displayManager.displays[0]
        val disabledStates =
            listOf(Display.STATE_OFF, Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND)
        bufferedLog("Checking display: ${display.displayId}, state: ${display.state}, required: $disabledStates")
        return !disabledStates.contains(display.state)
    }

    private fun Context.initManagers() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            ?: throw NullPointerException("Unable to obtain audio service")
        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager?
            ?: throw NullPointerException("Unable to obtain power service")
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager?
            ?: throw NullPointerException("Unable to obtain display service")
        vibrator = getVibrator()
        sessionHelper = getMediaSessionLegacyHelper()
    }

    private fun initPrefs() {
        prefs = SharedPreferencesUtil.prefs()
    }

    private fun Context.getMediaSessionLegacyHelper(): Any {
        val context = this
        val classLoader = javaClass.classLoader
        val mediaSessionHelperClass = XposedHelpers.findClass(
            CLASS_MEDIA_SESSION_LEGACY_HELPER,
            classLoader
        )
        return XposedHelpers.callStaticMethod(
            mediaSessionHelperClass,
            "getHelper",
            context
        )
    }

    private fun Context.initControllers() {
        val sessionManager = XposedHelpers.getObjectField(sessionHelper, "mSessionManager")
        val componentNameClass =
            XposedHelpers.findClass(CLASS_COMPONENT_NAME, classLoader)

        @Suppress("UNCHECKED_CAST")
        mediaControllers = XposedHelpers.callMethod(
            sessionManager,
            "getActiveSessions",
            arrayOf(componentNameClass),
            null
        ) as List<MediaController>?
    }

    private fun MethodHookParam.doHook(event: KeyEvent) {
        event.applyForAction {
            val pendingEvent = state.pendingEvent
            if (pendingEvent != null) {
                bufferedLog("Already having a pending event, using it")
                pendingEvent.doHandle(this@doHook)
            } else {
                when (it) {
                    Action.PRESSED -> handlePressedAction(this)
                    Action.UNPRESSED -> handleUnpressedAction(this)
                }
            }
            setResult(0)
        }
    }

    private fun MethodHookParam.handlePressedAction(keyHelper: KeyHelper) {
        bufferedLog("Volume pressed action received, down: ${state.isDownPressed}, up: ${state.isUpPressed}")
        updateState { isLongPress = false }
        if (state.isUpPressed && state.isDownPressed) {
            bufferedLog("Aborting delayed skip")
            abortSkip()
        } else {
            if (getMediaController().isMusicActive()) {
                bufferedLog("Music is active, creating delayed skip")
                val event = keyHelper.getMatchingMediaEvent()
                delay(event)
                event.getSecondaryEvent()?.also {
                    delay(it, 2.4)
                }
            }
            bufferedLog("Creating delayed play pause")
            delay(MediaEvent.PlayPause)
        }
    }

    private fun MethodHookParam.handleUnpressedAction(keyHelper: KeyHelper) {
        bufferedLog("Volume unpressed action received, down: ${state.isDownPressed}, up: ${state.isUpPressed}")
        abortAll()
        val mediaController = getMediaController()
        bufferedLog("isMusicActive: ${mediaController.isMusicActive()}")
        if (!state.isLongPress && mediaController.isMusicActive()) {
            bufferedLog("Adjusting stream volume")
            keyHelper.adjustStreamVolume(getHandler())
        }
    }

    private fun hasActiveMediaController(): Boolean {
        val first = getFirstMediaController()
        val active = getMediaController()
        if (first != active && first != null) {
            return !first.isMusicActive()
        }
        return active != null
    }

    private fun getFirstMediaController(): MediaController? {
        return mediaControllers?.firstOrNull()
            ?.also { bufferedLog("First media controller: ${it.packageName}") }
    }

    private fun getMediaController(): MediaController? {
        val filterType = prefs.getAppFilterType()
        val apps = prefs.getApps(filterType)
        return mediaControllers
            ?.sortedByDescending { it.isMusicActive() }
            ?.find {
                when (filterType) {
                    AppFilterType.DISABLED -> true
                    AppFilterType.WHITE_LIST -> it.packageName in apps
                    AppFilterType.BLACK_LIST -> it.packageName !in apps
                }
            }
            ?.also { bufferedLog("Chosen media controller: ${it.packageName}") }
    }

    private fun MediaController?.isMusicActive() = when (this?.playbackState?.state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_BUFFERING -> true

        else -> false
    }

    private fun MethodHookParam.delay(event: MediaEvent, multiplier: Double = 1.0) {
        val handler = getHandler()
        handler.postDelayed(
            getRunnable(event.fieldName),
            (prefs.getLongPressDuration().toDouble() * multiplier).toLong()
        )
    }

    private fun MethodHookParam.abortSkip() {
        bufferedLog("Aborting skip")
        abortEvents(MediaEvent.Prev, MediaEvent.Next)
    }

    private fun MethodHookParam.abortAll() {
        bufferedLog("Aborting all")
        updateState { pendingEvent = null }
        abortEvents(*MediaEvent.entries.toTypedArray())
    }

    private fun MethodHookParam.abortEvents(vararg events: MediaEvent) {
        val handler = getHandler()
        events.forEach { event ->
            handler.removeCallbacks(getRunnable(event.fieldName))
        }
    }

    private fun MethodHookParam.getRunnable(fieldName: String) =
        XposedHelpers.getAdditionalInstanceField(thisObject, fieldName) as Runnable

    private fun MethodHookParam.getContext() = getObjectField("mContext") as Context

    private fun MethodHookParam.getHandler() = getObjectField("mHandler") as Handler

    private fun MethodHookParam.getObjectField(fieldName: String) =
        XposedHelpers.getObjectField(thisObject, fieldName)

    sealed class MediaEvent {
        val fieldName =
            "_volumeKeyControlRunnable_${javaClass.simpleName.replaceFirstChar { it.lowercase() }}"

        protected lateinit var controller: MediaController
        protected lateinit var controls: MediaController.TransportControls

        fun handle(param: MethodHookParam) {
            updateState { isLongPress = true }
            if (initController()) {
                vibrator.triggerVibration()
                if (getSecondaryEvent() != null) {
                    updateState { pendingEvent = this@MediaEvent }
                } else {
                    doHandle(param)
                }
            }
        }

        @Synchronized
        fun doHandle(param: MethodHookParam) {
            val context = param.getContext()
            bufferedLog("canHandle[${this::class.simpleName}]: ${canHandle()}")
            if (canHandle()) {
                bufferedLog("Sending ${this::class.simpleName}")
                param.abortAll()
                doHandle()
                runCatching {
                    RemotePrefsHelper.withRemotePrefs(context) {
                        val count = getLaunchedCount()
                        edit {
                            putInt(LAUNCHED_COUNT, count + 1)
                        }
                    }
                }
            } else {
                bufferedLog("Not sending ${this::class.simpleName}, down: ${state.isDownPressed}, up: ${state.isUpPressed}")
            }
        }

        abstract fun canHandle(): Boolean

        abstract fun doHandle()

        open fun getSecondaryEvent(): MediaEvent? = null

        private fun initController(): Boolean {
            return getMediaController()?.let {
                controller = it
                controls = it.transportControls
                true
            } ?: false
        }

        object PlayPause : MediaEvent() {
            override fun canHandle() = state.isUpPressed && state.isDownPressed

            override fun doHandle() {
                if (controller.isMusicActive()) controls.pause() else controls.play()
            }
        }

        abstract class BaseEvent : MediaEvent() {
            abstract val isUpBtnEvent: Boolean

            abstract fun isPrimary(): Boolean

            override fun canHandle(): Boolean {
                val isPressed = if (isUpBtnEvent) state.isUpPressed else state.isDownPressed
                return if (isPrimary()) !isPressed else true
            }
        }

        abstract class BaseSkipTrackEvent : BaseEvent() {
            override fun isPrimary() =
                prefs.isAddSecondaryAction() && prefs.getRewindActionType() == RewindActionType.TRACK_CHANGE
        }

        abstract class BaseRewindEvent : BaseEvent() {
            override fun isPrimary() =
                prefs.isAddSecondaryAction() && prefs.getRewindActionType() == RewindActionType.REWIND
        }

        object Next : BaseSkipTrackEvent() {
            override val isUpBtnEvent = true

            override fun getSecondaryEvent() = FastForward.takeIf { isPrimary() }

            override fun doHandle() {
                controls.skipToNext()
            }
        }

        object Prev : BaseSkipTrackEvent() {
            override val isUpBtnEvent = false

            override fun getSecondaryEvent() = Rewind.takeIf { isPrimary() }

            override fun doHandle() {
                controls.skipToPrevious()
            }
        }

        object FastForward : BaseRewindEvent() {
            override val isUpBtnEvent = true

            override fun getSecondaryEvent() = Next.takeIf { isPrimary() }

            override fun doHandle() {
                val currentPosition = controller.playbackState?.position ?: 0
                val duration =
                    controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)
                        ?: Long.MAX_VALUE
                val newPosition =
                    min(currentPosition + prefs.getRewindDuration() * 1000, duration)
                controls.seekTo(newPosition)
            }
        }

        object Rewind : BaseRewindEvent() {
            override val isUpBtnEvent = false

            override fun getSecondaryEvent() = Prev.takeIf { isPrimary() }

            override fun doHandle() {
                val currentPosition = controller.playbackState?.position ?: 0
                val newPosition = max(currentPosition - prefs.getRewindDuration() * 1000, 0)
                controls.seekTo(newPosition)
            }
        }

        companion object {
            val entries = listOf(PlayPause, Next, Prev, FastForward, Rewind)
        }
    }

    private class KeyHelper private constructor(private val origKey: Key) {
        private val key = if (prefs.isSwapButtons()) {
            when (origKey) {
                Key.UP -> Key.DOWN
                Key.DOWN -> Key.UP
            }
        } else {
            origKey
        }

        fun getMatchingMediaEvent(): MediaEvent {
            val isTrackChange = prefs.getRewindActionType() == RewindActionType.TRACK_CHANGE
            val isUp = key == Key.UP
            return when {
                isTrackChange && isUp -> MediaEvent.Next
                isTrackChange && !isUp -> MediaEvent.Prev
                !isTrackChange && isUp -> MediaEvent.FastForward
                else -> MediaEvent.Rewind
            }
        }

        fun updateFlags(action: Action) {
            val pressed = action == Action.PRESSED
            updateState {
                when (key) {
                    Key.UP -> isUpPressed = pressed
                    Key.DOWN -> isDownPressed = pressed
                }
            }
        }

        fun adjustStreamVolume(handler: Handler) {
            try {
                val keyCode = when (origKey) {
                    Key.UP -> KeyEvent.KEYCODE_VOLUME_UP
                    Key.DOWN -> KeyEvent.KEYCODE_VOLUME_DOWN
                }

                fun sendEvent(action: Int) {
                    XposedHelpers.callMethod(
                        sessionHelper,
                        "sendVolumeKeyEvent",
                        arrayOf(
                            KeyEvent::class.java,
                            Int::class.javaPrimitiveType,
                            Boolean::class.javaPrimitiveType
                        ),
                        KeyEvent(action, keyCode),
                        AudioManager.STREAM_MUSIC,
                        false
                    )
                }

                sendEvent(KeyEvent.ACTION_DOWN)
                handler.postDelayed({
                    sendEvent(KeyEvent.ACTION_UP)
                }, 20)
            } catch (e: Exception) {
                bufferedLog("Failed to adjust stream volume: ${e.message}")
                bufferedLog("Falling back to adjustStreamVolume")

                val direction = when (origKey) {
                    Key.UP -> AudioManager.ADJUST_RAISE
                    Key.DOWN -> AudioManager.ADJUST_LOWER
                }
                audioManager.adjustStreamVolume(AudioManager.USE_DEFAULT_STREAM_TYPE, direction, 0)
            }
        }

        companion object {
            private enum class Key(val keyCode: Int) {
                UP(KeyEvent.KEYCODE_VOLUME_UP),
                DOWN(KeyEvent.KEYCODE_VOLUME_DOWN),
            }

            fun get(keyCode: Int) =
                Key.entries.find { it.keyCode == keyCode }?.let { KeyHelper(it) }
        }
    }

    private enum class Action(val actionCode: Int) {
        PRESSED(KeyEvent.ACTION_DOWN),
        UNPRESSED(KeyEvent.ACTION_UP);
    }

    private fun updateState(fn: State.Builder.() -> Unit) {
        val builder = State.Builder(state)
        fn.invoke(builder)
        state = builder.build()
        bufferedLog("SETTING NEW STATE: $state")
    }

    private fun updateFlags(event: KeyEvent) {
        event.applyForAction { updateFlags(it) }
    }

    private fun KeyEvent.applyForAction(fn: KeyHelper.(Action) -> Unit) {
        val action = Action.entries.find { it.actionCode == action }
        val keyHelper = KeyHelper.get(keyCode)
        if (keyHelper != null && action != null) {
            fn.invoke(keyHelper, action)
        }
    }
}
