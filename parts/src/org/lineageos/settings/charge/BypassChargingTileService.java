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

import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import org.lineageos.settings.R;

/**
 * QS tile mirror of the bypass-charging switch on the charging screen.
 * Both talk to the kernel node only through BypassChargingUtils, so a
 * toggle from either place is reflected in the other next time it's shown.
 *
 * The kernel-side transition takes ~5s (see the sysfs ABI doc), so onClick
 * doesn't just flip the tile — it shows the pending state and polls until
 * the kernel confirms, same reasoning as the fragment's switch.
 */
public class BypassChargingTileService extends TileService {
    private static final String TAG = "BypassChargingTileService";
    private static final long POLL_INTERVAL_MS = 250L;
    private static final long POLL_TIMEOUT_MS = 8000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mPollDeadline;

    @Override
    public void onStartListening() {
        super.onStartListening();
        BypassChargingUtils.State state = BypassChargingUtils.readState();
        updateTile(state);
        if (BypassChargingUtils.isPending(state)) {
            mPollDeadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
            mHandler.postDelayed(mPollRunnable, POLL_INTERVAL_MS);
        }
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onClick() {
        super.onClick();
        BypassChargingUtils.State current = BypassChargingUtils.readState();
        if (BypassChargingUtils.isPending(current)) {
            // already mid-transition, ignore extra taps rather than risk
            // hitting the kernel's -EBUSY path
            return;
        }

        boolean requestOn = !BypassChargingUtils.isOn(current);
        if (!BypassChargingUtils.requestState(requestOn)) {
            Log.w(TAG, "bypass_charging_enable write rejected");
            updateTile(BypassChargingUtils.readState());
            return;
        }

        updateTile(requestOn ? BypassChargingUtils.State.PENDING_ON
                              : BypassChargingUtils.State.PENDING_OFF);
        mPollDeadline = System.currentTimeMillis() + POLL_TIMEOUT_MS;
        mHandler.postDelayed(mPollRunnable, POLL_INTERVAL_MS);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
    }

    private final Runnable mPollRunnable = new Runnable() {
        @Override
        public void run() {
            BypassChargingUtils.State state = BypassChargingUtils.readState();
            updateTile(state);
            if (BypassChargingUtils.isPending(state)
                    && System.currentTimeMillis() < mPollDeadline) {
                mHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        }
    };

    private void updateTile(BypassChargingUtils.State state) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }

        tile.setLabel(getString(R.string.bypass_charging_tile_label));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_bypass_charging));

        switch (state) {
            case ON:
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle(getString(R.string.bypass_charging_summary_on));
                break;
            case PENDING_ON:
            case PENDING_OFF:
                tile.setState(Tile.STATE_ACTIVE);
                tile.setSubtitle(getString(R.string.bypass_charging_summary_pending));
                break;
            case UNAVAILABLE:
                tile.setState(Tile.STATE_UNAVAILABLE);
                tile.setSubtitle(getString(R.string.bypass_charging_summary_unavailable));
                break;
            case OFF:
            default:
                tile.setState(Tile.STATE_INACTIVE);
                tile.setSubtitle(getString(R.string.bypass_charging_summary_off));
                break;
        }

        tile.updateTile();
    }
}
