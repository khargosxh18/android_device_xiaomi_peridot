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

import android.os.SystemProperties;

public final class ChargeModeUtils {

    public static final String PROP = "persist.sys.charge.mode";

    public static final int MODE_OK = 0;
    public static final int MODE_COOL = 1;
    public static final int MODE_NUKE = 2;
    public static final int MODE_COUNT = 3;

    private ChargeModeUtils() {
    }

    public static int getMode() {
        int mode = SystemProperties.getInt(PROP, MODE_OK);
        return (mode >= 0 && mode < MODE_COUNT) ? mode : MODE_OK;
    }

    public static void setMode(int mode) {
        SystemProperties.set(PROP, String.valueOf(mode));
    }
}
