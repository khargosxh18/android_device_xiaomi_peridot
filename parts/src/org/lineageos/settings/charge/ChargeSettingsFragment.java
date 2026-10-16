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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import org.lineageos.settings.R;

/**
 * Lets the user pick a charge mode directly, instead of only being able to
 * cycle through OK -> COOL -> NUKE via the QS tile. Both go through
 * ChargeModeUtils (a system property the native charging daemon reads), so
 * they stay in sync no matter which one was used last.
 *
 * Also hosts the bypass-charging switch. That node is asynchronous on the
 * kernel side (~5s, see BypassChargingUtils/the sysfs ABI doc), so the
 * switch polls for confirmation instead of assuming the write landed
 * immediately, and disables itself while a transition is in flight so the
 * user can't fire a second request into the kernel's -EBUSY path.
 */
public class ChargeSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String CHARGE_MODE_PREF = "charge_mode_pref";
    private static final String BYPASS_CHARGING_PREF = "bypass_charging_pref";
    private static final String BYPASS_MODE_PREF = "bypass_mode_pref";
    private static final long BYPASS_POLL_INTERVAL_MS = 250L;
    private static final long BYPASS_POLL_TIMEOUT_MS = 8000L; // headroom over the ~5s kernel sequence

    private ListPreference mChargeModePref;
    private SwitchPreferenceCompat mBypassChargingPref;
    private ListPreference mBypassModePref;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mBypassPollDeadline;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.charge_settings);

        mChargeModePref = (ListPreference) findPreference(CHARGE_MODE_PREF);
        int savedIndex = ChargeModeUtils.getMode();
        mChargeModePref.setValue(String.valueOf(savedIndex));
        updateSummary(savedIndex);
        mChargeModePref.setOnPreferenceChangeListener(this);

        mBypassChargingPref = (SwitchPreferenceCompat) findPreference(BYPASS_CHARGING_PREF);
        mBypassChargingPref.setOnPreferenceChangeListener(this);

        mBypassModePref = (ListPreference) findPreference(BYPASS_MODE_PREF);
        mBypassModePref.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshBypassChargingState();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        if (CHARGE_MODE_PREF.equals(preference.getKey())) {
            int index = Integer.parseInt((String) newValue);
            ChargeModeUtils.setMode(index);
            updateSummary(index);
            return true;
        }

        if (BYPASS_MODE_PREF.equals(preference.getKey())) {
            String mode = (String) newValue;
            if (!BypassChargingUtils.writeMode(mode)) {
                return false;
            }
            BypassChargingUtils.saveMode(requireContext(), mode);
            updateBypassModeSummary(mode, false);
            return true;
        }

        if (BYPASS_CHARGING_PREF.equals(preference.getKey())) {
            boolean enable = (Boolean) newValue;
            mBypassChargingPref.setEnabled(false);
            mBypassChargingPref.setSummary(R.string.bypass_charging_summary_pending);
            if (!BypassChargingUtils.requestState(enable)) {
                // write rejected outright (missing node, or -EBUSY) — don't
                // let the switch show a state that was never applied
                mBypassChargingPref.setEnabled(true);
                refreshBypassChargingState();
                return false;
            }
            mBypassPollDeadline = System.currentTimeMillis() + BYPASS_POLL_TIMEOUT_MS;
            mHandler.postDelayed(mBypassPollRunnable, BYPASS_POLL_INTERVAL_MS);
            return true;
        }

        return false;
    }

    private final Runnable mBypassPollRunnable = new Runnable() {
        @Override
        public void run() {
            BypassChargingUtils.State state = BypassChargingUtils.readState();
            applyBypassChargingState(state);

            if (BypassChargingUtils.isPending(state)
                    && System.currentTimeMillis() < mBypassPollDeadline) {
                mHandler.postDelayed(this, BYPASS_POLL_INTERVAL_MS);
            } else {
                mBypassChargingPref.setEnabled(true);
            }
        }
    };

    private void refreshBypassChargingState() {
        applyBypassChargingState(BypassChargingUtils.readState());
    }

    private void applyBypassChargingState(BypassChargingUtils.State state) {
        mBypassChargingPref.setChecked(BypassChargingUtils.isOn(state));
        refreshBypassMode(state);
        switch (state) {
            case ON:
                mBypassChargingPref.setSummary(R.string.bypass_charging_summary_on);
                break;
            case OFF:
                mBypassChargingPref.setSummary(R.string.bypass_charging_summary_off);
                break;
            case PENDING_ON:
            case PENDING_OFF:
                mBypassChargingPref.setSummary(R.string.bypass_charging_summary_pending);
                break;
            case UNAVAILABLE:
            default:
                mBypassChargingPref.setSummary(R.string.bypass_charging_summary_unavailable);
                mBypassChargingPref.setEnabled(false);
                break;
        }
    }

    /**
     * Mirrors the kernel's selected mode into the list. The picker is locked
     * while bypass is on or switching: the kernel keeps the scene it engaged
     * with, so changing the selection mid-session would show a mode that
     * isn't the one actually running.
     */
    private void refreshBypassMode(BypassChargingUtils.State state) {
        String mode = BypassChargingUtils.readMode();
        if (mode == null || state == BypassChargingUtils.State.UNAVAILABLE) {
            mBypassModePref.setEnabled(false);
            return;
        }
        mBypassModePref.setValue(mode);
        boolean locked = BypassChargingUtils.isOn(state) || BypassChargingUtils.isPending(state);
        mBypassModePref.setEnabled(!locked);
        updateBypassModeSummary(mode, locked);
    }

    private void updateBypassModeSummary(String mode, boolean locked) {
        int index = mBypassModePref.findIndexOfValue(mode);
        if (index < 0) {
            return;
        }
        CharSequence label = mBypassModePref.getEntries()[index];
        mBypassModePref.setSummary(locked
                ? getString(R.string.bypass_mode_locked, label)
                : label);
    }

    private void updateSummary(int index) {
        CharSequence[] entries = mChargeModePref.getEntries();
        if (index >= 0 && index < entries.length) {
            mChargeModePref.setSummary(entries[index]);
        }
    }
}
