package com.fakelocation.app.safety

import android.content.Context

/**
 * Soft guardrails to reduce accidental WeChat risk-control exposure.
 * Does NOT attempt to hide mock providers or bypass detection.
 */
object SafetyPolicy {
    /** Default walking pace. */
    const val DEFAULT_SPEED_MPS = 1.2f

    /** Soft cap shown on slider; faster values need explicit confirm. */
    const val SAFE_SPEED_MPS = 3.5f

    /** Absolute UI max — beyond this is rejected. */
    const val MAX_SPEED_MPS = 5.0f

    /** Whole simulation session hard limit. */
    const val MAX_SESSION_MS = 30 * 60 * 1000L

    /** After reaching destination, keep last point briefly then auto-stop. */
    const val HOLD_AT_END_MS = 60 * 1000L

    /** Single-point / stay-in-place sessions can hold longer, still capped by MAX_SESSION_MS. */
    const val STATIONARY_HOLD_MS = 10 * 60 * 1000L

    /** Reject absurd teleports between consecutive route points. */
    const val MAX_WAYPOINT_JUMP_METERS = 50_000.0

    private const val PREFS = "safety_prefs"
    private const val KEY_CONSENT = "wechat_risk_consent_v1"

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_CONSENT, false)

    fun setConsent(context: Context, accepted: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CONSENT, accepted)
            .apply()
    }
}
