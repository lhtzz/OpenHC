package io.github.gcbond.openhc.ui

import android.content.Context
import io.github.gcbond.openhc.R
import io.github.gcbond.openhc.connection.EndCause
import io.github.gcbond.openhc.connection.Notice
import io.github.gcbond.openhc.core.auth.AuthFailure
import io.github.gcbond.openhc.core.session.WirelessEndReason
import io.github.gcbond.openhc.core.session.WirelessStep as SessionStep
import io.github.gcbond.openhc.wireless.AutoHold
import io.github.gcbond.openhc.wireless.BeaconStatus
import io.github.gcbond.openhc.wireless.WirelessEndCause
import io.github.gcbond.openhc.wireless.WirelessNotice
import io.github.gcbond.openhc.wireless.WirelessPermissions
import io.github.gcbond.openhc.wireless.WirelessPhase
import io.github.gcbond.openhc.wireless.WirelessStep
import io.github.gcbond.openhc.wireless.WirelessUiState

// 无线连接状态 → 界面文字。

internal fun wirelessPhaseTitle(context: Context, state: WirelessUiState): String = context.getString(
    when (state.phase) {
        WirelessPhase.IDLE -> R.string.wireless_phase_idle
        // 自动进入的等待：标题说清是在等配对过的手机（二维码也照样显示，新手机能扫）。
        WirelessPhase.PREPARING -> if (state.autoReconnect) R.string.wireless_phase_auto else R.string.wireless_phase_preparing
        WirelessPhase.WAITING_FOR_SCAN -> if (state.autoReconnect) R.string.wireless_phase_auto else R.string.wireless_phase_scan
        WirelessPhase.CONNECTING -> R.string.wireless_phase_connecting
        WirelessPhase.PROJECTING -> R.string.wireless_phase_projecting
        WirelessPhase.STOPPING -> R.string.wireless_phase_stopping
    },
)

internal fun wirelessHint(context: Context, state: WirelessUiState): String {
    state.step?.let { return context.getString(stepHint(context, it)) }
    return when (state.phase) {
        WirelessPhase.IDLE -> context.getString(R.string.wireless_hint_idle)
        WirelessPhase.PREPARING -> context.getString(R.string.wireless_hint_preparing)
        WirelessPhase.WAITING_FOR_SCAN ->
            context.getString(if (state.autoReconnect) R.string.wireless_hint_auto else R.string.wireless_hint_scan)
        WirelessPhase.CONNECTING -> context.getString(R.string.hint_connecting)
        // 投屏段与有线相同，沿用有线的说明（分辨率、帧率、等第一帧、手机没开放触摸）。
        WirelessPhase.PROJECTING -> {
            val p = state.projection
            val video = p.video
            when {
                video == null -> context.getString(R.string.hint_negotiating)
                !p.firstFrame -> context.getString(R.string.hint_projecting_waiting_frame, video.width, video.height, video.fps)
                !video.touchInput -> context.getString(R.string.hint_projecting_no_touch, video.width, video.height, video.fps)
                else -> context.getString(R.string.hint_projecting, video.width, video.height, video.fps)
            }
        }
        WirelessPhase.STOPPING -> context.getString(R.string.wireless_hint_stopping)
    }
}

private fun stepHint(context: Context, step: WirelessStep): Int = when (step) {
    WirelessStep.BLUETOOTH_HANDSHAKE -> R.string.wireless_hint_bt_handshake
    WirelessStep.WAITING_AUTH -> R.string.wireless_hint_waiting_auth
    WirelessStep.CONFIRM_ON_PHONE -> R.string.wireless_hint_confirm_on_phone
    WirelessStep.AUTH_PAIRING -> R.string.wireless_hint_auth_pairing
    WirelessStep.AUTH_TRUSTED -> R.string.wireless_hint_auth_trusted
    WirelessStep.NEGOTIATING_WIFI -> R.string.wireless_hint_negotiating_hotspot
    WirelessStep.SENDING_NETWORK -> R.string.wireless_hint_sending_hotspot
    WirelessStep.WAITING_PHONE_JOIN -> R.string.wireless_hint_waiting_join_hotspot
    WirelessStep.WIFI_HANDSHAKE -> R.string.wireless_hint_wifi_handshake
    WirelessStep.DEVICE_SESSION -> R.string.wireless_hint_device_session
    WirelessStep.WAITING_PROJECTION -> R.string.wireless_hint_waiting_projection
    WirelessStep.NEGOTIATING_PROJECTION -> R.string.wireless_hint_negotiating_projection
}

internal fun wirelessNoticeText(context: Context, notice: WirelessNotice): String = when (notice) {
    WirelessNotice.IdentityMissing -> context.getString(R.string.notice_identity_missing)
    is WirelessNotice.IdentityInvalid -> context.getString(R.string.notice_identity_invalid, notice.reason)
    WirelessNotice.BluetoothAddressMissing -> context.getString(R.string.wireless_notice_bt_address)
    WirelessNotice.BluetoothUnsupported -> context.getString(R.string.wireless_notice_bt_unsupported)
    WirelessNotice.BluetoothOff -> context.getString(R.string.wireless_notice_bt_off)
    WirelessNotice.HotspotUnsupported -> context.getString(R.string.wireless_notice_hotspot_unsupported)
    WirelessNotice.WriteSettingsRequired -> context.getString(R.string.wireless_notice_write_settings)
    is WirelessNotice.PermissionsDenied -> when {
        notice.approximateOnly -> context.getString(R.string.wireless_notice_precise_location)
        notice.permanently -> context.getString(R.string.wireless_notice_permissions_permanent, permissionNames(context, notice.missing))
        else -> context.getString(R.string.wireless_notice_permissions, permissionNames(context, notice.missing))
    }
    WirelessNotice.WiredActive -> context.getString(R.string.wireless_notice_wired_active)
    WirelessNotice.WiredOnly -> context.getString(R.string.wireless_notice_wired_only)
    is WirelessNotice.TrustStoreCorrupt -> context.getString(R.string.notice_trust_corrupt, notice.reason)
    is WirelessNotice.TrustStoreUnavailable -> context.getString(R.string.notice_trust_unavailable, notice.reason)
    is WirelessNotice.StartFailed -> context.getString(R.string.notice_start_failed, notice.reason)
    is WirelessNotice.Ended -> endedText(context, notice.cause)
}

private fun permissionNames(context: Context, permissions: List<String>): String {
    val names = permissions.map {
        context.getString(
            when (it) {
                WirelessPermissions.BLUETOOTH_CONNECT -> R.string.wireless_perm_bluetooth
                WirelessPermissions.NEARBY_WIFI_DEVICES -> R.string.wireless_perm_nearby_wifi
                else -> R.string.wireless_perm_location
            },
        )
    }
    return names.joinToString(context.getString(R.string.wireless_perm_list_separator))
}

private fun endedText(context: Context, cause: WirelessEndCause): String {
    val reason = (cause as? WirelessEndCause.Session)?.reason
    // WiFi 段的结束与有线相同，沿用有线的说法。
    if (reason is WirelessEndReason.Wired) return noticeText(context, Notice.Ended(EndCause.Session(reason.reason)))
    val text = when (cause) {
        WirelessEndCause.UserStop -> context.getString(R.string.end_user_stop)
        is WirelessEndCause.Failure -> context.getString(R.string.end_failure, cause.message)
        is WirelessEndCause.Session -> wirelessEndText(context, cause.reason)
    }
    return context.getString(R.string.wireless_notice_ended, text)
}

/** 常见原因给一句好懂的话；会话层自带的说明已经是中文、不含密钥和设备标识，放在括号里。 */
private fun wirelessEndText(context: Context, reason: WirelessEndReason): String = when (reason) {
    WirelessEndReason.LocalStop -> context.getString(R.string.end_user_stop)
    is WirelessEndReason.Wired -> reason.toString()
    is WirelessEndReason.AuthFailed -> when (reason.failure) {
        is AuthFailure.ConnectionClosed -> context.getString(R.string.wireless_end_auth_closed)
        AuthFailure.NotTrusted -> context.getString(R.string.wireless_end_not_trusted)
        else -> context.getString(R.string.wireless_end_auth_failed, reason.failure.toString())
    }
    is WirelessEndReason.Failed -> if (reason.step == SessionStep.BLUETOOTH && isListenFailure(reason.detail)) {
        // 开监听就被系统拒了（安卓 4.4 上只有一句 Error: -1），不是手机的问题。
        // 结论已经写进诊断日志，界面上只给一句明确的处置办法，不把技术细节糊在用户脸上。
        context.getString(R.string.wireless_end_bluetooth_listen)
    } else context.getString(
        when (reason.step) {
            SessionStep.BLUETOOTH -> R.string.wireless_end_step_bluetooth
            SessionStep.AUTH -> R.string.wireless_end_step_auth
            SessionStep.NEGOTIATION -> R.string.wireless_end_step_negotiation
            SessionStep.WIFI_NETWORK -> R.string.wireless_end_step_hotspot
            SessionStep.PHONE_JOIN -> R.string.wireless_end_step_join_hotspot
        },
        reason.detail,
    )
    is WirelessEndReason.ReconnectFailed ->
        if (reason.needsPairing) context.getString(R.string.wireless_end_reconnect_pairing) else context.getString(R.string.wireless_end_reconnect, reason.detail)
    is WirelessEndReason.InternalError -> context.getString(R.string.wireless_end_internal, reason.message)
}

/**
 * 结束原因是不是「开监听就被系统拒了」：平台层报的说明以「开监听失败」开头
 * （见 RfcommServer，失败时后面还带着车机蓝牙的现状和探针结论）。
 */
private fun isListenFailure(detail: String): Boolean = detail.contains("开监听失败")

/** 这次连接里 BLE 广播的情况；停了不显示。 */
internal fun wirelessBeaconText(context: Context, status: BeaconStatus): String? = when (status) {
    BeaconStatus.Starting -> context.getString(R.string.wireless_beacon_starting)
    is BeaconStatus.Advertising -> context.getString(R.string.wireless_beacon_advertising, status.publishes)
    is BeaconStatus.Retrying -> context.getString(R.string.wireless_beacon_retrying, status.reason, (status.retryInMillis / 1000).toInt())
    is BeaconStatus.Failed -> context.getString(R.string.wireless_beacon_failed, status.reason)
    BeaconStatus.Stopped -> null
}

/** 「自动连接手机」现在能不能起作用（无线页空闲时一行说明）；开关关着不说。 */
internal fun wirelessAutoHoldText(context: Context, hold: AutoHold): String? = when (hold) {
    AutoHold.READY -> context.getString(R.string.wireless_auto_hold_ready)
    AutoHold.NO_KNOWN_PHONE -> context.getString(R.string.wireless_auto_hold_no_known)
    AutoHold.OVERLAY_MISSING -> context.getString(R.string.wireless_auto_hold_overlay)
    AutoHold.UI_NOT_SHOWN -> context.getString(R.string.wireless_auto_hold_ui_not_shown)
    AutoHold.WIRED_ONLY -> context.getString(R.string.wireless_auto_hold_wired_only)
    AutoHold.DISABLED -> null
    AutoHold.PAUSED_BY_USER -> context.getString(R.string.wireless_auto_hold_paused)
    AutoHold.WIRED_ACTIVE -> context.getString(R.string.wireless_auto_hold_wired)
    AutoHold.HOTSPOT_UNSUPPORTED -> context.getString(R.string.wireless_auto_hold_hotspot_unsupported)
    AutoHold.IDENTITY_MISSING -> context.getString(R.string.wireless_auto_hold_identity)
    AutoHold.NO_PAIRED_PHONE -> context.getString(R.string.wireless_auto_hold_no_phone)
    AutoHold.BLUETOOTH_OFF -> context.getString(R.string.wireless_auto_hold_bt_off)
    AutoHold.PERMISSIONS_MISSING -> context.getString(R.string.wireless_auto_hold_permissions)
    AutoHold.ADVERTISING_UNSUPPORTED -> context.getString(R.string.wireless_auto_hold_unsupported)
    AutoHold.BLUETOOTH_ADDRESS_MISSING -> context.getString(R.string.wireless_auto_hold_address)
    AutoHold.FOREGROUND_UNAVAILABLE -> context.getString(R.string.wireless_auto_hold_foreground)
    AutoHold.START_FAILED -> context.getString(R.string.wireless_auto_hold_failed)
}
