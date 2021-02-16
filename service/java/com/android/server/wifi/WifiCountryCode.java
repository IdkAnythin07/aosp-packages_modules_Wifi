/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wifi;

import android.annotation.NonNull;
import android.content.Context;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Provide functions for making changes to WiFi country code.
 * This Country Code is from MCC or phone default setting. This class sends Country Code
 * to driver through wpa_supplicant when ClientModeImpl marks current state as ready
 * using setReadyForChange(true).
 */
public class WifiCountryCode {
    private static final String TAG = "WifiCountryCode";
    private final Context mContext;
    private final TelephonyManager mTelephonyManager;
    private final ActiveModeWarden mActiveModeWarden;
    private List<ChangeListener> mListeners = new ArrayList<>();
    private boolean DBG = false;
    /**
     * Map of active ClientModeManager instance to whether it is ready for country code change.
     *
     * - When a new ClientModeManager instance is created, it is added to this map and starts out
     * ready for any country code changes (value = true).
     * - When the ClientModeManager instance starts a connection attempt, it is marked not ready for
     * country code changes (value = false).
     * - When the ClientModeManager instance ends the connection, it is again marked ready for
     * country code changes (value = true).
     * - When the ClientModeManager instance is destroyed, it is removed from this map.
     */
    private final Map<ConcreteClientModeManager, Boolean> mCmmToReadyForChangeMap =
            new ArrayMap<>();
    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private String mDefaultCountryCode = null;
    private String mTelephonyCountryCode = null;
    private String mDriverCountryCode = null;
    private String mTelephonyCountryTimestamp = null;
    private String mDriverCountryTimestamp = null;
    private String mReadyTimestamp = null;
    private boolean mForceCountryCode = false;

    private class ModeChangeCallbackInternal implements ActiveModeWarden.ModeChangeCallback {
        @Override
        public void onActiveModeManagerAdded(@NonNull ActiveModeManager activeModeManager) {
            if (activeModeManager.getRole() instanceof ActiveModeManager.ClientRole) {
                // Add this CMM for tracking. Interface is up and HAL is initialized at this point.
                // If this device runs the 1.5 HAL version, use the IWifiChip.setCountryCode()
                // to set the country code.
                mCmmToReadyForChangeMap.put((ConcreteClientModeManager) activeModeManager, true);
                evaluateAllCmmStateAndApplyIfAllReady();
            }
        }

        @Override
        public void onActiveModeManagerRemoved(@NonNull ActiveModeManager activeModeManager) {
            if (mCmmToReadyForChangeMap.remove(activeModeManager) != null) {
                // Remove this CMM from tracking.
                evaluateAllCmmStateAndApplyIfAllReady();
            }
        }

        @Override
        public void onActiveModeManagerRoleChanged(@NonNull ActiveModeManager activeModeManager) {
            if (activeModeManager.getRole() == ActiveModeManager.ROLE_CLIENT_PRIMARY) {
                // Set this CMM ready for change. This is needed to handle the transition from
                // ROLE_CLIENT_SCAN_ONLY to ROLE_CLIENT_PRIMARY on devices running older HAL
                // versions (since the IWifiChip.setCountryCode() was only added in the 1.5 HAL
                // version, before that we need to wait till supplicant is up for country code
                // change.
                mCmmToReadyForChangeMap.put((ConcreteClientModeManager) activeModeManager, true);
                evaluateAllCmmStateAndApplyIfAllReady();
            }
        }
    }

    private class ClientModeListenerInternal implements ClientModeImplListener {
        @Override
        public void onConnectionStart(@NonNull ConcreteClientModeManager clientModeManager) {
            if (mCmmToReadyForChangeMap.get(clientModeManager) == null) {
                Log.wtf(TAG, "Connection start received from unknown client mode manager");
            }
            // connection start. CMM not ready for country code change.
            mCmmToReadyForChangeMap.put(clientModeManager, false);
            evaluateAllCmmStateAndApplyIfAllReady();
        }

        @Override
        public void onConnectionEnd(@NonNull ConcreteClientModeManager clientModeManager) {
            if (mCmmToReadyForChangeMap.get(clientModeManager) == null) {
                Log.wtf(TAG, "Connection end received from unknown client mode manager");
            }
            // connection end. CMM ready for country code change.
            mCmmToReadyForChangeMap.put(clientModeManager, true);
            evaluateAllCmmStateAndApplyIfAllReady();
        }

    }

    public WifiCountryCode(
            Context context,
            ActiveModeWarden activeModeWarden,
            ClientModeImplMonitor clientModeImplMonitor,
            String oemDefaultCountryCode) {
        mContext = context;
        mTelephonyManager = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        mActiveModeWarden = activeModeWarden;

        mActiveModeWarden.registerModeChangeCallback(new ModeChangeCallbackInternal());
        clientModeImplMonitor.registerListener(new ClientModeListenerInternal());

        if (!TextUtils.isEmpty(oemDefaultCountryCode)) {
            mDefaultCountryCode = oemDefaultCountryCode.toUpperCase(Locale.US);
        }

        Log.d(TAG, "mDefaultCountryCode " + mDefaultCountryCode);
    }

    /**
     * The class for country code related change listener
     */
    public interface ChangeListener {
        /**
         * Called when country code set to native layer successful.
         */
        void onDriverCountryCodeChanged(String countryCode);
    }

    /**
     * Register Country code changed listener.
     */
    public void registerListener(@NonNull ChangeListener listener) {
        mListeners.add(listener);
    }

    /**
     * Enable verbose logging for WifiCountryCode.
     */
    public void enableVerboseLogging(boolean verbose) {
        DBG = verbose;
    }

    private void initializeTelephonyCountryCodeIfNeeded() {
        // If we don't have telephony country code set yet, poll it.
        if (mTelephonyCountryCode == null) {
            Log.d(TAG, "Reading country code from telephony");
            setCountryCode(mTelephonyManager.getNetworkCountryIso());
        }
    }

    /**
     * We call native code to request country code changes only if all {@link ClientModeManager}
     * instances are ready for country code change. Country code is a chip level configuration and
     * results in all the connections on the chip being disrupted.
     *
     * @return true if there are active CMM's and all are ready for country code change.
     */
    private boolean isReady() {
        return !mCmmToReadyForChangeMap.isEmpty()
                && mCmmToReadyForChangeMap.values().stream().allMatch(r -> r);
    }

    /**
     * Check all active CMM instances and apply country code change if ready.
     */
    private void evaluateAllCmmStateAndApplyIfAllReady() {
        Log.d(TAG, "evaluateAllCmmStateAndApplyIfAllReady: " + mCmmToReadyForChangeMap);
        if (isReady()) {
            mReadyTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
            // We are ready to set country code now.
            // We need to post pending country code request.
            initializeTelephonyCountryCodeIfNeeded();
            updateCountryCode();
        }
    }

    /**
     * Enable force-country-code mode
     * This is for forcing a country using cmd wifi from adb shell
     * This is for test purpose only and we should disallow any update from
     * telephony in this mode
     * @param countryCode The forced two-letter country code
     */
    synchronized void enableForceCountryCode(String countryCode) {
        if (TextUtils.isEmpty(countryCode)) {
            Log.d(TAG, "Fail to force country code because the received country code is empty");
            return;
        }
        mForceCountryCode = true;
        mTelephonyCountryCode = countryCode.toUpperCase(Locale.US);

        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (isReady()) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }
    }

    /**
     * Disable force-country-code mode
     */
    synchronized void disableForceCountryCode() {
        mForceCountryCode = false;
        mTelephonyCountryCode = null;

        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (isReady()) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }
    }

    private boolean setCountryCode(String countryCode) {
        if (mForceCountryCode) {
            Log.d(TAG, "Telephony Country code ignored due to force-country-code mode");
            return false;
        }
        Log.d(TAG, "Set telephony country code to: " + countryCode);
        mTelephonyCountryTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));

        // Empty country code.
        if (TextUtils.isEmpty(countryCode)) {
            if (mContext.getResources()
                        .getBoolean(R.bool.config_wifi_revert_country_code_on_cellular_loss)) {
                Log.d(TAG, "Received empty country code, reset to default country code");
                mTelephonyCountryCode = null;
            }
        } else {
            mTelephonyCountryCode = countryCode.toUpperCase(Locale.US);
        }
        return true;
    }

    /**
     * Handle country code change request.
     * @param countryCode The country code intended to set.
     * This is supposed to be from Telephony service.
     * otherwise we think it is from other applications.
     * @return Returns true if the country code passed in is acceptable.
     */
    public boolean setCountryCodeAndUpdate(String countryCode) {
        if (!setCountryCode(countryCode)) return false;
        // If wpa_supplicant is ready we set the country code now, otherwise it will be
        // set once wpa_supplicant is ready.
        if (isReady()) {
            updateCountryCode();
        } else {
            Log.d(TAG, "skip update supplicant not ready yet");
        }

        return true;
    }

    /**
     * Method to get the Country Code that was sent to wpa_supplicant.
     *
     * @return Returns the local copy of the Country Code that was sent to the driver upon
     * setReadyForChange(true).
     * If wpa_supplicant was never started, this may be null even if a SIM reported a valid
     * country code.
     * Returns null if no Country Code was sent to driver.
     */
    @VisibleForTesting
    public synchronized String getCountryCodeSentToDriver() {
        return mDriverCountryCode;
    }

    /**
     * Method to return the currently reported Country Code from the SIM or phone default setting.
     *
     * @return The currently reported Country Code from the SIM. If there is no Country Code
     * reported from SIM, a phone default Country Code will be returned.
     * Returns null when there is no Country Code available.
     */
    public synchronized String getCountryCode() {
        initializeTelephonyCountryCodeIfNeeded();
        return pickCountryCode();
    }

    /**
     * Method to dump the current state of this WifiCounrtyCode object.
     */
    public synchronized void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("mRevertCountryCodeOnCellularLoss: "
                + mContext.getResources().getBoolean(
                        R.bool.config_wifi_revert_country_code_on_cellular_loss));
        pw.println("mDefaultCountryCode: " + mDefaultCountryCode);
        pw.println("mDriverCountryCode: " + mDriverCountryCode);
        pw.println("mTelephonyCountryCode: " + mTelephonyCountryCode);
        pw.println("mTelephonyCountryTimestamp: " + mTelephonyCountryTimestamp);
        pw.println("mDriverCountryTimestamp: " + mDriverCountryTimestamp);
        pw.println("mReadyTimestamp: " + mReadyTimestamp);
        pw.println("isReady: " + isReady());
        pw.println("mCmmToReadyForChangeMap: " + mCmmToReadyForChangeMap);
    }

    private void updateCountryCode() {
        String country = pickCountryCode();
        Log.d(TAG, "updateCountryCode to " + country);

        // We do not check if the country code equals the current one.
        // There are two reasons:
        // 1. Wpa supplicant may silently modify the country code.
        // 2. If Wifi restarted therefore wpa_supplicant also restarted,
        // the country code counld be reset to '00' by wpa_supplicant.
        if (country != null) {
            setCountryCodeNative(country);
        }
        // We do not set country code if there is no candidate. This is reasonable
        // because wpa_supplicant usually starts with an international safe country
        // code setting: '00'.
    }

    private String pickCountryCode() {
        if (mTelephonyCountryCode != null) {
            return mTelephonyCountryCode;
        }
        if (mDefaultCountryCode != null) {
            return mDefaultCountryCode;
        }
        // If there is no candidate country code we will return null.
        return null;
    }

    private boolean setCountryCodeNative(String country) {
        Set<ConcreteClientModeManager> cmms = mCmmToReadyForChangeMap.keySet();

        // Set the country code using one of the active client mode managers. Since
        // country code is a chip level global setting, it can be set as long
        // as there is at least one active interface to communicate to Wifi chip
        for (ConcreteClientModeManager cm : cmms) {
            if (cm.setCountryCode(country)) {
                mDriverCountryTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
                mDriverCountryCode = country;
                Log.d(TAG, "Succeeded to set country code to: " + country);
                for (ChangeListener listener : mListeners) {
                    listener.onDriverCountryCodeChanged(country);
                }
                return true;
            }
        }
        Log.d(TAG, "Failed to set country code to: " + country);
        return false;
    }
}

