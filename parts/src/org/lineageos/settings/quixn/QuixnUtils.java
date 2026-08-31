/*
 * Copyright (C) 2024 The LineageOS Project
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.lineageos.settings.quixn;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Log;

public final class QuixnUtils {

    private static final String TAG = "QuixnUtils";

    static final String THERMALD_SERVICE_NAME = "mi_thermald";
    static final String CTL_START_PROP = "ctl.start";
    static final String CTL_STOP_PROP = "ctl.stop";

    // Single source of truth for Quixn state. init.mi_thermald.rc gates its
    // own "on boot" / "on charger" start triggers on this same property, so
    // there is no app-side race to win at boot time - init simply never
    // (re)starts mi_thermald while this is "1".
    static final String PROP_QUIXN_DISABLED = "persist.sys.quixn.thermald_disabled";

    private QuixnUtils() {}

    public static boolean isQuixnEnabled(Context context) {
        return SystemProperties.getBoolean(PROP_QUIXN_DISABLED, false);
    }

    public static void setQuixnEnabled(Context context, boolean enabled) {
        try {
            SystemProperties.set(PROP_QUIXN_DISABLED, enabled ? "1" : "0");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set " + PROP_QUIXN_DISABLED, e);
        }
    }

    public static void stopThermald() {
        try {
            SystemProperties.set(CTL_STOP_PROP, THERMALD_SERVICE_NAME);
            Log.d(TAG, "Stopped " + THERMALD_SERVICE_NAME);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop " + THERMALD_SERVICE_NAME, e);
        }
    }

    public static void startThermald() {
        try {
            SystemProperties.set(CTL_START_PROP, THERMALD_SERVICE_NAME);
            Log.d(TAG, "Started " + THERMALD_SERVICE_NAME);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start " + THERMALD_SERVICE_NAME, e);
        }
    }
}
