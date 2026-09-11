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

    private static final String NODE = "/sys/class/qcom-battery/bypass_charging_enable";

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

    public static boolean isPending(State state) {
        return state == State.PENDING_ON || state == State.PENDING_OFF;
    }

    public static boolean isOn(State state) {
        return state == State.ON || state == State.PENDING_ON;
    }
}
