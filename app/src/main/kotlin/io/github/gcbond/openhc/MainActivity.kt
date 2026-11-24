package io.github.gcbond.openhc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import io.github.gcbond.openhc.audio.MicPermission
import io.github.gcbond.openhc.connection.ConnectionController
import io.github.gcbond.openhc.connection.DeviceInfo
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.connection.Phase
import io.github.gcbond.openhc.core.hisight.HiSightInput
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.projection.ProjectionView
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.LogExport
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.ui.HomeActions
import io.github.gcbond.openhc.ui.HomeView
import io.github.gcbond.openhc.ui.SettingsView
import io.github.gcbond.openhc.ui.WirelessActions
import io.github.gcbond.openhc.wireless.WirelessController
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStartFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 唯一的界面：首页、设置、全屏投屏页。连接本身在 [ConnectionController] 和前台服务里，界面关掉不影响。
 *
 * android4-support 分支：从 ComponentActivity + Compose 改成系统 [Activity] + 原生 View。
 * 状态仍由 StateFlow 推过来，这里订阅后在主线程重画当前页面。
 * 权限申请从 registerForActivityResult 改成 requestPermissions / startActivityForResult（兼容安卓 4.1）。
 *
 * 插线拉起、开机自动打开、开机静默连接的逻辑与原版一致，见各方法上的说明。
 */
class MainActivity : Activity() {

    private enum class StartKind { START, USB_ATTACHED, BOOT }

    private enum class Screen { HOME, SETTINGS, PROJECTION }

    private lateinit var settings: AppSettings
    private var pendingStart: StartKind? = null

    private val controller: ConnectionController get() = (application as OpenHcApp).connection
    private val wireless: WirelessController get() = (application as OpenHcApp).wireless

    private lateinit var wirelessStart: WirelessStartFlow

    private lateinit var root: FrameLayout
    private var homeView: HomeView? = null
    private var settingsView: SettingsView? = null
    private var projectionView: ProjectionView? = null

    private var screen = Screen.HOME
    private var projectionWireless = false
    private var autoShownAttempt = 0
    private var autoShownWireless = 0

    private lateinit var identity: IdentityLoader.Result
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        identity = IdentityLoader.load(this)
        wirelessStart = WirelessStartFlow(this) { wireless }

        root = FrameLayout(this).apply { setBackgroundColor(io.github.gcbond.openhc.ui.Palette.Background) }
        setContentView(root)

        if (savedInstanceState == null) handleLaunch(intent)
        observe()
    }

    /**
     * 订阅三份状态（有线、无线、各自的解码器与触摸重置计数）。
     * 每次变化都重画当前页面：页面结构简单，整体重建比做增量更新可靠，也不会有漏更新。
     */
    private fun observe() {
        val j = Job()
        job = j
        val s = CoroutineScope(Dispatchers.Main + j)
        scope = s
        s.launch { controller.state.collect { renderCurrent() } }
        s.launch { wireless.state.collect { renderCurrent() } }
        s.launch { controller.decoder.collect { renderCurrent() } }
        s.launch { wireless.decoder.collect { renderCurrent() } }
        s.launch { controller.touchResets.collect { renderCurrent() } }
        s.launch { wireless.touchResets.collect { renderCurrent() } }
        renderCurrent()
    }

    override fun onDestroy() {
        job?.cancel()
        projectionView?.attachDecoder(null)
        super.onDestroy()
    }

    /**
     * 决定现在显示哪一页。
     *
     * 自动切投屏：任何一边进入投屏就切到投屏页；用户按返回回首页后，同一次连接不再强拉回去（用 attempt 记）。
     * 投屏结束回首页；要输连接码时也从设置页回首页。
     */
    private fun renderCurrent() {
        val wired = controller.state.value
        val w = wireless.state.value

        if (w.phase == WirelessPhase.PROJECTING && autoShownWireless != w.attempt) {
            autoShownWireless = w.attempt
            projectionWireless = true
            screen = Screen.PROJECTION
        }
        if (wired.phase == Phase.PROJECTING && autoShownAttempt != wired.attempt) {
            autoShownAttempt = wired.attempt
            projectionWireless = false
            screen = Screen.PROJECTION
        }
        if (screen == Screen.PROJECTION) {
            val still = if (projectionWireless) w.phase == WirelessPhase.PROJECTING else wired.phase == Phase.PROJECTING
            if (!still) screen = Screen.HOME
        }
        if (wired.phase == Phase.PAIRING && screen == Screen.SETTINGS) screen = Screen.HOME

        when (screen) {
            Screen.HOME -> showHome(wired, w)
            Screen.SETTINGS -> showSettings()
            Screen.PROJECTION -> showProjection()
        }
    }

    private fun showHome(
        wired: io.github.gcbond.openhc.connection.ConnectionUiState,
        w: io.github.gcbond.openhc.wireless.WirelessUiState,
    ) {
        detachProjection()
        val view = homeView ?: HomeView(this).also { homeView = it }
        view.render(
            wired = wired,
            wireless = w,
            identity = identity,
            actions = HomeActions(
                onStart = { requestStart(StartKind.START) },
                onStop = { controller.stop() },
                onRetryNow = { controller.retryNow() },
                onGrantVpn = { requestStart(StartKind.START) },
                onResetTrustStore = { controller.resetTrustStoreAndStart() },
            ),
            wirelessActions = WirelessActions(
                onStart = { wirelessStart.start() },
                onStop = { wireless.stop() },
                onShowProjection = {
                    if (w.phase == WirelessPhase.PROJECTING) {
                        projectionWireless = true
                        screen = Screen.PROJECTION
                        renderCurrent()
                    }
                },
                onOpenSettings = { screen = Screen.SETTINGS; renderCurrent() },
                onOpenAppSettings = { wirelessStart.openAppSettings() },
                onEnableBluetooth = { wirelessStart.requestEnableBluetooth() },
                onResetTrustStore = { wireless.resetTrustStoreAndStart() },
            ),
            onShowProjection = {
                if (wired.phase == Phase.PROJECTING) {
                    projectionWireless = false
                    screen = Screen.PROJECTION
                    renderCurrent()
                }
            },
            onOpenSettings = { screen = Screen.SETTINGS; renderCurrent() },
        )
        setContent(view)
    }

    private fun showSettings() {
        detachProjection()
        val view = settingsView ?: SettingsView(this).also { settingsView = it }
        view.render(
            settings = settings,
            systemBluetoothAddress = DeviceInfo.systemBluetoothAddress(this),
            // 「自动读取」按钮：用户手动触发，把所有来源的候选值都列出来让车主挑。
            onReadCandidates = { DeviceInfo.bluetoothAddressCandidates(this) },
            onBack = {
                wireless.onSettingsClosed()
                screen = Screen.HOME
                renderCurrent()
            },
        )
        setContent(view)
    }

    private fun showProjection() {
        val decoder = if (projectionWireless) wireless.decoder.value else controller.decoder.value
        val state = if (projectionWireless) wireless.state.value.projection else controller.state.value
        val resets = if (projectionWireless) wireless.touchResets.value else controller.touchResets.value

        val view = projectionView ?: ProjectionView(this).also { projectionView = it }
        if (view.parent != null) (view.parent as ViewGroup).removeView(view)
        view.fullScreen = io.github.gcbond.openhc.connection.DisplayArea.activeFullScreen
        view.immersive = true
        view.setVideoSize(state.videoWidth, state.videoHeight)
        view.onInput = { input: HiSightInput ->
            if (projectionWireless) wireless.sendInput(input) else controller.sendInput(input)
        }
        view.attachDecoder(decoder)
        // touchResets 变了就重置一次触摸状态（发送端报告触摸对不上时）。
        if (view.tag != resets) {
            view.tag = resets
            view.resetTouches()
        }

        val frame = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        frame.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val message = when {
            !state.firstFrame -> getString(R.string.projection_waiting_frame)
            state.decoderProblem != null -> getString(R.string.projection_decoder_problem, state.decoderProblem)
            else -> ""
        }
        if (message.isNotEmpty()) {
            frame.addView(
                TextView(this).apply {
                    text = message
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 15f
                    setBackgroundColor(0xAA000000.toInt())
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    leftMargin = dp(12)
                    topMargin = dp(12)
                },
            )
        }
        setContent(frame)
    }

    /** 换页时把投屏视图从窗口上摘下来（Surface 还给解码器前要先断开）。 */
    private fun detachProjection() {
        projectionView?.let { view ->
            if (view.parent != null) (view.parent as ViewGroup).removeView(view)
            view.attachDecoder(null)
        }
    }

    private fun setContent(view: android.view.View) {
        if (root.childCount == 1 && root.getChildAt(0) === view) return
        root.removeAllViews()
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    override fun onResume() {
        super.onResume()
        // 记下底部导航栏的实际高度（不管此刻显不显示），「全屏显示」关时按它让出画面、算声明尺寸。
        root.post {
            val bar = navigationBarHeight()
            if (bar > 0) io.github.gcbond.openhc.connection.DisplayArea.measuredNavigationBarHeight = bar
        }
        wireless.onAppForeground()
    }

    private fun navigationBarHeight(): Int {
        val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /**
     * 界面没处理的方向盘、媒体键交给 [io.github.gcbond.openhc.input.MediaKeys]：有能发键的连接时转给手机并吃掉，
     * 系统就不再把它派给别的媒体应用；没有连接时照常交给系统。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        io.github.gcbond.openhc.input.MediaKeys.onWindowKey(event) || super.onKeyUp(keyCode, event)

    /**
     * 返回键：设置页、投屏页都回首页；投屏时连接保持，可以再回到投屏画面。
     *
     * android4-support 分支：安卓 4.x 没有 OnBackPressedDispatcher / 预测性返回，只能走这个旧回调。
     * lint 的 GestureBackNavigation 是针对新系统的提醒，这里按设计忽略。
     */
    @Deprecated("安卓 4.x 上没有 OnBackPressedDispatcher")
    @SuppressLint("GestureBackNavigation")
    override fun onBackPressed() {
        when (screen) {
            Screen.SETTINGS -> {
                wireless.onSettingsClosed()
                screen = Screen.HOME
                renderCurrent()
            }
            Screen.PROJECTION -> {
                screen = Screen.HOME
                renderCurrent()
            }
            Screen.HOME -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        if (BootStart.isBootLaunch(intent)) {
            if (BootStart.waitForCable(this)) {
                Log.i(TAG, "开机自动打开：开始等插线")
                requestStart(StartKind.BOOT)
            } else {
                Log.i(TAG, "开机自动打开：不等插线（插线时由系统拉起本应用再开始有线），无线自动连手机")
            }
        } else if (intent?.action == BootStartPolicy.ACTION_SHOW_PROJECTION) {
            Log.i(TAG, "开机静默连接：手机连上了，界面带到前台")
        } else {
            handleUsbAttach(intent)
        }
    }

    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // 任务最初是插线拉起的话，从「最近任务」重开时系统会把那次的 Intent 再给一遍，那不是插线。
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        // 被「设备接入」拉起，说明用户在系统框里同意过用本应用打开手机：之后有线会话先等系统自动授权。
        settings.usbAttachAccepted = true
        Log.i(TAG, "插线拉起")
        requestStart(StartKind.USB_ATTACHED)
    }

    private fun requestStart(kind: StartKind) {
        if (wireless.blocksWired) {
            // 两种连接同时只能有一种：插线拉起时只记一笔，用户点开始时给出原因。
            Log.i(TAG, "无线连接正在进行，不开始有线连接（$kind）")
            if (kind == StartKind.START) {
                controller.reportBlocked(Notice.StartFailed(getString(R.string.wireless_notice_wireless_active)))
            }
            return
        }
        pendingStart = kind
        askNextPermission()
    }

    /** 开始前的运行时权限按顺序问，每样只自动问一次；问完（同意与否）接着 [continueStart]。 */
    private fun askNextPermission() {
        if (pendingStart == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !settings.notificationPermissionAsked &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            settings.notificationPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
            return
        }
        val audio = AudioPreferences(this)
        if (audio.micEnabled && !audio.micPermissionAsked && !MicPermission.granted(this)) {
            audio.micPermissionAsked = true
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        continueStart()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_NOTIFICATION -> askNextPermission()
            REQ_MIC -> {
                if (grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
                    Log.i(TAG, "用户没有给录音权限：这次不声明车机麦克风")
                    Toast.makeText(this, R.string.mic_permission_denied, Toast.LENGTH_LONG).show()
                }
                askNextPermission()
            }
        }
    }

    private fun continueStart() {
        val kind = pendingStart ?: return
        when (val consent = VpnConsent.check(this)) {
            VpnConsent.Status.Granted -> {
                pendingStart = null
                dispatch(kind)
            }
            is VpnConsent.Status.NeedsUserConsent -> try {
                @Suppress("DEPRECATION")
                startActivityForResult(consent.intent, REQ_VPN)
            } catch (e: ActivityNotFoundException) {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(getString(R.string.vpn_dialog_missing)))
            }
            is VpnConsent.Status.Unavailable -> {
                pendingStart = null
                controller.reportBlocked(Notice.VpnUnavailable(consent.reason))
            }
        }
    }

    @Deprecated("安卓 4.x 上没有 Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        // 设置页「导出日志」：系统「另存为」界面选完目录回来，把日志写进去。
        if (requestCode == LogExport.REQ_SAVE) {
            val uri = if (resultCode == RESULT_OK) data?.data else null
            Toast.makeText(this, LogExport.finishSave(this, uri), Toast.LENGTH_LONG).show()
            return
        }
        if (requestCode != REQ_VPN) return
        val kind = pendingStart
        pendingStart = null
        if (kind == null) return
        if (resultCode == RESULT_OK && VpnConsent.isGranted(this)) {
            dispatch(kind)
        } else {
            Log.i(TAG, "用户没有同意 VPN 授权")
            controller.reportBlocked(Notice.VpnConsentRequired)
        }
    }

    private fun dispatch(kind: StartKind) {
        // 无线在被动等已配对的手机回连的话先让出。
        wireless.yieldToWired()
        when (kind) {
            StartKind.START, StartKind.BOOT -> controller.start()
            StartKind.USB_ATTACHED -> controller.onUsbAttached()
        }
    }

    private companion object {
        const val TAG = "OpenHC-App"
        const val REQ_NOTIFICATION = 1001
        const val REQ_MIC = 1002
        const val REQ_VPN = 1003
    }
}
