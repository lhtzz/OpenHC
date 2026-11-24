package io.github.gcbond.openhc.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.gcbond.openhc.BuildConfig
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.BluetoothAddressCandidate
import io.github.gcbond.openhc.connection.BluetoothAddressPolicy
import io.github.gcbond.openhc.connection.BluetoothSource
import io.github.gcbond.openhc.connection.DisplayPlan
import io.github.gcbond.openhc.connection.ScreenSpec
import io.github.gcbond.openhc.connection.UsbAutoConnect
import io.github.gcbond.openhc.connection.VideoClarity
import io.github.gcbond.openhc.platform.media.AudioPreview
import io.github.gcbond.openhc.platform.media.AudioStreamChoice
import io.github.gcbond.openhc.platform.media.AudioUsage
import io.github.gcbond.openhc.platform.net.VpnConsent
import io.github.gcbond.openhc.settings.AppSettings
import io.github.gcbond.openhc.settings.AudioPreferences
import io.github.gcbond.openhc.settings.ClarityPreferences
import io.github.gcbond.openhc.settings.MediaKeyPreferences
import io.github.gcbond.openhc.settings.PcmPhoneRecord
import io.github.gcbond.openhc.settings.WirelessNetworkPreferences
import io.github.gcbond.openhc.settings.WirelessPreferences
import io.github.gcbond.openhc.startup.BootOutcome
import io.github.gcbond.openhc.startup.BootRecord
import io.github.gcbond.openhc.startup.BootStart
import io.github.gcbond.openhc.startup.BootStartPolicy
import io.github.gcbond.openhc.wireless.LePairingFailure
import io.github.gcbond.openhc.wireless.LePairingRules
import io.github.gcbond.openhc.wireless.LePairingSession
import io.github.gcbond.openhc.wireless.LePairingStage
import io.github.gcbond.openhc.wireless.LePairingStatus
import io.github.gcbond.openhc.wireless.WirelessNetworkPlan
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * 设置页：画面、方向盘按键、连接方式、启动、声音（实验）、诊断。
 *
 * android4-support 分支：从 Compose 改成原生 View。结构与原版一致：
 * 顶栏「设置」+ 生效说明 + 「返回」；宽屏两列（左：画面、方向盘按键；右：连接方式、启动、声音、诊断），窄屏一列。
 * 每次 [render] 重建整页 —— 设置项都是即时生效的，重建最简单可靠。
 */
internal class SettingsView(context: Context) : ScrollView(context) {

    private val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var onBackAction: () -> Unit = {}
    private var renderFn: (() -> Unit)? = null
    private var pendingLePairing = false

    init {
        setBackgroundColor(Palette.Background)
        isFillViewport = true
        addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun render(
        settings: AppSettings,
        systemBluetoothAddress: String?,
        onReadCandidates: () -> List<BluetoothAddressCandidate>,
        onBack: () -> Unit,
    ) {
        onBackAction = onBack
        val wide = resources.displayMetrics.widthPixels >= context.dp(760f)
        val compact = resources.displayMetrics.heightPixels < context.dp(Dimens.CompactHeight)
        val side = context.dp(if (resources.displayMetrics.widthPixels >= context.dp(Dimens.WIDE_PAGE_MIN)) Dimens.PageSide else Dimens.PageSideNarrow)

        // 重画用同一个入口：设置项改完调 rebuild() 刷新界面。
        val rebuild = {
            render(settings, systemBluetoothAddress, onReadCandidates, onBack)
        }
        renderFn = rebuild

        root.removeAllViews()
        root.setPadding(side, 0, side, 0)
        root.addView(topBar())

        val left = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(pictureGroup(settings, rebuild, compact))
        left.addView(spacer(if (compact) 12 else 20))
        left.addView(mediaKeyGroup(rebuild))

        val right = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        right.addView(connectionGroup(settings, systemBluetoothAddress, onReadCandidates, rebuild, compact))
        right.addView(spacer(if (compact) 12 else 20))
        right.addView(bootGroup(settings, rebuild, compact))
        right.addView(spacer(if (compact) 12 else 20))
        right.addView(audioGroup(rebuild, compact))
        right.addView(spacer(if (compact) 12 else 20))
        right.addView(diagnosticGroup())

        if (wide) {
            val columns = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            columns.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            columns.addView(spacer(16, horizontal = true))
            columns.addView(right, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(columns)
        } else {
            root.addView(left)
            root.addView(spacer(12))
            root.addView(right)
        }

        root.addView(footer(compact), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(12f)
            bottomMargin = context.dp(16f)
        })
    }

    private fun rebuild() {
        renderFn?.invoke()
    }

    private fun topBar(): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(pageTitle(context, context.getString(R.string.settings_title)))
        row.addView(
            context.textView(context.getString(R.string.settings_apply_hint), sizeSp = 12f, color = Palette.Muted),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = context.dp(16f)
            },
        )
        row.addView(topBarButton(context, context.getString(R.string.action_back)) { onBackAction() })
        return row
    }

    private fun spacer(heightDp: Int, horizontal: Boolean = false): View = View(context).apply {
        layoutParams = if (horizontal) {
            LinearLayout.LayoutParams(context.dp(heightDp.toFloat()), 1)
        } else {
            LinearLayout.LayoutParams(1, context.dp(heightDp.toFloat()))
        }
    }

    private fun footer(compact: Boolean): View =
        context.textView(
            context.getString(R.string.home_version, BuildConfig.VERSION_NAME) + "\n" + context.getString(R.string.home_tagline),
            sizeSp = if (compact) 11f else 12f,
            color = Palette.Muted,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL }

    // ---------- 画面 ----------

    private fun pictureGroup(settings: AppSettings, rebuild: () -> Unit, compact: Boolean): View {
        val rows = mutableListOf<View>()

        // 帧率：自动 + 固定几档。
        val fpsValues = listOf(0) + DisplayPlan.FPS_CHOICES
        val fpsLabels = fpsValues.map {
            if (it == 0) context.getString(R.string.settings_choice_auto) else context.getString(R.string.settings_fps_fixed, it)
        }
        rows += choiceRow(
            title = context.getString(R.string.settings_fps),
            options = fpsLabels,
            selected = fpsValues.indexOf(settings.fps).coerceAtLeast(0),
            description = null,
        ) { index ->
            settings.fps = fpsValues[index]
            rebuild()
        }

        // 清晰度。
        val clarityPrefs = ClarityPreferences(context)
        val clarities = VideoClarity.entries
        rows += choiceRow(
            title = context.getString(R.string.settings_clarity),
            options = clarities.map { context.getString(clarityShort(it)) },
            selected = clarities.indexOf(clarityPrefs.clarity).coerceAtLeast(0),
            description = clarityLabel(clarityPrefs.clarity),
        ) { index ->
            clarityPrefs.clarity = clarities[index]
            rebuild()
        }

        // 界面大小：只缩小（100 / 85 / 75 / 65%）。
        val scaleValues = DisplayPlan.UI_SCALE_CHOICES
        rows += choiceRow(
            title = context.getString(R.string.settings_ui_scale),
            options = scaleValues.map { context.getString(R.string.settings_ui_scale_value, it) },
            selected = scaleValues.indexOf(settings.uiScalePercent).coerceAtLeast(0),
            description = context.getString(R.string.settings_ui_scale_hint),
        ) { index ->
            settings.uiScalePercent = scaleValues[index]
            rebuild()
        }

        // 全屏显示。
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_full_screen),
            description = context.getString(R.string.settings_full_screen_hint),
            checked = settings.fullScreen,
        ) {
            settings.fullScreen = it
            rebuild()
        }

        return settingsGroup(context, context.getString(R.string.settings_group_picture), rows)
    }

    private fun clarityShort(clarity: VideoClarity): Int = when (clarity) {
        VideoClarity.STANDARD -> R.string.settings_clarity_standard_short
        VideoClarity.HIGH -> R.string.settings_clarity_high_short
        VideoClarity.HIGHEST -> R.string.settings_clarity_highest_short
    }

    private fun clarityLabel(clarity: VideoClarity): String {
        val mbps = (clarity.bitrate ?: 0) / 1_000_000
        return when (clarity) {
            VideoClarity.STANDARD -> context.getString(R.string.settings_clarity_standard)
            VideoClarity.HIGH -> context.getString(R.string.settings_clarity_high, mbps)
            VideoClarity.HIGHEST -> context.getString(R.string.settings_clarity_highest, mbps)
        }
    }

    /** 一行标题 + 说明 + 一排分段选项。 */
    private fun choiceRow(
        title: String,
        options: List<String>,
        selected: Int,
        description: String?,
        onSelect: (Int) -> Unit,
    ): View {
        val inner = context.column()
        inner.addView(context.textView(title, sizeSp = 15f))
        if (description != null) {
            inner.addView(context.textView(description, sizeSp = 13f, color = Palette.Muted).apply {
                setPadding(0, context.dp(3f), 0, 0)
            })
        }
        inner.addView(segmented(context, options, selected, onSelect), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(8f) })
        return inner.apply { setPadding(0, context.dp(8f), 0, context.dp(8f)) }
    }

    // ---------- 方向盘按键 ----------

    private fun mediaKeyGroup(rebuild: () -> Unit): View {
        val prefs = MediaKeyPreferences(context)
        val row = switchRow(
            context = context,
            title = context.getString(R.string.settings_media_keys_enabled),
            description = context.getString(R.string.settings_media_keys_hint),
            checked = prefs.enabled,
        ) {
            prefs.enabled = it
            rebuild()
        }
        return settingsGroup(context, context.getString(R.string.settings_media_keys), listOf(row))
    }

    // ---------- 连接方式 ----------

    private fun connectionGroup(
        settings: AppSettings,
        systemBluetoothAddress: String?,
        onReadCandidates: () -> List<BluetoothAddressCandidate>,
        rebuild: () -> Unit,
        compact: Boolean,
    ): View {
        val wirelessPrefs = WirelessPreferences(context)
        val rows = mutableListOf<View>()

        // 只用数据线连接。
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_wireless_wired_only),
            description = context.getString(R.string.settings_wireless_wired_only_hint),
            checked = wirelessPrefs.wiredOnly,
        ) { value ->
            wirelessPrefs.wiredOnly = value
            (context.applicationContext as? io.github.gcbond.openhc.OpenHcApp)?.wireless?.onWiredOnlyChanged()
            rebuild()
        }

        // 车机蓝牙地址。
        rows += bluetoothAddressRow(settings, systemBluetoothAddress, onReadCandidates, rebuild)

        // 补全手机蓝牙配对。
        rows += settingsRow(
            context,
            context.getString(R.string.settings_le_pair),
            context.getString(R.string.settings_le_pair_hint),
            actionButton(context, context.getString(R.string.settings_le_pair_value)) { openLePairingDialog() },
        )

        // 插线时不再弹窗。
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_usb_auto),
            description = context.getString(
                if (UsbAutoConnect.isEnabled(context)) R.string.settings_usb_auto_hint_on else R.string.settings_usb_auto_hint_off,
            ),
            checked = UsbAutoConnect.isEnabled(context),
        ) { value ->
            UsbAutoConnect.setEnabled(context, value)
            rebuild()
        }

        // 退出后恢复车机 WiFi。
        val netPrefs = WirelessNetworkPreferences(context)
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_wireless_restore),
            description = context.getString(R.string.settings_wireless_restore_hint),
            checked = netPrefs.restoreWifiOnExit,
        ) { value ->
            netPrefs.restoreWifiOnExit = value
            rebuild()
        }

        // 用「车机自带热点」。
        rows += carHotspotRow(wirelessPrefs, rebuild)

        return settingsGroup(context, context.getString(R.string.settings_group_connection), rows)
    }

    /**
     * 车机自带热点：开关 + 热点名 / 密码 / 频段。开着时 OpenHC 不再自己开热点，用车主在车机设置里开好的那个。
     * 安卓 4.x～7.x 上反射调 setWifiApEnabled 成功率低，这条路最稳。
     */
    private fun carHotspotRow(wirelessPrefs: WirelessPreferences, rebuild: () -> Unit): View {
        val enabled = wirelessPrefs.carHotspot
        val inner = context.column()

        val toggle = PillSwitchView(context).apply {
            isChecked = enabled
            setOnCheckedChange { value ->
                wirelessPrefs.carHotspot = value
                rebuild()
            }
        }
        inner.addView(
            settingsRow(
                context,
                context.getString(R.string.settings_car_hotspot),
                context.getString(R.string.settings_car_hotspot_hint),
                toggle,
            ),
        )

        if (enabled) {
            val nameInput = EditText(context).apply {
                hint = context.getString(R.string.settings_car_hotspot_name)
                setText(wirelessPrefs.carHotspotName)
                inputType = InputType.TYPE_CLASS_TEXT
                setSingleLine(true)
                setTextColor(Palette.Text)
                setHintTextColor(Palette.Muted)
            }
            val passwordInput = EditText(context).apply {
                hint = context.getString(R.string.settings_car_hotspot_password)
                setText(wirelessPrefs.carHotspotPassword)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setTextColor(Palette.Text)
                setHintTextColor(Palette.Muted)
            }
            inner.addView(nameInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            })
            inner.addView(passwordInput, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6f)
            })

            val saveRow = context.row()
            saveRow.addView(actionButton(context, context.getString(R.string.settings_car_hotspot_save)) {
                val name = nameInput.text.toString().trim()
                val password = passwordInput.text.toString()
                if (name.isEmpty() || password.isEmpty()) {
                    Toast.makeText(context, R.string.settings_car_hotspot_save, Toast.LENGTH_SHORT).show()
                } else {
                    wirelessPrefs.carHotspotName = name
                    wirelessPrefs.carHotspotPassword = password
                    Toast.makeText(context, R.string.settings_car_hotspot_saved, Toast.LENGTH_SHORT).show()
                    rebuild()
                }
            })
            inner.addView(saveRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(8f)
            })

            inner.addView(
                switchRow(
                    context,
                    context.getString(R.string.settings_car_hotspot_5g),
                    null,
                    wirelessPrefs.carHotspotFiveGhz,
                ) { value -> wirelessPrefs.carHotspotFiveGhz = value },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = context.dp(6f)
                },
            )
        }
        return inner.apply { setPadding(0, context.dp(8f), 0, context.dp(8f)) }
    }

    /** 车机蓝牙地址：显示当前值 + 来源；点开展开手动填写、保存、清除、自动读取。 */
    private fun bluetoothAddressRow(
        settings: AppSettings,
        systemBluetoothAddress: String?,
        onReadCandidates: () -> List<BluetoothAddressCandidate>,
        rebuild: () -> Unit,
    ): View {
        val savedManual = settings.manualBluetoothAddress
        val current = BluetoothAddressPolicy.choose(systemBluetoothAddress, savedManual)
        val address = current.address

        val inner = context.column()
        val titleRow = context.row()
        titleRow.addView(
            context.column().apply {
                addView(context.textView(context.getString(R.string.settings_bt), sizeSp = 15f))
                addView(
                    context.textView(address ?: context.getString(R.string.settings_bt_value_missing), sizeSp = 14f, color = if (address == null) Palette.Warn else Palette.Text).apply {
                        setPadding(0, context.dp(2f), 0, 0)
                    },
                )
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        inner.addView(titleRow)

        val source = when (current.source) {
            BluetoothSource.SYSTEM_PROPERTY -> context.getString(R.string.settings_bt_current_property, address ?: "")
            BluetoothSource.MANUAL -> context.getString(R.string.settings_bt_current_manual, address ?: "")
            BluetoothSource.UNAVAILABLE -> context.getString(R.string.settings_bt_current_unavailable)
        }
        inner.addView(context.textView(source + "\n" + context.getString(R.string.settings_bt_hint), sizeSp = 12f, color = Palette.Muted).apply {
            setPadding(0, context.dp(4f), 0, 0)
        })

        // 手动填写。
        val input = EditText(context).apply {
            hint = context.getString(R.string.settings_bt_manual_label)
            setText(savedManual ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(17))
            setSingleLine(true)
            setTextColor(Palette.Text)
            setHintTextColor(Palette.Muted)
        }
        inner.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8f)
        })

        val buttonRow = context.row()
        buttonRow.addView(actionButton(context, context.getString(R.string.action_save)) {
            val normalized = BluetoothAddressPolicy.normalize(input.text.toString())
            if (normalized != null) {
                settings.manualBluetoothAddress = normalized
                rebuild()
            } else {
                Toast.makeText(context, R.string.settings_bt_invalid, Toast.LENGTH_SHORT).show()
            }
        })
        buttonRow.addView(actionButton(context, context.getString(R.string.action_clear)) {
            settings.manualBluetoothAddress = null
            rebuild()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(12f)
        })
        buttonRow.addView(actionButton(context, context.getString(R.string.action_read_bt_address)) {
            val found = onReadCandidates()
            if (found.isEmpty()) {
                Toast.makeText(context, R.string.settings_bt_read_not_found, Toast.LENGTH_LONG).show()
            } else {
                // 列出所有候选值让车主对着「关于本机」挑。
                showCandidates(found, input)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(12f)
        })
        inner.addView(buttonRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8f)
        })
        return inner.apply { setPadding(0, context.dp(8f), 0, context.dp(8f)) }
    }

    /** 自动读到的候选值：点一个填进输入框。 */
    private fun showCandidates(candidates: List<BluetoothAddressCandidate>, input: EditText) {
        val text = candidates.joinToString("\n") {
            context.getString(
                if (it.usable) R.string.settings_bt_candidate else R.string.settings_bt_candidate_unusable,
                it.address,
                it.source,
            )
        }
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.settings_bt_read_verify)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                candidates.firstOrNull { it.usable }?.let { input.setText(it.address) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---------- 启动 ----------

    private fun bootGroup(settings: AppSettings, rebuild: () -> Unit, compact: Boolean): View {
        val rows = mutableListOf<View>()

        val enabled = BootStart.isEnabled(context)
        val silent = BootStart.isSilent(context)
        val wirelessPrefs = WirelessPreferences(context)

        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_boot),
            description = context.getString(R.string.settings_boot_hint),
            checked = enabled,
        ) { value ->
            BootStart.setEnabled(context, value)
            rebuild()
        }

        // 自动连接手机。
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_wireless_connect_on_bt),
            description = context.getString(R.string.settings_wireless_connect_on_bt_hint),
            checked = wirelessPrefs.connectOnBluetooth,
        ) { value ->
            wirelessPrefs.connectOnBluetooth = value
            (context.applicationContext as? io.github.gcbond.openhc.OpenHcApp)?.wireless?.onConnectOnBluetoothChanged()
            rebuild()
        }

        // 开机静默连接。
        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_boot_silent),
            description = context.getString(R.string.settings_boot_silent_hint),
            checked = silent,
        ) { value ->
            BootStart.setSilent(context, value)
            rebuild()
        }

        // 悬浮窗权限状态。
        if (BootStartPolicy.overlayRequired(Build.VERSION.SDK_INT)) {
            if (BootStart.canDrawOverlays(context)) {
                rows += rowDescription(context, context.getString(R.string.settings_boot_overlay_granted))
            } else {
                rows += settingsRow(
                    context,
                    context.getString(R.string.settings_boot_overlay_missing),
                    null,
                    actionButton(context, context.getString(R.string.settings_boot_open_app_settings)) {
                        openFirstAvailable(listOf(BootStart.overlaySettingsIntent(context), BootStart.appDetailsIntent(context)))
                    },
                )
            }
        } else {
            rows += rowDescription(context, context.getString(R.string.settings_boot_overlay_not_needed, Build.VERSION.RELEASE))
        }

        // 自启动管理。
        val byd = BootStart.bydAutostartIntent(context)
        if (byd != null) {
            rows += settingsRow(
                context,
                context.getString(R.string.settings_boot_byd_title),
                context.getString(R.string.settings_boot_byd_note),
                actionButton(context, context.getString(R.string.settings_boot_open_byd)) {
                    openFirstAvailable(listOf(byd, BootStart.appDetailsIntent(context)))
                },
            )
        } else {
            rows += settingsRow(
                context,
                context.getString(R.string.settings_boot_autostart_title),
                context.getString(R.string.settings_boot_autostart_note),
                actionButton(context, context.getString(R.string.settings_boot_open_app_settings)) {
                    openFirstAvailable(listOf(BootStart.appDetailsIntent(context)))
                },
            )
        }

        // 上次开机的结果。
        val record = BootStart.lastRecord(context)
        if (enabled && record != null) {
            rows += rowDescription(context, lastBootText(record))
        }

        return settingsGroup(context, context.getString(R.string.settings_boot_title), rows)
    }

    private fun lastBootText(record: BootRecord): String {
        val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(record.atMillis))
        val kind = if (BootStartPolicy.isFreshBoot(record.sinceBootMillis)) {
            context.getString(R.string.settings_boot_fresh)
        } else {
            context.getString(R.string.settings_boot_resent, record.sinceBootMillis / 60_000)
        }
        val outcome = context.getString(
            when (record.outcome) {
                BootOutcome.PENDING -> R.string.settings_boot_outcome_pending
                BootOutcome.OPENED -> R.string.settings_boot_outcome_opened
                BootOutcome.COVERED -> R.string.settings_boot_outcome_covered
                BootOutcome.BLOCKED_NO_OVERLAY -> R.string.settings_boot_outcome_no_overlay
                BootOutcome.NOT_SHOWN -> R.string.settings_boot_outcome_not_shown
                BootOutcome.FAILED -> R.string.settings_boot_outcome_failed
                BootOutcome.SILENT_WIRED -> R.string.settings_boot_outcome_silent_wired
                BootOutcome.SILENT_WIRELESS -> R.string.settings_boot_outcome_silent_wireless
                BootOutcome.SILENT_STANDBY -> R.string.settings_boot_outcome_silent_standby
            },
        )
        return context.getString(R.string.settings_boot_last, time, kind, outcome)
    }

    /** 依次尝试打开几个系统页，都打不开就提示。 */
    private fun openFirstAvailable(intents: List<Intent>) {
        for (intent in intents) {
            try {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // 换下一个。
            } catch (e: SecurityException) {
                // 同上。
            }
        }
        Toast.makeText(context, R.string.settings_boot_no_settings_page, Toast.LENGTH_LONG).show()
    }

    // ---------- 声音（实验） ----------

    private fun audioGroup(rebuild: () -> Unit, compact: Boolean): View {
        val prefs = AudioPreferences(context)
        val rows = mutableListOf<View>()

        rows += switchRow(
            context = context,
            title = context.getString(R.string.settings_audio_enabled),
            description = context.getString(R.string.settings_audio_hint),
            checked = prefs.enabled,
        ) { value ->
            prefs.enabled = value
            rebuild()
        }

        // 声音延后。
        val delays = AudioPreferences.DELAY_CHOICES
        rows += choiceRow(
            title = context.getString(R.string.settings_audio_delay),
            options = delays.map {
                if (it == 0) context.getString(R.string.settings_audio_delay_min) else context.getString(R.string.settings_audio_delay_plus, it)
            },
            selected = delays.indexOf(prefs.delayMillis).coerceAtLeast(0),
            description = context.getString(R.string.settings_audio_delay_hint),
        ) { index ->
            prefs.delayMillis = delays[index]
            rebuild()
        }

        // 导航声道、音乐声道（带试听）。
        rows += streamRow(
            title = context.getString(R.string.settings_audio_navigation_stream),
            description = context.getString(R.string.settings_audio_navigation_stream_hint),
            usage = AudioUsage.NAVIGATION,
            selected = prefs.navigationStream,
            lowLatency = prefs.lowLatency,
        ) { prefs.navigationStream = it; rebuild() }

        rows += streamRow(
            title = context.getString(R.string.settings_audio_media_stream),
            description = context.getString(R.string.settings_audio_media_stream_hint),
            usage = AudioUsage.MEDIA,
            selected = prefs.mediaStream,
            lowLatency = prefs.lowLatency,
        ) { prefs.mediaStream = it; rebuild() }

        // 已自动改用 AAC 的手机。
        val fallbacks = prefs.pcmFallbacks().records.filter { it.useAac }
        if (fallbacks.isEmpty()) {
            rows += rowDescription(context, context.getString(R.string.settings_audio_fallback_none))
        } else {
            fallbacks.forEach { record ->
                rows += settingsRow(
                    context,
                    context.getString(R.string.settings_audio_fallback_title),
                    fallbackText(record),
                    actionButton(context, context.getString(R.string.settings_audio_fallback_reset)) {
                        prefs.forgetPcmFallback(record.key)
                        rebuild()
                    },
                )
            }
        }

        return settingsGroup(context, context.getString(R.string.settings_audio), rows)
    }

    private fun fallbackText(record: PcmPhoneRecord): String {
        val name = record.name ?: context.getString(R.string.settings_audio_fallback_unnamed)
        val date = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(record.updatedAtMillis))
        return context.getString(
            R.string.settings_audio_fallback_item,
            name,
            record.key.take(6),
            record.lastFailure ?: "",
            date,
        )
    }

    /** 一路声音放到哪个声道：标题/说明/「试听」+ 一排能左右滑的声道。 */
    private fun streamRow(
        title: String,
        description: String,
        usage: AudioUsage,
        selected: Int,
        lowLatency: Boolean,
        onSelect: (Int) -> Unit,
    ): View {
        val inner = context.column()
        val head = context.row()
        head.addView(
            context.column().apply {
                addView(context.textView(title, sizeSp = 15f))
                addView(context.textView(description, sizeSp = 12f, color = Palette.Muted).apply {
                    setPadding(0, context.dp(2f), 0, 0)
                })
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        val note = context.textView("", sizeSp = 12f, color = Palette.Muted)
        note.visibility = View.GONE
        head.addView(actionButton(context, context.getString(R.string.settings_audio_preview)) {
            AudioPreview.play(usage, selected, lowLatency) { result ->
                Log.i("OpenHC-Audio", "试听${usage.label}：声道 ${AudioStreamChoice.label(result.stream)}")
                Handler(Looper.getMainLooper()).post {
                    note.text = previewText(result)
                    note.visibility = View.VISIBLE
                }
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = context.dp(12f)
        })
        inner.addView(head)
        inner.addView(note)

        // 声道：自动 + 1..20，横向可滑。
        val scroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            background = context.roundedBackground(Palette.SegmentBackground, 8f)
        }
        val chips = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(context.dp(3f), context.dp(3f), context.dp(3f), context.dp(3f))
        }
        val choices = listOf(AudioStreamChoice.AUTO) + (AudioStreamChoice.MIN..AudioStreamChoice.MAX)
        choices.forEach { value ->
            val on = value == selected
            chips.addView(
                context.textView(
                    if (value == AudioStreamChoice.AUTO) context.getString(R.string.settings_audio_stream_auto) else value.toString(),
                    sizeSp = 13f,
                    color = if (on) Palette.Text else Palette.Muted,
                    bold = on,
                ).apply {
                    gravity = Gravity.CENTER
                    setPadding(context.dp(14f), context.dp(7f), context.dp(14f), context.dp(7f))
                    if (on) background = context.roundedBackground(Palette.On, 6f)
                    isClickable = true
                    setOnClickListener { onSelect(value) }
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    rightMargin = context.dp(2f)
                },
            )
        }
        scroll.addView(chips)
        inner.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = context.dp(8f)
        })
        return inner.apply { setPadding(0, context.dp(8f), 0, context.dp(8f)) }
    }

    private fun previewText(result: AudioPreview.Result): String = when {
        result.error != null -> context.getString(R.string.settings_audio_preview_failed, result.error)
        result.streamRejected -> context.getString(R.string.settings_audio_preview_rejected, result.stream)
        result.stream == AudioStreamChoice.AUTO -> context.getString(R.string.settings_audio_preview_auto)
        else -> context.getString(R.string.settings_audio_preview_stream, result.stream)
    }

    // ---------- 诊断 ----------

    private fun diagnosticGroup(): View {
        val view = settingsRow(
            context,
            context.getString(R.string.settings_log_open),
            context.getString(R.string.settings_log_summary),
            actionButton(context, context.getString(R.string.settings_log_open)) { openDiagnosticLog() },
        )
        // 日志写在应用私有目录里（别的应用和文件管理器进不去），所以要给一条路把它弄出来：
        // 安卓 4.4 起用系统的「另存为」挑目录，4.1～4.3 直接落到外置存储的 OpenHC/ 目录。
        val exportSummary = if (io.github.gcbond.openhc.settings.LogExport.canSaveToChosenFolder()) {
            context.getString(R.string.settings_log_export_summary)
        } else {
            context.getString(R.string.settings_log_path, "/sdcard/OpenHC/")
        }
        val export = settingsRow(
            context,
            context.getString(R.string.settings_log_export),
            exportSummary,
            actionButton(context, context.getString(R.string.settings_log_export)) { exportDiagnosticLog() },
        )
        val share = settingsRow(
            context,
            context.getString(R.string.log_share),
            context.getString(R.string.log_share_subject),
            actionButton(context, context.getString(R.string.log_share)) {
                io.github.gcbond.openhc.settings.LogExport.share(context)
            },
        )
        val bluetooth = settingsRow(
            context,
            context.getString(R.string.settings_bluetooth_test),
            context.getString(R.string.settings_bluetooth_test_summary),
            actionButton(context, context.getString(R.string.settings_bluetooth_test)) { runBluetoothTest() },
        )
        return settingsGroup(
            context,
            context.getString(R.string.settings_group_diagnostic),
            listOf(view, export, share, bluetooth),
        )
    }

    /**
     * 蓝牙能力自检：不开连接、不连手机，直接把「开 RFCOMM 监听」这件事试一遍，看这台车机给不给开。
     * 安卓 4.4 上失败只有一句 `Error: -1`，所以要一次把几种开法和环境都试出来，结果直接显示给用户看。
     */
    private fun runBluetoothTest() {
        // 开监听、试通道号都要走系统蓝牙服务，最坏情况要几秒，放后台线程，别卡住界面。
        val handler = Handler(Looper.getMainLooper())
        Toast.makeText(context, R.string.settings_bluetooth_test_running, Toast.LENGTH_SHORT).show()
        Thread({
            val result = try {
                io.github.gcbond.openhc.platform.bluetooth.RfcommListeners.selfTest(context)
            } catch (e: Exception) {
                "自检出错：${e.javaClass.simpleName}：${e.message}"
            }
            io.github.gcbond.openhc.settings.DiagnosticLog.log("OpenHC-Wireless", "蓝牙自检：$result")
            handler.post {
                android.app.AlertDialog.Builder(context)
                    .setTitle(R.string.settings_bluetooth_test)
                    .setMessage(result)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }, "OpenHC-BluetoothTest").start()
    }

    /** 导出日志：有「另存为」就交给系统让用户挑目录，没有就直接写外置存储。 */
    private fun exportDiagnosticLog() {
        val activity = context as? Activity
        if (activity != null && io.github.gcbond.openhc.settings.LogExport.launchSave(activity)) return
        // 安卓 4.1～4.3 没有「另存为」界面，直接写到 /sdcard/OpenHC/。
        val message = io.github.gcbond.openhc.settings.LogExport.saveToLegacyDir(context)
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    private fun openDiagnosticLog() {
        val text = try {
            io.github.gcbond.openhc.settings.DiagnosticLog.read()
        } catch (e: Exception) {
            e.message ?: e.javaClass.simpleName
        }
        val scroll = ScrollView(context).apply {
            setPadding(context.dp(12f), context.dp(8f), context.dp(12f), context.dp(8f))
        }
        scroll.addView(
            context.textView(text, sizeSp = 11f, color = Palette.Text).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
        )
        android.app.AlertDialog.Builder(context)
            .setTitle(R.string.settings_log_open)
            .setMessage(context.getString(R.string.settings_log_path, io.github.gcbond.openhc.settings.DiagnosticLog.location()))
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.log_export) { _, _ -> exportDiagnosticLog() }
            .show()
    }

    // ---------- 补全蓝牙配对对话框 ----------

    private fun openLePairingDialog() {
        // checkSelfPermission / requestPermissions 都是 API 23 才有，走 Compat：安卓 4.1 上权限装即授予，
        // missing 会是空的，直接弹对话框。
        val missing = LePairingRules.requiredPermissions(Build.VERSION.SDK_INT).filter {
            !io.github.gcbond.openhc.compat.Compat.granted(context, it)
        }
        val activity = context as? Activity
        if (missing.isNotEmpty() && activity != null) {
            io.github.gcbond.openhc.compat.Compat.requestPermissions(activity, missing.toTypedArray(), REQ_LE_PAIRING)
            pendingLePairing = true
            return
        }
        showLePairingDialog()
    }

    fun showLePairingDialog() {
        val session = LePairingSession(context)
        val message = context.textView(context.getString(R.string.le_pair_preparing), sizeSp = 15f)
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle(R.string.le_pair_title)
            .setView(ScrollView(context).apply {
                setPadding(context.dp(16f), context.dp(8f), context.dp(16f), context.dp(8f))
                addView(message)
            })
            .setNegativeButton(R.string.le_pair_cancel) { _, _ -> session.close("对话框关了") }
            .setPositiveButton(R.string.le_pair_done) { _, _ -> session.close("对话框关了") }
            .setCancelable(false)
            .create()

        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)
        scope.launch {
            session.status.collect { status ->
                message.text = pairingMessage(status)
                message.setTextColor(if (status.stage == LePairingStage.FAILED) Palette.Warn else Palette.Text)
            }
        }
        dialog.setOnDismissListener { session.close("对话框关了") }
        session.start()
        dialog.show()
    }

    private fun pairingMessage(status: LePairingStatus): String {
        val name = LePairingRules.PAIRING_NAME
        val detail = status.detail ?: context.getString(R.string.le_pair_reason_unknown)
        return when (status.stage) {
            LePairingStage.PREPARING -> context.getString(R.string.le_pair_preparing)
            LePairingStage.ADVERTISING -> context.getString(R.string.le_pair_advertising, name)
            LePairingStage.BONDING -> context.getString(R.string.le_pair_bonding)
            LePairingStage.PAIRED -> context.getString(R.string.le_pair_paired)
            LePairingStage.BOND_FAILED -> context.getString(R.string.le_pair_bond_failed, name)
            LePairingStage.EXPIRED -> context.getString(R.string.le_pair_expired)
            LePairingStage.FAILED -> when (status.failure) {
                LePairingFailure.NO_BLUETOOTH -> context.getString(R.string.le_pair_failed_no_bluetooth)
                LePairingFailure.BLUETOOTH_OFF -> context.getString(R.string.le_pair_failed_bluetooth_off)
                LePairingFailure.PERMISSIONS -> context.getString(R.string.le_pair_failed_permissions)
                LePairingFailure.NAME_UNKNOWN -> context.getString(R.string.le_pair_failed_name_unknown, name)
                LePairingFailure.NAME_NOT_SAVED -> context.getString(R.string.le_pair_failed_name_not_saved)
                LePairingFailure.RENAME_REFUSED -> context.getString(R.string.le_pair_failed_rename)
                LePairingFailure.GATT -> context.getString(R.string.le_pair_failed_gatt, detail)
                LePairingFailure.ADVERTISING_UNSUPPORTED -> context.getString(R.string.le_pair_failed_unsupported)
                LePairingFailure.ADVERTISING_BUSY -> context.getString(R.string.le_pair_failed_busy)
                LePairingFailure.ADVERTISING, null -> context.getString(R.string.le_pair_failed_advertising, detail)
            }
        }
    }

    /** 权限申请回来后才打开配对对话框。 */
    fun onPermissionResult() {
        if (pendingLePairing) {
            pendingLePairing = false
            showLePairingDialog()
        }
    }

    private companion object {
        const val REQ_LE_PAIRING = 2001
    }
}
