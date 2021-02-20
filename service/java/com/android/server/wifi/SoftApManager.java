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

import static android.net.wifi.WifiManager.SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED;

import static com.android.server.wifi.util.ApConfigUtil.ERROR_GENERIC;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_NO_CHANNEL;
import static com.android.server.wifi.util.ApConfigUtil.ERROR_UNSUPPORTED_CONFIGURATION;
import static com.android.server.wifi.util.ApConfigUtil.SUCCESS;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;
import android.net.MacAddress;
import android.net.wifi.ScanResult;
import android.net.wifi.SoftApCapability;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.SoftApInfo;
import android.net.wifi.WifiAnnotations;
import android.net.wifi.WifiClient;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IState;
import com.android.internal.util.Preconditions;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;
import com.android.internal.util.WakeupMessage;
import com.android.modules.utils.build.SdkLevel;
import com.android.server.wifi.WifiNative.InterfaceCallback;
import com.android.server.wifi.WifiNative.SoftApListener;
import com.android.server.wifi.coex.CoexManager;
import com.android.server.wifi.coex.CoexManager.CoexListener;
import com.android.server.wifi.util.ApConfigUtil;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manage WiFi in AP mode.
 * The internal state machine runs under the ClientModeImpl handler thread context.
 */
public class SoftApManager implements ActiveModeManager {
    private static final String TAG = "SoftApManager";

    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Timeout";
    @VisibleForTesting
    public static final String SOFT_AP_SEND_MESSAGE_IDLE_IN_BRIDGED_MODE_TIMEOUT_TAG = TAG
            + " Soft AP Send Message Bridged Mode Idle Timeout";


    private final WifiContext mContext;
    private final FrameworkFacade mFrameworkFacade;
    private final WifiNative mWifiNative;
    private final CoexManager mCoexManager;

    private final SoftApNotifier mSoftApNotifier;

    @VisibleForTesting
    static final long SOFT_AP_PENDING_DISCONNECTION_CHECK_DELAY_MS = 1000;

    private final String mCountryCode;

    private final SoftApStateMachine mStateMachine;

    private final Listener<SoftApManager> mModeListener;
    private final WifiServiceImpl.SoftApCallbackInternal mSoftApCallback;

    private String mApInterfaceName;
    private boolean mIfaceIsUp;
    private boolean mIfaceIsDestroyed;

    private final WifiApConfigStore mWifiApConfigStore;

    private final WifiMetrics mWifiMetrics;
    private final long mId;

    private boolean mIsUnsetBssid;

    private boolean mVerboseLoggingEnabled = false;

    @NonNull
    private SoftApModeConfiguration mApConfig;

    @NonNull
    private Map<String, SoftApInfo> mCurrentSoftApInfoMap = new HashMap<>();

    @NonNull
    private SoftApCapability mCurrentSoftApCapability;

    private Map<String, List<WifiClient>> mConnectedClientWithApInfoMap = new HashMap<>();
    @VisibleForTesting
    Map<WifiClient, Integer> mPendingDisconnectClients = new HashMap<>();

    private boolean mTimeoutEnabled = false;
    private boolean mBridgedModeOpportunisticsShutdownTimeoutEnabled = false;

    private String mStartTimestamp;

    private long mDefaultShutdownTimeoutMillis;

    private long mDefaultShutdownIdleInstanceInBridgedModeTimeoutMillis;

    private static final SimpleDateFormat FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    private WifiDiagnostics mWifiDiagnostics;

    @Nullable
    private SoftApRole mRole = null;
    @Nullable
    private WorkSource mRequestorWs = null;

    private boolean mEverReportMetricsForMaxClient = false;

    @NonNull
    private Set<MacAddress> mBlockedClientList = new HashSet<>();

    @NonNull
    private Set<MacAddress> mAllowedClientList = new HashSet<>();

    @VisibleForTesting
    public WakeupMessage mSoftApTimeoutMessage;
    @VisibleForTesting
    public WakeupMessage mSoftApBridgedModeIdleInstanceTimeoutMessage;

    // Internal flag which is used to avoid the timer re-schedule.
    private boolean mIsBridgedModeIdleInstanceTimerActive = false;

    /**
     * Listener for soft AP events.
     */
    private final SoftApListener mSoftApListener = new SoftApListener() {
        @Override
        public void onFailure() {
            mStateMachine.sendMessage(SoftApStateMachine.CMD_FAILURE);
        }

        @Override
        public void onInfoChanged(String apIfaceInstance, int frequency,
                @WifiAnnotations.Bandwidth int bandwidth,
                @WifiAnnotations.WifiStandard int generation,
                MacAddress apIfaceInstanceMacAddress) {
            SoftApInfo apInfo = new SoftApInfo();
            apInfo.setFrequency(frequency);
            apInfo.setBandwidth(bandwidth);
            apInfo.setWifiStandard(generation);
            if (apIfaceInstanceMacAddress != null) {
                apInfo.setBssid(apIfaceInstanceMacAddress);
            }
            apInfo.setApInstanceIdentifier(apIfaceInstance != null
                    ? apIfaceInstance : mApInterfaceName);
            mStateMachine.sendMessage(
                    SoftApStateMachine.CMD_AP_INFO_CHANGED, 0, 0, apInfo);
        }

        @Override
        public void onConnectedClientsChanged(String apIfaceInstance, MacAddress clientAddress,
                boolean isConnected) {
            if (clientAddress != null) {
                WifiClient client = new WifiClient(clientAddress, apIfaceInstance != null
                        ? apIfaceInstance : mApInterfaceName);
                mStateMachine.sendMessage(SoftApStateMachine.CMD_ASSOCIATED_STATIONS_CHANGED,
                        isConnected ? 1 : 0, 0, client);
            } else {
                Log.e(getTag(), "onConnectedClientsChanged: Invalid type returned");
            }
        }
    };

    private final CoexListener mCoexListener = new CoexListener() {
        @Override
        public void onCoexUnsafeChannelsChanged() {
            final SoftApConfiguration config = mApConfig.getSoftApConfiguration();
            if (config == null) {
                return;
            }
            // TODO: (AP+AP) Shut down hard unsafe 5GHz AP if there is already a 2.4GHz AP
        }
    };

    public SoftApManager(
            @NonNull WifiContext context,
            @NonNull Looper looper,
            @NonNull FrameworkFacade framework,
            @NonNull WifiNative wifiNative,
            @NonNull CoexManager coexManager,
            String countryCode,
            @NonNull Listener<SoftApManager> listener,
            @NonNull WifiServiceImpl.SoftApCallbackInternal callback,
            @NonNull WifiApConfigStore wifiApConfigStore,
            @NonNull SoftApModeConfiguration apConfig,
            @NonNull WifiMetrics wifiMetrics,
            @NonNull WifiDiagnostics wifiDiagnostics,
            @NonNull SoftApNotifier softApNotifier,
            long id,
            @NonNull WorkSource requestorWs,
            @NonNull SoftApRole role,
            boolean verboseLoggingEnabled) {
        mContext = context;
        mFrameworkFacade = framework;
        mSoftApNotifier = softApNotifier;
        mWifiNative = wifiNative;
        mCoexManager = coexManager;
        mCountryCode = countryCode;
        mModeListener = listener;
        mSoftApCallback = callback;
        mWifiApConfigStore = wifiApConfigStore;
        SoftApConfiguration softApConfig = apConfig.getSoftApConfiguration();
        mCurrentSoftApCapability = apConfig.getCapability();
        // null is a valid input and means we use the user-configured tethering settings.
        if (softApConfig == null) {
            softApConfig = mWifiApConfigStore.getApConfiguration();
            // may still be null if we fail to load the default config
        }
        if (softApConfig != null) {
            mIsUnsetBssid = softApConfig.getBssid() == null;
            if (mCurrentSoftApCapability.areFeaturesSupported(
                    SoftApCapability.SOFTAP_FEATURE_MAC_ADDRESS_CUSTOMIZATION)) {
                softApConfig = mWifiApConfigStore.randomizeBssidIfUnset(mContext, softApConfig);
            }
        }
        mApConfig = new SoftApModeConfiguration(apConfig.getTargetMode(),
                softApConfig, mCurrentSoftApCapability);
        mWifiMetrics = wifiMetrics;
        mWifiDiagnostics = wifiDiagnostics;
        mStateMachine = new SoftApStateMachine(looper);
        if (softApConfig != null) {
            mBlockedClientList = new HashSet<>(softApConfig.getBlockedClientList());
            mAllowedClientList = new HashSet<>(softApConfig.getAllowedClientList());
            mTimeoutEnabled = softApConfig.isAutoShutdownEnabled();
            mBridgedModeOpportunisticsShutdownTimeoutEnabled =
                    softApConfig.isBridgedModeOpportunisticShutdownEnabledInternal();
        }
        mDefaultShutdownTimeoutMillis = mContext.getResources().getInteger(
                R.integer.config_wifiFrameworkSoftApShutDownTimeoutMilliseconds);
        mDefaultShutdownIdleInstanceInBridgedModeTimeoutMillis = mContext.getResources().getInteger(
                R.integer
                .config_wifiFrameworkSoftApShutDownIdleInstanceInBridgedModeTimeoutMillisecond);
        mId = id;
        mRole = role;
        enableVerboseLogging(verboseLoggingEnabled);
        mStateMachine.sendMessage(SoftApStateMachine.CMD_START, requestorWs);
    }

    @Override
    public long getId() {
        return mId;
    }

    private String getTag() {
        return TAG + "[" + (mApInterfaceName == null ? "unknown" : mApInterfaceName) + "]";
    }

    /**
     * Stop soft AP.
     */
    @Override
    public void stop() {
        Log.d(getTag(), " currentstate: " + getCurrentStateName());
        mStateMachine.sendMessage(SoftApStateMachine.CMD_STOP);
    }

    private boolean isBridgedMode() {
        return (SdkLevel.isAtLeastS() && mApConfig != null
                && mApConfig.getSoftApConfiguration() != null
                && mApConfig.getSoftApConfiguration().getBands().length > 1);
    }

    private long getShutdownTimeoutMillis() {
        long timeout = mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis();
        return timeout > 0 ? timeout : mDefaultShutdownTimeoutMillis;
    }

    @Override
    @Nullable public SoftApRole getRole() {
        return mRole;
    }

    /** Set the role of this SoftApManager */
    public void setRole(SoftApRole role) {
        // softap does not allow in-place switching of roles.
        Preconditions.checkState(mRole == null);
        mRole = role;
    }

    @Override
    public String getInterfaceName() {
        return mApInterfaceName;
    }

    @Override
    public WorkSource getRequestorWs() {
        return mRequestorWs;
    }

    /**
     * Update AP capability. Called when carrier config or device resouce config changed.
     *
     * @param capability new AP capability.
     */
    public void updateCapability(@NonNull SoftApCapability capability) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CAPABILITY, capability);
    }

    /**
     * Update AP configuration. Called when setting update config via
     * {@link WifiManager#setSoftApConfiguration(SoftApConfiguration)}
     *
     * @param config new AP config.
     */
    public void updateConfiguration(@NonNull SoftApConfiguration config) {
        mStateMachine.sendMessage(SoftApStateMachine.CMD_UPDATE_CONFIG, config);
    }

    /**
     * Retrieve the {@link SoftApModeConfiguration} instance associated with this mode manager.
     */
    public SoftApModeConfiguration getSoftApModeConfiguration() {
        return mApConfig;
    }

    /**
     * Dump info about this softap manager.
     */
    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of SoftApManager id=" + mId);

        pw.println("current StateMachine mode: " + getCurrentStateName());
        pw.println("mRole: " + mRole);
        pw.println("mApInterfaceName: " + mApInterfaceName);
        pw.println("mIfaceIsUp: " + mIfaceIsUp);
        pw.println("mSoftApCountryCode: " + mCountryCode);
        pw.println("mApConfig.targetMode: " + mApConfig.getTargetMode());
        SoftApConfiguration softApConfig = mApConfig.getSoftApConfiguration();
        pw.println("mApConfig.SoftApConfiguration.SSID: " + softApConfig.getSsid());
        pw.println("mApConfig.SoftApConfiguration.mBand: " + softApConfig.getBand());
        pw.println("mApConfig.SoftApConfiguration.hiddenSSID: " + softApConfig.isHiddenSsid());
        pw.println("getConnectedClientList().size(): " + getConnectedClientList().size());
        pw.println("mTimeoutEnabled: " + mTimeoutEnabled);
        pw.println("mBridgedModeOpportunisticsShutdownTimeoutEnabled: "
                + mBridgedModeOpportunisticsShutdownTimeoutEnabled);
        pw.println("mCurrentSoftApInfoMap " + mCurrentSoftApInfoMap);
        pw.println("mStartTimestamp: " + mStartTimestamp);
        mStateMachine.dump(fd, pw, args);
    }

    @Override
    public void enableVerboseLogging(boolean verbose) {
        mVerboseLoggingEnabled = verbose;
    }

    @Override
    public String toString() {
        return "SoftApManager{id=" + getId()
                + " iface=" + getInterfaceName()
                + " role=" + getRole()
                + "}";
    }

    private String getCurrentStateName() {
        IState currentState = mStateMachine.getCurrentState();

        if (currentState != null) {
            return currentState.getName();
        }

        return "StateMachine not active";
    }

    /**
     * Update AP state.
     *
     * @param newState     new AP state
     * @param currentState current AP state
     * @param reason       Failure reason if the new AP state is in failure state
     */
    private void updateApState(int newState, int currentState, int reason) {
        mSoftApCallback.onStateChanged(newState, reason);

        //send the AP state change broadcast
        final Intent intent = new Intent(WifiManager.WIFI_AP_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_STATE, newState);
        intent.putExtra(WifiManager.EXTRA_PREVIOUS_WIFI_AP_STATE, currentState);
        if (newState == WifiManager.WIFI_AP_STATE_FAILED) {
            //only set reason number when softAP start failed
            intent.putExtra(WifiManager.EXTRA_WIFI_AP_FAILURE_REASON, reason);
        }

        intent.putExtra(WifiManager.EXTRA_WIFI_AP_INTERFACE_NAME, mApInterfaceName);
        intent.putExtra(WifiManager.EXTRA_WIFI_AP_MODE, mApConfig.getTargetMode());
        mContext.sendStickyBroadcastAsUser(intent, UserHandle.ALL);
    }

    private int setMacAddress() {
        MacAddress mac = mApConfig.getSoftApConfiguration().getBssid();

        if (mac == null) {
            // If no BSSID is explicitly requested, (re-)configure the factory MAC address. Some
            // drivers may not support setting the MAC at all, so fail soft in this case.
            if (!mWifiNative.resetApMacToFactoryMacAddress(mApInterfaceName)) {
                Log.w(getTag(), "failed to reset to factory MAC address; "
                        + "continuing with current MAC");
            }
        } else {
            if (mWifiNative.isApSetMacAddressSupported(mApInterfaceName)) {
                if (!mWifiNative.setApMacAddress(mApInterfaceName, mac)) {
                    Log.e(getTag(), "failed to set explicitly requested MAC address");
                    return ERROR_GENERIC;
                }
            } else if (!mIsUnsetBssid) {
                // If hardware does not support MAC address setter,
                // only report the error for non randomization.
                return ERROR_UNSUPPORTED_CONFIGURATION;
            }
        }

        return SUCCESS;
    }

    private int setCountryCode() {
        int band = mApConfig.getSoftApConfiguration().getBand();
        if (TextUtils.isEmpty(mCountryCode)) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Country code is mandatory for 5GHz band.
                Log.e(getTag(), "Invalid country code, required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Absence of country code is not fatal for 2Ghz & Any band options.
            return SUCCESS;
        }

        if (!mWifiNative.setApCountryCode(
                mApInterfaceName, mCountryCode.toUpperCase(Locale.ROOT))) {
            if (band == SoftApConfiguration.BAND_5GHZ) {
                // Return an error if failed to set country code when AP is configured for
                // 5GHz band.
                Log.e(getTag(), "Failed to set country code, "
                        + "required for setting up soft ap in 5GHz");
                return ERROR_GENERIC;
            }
            // Failure to set country code is not fatal for other band options.
        }
        return SUCCESS;
    }

    /**
     * Start a soft AP instance as configured.
     *
     * @return integer result code
     */
    private int startSoftAp() {
        SoftApConfiguration config = mApConfig.getSoftApConfiguration();
        Log.d(getTag(), "band " + config.getBand() + " iface "
                + mApInterfaceName + " country " + mCountryCode);

        int result = setMacAddress();
        if (result != SUCCESS) {
            return result;
        }

        result = setCountryCode();
        if (result != SUCCESS) {
            return result;
        }

        // Make a copy of configuration for updating AP band and channel.
        SoftApConfiguration.Builder localConfigBuilder = new SoftApConfiguration.Builder(config);

        boolean acsEnabled = mCurrentSoftApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_ACS_OFFLOAD);

        result = ApConfigUtil.updateApChannelConfig(
                mWifiNative, mCoexManager, mContext.getResources(), mCountryCode,
                localConfigBuilder, config, acsEnabled);
        if (result != SUCCESS) {
            Log.e(getTag(), "Failed to update AP band and channel");
            return result;
        }

        if (config.isHiddenSsid()) {
            Log.d(getTag(), "SoftAP is a hidden network");
        }

        if (!ApConfigUtil.checkSupportAllConfiguration(config, mCurrentSoftApCapability)) {
            Log.d(getTag(), "Unsupported Configuration detect! config = " + config);
            return ERROR_UNSUPPORTED_CONFIGURATION;
        }

        if (!mWifiNative.startSoftAp(mApInterfaceName,
                  localConfigBuilder.build(),
                  mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED,
                  mSoftApListener)) {
            Log.e(getTag(), "Soft AP start failed");
            return ERROR_GENERIC;
        }

        mWifiDiagnostics.startLogging(mApInterfaceName);
        mStartTimestamp = FORMATTER.format(new Date(System.currentTimeMillis()));
        Log.d(getTag(), "Soft AP is started ");

        return SUCCESS;
    }

    /**
     * Disconnect all connected clients on active softap interface(s).
     * This is usually done just before stopSoftAp().
     */
    private void disconnectAllClients() {
        for (WifiClient client : getConnectedClientList()) {
            mWifiNative.forceClientDisconnect(mApInterfaceName, client.getMacAddress(),
                    SAP_CLIENT_DISCONNECT_REASON_CODE_UNSPECIFIED);
        }
    }

    /**
     * Teardown soft AP and teardown the interface.
     */
    private void stopSoftAp() {
        disconnectAllClients();
        mWifiDiagnostics.stopLogging(mApInterfaceName);
        mWifiNative.teardownInterface(mApInterfaceName);
        Log.d(getTag(), "Soft AP is stopped");
    }

    private void addClientToPendingDisconnectionList(WifiClient client, int reason) {
        Log.d(getTag(), "Fail to disconnect client: " + client.getMacAddress()
                + ", add it into pending list");
        mPendingDisconnectClients.put(client, reason);
        mStateMachine.getHandler().removeMessages(
                SoftApStateMachine.CMD_FORCE_DISCONNECT_PENDING_CLIENTS);
        mStateMachine.sendMessageDelayed(
                SoftApStateMachine.CMD_FORCE_DISCONNECT_PENDING_CLIENTS,
                SOFT_AP_PENDING_DISCONNECTION_CHECK_DELAY_MS);
    }

    private List<WifiClient> getConnectedClientList() {
        List<WifiClient> connectedClientList = new ArrayList<>();
        for (List<WifiClient> it : mConnectedClientWithApInfoMap.values()) {
            connectedClientList.addAll(it);
        }
        return connectedClientList;
    }

    private boolean checkSoftApClient(SoftApConfiguration config, WifiClient newClient) {
        if (!mCurrentSoftApCapability.areFeaturesSupported(
                SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
            return true;
        }

        if (mBlockedClientList.contains(newClient.getMacAddress())) {
            Log.d(getTag(), "Force disconnect for client: " + newClient + "in blocked list");
            if (!mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER)) {
                addClientToPendingDisconnectionList(newClient,
                        WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            }
            return false;
        }
        if (config.isClientControlByUserEnabled()
                && !mAllowedClientList.contains(newClient.getMacAddress())) {
            mSoftApCallback.onBlockedClientConnecting(newClient,
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            Log.d(getTag(), "Force disconnect for unauthorized client: " + newClient);
            if (!mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER)) {
                addClientToPendingDisconnectionList(newClient,
                        WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
            }
            return false;
        }
        int maxConfig = mCurrentSoftApCapability.getMaxSupportedClients();
        if (config.getMaxNumberOfClients() > 0) {
            maxConfig = Math.min(maxConfig, config.getMaxNumberOfClients());
        }

        if (getConnectedClientList().size() >= maxConfig) {
            Log.i(getTag(), "No more room for new client:" + newClient);
            if (!mWifiNative.forceClientDisconnect(
                    mApInterfaceName, newClient.getMacAddress(),
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS)) {
                addClientToPendingDisconnectionList(newClient,
                        WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            }
            mSoftApCallback.onBlockedClientConnecting(newClient,
                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
            // Avoid report the max client blocked in the same settings.
            if (!mEverReportMetricsForMaxClient) {
                mWifiMetrics.noteSoftApClientBlocked(maxConfig);
                mEverReportMetricsForMaxClient = true;
            }
            return false;
        }
        return true;
    }

    private class SoftApStateMachine extends StateMachine {
        // Commands for the state machine.
        public static final int CMD_START = 0;
        public static final int CMD_STOP = 1;
        public static final int CMD_FAILURE = 2;
        public static final int CMD_INTERFACE_STATUS_CHANGED = 3;
        public static final int CMD_ASSOCIATED_STATIONS_CHANGED = 4;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT = 5;
        public static final int CMD_INTERFACE_DESTROYED = 7;
        public static final int CMD_INTERFACE_DOWN = 8;
        public static final int CMD_AP_INFO_CHANGED = 9;
        public static final int CMD_UPDATE_CAPABILITY = 10;
        public static final int CMD_UPDATE_CONFIG = 11;
        public static final int CMD_FORCE_DISCONNECT_PENDING_CLIENTS = 12;
        public static final int CMD_NO_ASSOCIATED_STATIONS_TIMEOUT_ON_ONE_INSTANCE = 13;

        private final State mIdleState = new IdleState();
        private final State mStartedState = new StartedState();

        private final InterfaceCallback mWifiNativeInterfaceCallback = new InterfaceCallback() {
            @Override
            public void onDestroyed(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_DESTROYED);
                }
            }

            @Override
            public void onUp(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 1);
                }
            }

            @Override
            public void onDown(String ifaceName) {
                if (mApInterfaceName != null && mApInterfaceName.equals(ifaceName)) {
                    sendMessage(CMD_INTERFACE_STATUS_CHANGED, 0);
                }
            }
        };

        SoftApStateMachine(Looper looper) {
            super(TAG, looper);

            // CHECKSTYLE:OFF IndentationCheck
            addState(mIdleState);
                addState(mStartedState, mIdleState);
            // CHECKSTYLE:ON IndentationCheck

            setInitialState(mIdleState);
            start();
        }

        private class IdleState extends State {
            @Override
            public void enter() {
                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
            }

            @Override
            public void exit() {
                mModeListener.onStopped(SoftApManager.this);
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_STOP:
                        mStateMachine.quitNow();
                        break;
                    case CMD_START:
                        mRequestorWs = (WorkSource) message.obj;
                        SoftApConfiguration config = mApConfig.getSoftApConfiguration();
                        if (config == null || config.getSsid() == null) {
                            Log.e(getTag(), "Unable to start soft AP without valid configuration");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            mModeListener.onStartFailure(SoftApManager.this);
                            break;
                        }
                        if (isBridgedMode()) {
                            boolean isFallbackToSingleAp = false;
                            int newSingleApBand = 0;
                            for (int targetBand : config.getBands()) {
                                int availableBand = ApConfigUtil.removeUnavailableBands(
                                        mCurrentSoftApCapability, targetBand);
                                if (targetBand != availableBand) {
                                    isFallbackToSingleAp = true;
                                }
                                newSingleApBand |= availableBand;
                            }
                            if (isFallbackToSingleAp) {
                                newSingleApBand = ApConfigUtil.append24GToBandIf24GSupported(
                                        newSingleApBand, mContext);
                                Log.i(getTag(), "Fallback to single AP mode with band "
                                        + newSingleApBand);
                                SoftApConfiguration singleBandConfig =
                                        new SoftApConfiguration.Builder(config)
                                        .setBand(newSingleApBand)
                                        .build();
                                mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                        singleBandConfig, mCurrentSoftApCapability);
                            }
                        }
                        mApInterfaceName = mWifiNative.setupInterfaceForSoftApMode(
                                mWifiNativeInterfaceCallback, mRequestorWs,
                                mApConfig.getSoftApConfiguration().getBand(), isBridgedMode());
                        if (TextUtils.isEmpty(mApInterfaceName)) {
                            Log.e(getTag(), "setup failure when creating ap interface.");
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_DISABLED,
                                    WifiManager.SAP_START_FAILURE_GENERAL);
                            mWifiMetrics.incrementSoftApStartResult(
                                    false, WifiManager.SAP_START_FAILURE_GENERAL);
                            mModeListener.onStartFailure(SoftApManager.this);
                            break;
                        }
                        mSoftApNotifier.dismissSoftApShutdownTimeoutExpiredNotification();
                        updateApState(WifiManager.WIFI_AP_STATE_ENABLING,
                                WifiManager.WIFI_AP_STATE_DISABLED, 0);
                        int result = startSoftAp();
                        if (result != SUCCESS) {
                            int failureReason = WifiManager.SAP_START_FAILURE_GENERAL;
                            if (result == ERROR_NO_CHANNEL) {
                                failureReason = WifiManager.SAP_START_FAILURE_NO_CHANNEL;
                            } else if (result == ERROR_UNSUPPORTED_CONFIGURATION) {
                                failureReason = WifiManager
                                        .SAP_START_FAILURE_UNSUPPORTED_CONFIGURATION;
                            }
                            updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                    WifiManager.WIFI_AP_STATE_ENABLING,
                                    failureReason);
                            stopSoftAp();
                            mWifiMetrics.incrementSoftApStartResult(false, failureReason);
                            mModeListener.onStartFailure(SoftApManager.this);
                            break;
                        }
                        transitionTo(mStartedState);
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        Log.d(getTag(), "Configuration changed to " + newConfig);
                        mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                newConfig, mCurrentSoftApCapability);
                        mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                        mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                        mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                        mBridgedModeOpportunisticsShutdownTimeoutEnabled =
                                newConfig.isBridgedModeOpportunisticShutdownEnabledInternal();
                        break;
                    default:
                        // Ignore all other commands.
                        break;
                }

                return HANDLED;
            }
        }

        private class StartedState extends State {
            private void scheduleTimeoutMessages() {
                // When SAP started, the mCurrentSoftApInfoMap is 0 because info does not update.
                // Don't trigger bridged mode shutdown timeout when only one active instance
                // In Dual AP, one instance may already be closed due to LTE coexistence or DFS
                // restrictions or due to inactivity. i.e. mCurrentSoftApInfoMap.size() is 1)
                final int connectedClients = getConnectedClientList().size();
                if (isBridgedMode() && mCurrentSoftApInfoMap.size() != 1) {
                    if (mBridgedModeOpportunisticsShutdownTimeoutEnabled
                            && (connectedClients == 0 || getIdleInstances().size() != 0)) {
                        if (!mIsBridgedModeIdleInstanceTimerActive) {
                            mSoftApBridgedModeIdleInstanceTimeoutMessage.schedule(SystemClock
                                    .elapsedRealtime()
                                    + mDefaultShutdownIdleInstanceInBridgedModeTimeoutMillis);
                            mIsBridgedModeIdleInstanceTimerActive = true;
                            Log.d(getTag(), "Bridged mode instance opportunistic timeout message"
                                    + " scheduled, delay = "
                                    + mDefaultShutdownIdleInstanceInBridgedModeTimeoutMillis);
                        }
                    } else {
                        cancelBridgedModeIdleInstanceTimeoutMessage();
                    }
                }
                if (!mTimeoutEnabled || connectedClients != 0) {
                    cancelTimeoutMessage();
                    return;
                }
                long timeout = getShutdownTimeoutMillis();
                mSoftApTimeoutMessage.schedule(SystemClock.elapsedRealtime()
                        + timeout);
                Log.d(getTag(), "Timeout message scheduled, delay = "
                        + timeout);
            }

            private Set<String> getIdleInstances() {
                Set<String> idleInstances = new HashSet<String>();
                for (String instance : mConnectedClientWithApInfoMap.keySet()) {
                    if (mConnectedClientWithApInfoMap.getOrDefault(
                            instance, Collections.emptyList()).size() == 0) {
                        idleInstances.add(instance);
                    }
                }
                return idleInstances;
            }

            private void cancelTimeoutMessage() {
                mSoftApTimeoutMessage.cancel();
                Log.d(getTag(), "Timeout message canceled");
            }

            private void cancelBridgedModeIdleInstanceTimeoutMessage() {
                mSoftApBridgedModeIdleInstanceTimeoutMessage.cancel();
                mIsBridgedModeIdleInstanceTimerActive = false;
                Log.d(getTag(), "Bridged mode idle instance timeout message canceled");
            }

            /**
             * When configuration changed, it need to force some clients disconnect to match the
             * configuration.
             */
            private void updateClientConnection() {
                if (!mCurrentSoftApCapability.areFeaturesSupported(
                        SoftApCapability.SOFTAP_FEATURE_CLIENT_FORCE_DISCONNECT)) {
                    return;
                }
                final int maxAllowedClientsByHardwareAndCarrier =
                        mCurrentSoftApCapability.getMaxSupportedClients();
                final int userApConfigMaxClientCount =
                        mApConfig.getSoftApConfiguration().getMaxNumberOfClients();
                int finalMaxClientCount = maxAllowedClientsByHardwareAndCarrier;
                if (userApConfigMaxClientCount > 0) {
                    finalMaxClientCount = Math.min(userApConfigMaxClientCount,
                            maxAllowedClientsByHardwareAndCarrier);
                }
                List<WifiClient> currentClients = getConnectedClientList();
                int targetDisconnectClientNumber = currentClients.size() - finalMaxClientCount;
                List<WifiClient> allowedConnectedList = new ArrayList<>();
                Iterator<WifiClient> iterator = currentClients.iterator();
                while (iterator.hasNext()) {
                    WifiClient client = iterator.next();
                    if (mBlockedClientList.contains(client.getMacAddress())
                              || (mApConfig.getSoftApConfiguration().isClientControlByUserEnabled()
                              && !mAllowedClientList.contains(client.getMacAddress()))) {
                        Log.d(getTag(), "Force disconnect for not allowed client: " + client);
                        if (!mWifiNative.forceClientDisconnect(
                                mApInterfaceName, client.getMacAddress(),
                                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER)) {
                            addClientToPendingDisconnectionList(client,
                                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_BLOCKED_BY_USER);
                        }
                        targetDisconnectClientNumber--;
                    } else {
                        allowedConnectedList.add(client);
                    }
                }

                if (targetDisconnectClientNumber > 0) {
                    Iterator<WifiClient> allowedClientIterator = allowedConnectedList.iterator();
                    while (allowedClientIterator.hasNext()) {
                        if (targetDisconnectClientNumber == 0) break;
                        WifiClient allowedClient = allowedClientIterator.next();
                        Log.d(getTag(), "Force disconnect for client due to no more room: "
                                + allowedClient);
                        if (!mWifiNative.forceClientDisconnect(
                                mApInterfaceName, allowedClient.getMacAddress(),
                                WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS)) {
                            addClientToPendingDisconnectionList(allowedClient,
                                    WifiManager.SAP_CLIENT_BLOCK_REASON_CODE_NO_MORE_STAS);
                        }
                        targetDisconnectClientNumber--;
                    }
                }
            }

            /**
             * Set stations associated with this soft AP
             * @param client The station for which connection state changed.
             * @param isConnected True for the connection changed to connect, otherwise false.
             */
            private void updateConnectedClients(WifiClient client, boolean isConnected) {
                if (client == null) {
                    return;
                }

                if (null != mPendingDisconnectClients.remove(client)) {
                    Log.d(getTag(), "Remove client: " + client.getMacAddress()
                            + "from pending disconnectionlist");
                }

                List clientList = mConnectedClientWithApInfoMap.computeIfAbsent(
                        client.getApInstanceIdentifier(), k -> new ArrayList<>());
                int index = clientList.indexOf(client);

                if ((index != -1) == isConnected) {
                    Log.e(getTag(), "Drop client connection event, client "
                            + client + "isConnected: " + isConnected
                            + " , duplicate event or client is blocked");
                    return;
                }
                if (isConnected) {
                    boolean isAllow = checkSoftApClient(
                            mApConfig.getSoftApConfiguration(), client);
                    if (isAllow) {
                        clientList.add(client);
                    } else {
                        return;
                    }
                } else {
                    if (null == clientList.remove(index)) {
                        Log.e(getTag(), "client doesn't exist in list, it should NOT happen");
                    }
                }

                // Update clients list.
                mConnectedClientWithApInfoMap.put(client.getApInstanceIdentifier(), clientList);
                SoftApInfo currentInfoWithClientsChanged = mCurrentSoftApInfoMap
                        .get(client.getApInstanceIdentifier());
                Log.d(getTag(), "The connected wifi stations have changed with count: "
                        + clientList.size() + ": " + clientList + " on the AP which info is "
                        + currentInfoWithClientsChanged);

                if (mSoftApCallback != null) {
                    mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                            mConnectedClientWithApInfoMap, isBridgedMode());
                } else {
                    Log.e(getTag(),
                            "SoftApCallback is null. Dropping ConnectedClientsChanged event.");
                }

                mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                        getConnectedClientList().size(), mApConfig.getTargetMode());

                scheduleTimeoutMessages();
            }

            /**
             * @param apInfo, the new SoftApInfo changed. Null used to clean up.
             */
            private void updateSoftApInfo(@Nullable SoftApInfo apInfo, boolean isRemoved) {
                Log.d(getTag(), "SoftApInfo update " + apInfo);
                if (apInfo == null) {
                    // Clean up
                    mCurrentSoftApInfoMap.clear();
                    mConnectedClientWithApInfoMap.clear();
                    mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                            mConnectedClientWithApInfoMap, isBridgedMode());
                    return;
                }

                if (apInfo.equals(mCurrentSoftApInfoMap.get(apInfo.getApInstanceIdentifier()))) {
                    if (isRemoved) {
                        mCurrentSoftApInfoMap.remove(apInfo.getApInstanceIdentifier());
                        mConnectedClientWithApInfoMap.remove(apInfo.getApInstanceIdentifier());
                        mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                                mConnectedClientWithApInfoMap, isBridgedMode());
                    }
                    return;
                }

                // Make sure an empty client list is created when info updated
                List clientList = mConnectedClientWithApInfoMap.computeIfAbsent(
                        apInfo.getApInstanceIdentifier(), k -> new ArrayList<>());

                if (clientList.size() != 0) {
                    Log.e(getTag(), "The info: " + apInfo
                            + " changed when client connected, it should NOT happen!!");
                }

                // Update the info when getting two infos in bridged mode.
                // TODO: b/173999527. It may only one instance come up when starting bridged AP.
                // Consider the handling with co-ex mechanism in bridged mode.
                boolean waitForAnotherSoftApInfoInBridgedMode =
                        isBridgedMode() && mCurrentSoftApInfoMap.size() == 0;

                mCurrentSoftApInfoMap.put(apInfo.getApInstanceIdentifier(), new SoftApInfo(apInfo));
                if (!waitForAnotherSoftApInfoInBridgedMode) {
                    mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                            mConnectedClientWithApInfoMap, isBridgedMode());
                }

                // ignore invalid freq and softap disable case for metrics
                if (apInfo.getFrequency() > 0
                        && apInfo.getBandwidth() != SoftApInfo.CHANNEL_WIDTH_INVALID) {
                    // TODO: b/173999527 Update the metrics in bridged AP mode.
                    mWifiMetrics.addSoftApChannelSwitchedEvent(new SoftApInfo(apInfo),
                            mApConfig.getTargetMode());
                    updateUserBandPreferenceViolationMetricsIfNeeded(apInfo);
                }
            }

            private void onUpChanged(boolean isUp) {
                if (isUp == mIfaceIsUp) {
                    return;  // no change
                }

                mIfaceIsUp = isUp;
                if (isUp) {
                    Log.d(getTag(), "SoftAp is ready for use");
                    updateApState(WifiManager.WIFI_AP_STATE_ENABLED,
                            WifiManager.WIFI_AP_STATE_ENABLING, 0);
                    mModeListener.onStarted(SoftApManager.this);
                    mWifiMetrics.incrementSoftApStartResult(true, 0);
                    mCurrentSoftApInfoMap.clear();
                    mConnectedClientWithApInfoMap.clear();
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                                mConnectedClientWithApInfoMap, isBridgedMode());
                    }
                } else {
                    // the interface was up, but goes down
                    sendMessage(CMD_INTERFACE_DOWN);
                }
                mWifiMetrics.addSoftApUpChangedEvent(isUp, mApConfig.getTargetMode(),
                        mDefaultShutdownTimeoutMillis);
                if (isUp) {
                    mWifiMetrics.updateSoftApConfiguration(mApConfig.getSoftApConfiguration(),
                            mApConfig.getTargetMode());
                    mWifiMetrics.updateSoftApCapability(mCurrentSoftApCapability,
                            mApConfig.getTargetMode());
                }
            }

            @Override
            public void enter() {
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                onUpChanged(mWifiNative.isInterfaceUp(mApInterfaceName));

                Handler handler = mStateMachine.getHandler();
                mSoftApTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT);

                mSoftApBridgedModeIdleInstanceTimeoutMessage = new WakeupMessage(mContext, handler,
                        SOFT_AP_SEND_MESSAGE_IDLE_IN_BRIDGED_MODE_TIMEOUT_TAG,
                        SoftApStateMachine.CMD_NO_ASSOCIATED_STATIONS_TIMEOUT_ON_ONE_INSTANCE);

                mCoexManager.registerCoexListener(mCoexListener);
                Log.d(getTag(), "Resetting connected clients on start");
                mConnectedClientWithApInfoMap.clear();
                mPendingDisconnectClients.clear();
                mEverReportMetricsForMaxClient = false;
                scheduleTimeoutMessages();
            }

            @Override
            public void exit() {
                if (!mIfaceIsDestroyed) {
                    stopSoftAp();
                }

                mCoexManager.unregisterCoexListener(mCoexListener);
                if (getConnectedClientList().size() != 0) {
                    Log.d(getTag(), "Resetting num stations on stop");
                    mConnectedClientWithApInfoMap.clear();
                    if (mSoftApCallback != null) {
                        mSoftApCallback.onConnectedClientsOrInfoChanged(mCurrentSoftApInfoMap,
                                mConnectedClientWithApInfoMap, isBridgedMode());
                    }
                    mWifiMetrics.addSoftApNumAssociatedStationsChangedEvent(
                            0, mApConfig.getTargetMode());
                }
                mPendingDisconnectClients.clear();
                cancelTimeoutMessage();
                cancelBridgedModeIdleInstanceTimeoutMessage();

                // Need this here since we are exiting |Started| state and won't handle any
                // future CMD_INTERFACE_STATUS_CHANGED events after this point
                mWifiMetrics.addSoftApUpChangedEvent(false, mApConfig.getTargetMode(),
                        mDefaultShutdownTimeoutMillis);
                updateApState(WifiManager.WIFI_AP_STATE_DISABLED,
                        WifiManager.WIFI_AP_STATE_DISABLING, 0);

                mApInterfaceName = null;
                mIfaceIsUp = false;
                mIfaceIsDestroyed = false;
                mRole = null;
                updateSoftApInfo(null, false);
            }

            private void updateUserBandPreferenceViolationMetricsIfNeeded(SoftApInfo apInfo) {
                // The band preference violation only need to detect in single AP mode.
                if (isBridgedMode()) return;
                int band = mApConfig.getSoftApConfiguration().getBand();
                boolean bandPreferenceViolated =
                        (ScanResult.is24GHz(apInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_2GHZ))
                        || (ScanResult.is5GHz(apInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_5GHZ))
                        || (ScanResult.is6GHz(apInfo.getFrequency())
                            && !ApConfigUtil.containsBand(band,
                                    SoftApConfiguration.BAND_6GHZ));

                if (bandPreferenceViolated) {
                    Log.e(getTag(), "Channel does not satisfy user band preference: "
                            + apInfo.getFrequency());
                    mWifiMetrics.incrementNumSoftApUserBandPreferenceUnsatisfied();
                }
            }

            @Override
            public boolean processMessage(Message message) {
                switch (message.what) {
                    case CMD_ASSOCIATED_STATIONS_CHANGED:
                        if (!(message.obj instanceof WifiClient)) {
                            Log.e(getTag(), "Invalid type returned for"
                                    + " CMD_ASSOCIATED_STATIONS_CHANGED");
                            break;
                        }
                        boolean isConnected = (message.arg1 == 1);
                        WifiClient client = (WifiClient) message.obj;
                        Log.d(getTag(), "CMD_ASSOCIATED_STATIONS_CHANGED, Client: "
                                + client.getMacAddress().toString() + " isConnected: "
                                + isConnected);
                        updateConnectedClients(client, isConnected);
                        break;
                    case CMD_AP_INFO_CHANGED:
                        if (!(message.obj instanceof SoftApInfo)) {
                            Log.e(getTag(), "Invalid type returned for"
                                    + " CMD_AP_INFO_CHANGED");
                            break;
                        }
                        SoftApInfo apInfo = (SoftApInfo) message.obj;
                        if (apInfo.getFrequency() < 0) {
                            Log.e(getTag(), "Invalid ap channel frequency: "
                                    + apInfo.getFrequency());
                            break;
                        }
                        // Update shutdown timeout
                        apInfo.setAutoShutdownTimeoutMillis(mTimeoutEnabled
                                ? getShutdownTimeoutMillis() : 0);
                        updateSoftApInfo(apInfo, false);
                        break;
                    case CMD_INTERFACE_STATUS_CHANGED:
                        boolean isUp = message.arg1 == 1;
                        onUpChanged(isUp);
                        break;
                    case CMD_STOP:
                        if (mIfaceIsUp) {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                    WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        } else {
                            updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                    WifiManager.WIFI_AP_STATE_ENABLING, 0);
                        }
                        quitNow();
                        break;
                    case CMD_START:
                        // Already started, ignore this command.
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT:
                        if (!mTimeoutEnabled) {
                            Log.wtf(getTag(), "Timeout message received while timeout is disabled."
                                    + " Dropping.");
                            break;
                        }
                        if (getConnectedClientList().size() != 0) {
                            Log.wtf(getTag(), "Timeout message received but has clients. "
                                    + "Dropping.");
                            break;
                        }
                        mSoftApNotifier.showSoftApShutdownTimeoutExpiredNotification();
                        Log.i(getTag(), "Timeout message received. Stopping soft AP.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        quitNow();
                        break;
                    case CMD_NO_ASSOCIATED_STATIONS_TIMEOUT_ON_ONE_INSTANCE:
                        if (!mBridgedModeOpportunisticsShutdownTimeoutEnabled) {
                            Log.wtf(getTag(), "Bridged Mode Timeout message received"
                                    + " while timeout is disabled. Dropping.");
                            break;
                        }
                        Set<String> idleInstances = getIdleInstances();
                        if (idleInstances.size() == 0) {
                            break;
                        }
                        Log.d(getTag(), "Instance idle timout, the number of the idle instances is "
                                + idleInstances.size());
                        String shutDownInstanceName = "";
                        int currentMaximumFrequencyOnAP = 0;
                        for (String instance : idleInstances) {
                            int frequencyOnInstance =
                                    mCurrentSoftApInfoMap.get(instance).getFrequency();
                            if (frequencyOnInstance > currentMaximumFrequencyOnAP) {
                                currentMaximumFrequencyOnAP = frequencyOnInstance;
                                shutDownInstanceName = instance;
                            }
                        }
                        if (!shutDownInstanceName.isEmpty()) {
                            Log.i(getTag(), "remove instance " + shutDownInstanceName
                                    + " from bridged iface " + mApInterfaceName);
                            mWifiNative.removeIfaceInstanceFromBridgedApIface(mApInterfaceName,
                                    shutDownInstanceName);
                            // Remove the info and update it.
                            updateSoftApInfo(mCurrentSoftApInfoMap.get(shutDownInstanceName), true);
                        }
                        break;
                    case CMD_INTERFACE_DESTROYED:
                        Log.d(getTag(), "Interface was cleanly destroyed.");
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_ENABLED, 0);
                        mIfaceIsDestroyed = true;
                        quitNow();
                        break;
                    case CMD_FAILURE:
                        Log.w(getTag(), "hostapd failure, stop and report failure");
                        /* fall through */
                    case CMD_INTERFACE_DOWN:
                        Log.w(getTag(), "interface error, stop and report failure");
                        updateApState(WifiManager.WIFI_AP_STATE_FAILED,
                                WifiManager.WIFI_AP_STATE_ENABLED,
                                WifiManager.SAP_START_FAILURE_GENERAL);
                        updateApState(WifiManager.WIFI_AP_STATE_DISABLING,
                                WifiManager.WIFI_AP_STATE_FAILED, 0);
                        quitNow();
                        break;
                    case CMD_UPDATE_CAPABILITY:
                        // Capability should only changed by carrier requirement. Only apply to
                        // Tether Mode
                        if (mApConfig.getTargetMode() ==  WifiManager.IFACE_IP_MODE_TETHERED) {
                            SoftApCapability capability = (SoftApCapability) message.obj;
                            mCurrentSoftApCapability = new SoftApCapability(capability);
                            mWifiMetrics.updateSoftApCapability(mCurrentSoftApCapability,
                                    mApConfig.getTargetMode());
                            updateClientConnection();
                        }
                        break;
                    case CMD_UPDATE_CONFIG:
                        SoftApConfiguration newConfig = (SoftApConfiguration) message.obj;
                        SoftApConfiguration currentConfig = mApConfig.getSoftApConfiguration();
                        if (mIsUnsetBssid) {
                            // Current bssid is ramdonized because unset. Set back to null.
                            currentConfig = new SoftApConfiguration.Builder(currentConfig)
                                    .setBssid(null)
                                    .build();
                        }
                        if (!ApConfigUtil.checkConfigurationChangeNeedToRestart(
                                currentConfig, newConfig)) {
                            Log.d(getTag(), "Configuration changed to " + newConfig);
                            if (mApConfig.getSoftApConfiguration().getMaxNumberOfClients()
                                    != newConfig.getMaxNumberOfClients()) {
                                Log.d(getTag(), "Max Client changed, reset to record the metrics");
                                mEverReportMetricsForMaxClient = false;
                            }
                            boolean needRescheduleTimer =
                                    mApConfig.getSoftApConfiguration().getShutdownTimeoutMillis()
                                    != newConfig.getShutdownTimeoutMillis()
                                    || mTimeoutEnabled != newConfig.isAutoShutdownEnabled()
                                    || mBridgedModeOpportunisticsShutdownTimeoutEnabled
                                    != newConfig
                                    .isBridgedModeOpportunisticShutdownEnabledInternal();
                            mBlockedClientList = new HashSet<>(newConfig.getBlockedClientList());
                            mAllowedClientList = new HashSet<>(newConfig.getAllowedClientList());
                            mTimeoutEnabled = newConfig.isAutoShutdownEnabled();
                            mBridgedModeOpportunisticsShutdownTimeoutEnabled =
                                    newConfig.isBridgedModeOpportunisticShutdownEnabledInternal();
                            mApConfig = new SoftApModeConfiguration(mApConfig.getTargetMode(),
                                    newConfig, mCurrentSoftApCapability);
                            updateClientConnection();
                            if (needRescheduleTimer) {
                                cancelTimeoutMessage();
                                cancelBridgedModeIdleInstanceTimeoutMessage();
                                scheduleTimeoutMessages();
                                // Update SoftApInfo
                                for (SoftApInfo info : mCurrentSoftApInfoMap.values()) {
                                    SoftApInfo newInfo = new SoftApInfo(info);
                                    newInfo.setAutoShutdownTimeoutMillis(mTimeoutEnabled
                                            ? getShutdownTimeoutMillis() : 0);
                                    updateSoftApInfo(newInfo, false);
                                }
                            }
                            mWifiMetrics.updateSoftApConfiguration(
                                    mApConfig.getSoftApConfiguration(),
                                    mApConfig.getTargetMode());
                        } else {
                            Log.d(getTag(), "Ignore the config: " + newConfig
                                    + " update since it requires restart");
                        }
                        break;
                    case CMD_FORCE_DISCONNECT_PENDING_CLIENTS:
                        if (mPendingDisconnectClients.size() != 0) {
                            Log.d(getTag(), "Disconnect pending list is NOT empty");
                            mPendingDisconnectClients.forEach((pendingClient, reason)->
                                    mWifiNative.forceClientDisconnect(mApInterfaceName,
                                    pendingClient.getMacAddress(), reason));
                            sendMessageDelayed(
                                    SoftApStateMachine.CMD_FORCE_DISCONNECT_PENDING_CLIENTS,
                                    SOFT_AP_PENDING_DISCONNECTION_CHECK_DELAY_MS);
                        }
                        break;
                    default:
                        return NOT_HANDLED;
                }
                return HANDLED;
            }
        }
    }
}
