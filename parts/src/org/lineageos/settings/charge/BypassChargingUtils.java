/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.lineageos.settings.charge;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.lineageos.settings.utils.FileUtils;

/**
 * Talks to /sys/class/qcom-battery/bypass_charging_enable. The kernel side
 * runs the actual smart_chg/sport_mode write sequence asynchronously (~5s,
 * disables fastcharge first, restores it on revert) — see the ABI doc for
 * that node. This class only ever does plain file I/O: the app runs as
 * android.uid.system and is privileged, so no root shell is needed, same
 * as every other node this app already talks to via FileUtils.
 *
 * Shared by ChargeSettingsFragment (the switch on the charging screen) and
 * BypassChargingTileService (the QS tile), so both read the exact same
 * state and neither can drift out of sync with the other.
 */
public final class BypassChargingUtils {

    private static final String TAG = "BypassChargingUtils";
    private static final String PREFS = "bypass_charging";
    private static final String KEY_MODE = "mode";

    private static final String NODE = "/sys/class/qcom-battery/bypass_charging_enable";
    // Lists every mode with the active one bracketed, e.g. "[navigation] tgame nolimit".
    // Write a mode name to select it; it is applied on the next enable.
    private static final String MODE_NODE = "/sys/class/qcom-battery/bypass_charging_mode";

    public enum State {
        OFF,
        ON,
        PENDING_OFF,
        PENDING_ON,
        UNAVAILABLE,
    }

    private BypassChargingUtils() {
        // static-only
    }

    public static State readState() {
        String raw = FileUtils.readOneLine(NODE);
        if (raw == null) {
            return State.UNAVAILABLE;
        }
        String v = raw.trim();
        switch (v) {
            case "0":
                return State.OFF;
            case "1":
                return State.ON;
            case "0-pending":
                return State.PENDING_OFF;
            case "1-pending":
                return State.PENDING_ON;
            default:
                return State.UNAVAILABLE;
        }
    }

    /**
     * Requests a transition. Returns false immediately if the write itself
     * failed (node missing, or the kernel returned -EBUSY because a
     * transition was already in flight) — callers should not assume the
     * transition is complete just because this returns true; poll
     * {@link #readState()} until it's no longer PENDING_*.
     */
    public static boolean requestState(boolean enable) {
        return FileUtils.writeLine(NODE, enable ? "1" : "0");
    }

    /**
     * Returns the currently selected mode name (e.g. "navigation"), or null
     * if the node is missing (older kernel) or unparsable.
     */
    public static String readMode() {
        String raw = FileUtils.readOneLine(MODE_NODE);
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.indexOf(']', start + 1);
        if (start < 0 || end < 0) {
            return null;
        }
        return raw.substring(start + 1, end).trim();
    }

    /**
     * Selects the mode used by the next enable. Has no effect on a bypass
     * that is already engaged (the kernel latches the scene at engage time).
     */
    public static boolean writeMode(String mode) {
        return FileUtils.writeLine(MODE_NODE, mode);
    }

    /** Remembers the user's mode choice so it can be re-applied after a reboot. */
    public static void saveMode(Context context, String mode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_MODE, mode).apply();
    }

    /**
     * Re-applies the saved mode to the kernel. The kernel resets to its
     * default (navigation) on every boot, so this runs from BOOT_COMPLETED.
     * No-op if nothing was ever saved or the node doesn't exist.
     */
    public static void restoreMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_MODE, null);
        if (mode == null) {
            return;
        }
        if (!writeMode(mode)) {
            Log.w(TAG, "Failed to restore bypass mode: " + mode);
        }
    }

    public static boolean isPending(State state) {
        return state == State.PENDING_ON || state == State.PENDING_OFF;
    }

    public static boolean isOn(State state) {
        return state == State.ON || state == State.PENDING_ON;
    }
}
