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

package com.android.server.wifi.aware;

import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_128;
import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_PK_256;
import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_128;
import static android.net.wifi.aware.Characteristics.WIFI_AWARE_CIPHER_SUITE_NCS_SK_256;

import android.hardware.wifi.V1_0.IWifiNanIface;
import android.hardware.wifi.V1_0.NanBandIndex;
import android.hardware.wifi.V1_0.NanBandSpecificConfig;
import android.hardware.wifi.V1_0.NanConfigRequest;
import android.hardware.wifi.V1_0.NanDataPathSecurityType;
import android.hardware.wifi.V1_0.NanEnableRequest;
import android.hardware.wifi.V1_0.NanInitiateDataPathRequest;
import android.hardware.wifi.V1_0.NanMatchAlg;
import android.hardware.wifi.V1_0.NanPublishRequest;
import android.hardware.wifi.V1_0.NanRangingIndication;
import android.hardware.wifi.V1_0.NanRespondToDataPathIndicationRequest;
import android.hardware.wifi.V1_0.NanSubscribeRequest;
import android.hardware.wifi.V1_0.NanTransmitFollowupRequest;
import android.hardware.wifi.V1_0.NanTxType;
import android.hardware.wifi.V1_0.WifiStatus;
import android.hardware.wifi.V1_0.WifiStatusCode;
import android.hardware.wifi.V1_6.NanCipherSuiteType;
import android.net.wifi.aware.AwareParams;
import android.net.wifi.aware.ConfigRequest;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.WifiAwareDataPathSecurityConfig;
import android.net.wifi.util.HexEncoding;
import android.os.RemoteException;
import android.util.ArrayMap;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BasicShellCommandHandler;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Translates Wi-Fi Aware requests from the framework to the HAL (HIDL).
 *
 * Delegates the management of the NAN interface to WifiAwareNativeManager.
 */
public class WifiAwareNativeApi implements WifiAwareShellCommand.DelegatedShellCommand {
    private static final String TAG = "WifiAwareNativeApi";
    private static final boolean VDBG = false; // STOPSHIP if true
    private boolean mDbg = false;

    @VisibleForTesting
    static final String SERVICE_NAME_FOR_OOB_DATA_PATH = "Wi-Fi Aware Data Path";

    private final WifiAwareNativeManager mHal;
    private SparseIntArray mTransactionIds; // VDBG only!

    public WifiAwareNativeApi(WifiAwareNativeManager wifiAwareNativeManager) {
        mHal = wifiAwareNativeManager;
        onReset();
        if (VDBG) {
            mTransactionIds = new SparseIntArray();
        }
    }

    /**
     * Enable verbose logging.
     */
    public void enableVerboseLogging(boolean verbose) {
        mDbg = verbose | VDBG;
    }

    private void recordTransactionId(int transactionId) {
        if (!VDBG) return;

        if (transactionId == 0) {
            return; // tid == 0 is used as a placeholder transaction ID in several commands
        }

        int count = mTransactionIds.get(transactionId);
        if (count != 0) {
            Log.wtf(TAG, "Repeated transaction ID == " + transactionId);
        }
        mTransactionIds.append(transactionId, count + 1);
    }

    /**
     * (HIDL) Cast the input to a 1.2 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_2.IWifiNanIface mockableCastTo_1_2(IWifiNanIface iface) {
        return android.hardware.wifi.V1_2.IWifiNanIface.castFrom(iface);
    }

    /**
     * (HIDL) Cast the input to a 1.4 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_4.IWifiNanIface mockableCastTo_1_4(IWifiNanIface iface) {
        return android.hardware.wifi.V1_4.IWifiNanIface.castFrom(iface);
    }

    /**
     * (HIDL) Cast the input to a 1.5 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_5.IWifiNanIface mockableCastTo_1_5(IWifiNanIface iface) {
        return android.hardware.wifi.V1_5.IWifiNanIface.castFrom(iface);
    }

    /**
     * (HIDL) Cast the input to a 1.6 NAN interface (possibly resulting in a null).
     *
     * Separate function so can be mocked in unit tests.
     */
    public android.hardware.wifi.V1_6.IWifiNanIface mockableCastTo_1_6(IWifiNanIface iface) {
        return android.hardware.wifi.V1_6.IWifiNanIface.castFrom(iface);
    }

    /*
     * Parameters settable through the shell command.
     * see wifi/1.0/types.hal NanBandSpecificConfig.discoveryWindowIntervalVal and
     * wifi/1.2/types.hal NanConfigRequestSupplemental_1_2 for description
     */
    /* package */ static final String POWER_PARAM_DEFAULT_KEY = "default";
    /* package */ static final String POWER_PARAM_INACTIVE_KEY = "inactive";
    /* package */ static final String POWER_PARAM_IDLE_KEY = "idle";

    /* package */ static final String PARAM_DW_24GHZ = "dw_24ghz";
    private static final int PARAM_DW_24GHZ_DEFAULT = 1; // 1 -> DW=1, latency=512ms
    private static final int PARAM_DW_24GHZ_INACTIVE = 4; // 4 -> DW=8, latency=4s
    private static final int PARAM_DW_24GHZ_IDLE = 4; // == inactive

    /* package */ static final String PARAM_DW_5GHZ = "dw_5ghz";
    private static final int PARAM_DW_5GHZ_DEFAULT = 1; // 1 -> DW=1, latency=512ms
    private static final int PARAM_DW_5GHZ_INACTIVE = 0; // 0 = disabled
    private static final int PARAM_DW_5GHZ_IDLE = 0; // == inactive

    // TODO:
    /* package */ static final String PARAM_DW_6GHZ = "dw_6ghz";
    private static final int PARAM_DW_6GHZ_DEFAULT = 1; // 1 -> DW=1, latency=512ms
    private static final int PARAM_DW_6GHZ_INACTIVE = 0; // 0 = disabled
    private static final int PARAM_DW_6GHZ_IDLE = 0; // == inactive

    /* package */ static final String PARAM_DISCOVERY_BEACON_INTERVAL_MS =
            "disc_beacon_interval_ms";
    private static final int PARAM_DISCOVERY_BEACON_INTERVAL_MS_DEFAULT = 0; // Firmware defaults
    private static final int PARAM_DISCOVERY_BEACON_INTERVAL_MS_INACTIVE = 0; // Firmware defaults
    private static final int PARAM_DISCOVERY_BEACON_INTERVAL_MS_IDLE = 0; // Firmware defaults

    /* package */ static final String PARAM_NUM_SS_IN_DISCOVERY = "num_ss_in_discovery";
    private static final int PARAM_NUM_SS_IN_DISCOVERY_DEFAULT = 0; // Firmware defaults
    private static final int PARAM_NUM_SS_IN_DISCOVERY_INACTIVE = 0; // Firmware defaults
    private static final int PARAM_NUM_SS_IN_DISCOVERY_IDLE = 0; // Firmware defaults

    /* package */ static final String PARAM_ENABLE_DW_EARLY_TERM = "enable_dw_early_term";
    private static final int PARAM_ENABLE_DW_EARLY_TERM_DEFAULT = 0; // boolean: 0 = false
    private static final int PARAM_ENABLE_DW_EARLY_TERM_INACTIVE = 0; // boolean: 0 = false
    private static final int PARAM_ENABLE_DW_EARLY_TERM_IDLE = 0; // boolean: 0 = false

    /* package */ static final String PARAM_MAC_RANDOM_INTERVAL_SEC = "mac_random_interval_sec";
    private static final int PARAM_MAC_RANDOM_INTERVAL_SEC_DEFAULT = 1800; // 30 minutes

    private final Map<String, Map<String, Integer>> mSettablePowerParameters = new HashMap<>();
    private final Map<String, Integer> mSettableParameters = new HashMap<>();
    private final Map<String, Integer> mExternalSetParams = new ArrayMap<>();

    /**
     * Accept using parameter from external to config the Aware
     */
    public void setAwareParams(AwareParams parameters) {
        mExternalSetParams.clear();
        if (parameters == null) {
            return;
        }
        if (mDbg) {
            Log.v(TAG, "setting Aware Parameters=" + parameters);
        }
        if (parameters.getDiscoveryWindowWakeInterval24Ghz() > 0
                && parameters.getDiscoveryWindowWakeInterval24Ghz() <= 5) {
            mExternalSetParams.put(PARAM_DW_24GHZ,
                    parameters.getDiscoveryWindowWakeInterval24Ghz());
        }
        if (parameters.getDiscoveryWindowWakeInterval5Ghz() >= 0
                && parameters.getDiscoveryWindowWakeInterval5Ghz() <= 5) {
            mExternalSetParams.put(PARAM_DW_5GHZ, parameters.getDiscoveryWindowWakeInterval5Ghz());
        }
        if (parameters.getDiscoveryBeaconIntervalMillis() > 0) {
            mExternalSetParams.put(PARAM_DISCOVERY_BEACON_INTERVAL_MS,
                    parameters.getDiscoveryBeaconIntervalMillis());
        }
        if (parameters.getNumSpatialStreamsInDiscovery() > 0) {
            mExternalSetParams.put(PARAM_NUM_SS_IN_DISCOVERY,
                    parameters.getNumSpatialStreamsInDiscovery());
        }
        if (parameters.getMacRandomizationIntervalSeconds() > 0
                && parameters.getMacRandomizationIntervalSeconds() <= 1800) {
            mExternalSetParams.put(PARAM_MAC_RANDOM_INTERVAL_SEC,
                    parameters.getMacRandomizationIntervalSeconds());
        }
        if (parameters.isDwEarlyTerminationEnabled()) {
            mExternalSetParams.put(PARAM_ENABLE_DW_EARLY_TERM, 1);
        }
    }


    /**
     * Interpreter of adb shell command 'adb shell wifiaware native_api ...'.
     *
     * @return -1 if parameter not recognized or invalid value, 0 otherwise.
     */
    @Override
    public int onCommand(BasicShellCommandHandler parentShell) {
        final PrintWriter pw = parentShell.getErrPrintWriter();

        String subCmd = parentShell.getNextArgRequired();
        if (VDBG) Log.v(TAG, "onCommand: subCmd='" + subCmd + "'");
        switch (subCmd) {
            case "set": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                String valueStr = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: valueStr='" + valueStr + "'");
                int value;
                try {
                    value = Integer.valueOf(valueStr);
                } catch (NumberFormatException e) {
                    pw.println("Can't convert value to integer -- '" + valueStr + "'");
                    return -1;
                }
                mSettableParameters.put(name, value);
                return 0;
            }
            case "set-power": {
                String mode = parentShell.getNextArgRequired();
                String name = parentShell.getNextArgRequired();
                String valueStr = parentShell.getNextArgRequired();

                if (VDBG) {
                    Log.v(TAG, "onCommand: mode='" + mode + "', name='" + name + "'" + ", value='"
                            + valueStr + "'");
                }

                if (!mSettablePowerParameters.containsKey(mode)) {
                    pw.println("Unknown mode name -- '" + mode + "'");
                    return -1;
                }
                if (!mSettablePowerParameters.get(mode).containsKey(name)) {
                    pw.println("Unknown parameter name '" + name + "' in mode '" + mode + "'");
                    return -1;
                }

                int value;
                try {
                    value = Integer.valueOf(valueStr);
                } catch (NumberFormatException e) {
                    pw.println("Can't convert value to integer -- '" + valueStr + "'");
                    return -1;
                }
                mSettablePowerParameters.get(mode).put(name, value);
                return 0;
            }
            case "get": {
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: name='" + name + "'");
                if (!mSettableParameters.containsKey(name)) {
                    pw.println("Unknown parameter name -- '" + name + "'");
                    return -1;
                }

                parentShell.getOutPrintWriter().println((int) mSettableParameters.get(name));
                return 0;
            }
            case "get-power": {
                String mode = parentShell.getNextArgRequired();
                String name = parentShell.getNextArgRequired();
                if (VDBG) Log.v(TAG, "onCommand: mode='" + mode + "', name='" + name + "'");
                if (!mSettablePowerParameters.containsKey(mode)) {
                    pw.println("Unknown mode -- '" + mode + "'");
                    return -1;
                }
                if (!mSettablePowerParameters.get(mode).containsKey(name)) {
                    pw.println("Unknown parameter name -- '" + name + "' in mode '" + mode + "'");
                    return -1;
                }

                parentShell.getOutPrintWriter().println(
                        (int) mSettablePowerParameters.get(mode).get(name));
                return 0;
            }
            default:
                pw.println("Unknown 'wifiaware native_api <cmd>'");
        }

        return -1;
    }

    @Override
    public void onReset() {
        Map<String, Integer> defaultMap = new HashMap<>();
        defaultMap.put(PARAM_DW_24GHZ, PARAM_DW_24GHZ_DEFAULT);
        defaultMap.put(PARAM_DW_5GHZ, PARAM_DW_5GHZ_DEFAULT);
        defaultMap.put(PARAM_DW_6GHZ, PARAM_DW_6GHZ_DEFAULT);
        defaultMap.put(PARAM_DISCOVERY_BEACON_INTERVAL_MS,
                PARAM_DISCOVERY_BEACON_INTERVAL_MS_DEFAULT);
        defaultMap.put(PARAM_NUM_SS_IN_DISCOVERY, PARAM_NUM_SS_IN_DISCOVERY_DEFAULT);
        defaultMap.put(PARAM_ENABLE_DW_EARLY_TERM, PARAM_ENABLE_DW_EARLY_TERM_DEFAULT);

        Map<String, Integer> inactiveMap = new HashMap<>();
        inactiveMap.put(PARAM_DW_24GHZ, PARAM_DW_24GHZ_INACTIVE);
        inactiveMap.put(PARAM_DW_5GHZ, PARAM_DW_5GHZ_INACTIVE);
        inactiveMap.put(PARAM_DW_6GHZ, PARAM_DW_6GHZ_INACTIVE);
        inactiveMap.put(PARAM_DISCOVERY_BEACON_INTERVAL_MS,
                PARAM_DISCOVERY_BEACON_INTERVAL_MS_INACTIVE);
        inactiveMap.put(PARAM_NUM_SS_IN_DISCOVERY, PARAM_NUM_SS_IN_DISCOVERY_INACTIVE);
        inactiveMap.put(PARAM_ENABLE_DW_EARLY_TERM, PARAM_ENABLE_DW_EARLY_TERM_INACTIVE);

        Map<String, Integer> idleMap = new HashMap<>();
        idleMap.put(PARAM_DW_24GHZ, PARAM_DW_24GHZ_IDLE);
        idleMap.put(PARAM_DW_5GHZ, PARAM_DW_5GHZ_IDLE);
        idleMap.put(PARAM_DW_6GHZ, PARAM_DW_6GHZ_IDLE);
        idleMap.put(PARAM_DISCOVERY_BEACON_INTERVAL_MS,
                PARAM_DISCOVERY_BEACON_INTERVAL_MS_IDLE);
        idleMap.put(PARAM_NUM_SS_IN_DISCOVERY, PARAM_NUM_SS_IN_DISCOVERY_IDLE);
        idleMap.put(PARAM_ENABLE_DW_EARLY_TERM, PARAM_ENABLE_DW_EARLY_TERM_IDLE);

        mSettablePowerParameters.put(POWER_PARAM_DEFAULT_KEY, defaultMap);
        mSettablePowerParameters.put(POWER_PARAM_INACTIVE_KEY, inactiveMap);
        mSettablePowerParameters.put(POWER_PARAM_IDLE_KEY, idleMap);

        mSettableParameters.put(PARAM_MAC_RANDOM_INTERVAL_SEC,
                PARAM_MAC_RANDOM_INTERVAL_SEC_DEFAULT);
        mExternalSetParams.clear();
    }

    @Override
    public void onHelp(String command, BasicShellCommandHandler parentShell) {
        final PrintWriter pw = parentShell.getOutPrintWriter();

        pw.println("  " + command);
        pw.println("    set <name> <value>: sets named parameter to value. Names: "
                + mSettableParameters.keySet());
        pw.println("    set-power <mode> <name> <value>: sets named power parameter to value."
                + " Modes: " + mSettablePowerParameters.keySet()
                + ", Names: " + mSettablePowerParameters.get(POWER_PARAM_DEFAULT_KEY).keySet());
        pw.println("    get <name>: gets named parameter value. Names: "
                + mSettableParameters.keySet());
        pw.println("    get-power <mode> <name>: gets named parameter value."
                + " Modes: " + mSettablePowerParameters.keySet()
                + ", Names: " + mSettablePowerParameters.get(POWER_PARAM_DEFAULT_KEY).keySet());
    }

    /**
     * Query the firmware's capabilities.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     */
    public boolean getCapabilities(short transactionId) {
        if (mDbg) Log.v(TAG, "getCapabilities: transactionId=" + transactionId);
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "getCapabilities: null interface");
            return false;
        }
        android.hardware.wifi.V1_5.IWifiNanIface iface15 = mockableCastTo_1_5(iface);


        try {
            WifiStatus status;
            if (iface15 == null) {
                status = iface.getCapabilitiesRequest(transactionId);
            }  else {
                status = iface15.getCapabilitiesRequest_1_5(transactionId);
            }
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "getCapabilities: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "getCapabilities: exception: " + e);
            return false;
        }
    }

    /**
     * Enable and configure Aware.
     * @param transactionId Transaction ID for the transaction - used in the
     *            async callback to match with the original request.
     * @param configRequest Requested Aware configuration.
     * @param notifyIdentityChange Indicates whether or not to get address change callbacks.
     * @param initialConfiguration Specifies whether initial configuration
*            (true) or an update (false) to the configuration.
     * @param isInteractive PowerManager.isInteractive
     * @param isIdle PowerManager.isIdle
     * @param rangingEnabled Indicates whether or not enable ranging.
     * @param isInstantCommunicationEnabled Indicates whether or not enable instant communication
     * @param instantModeChannel
     */
    public boolean enableAndConfigure(short transactionId, ConfigRequest configRequest,
            boolean notifyIdentityChange, boolean initialConfiguration, boolean isInteractive,
            boolean isIdle, boolean rangingEnabled, boolean isInstantCommunicationEnabled,
            int instantModeChannel) {
        if (mDbg) {
            Log.v(TAG, "enableAndConfigure: transactionId=" + transactionId + ", configRequest="
                    + configRequest + ", notifyIdentityChange=" + notifyIdentityChange
                    + ", initialConfiguration=" + initialConfiguration
                    + ", isInteractive=" + isInteractive + ", isIdle=" + isIdle
                    + ", isRangingEnabled=" + rangingEnabled
                    + ", isInstantCommunicationEnabled=" + isInstantCommunicationEnabled
                    + ", instantModeChannel=" + instantModeChannel);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "enableAndConfigure: null interface");
            return false;
        }
        android.hardware.wifi.V1_2.IWifiNanIface iface12 = mockableCastTo_1_2(iface);
        android.hardware.wifi.V1_4.IWifiNanIface iface14 = mockableCastTo_1_4(iface);
        android.hardware.wifi.V1_5.IWifiNanIface iface15 = mockableCastTo_1_5(iface);
        android.hardware.wifi.V1_6.IWifiNanIface iface16 = mockableCastTo_1_6(iface);
        android.hardware.wifi.V1_2.NanConfigRequestSupplemental configSupplemental12 =
                new android.hardware.wifi.V1_2.NanConfigRequestSupplemental();
        android.hardware.wifi.V1_5.NanConfigRequestSupplemental configSupplemental15 =
                new android.hardware.wifi.V1_5.NanConfigRequestSupplemental();
        android.hardware.wifi.V1_6.NanConfigRequestSupplemental configSupplemental16 =
                new android.hardware.wifi.V1_6.NanConfigRequestSupplemental();
        if (iface12 != null || iface14 != null) {
            configSupplemental12.discoveryBeaconIntervalMs = 0;
            configSupplemental12.numberOfSpatialStreamsInDiscovery = 0;
            configSupplemental12.enableDiscoveryWindowEarlyTermination = false;
            configSupplemental12.enableRanging = rangingEnabled;
        }

        if (iface15 != null) {
            configSupplemental15.V1_2 = configSupplemental12;
            configSupplemental15.enableInstantCommunicationMode = isInstantCommunicationEnabled;
        }
        if (iface16 != null) {
            configSupplemental16.V1_5 = configSupplemental15;
            configSupplemental16.instantModeChannel = instantModeChannel;
        }

        NanBandSpecificConfig config24 = new NanBandSpecificConfig();
        config24.rssiClose = 60;
        config24.rssiMiddle = 70;
        config24.rssiCloseProximity = 60;
        config24.dwellTimeMs = (byte) 200;
        config24.scanPeriodSec = 20;
        if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_24GHZ]
                == ConfigRequest.DW_INTERVAL_NOT_INIT) {
            config24.validDiscoveryWindowIntervalVal = false;
        } else {
            config24.validDiscoveryWindowIntervalVal = true;
            config24.discoveryWindowIntervalVal =
                    (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                            .NAN_BAND_24GHZ];
        }

        NanBandSpecificConfig config5 = new NanBandSpecificConfig();
        config5.rssiClose = 60;
        config5.rssiMiddle = 75;
        config5.rssiCloseProximity = 60;
        config5.dwellTimeMs = (byte) 200;
        config5.scanPeriodSec = 20;
        if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_5GHZ]
                == ConfigRequest.DW_INTERVAL_NOT_INIT) {
            config5.validDiscoveryWindowIntervalVal = false;
        } else {
            config5.validDiscoveryWindowIntervalVal = true;
            config5.discoveryWindowIntervalVal =
                    (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                            .NAN_BAND_5GHZ];
        }

        // TODO: b/145609058
        // Need to review values for this config, currently it is a copy from config5
        NanBandSpecificConfig config6 = new NanBandSpecificConfig();
        config6.rssiClose = 60;
        config6.rssiMiddle = 75;
        config6.rssiCloseProximity = 60;
        config6.dwellTimeMs = (byte) 200;
        config6.scanPeriodSec = 20;
        if (configRequest.mDiscoveryWindowInterval[ConfigRequest.NAN_BAND_6GHZ]
                == ConfigRequest.DW_INTERVAL_NOT_INIT) {
            config6.validDiscoveryWindowIntervalVal = false;
        } else {
            config6.validDiscoveryWindowIntervalVal = true;
            config6.discoveryWindowIntervalVal =
                    (byte) configRequest.mDiscoveryWindowInterval[ConfigRequest
                            .NAN_BAND_6GHZ];
        }

        try {
            WifiStatus status;
            if (initialConfiguration) {
                if (iface14 != null || iface15 != null || iface16 != null) {
                    // translate framework to HIDL configuration (V_1.4)
                    android.hardware.wifi.V1_4.NanEnableRequest req =
                            new android.hardware.wifi.V1_4.NanEnableRequest();

                    req.operateInBand[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.operateInBand[NanBandIndex.NAN_BAND_5GHZ] = configRequest.mSupport5gBand;
                    req.operateInBand[android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] =
                            configRequest.mSupport6gBand;
                    req.hopCountMax = 2;
                    req.configParams.masterPref = (byte) configRequest.mMasterPreference;
                    req.configParams.disableDiscoveryAddressChangeIndication =
                            !notifyIdentityChange;
                    req.configParams.disableStartedClusterIndication = !notifyIdentityChange;
                    req.configParams.disableJoinedClusterIndication = !notifyIdentityChange;
                    req.configParams.includePublishServiceIdsInBeacon = true;
                    req.configParams.numberOfPublishServiceIdsInBeacon = 0;
                    req.configParams.includeSubscribeServiceIdsInBeacon = true;
                    req.configParams.numberOfSubscribeServiceIdsInBeacon = 0;
                    req.configParams.rssiWindowSize = 8;
                    req.configParams.macAddressRandomizationIntervalSec =
                            mExternalSetParams.getOrDefault(PARAM_MAC_RANDOM_INTERVAL_SEC,
                                    mSettableParameters.get(PARAM_MAC_RANDOM_INTERVAL_SEC));

                    req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;
                    req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;
                    req.configParams.bandSpecificConfig[
                            android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] = config6;

                    req.debugConfigs.validClusterIdVals = true;
                    req.debugConfigs.clusterIdTopRangeVal = (short) configRequest.mClusterHigh;
                    req.debugConfigs.clusterIdBottomRangeVal = (short) configRequest.mClusterLow;
                    req.debugConfigs.validIntfAddrVal = false;
                    req.debugConfigs.validOuiVal = false;
                    req.debugConfigs.ouiVal = 0;
                    req.debugConfigs.validRandomFactorForceVal = false;
                    req.debugConfigs.randomFactorForceVal = 0;
                    req.debugConfigs.validHopCountForceVal = false;
                    req.debugConfigs.hopCountForceVal = 0;
                    req.debugConfigs.validDiscoveryChannelVal = false;
                    req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_24GHZ] = 0;
                    req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_5GHZ] = 0;
                    req.debugConfigs.discoveryChannelMhzVal[
                            android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] = 0;
                    req.debugConfigs.validUseBeaconsInBandVal = false;
                    req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;
                    req.debugConfigs.useBeaconsInBandVal[
                            android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] = true;
                    req.debugConfigs.validUseSdfInBandVal = false;
                    req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;
                    req.debugConfigs.useSdfInBandVal[
                            android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] = true;
                    updateConfigForPowerSettings14(req.configParams, configSupplemental12,
                            isInteractive, isIdle);

                    if (iface16 != null) {
                        status = iface16.enableRequest_1_6(transactionId, req,
                                configSupplemental16);
                    } else if (iface15 != null) {
                        status = iface15.enableRequest_1_5(transactionId, req,
                                configSupplemental15);
                    } else {
                        status = iface14.enableRequest_1_4(transactionId, req,
                                configSupplemental12);
                    }
                } else {
                    // translate framework to HIDL configuration (before V_1.4)
                    NanEnableRequest req = new NanEnableRequest();

                    req.operateInBand[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.operateInBand[NanBandIndex.NAN_BAND_5GHZ] = configRequest.mSupport5gBand;
                    req.hopCountMax = 2;
                    req.configParams.masterPref = (byte) configRequest.mMasterPreference;
                    req.configParams.disableDiscoveryAddressChangeIndication =
                            !notifyIdentityChange;
                    req.configParams.disableStartedClusterIndication = !notifyIdentityChange;
                    req.configParams.disableJoinedClusterIndication = !notifyIdentityChange;
                    req.configParams.includePublishServiceIdsInBeacon = true;
                    req.configParams.numberOfPublishServiceIdsInBeacon = 0;
                    req.configParams.includeSubscribeServiceIdsInBeacon = true;
                    req.configParams.numberOfSubscribeServiceIdsInBeacon = 0;
                    req.configParams.rssiWindowSize = 8;
                    req.configParams.macAddressRandomizationIntervalSec =
                            mExternalSetParams.getOrDefault(PARAM_MAC_RANDOM_INTERVAL_SEC,
                                    mSettableParameters.get(PARAM_MAC_RANDOM_INTERVAL_SEC));

                    req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;
                    req.configParams.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;

                    req.debugConfigs.validClusterIdVals = true;
                    req.debugConfigs.clusterIdTopRangeVal = (short) configRequest.mClusterHigh;
                    req.debugConfigs.clusterIdBottomRangeVal = (short) configRequest.mClusterLow;
                    req.debugConfigs.validIntfAddrVal = false;
                    req.debugConfigs.validOuiVal = false;
                    req.debugConfigs.ouiVal = 0;
                    req.debugConfigs.validRandomFactorForceVal = false;
                    req.debugConfigs.randomFactorForceVal = 0;
                    req.debugConfigs.validHopCountForceVal = false;
                    req.debugConfigs.hopCountForceVal = 0;
                    req.debugConfigs.validDiscoveryChannelVal = false;
                    req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_24GHZ] = 0;
                    req.debugConfigs.discoveryChannelMhzVal[NanBandIndex.NAN_BAND_5GHZ] = 0;
                    req.debugConfigs.validUseBeaconsInBandVal = false;
                    req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.debugConfigs.useBeaconsInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;
                    req.debugConfigs.validUseSdfInBandVal = false;
                    req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_24GHZ] = true;
                    req.debugConfigs.useSdfInBandVal[NanBandIndex.NAN_BAND_5GHZ] = true;

                    updateConfigForPowerSettings(req.configParams, configSupplemental12,
                            isInteractive, isIdle);

                    if (iface12 != null) {
                        status = iface12.enableRequest_1_2(transactionId, req,
                                configSupplemental12);
                    } else {
                        status = iface.enableRequest(transactionId, req);
                    }
                }
            } else {
                if (iface14 != null || iface15 != null || iface16 != null) {
                    android.hardware.wifi.V1_4.NanConfigRequest req =
                            new android.hardware.wifi.V1_4.NanConfigRequest();
                    req.masterPref = (byte) configRequest.mMasterPreference;
                    req.disableDiscoveryAddressChangeIndication = !notifyIdentityChange;
                    req.disableStartedClusterIndication = !notifyIdentityChange;
                    req.disableJoinedClusterIndication = !notifyIdentityChange;
                    req.includePublishServiceIdsInBeacon = true;
                    req.numberOfPublishServiceIdsInBeacon = 0;
                    req.includeSubscribeServiceIdsInBeacon = true;
                    req.numberOfSubscribeServiceIdsInBeacon = 0;
                    req.rssiWindowSize = 8;
                    req.macAddressRandomizationIntervalSec =
                            mExternalSetParams.getOrDefault(PARAM_MAC_RANDOM_INTERVAL_SEC,
                                    mSettableParameters.get(PARAM_MAC_RANDOM_INTERVAL_SEC));

                    req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;
                    req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;
                    req.bandSpecificConfig[android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ] =
                            config6;

                    updateConfigForPowerSettings14(req, configSupplemental12,
                            isInteractive, isIdle);
                    if (iface16 != null) {
                        status = iface16.configRequest_1_6(transactionId, req,
                                configSupplemental16);
                    } else if (iface15 != null) {
                        status = iface15.configRequest_1_5(transactionId, req,
                                configSupplemental15);
                    } else {
                        status = iface14.configRequest_1_4(transactionId, req,
                                configSupplemental12);
                    }
                } else {
                    NanConfigRequest req = new NanConfigRequest();
                    req.masterPref = (byte) configRequest.mMasterPreference;
                    req.disableDiscoveryAddressChangeIndication = !notifyIdentityChange;
                    req.disableStartedClusterIndication = !notifyIdentityChange;
                    req.disableJoinedClusterIndication = !notifyIdentityChange;
                    req.includePublishServiceIdsInBeacon = true;
                    req.numberOfPublishServiceIdsInBeacon = 0;
                    req.includeSubscribeServiceIdsInBeacon = true;
                    req.numberOfSubscribeServiceIdsInBeacon = 0;
                    req.rssiWindowSize = 8;
                    req.macAddressRandomizationIntervalSec =
                            mExternalSetParams.getOrDefault(PARAM_MAC_RANDOM_INTERVAL_SEC,
                                    mSettableParameters.get(PARAM_MAC_RANDOM_INTERVAL_SEC));

                    req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ] = config24;
                    req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ] = config5;

                    updateConfigForPowerSettings(req, configSupplemental12, isInteractive, isIdle);

                    if (iface12 != null) {
                        status = iface12.configRequest_1_2(transactionId, req,
                                configSupplemental12);
                    } else {
                        status = iface.configRequest(transactionId, req);
                    }
                }
            }
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "enableAndConfigure: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "enableAndConfigure: exception: " + e);
            return false;
        }
    }

    /**
     * Disable Aware.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     */
    public boolean disable(short transactionId) {
        if (mDbg) Log.d(TAG, "disable");
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "disable: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.disableRequest(transactionId);
            mHal.releaseAware();
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "disable: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "disable: exception: " + e);
            return false;
        }
    }

    /**
     * Start or modify a service publish session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param publishId ID of the requested session - 0 to request a new publish
     *            session.
     * @param publishConfig Configuration of the discovery session.
     */
    public boolean publish(short transactionId, byte publishId, PublishConfig publishConfig) {
        if (mDbg) {
            Log.d(TAG, "publish: transactionId=" + transactionId + ", publishId=" + publishId
                    + ", config=" + publishConfig);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "publish: null interface");
            return false;
        }

        android.hardware.wifi.V1_6.IWifiNanIface iface16 = mockableCastTo_1_6(iface);
        if (iface16 == null) {
            NanPublishRequest req = new NanPublishRequest();
            req.baseConfigs.sessionId = publishId;
            req.baseConfigs.ttlSec = (short) publishConfig.mTtlSec;
            req.baseConfigs.discoveryWindowPeriod = 1;
            req.baseConfigs.discoveryCount = 0;
            convertNativeByteArrayToArrayList(publishConfig.mServiceName,
                    req.baseConfigs.serviceName);
            req.baseConfigs.discoveryMatchIndicator = NanMatchAlg.MATCH_NEVER;
            convertNativeByteArrayToArrayList(publishConfig.mServiceSpecificInfo,
                    req.baseConfigs.serviceSpecificInfo);
            convertNativeByteArrayToArrayList(publishConfig.mMatchFilter,
                    publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED
                            ? req.baseConfigs.txMatchFilter : req.baseConfigs.rxMatchFilter);
            req.baseConfigs.useRssiThreshold = false;
            req.baseConfigs.disableDiscoveryTerminationIndication =
                    !publishConfig.mEnableTerminateNotification;
            req.baseConfigs.disableMatchExpirationIndication = true;
            req.baseConfigs.disableFollowupReceivedIndication = false;

            req.autoAcceptDataPathRequests = false;

            req.baseConfigs.rangingRequired = publishConfig.mEnableRanging;

            req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            WifiAwareDataPathSecurityConfig securityConfig = publishConfig.getSecurityConfig();
            if (securityConfig != null) {
                req.baseConfigs.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {
                    req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.baseConfigs.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.baseConfigs.securityConfig.securityType =
                            NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.baseConfigs.securityConfig.passphrase);
                }
            }

            req.publishType = publishConfig.mPublishType;
            req.txType = NanTxType.BROADCAST;

            try {
                WifiStatus status = iface.startPublishRequest(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "publish: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "publish: exception: " + e);
                return false;
            }
        } else {
            android.hardware.wifi.V1_6.NanPublishRequest req =
                    new android.hardware.wifi.V1_6.NanPublishRequest();
            req.baseConfigs.sessionId = publishId;
            req.baseConfigs.ttlSec = (short) publishConfig.mTtlSec;
            req.baseConfigs.discoveryWindowPeriod = 1;
            req.baseConfigs.discoveryCount = 0;
            convertNativeByteArrayToArrayList(publishConfig.mServiceName,
                    req.baseConfigs.serviceName);
            req.baseConfigs.discoveryMatchIndicator = NanMatchAlg.MATCH_NEVER;
            convertNativeByteArrayToArrayList(publishConfig.mServiceSpecificInfo,
                    req.baseConfigs.serviceSpecificInfo);
            convertNativeByteArrayToArrayList(publishConfig.mMatchFilter,
                    publishConfig.mPublishType == PublishConfig.PUBLISH_TYPE_UNSOLICITED
                            ? req.baseConfigs.txMatchFilter : req.baseConfigs.rxMatchFilter);
            req.baseConfigs.useRssiThreshold = false;
            req.baseConfigs.disableDiscoveryTerminationIndication =
                    !publishConfig.mEnableTerminateNotification;
            req.baseConfigs.disableMatchExpirationIndication = true;
            req.baseConfigs.disableFollowupReceivedIndication = false;

            req.autoAcceptDataPathRequests = false;

            req.baseConfigs.rangingRequired = publishConfig.mEnableRanging;

            req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            WifiAwareDataPathSecurityConfig securityConfig = publishConfig.getSecurityConfig();
            if (securityConfig != null) {
                req.baseConfigs.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {
                    req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.baseConfigs.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.baseConfigs.securityConfig.securityType =
                            NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.baseConfigs.securityConfig.passphrase);
                }
                if (securityConfig.getPmkId() != null && securityConfig.getPmkId().length != 0) {
                    copyArray(securityConfig.getPmkId(), req.baseConfigs.securityConfig.scid);
                }
            }

            req.publishType = publishConfig.mPublishType;
            req.txType = NanTxType.BROADCAST;

            try {
                WifiStatus status = iface16.startPublishRequest_1_6(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "publish: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "publish: exception: " + e);
                return false;
            }
        }
    }

    /**
     * Start or modify a service subscription session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param subscribeId ID of the requested session - 0 to request a new
     *            subscribe session.
     * @param subscribeConfig Configuration of the discovery session.
     */
    public boolean subscribe(short transactionId, byte subscribeId,
            SubscribeConfig subscribeConfig) {
        if (mDbg) {
            Log.d(TAG, "subscribe: transactionId=" + transactionId + ", subscribeId=" + subscribeId
                    + ", config=" + subscribeConfig);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "subscribe: null interface");
            return false;
        }

        NanSubscribeRequest req = new NanSubscribeRequest();
        req.baseConfigs.sessionId = subscribeId;
        req.baseConfigs.ttlSec = (short) subscribeConfig.mTtlSec;
        req.baseConfigs.discoveryWindowPeriod = 1;
        req.baseConfigs.discoveryCount = 0;
        convertNativeByteArrayToArrayList(subscribeConfig.mServiceName,
                req.baseConfigs.serviceName);
        req.baseConfigs.discoveryMatchIndicator = NanMatchAlg.MATCH_ONCE;
        convertNativeByteArrayToArrayList(subscribeConfig.mServiceSpecificInfo,
                req.baseConfigs.serviceSpecificInfo);
        convertNativeByteArrayToArrayList(subscribeConfig.mMatchFilter,
                subscribeConfig.mSubscribeType == SubscribeConfig.SUBSCRIBE_TYPE_ACTIVE
                        ? req.baseConfigs.txMatchFilter : req.baseConfigs.rxMatchFilter);
        req.baseConfigs.useRssiThreshold = false;
        req.baseConfigs.disableDiscoveryTerminationIndication =
                !subscribeConfig.mEnableTerminateNotification;
        req.baseConfigs.disableMatchExpirationIndication = false;
        req.baseConfigs.disableFollowupReceivedIndication = false;

        req.baseConfigs.rangingRequired =
                subscribeConfig.mMinDistanceMmSet || subscribeConfig.mMaxDistanceMmSet;
        req.baseConfigs.configRangingIndications = 0;
        // TODO: b/69428593 remove correction factors once HAL converted from CM to MM
        if (subscribeConfig.mMinDistanceMmSet) {
            req.baseConfigs.distanceEgressCm = (short) Math.min(
                    subscribeConfig.mMinDistanceMm / 10, Short.MAX_VALUE);
            req.baseConfigs.configRangingIndications |= NanRangingIndication.EGRESS_MET_MASK;
        }
        if (subscribeConfig.mMaxDistanceMmSet) {
            req.baseConfigs.distanceIngressCm = (short) Math.min(
                    subscribeConfig.mMaxDistanceMm / 10, Short.MAX_VALUE);
            req.baseConfigs.configRangingIndications |= NanRangingIndication.INGRESS_MET_MASK;
        }

        // TODO: configure security
        req.baseConfigs.securityConfig.securityType = NanDataPathSecurityType.OPEN;

        req.subscribeType = subscribeConfig.mSubscribeType;

        try {
            WifiStatus status = iface.startSubscribeRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "subscribe: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "subscribe: exception: " + e);
            return false;
        }
    }

    /**
     * Send a message through an existing discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the existing publish/subscribe session.
     * @param requestorInstanceId ID of the peer to communicate with - obtained
     *            through a previous discovery (match) operation with that peer.
     * @param dest MAC address of the peer to communicate with - obtained
     *            together with requestorInstanceId.
     * @param message Message.
     * @param messageId Arbitary integer from host (not sent to HAL - useful for
     *                  testing/debugging at this level)
     */
    public boolean sendMessage(short transactionId, byte pubSubId, int requestorInstanceId,
            byte[] dest, byte[] message, int messageId) {
        if (mDbg) {
            Log.d(TAG,
                    "sendMessage: transactionId=" + transactionId + ", pubSubId=" + pubSubId
                            + ", requestorInstanceId=" + requestorInstanceId + ", dest="
                            + String.valueOf(HexEncoding.encode(dest)) + ", messageId=" + messageId
                            + ", message=" + (message == null ? "<null>"
                            : HexEncoding.encode(message)) + ", message.length=" + (message == null
                            ? 0 : message.length));
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "sendMessage: null interface");
            return false;
        }

        NanTransmitFollowupRequest req = new NanTransmitFollowupRequest();
        req.discoverySessionId = pubSubId;
        req.peerId = requestorInstanceId;
        copyArray(dest, req.addr);
        req.isHighPriority = false;
        req.shouldUseDiscoveryWindow = true;
        convertNativeByteArrayToArrayList(message, req.serviceSpecificInfo);
        req.disableFollowupResultIndication = false;

        try {
            WifiStatus status = iface.transmitFollowupRequest(transactionId, req);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "sendMessage: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "sendMessage: exception: " + e);
            return false;
        }
    }

    /**
     * Terminate a publish discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopPublish(short transactionId, byte pubSubId) {
        if (mDbg) {
            Log.d(TAG, "stopPublish: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "stopPublish: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.stopPublishRequest(transactionId, pubSubId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "stopPublish: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "stopPublish: exception: " + e);
            return false;
        }
    }

    /**
     * Terminate a subscribe discovery session.
     *
     * @param transactionId transactionId Transaction ID for the transaction -
     *            used in the async callback to match with the original request.
     * @param pubSubId ID of the publish/subscribe session - obtained when
     *            creating a session.
     */
    public boolean stopSubscribe(short transactionId, byte pubSubId) {
        if (mDbg) {
            Log.d(TAG, "stopSubscribe: transactionId=" + transactionId + ", pubSubId=" + pubSubId);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "stopSubscribe: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.stopSubscribeRequest(transactionId, pubSubId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "stopSubscribe: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "stopSubscribe: exception: " + e);
            return false;
        }
    }

    /**
     * Create a Aware network interface. This only creates the Linux interface - it doesn't actually
     * create the data connection.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean createAwareNetworkInterface(short transactionId, String interfaceName) {
        if (mDbg) {
            Log.v(TAG, "createAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "createAwareNetworkInterface: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.createDataInterfaceRequest(transactionId, interfaceName);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "createAwareNetworkInterface: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "createAwareNetworkInterface: exception: " + e);
            return false;
        }
    }

    /**
     * Deletes a Aware network interface. The data connection can (should?) be torn down previously.
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param interfaceName The name of the interface, e.g. "aware0".
     */
    public boolean deleteAwareNetworkInterface(short transactionId, String interfaceName) {
        if (mDbg) {
            Log.v(TAG, "deleteAwareNetworkInterface: transactionId=" + transactionId + ", "
                    + "interfaceName=" + interfaceName);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "deleteAwareNetworkInterface: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.deleteDataInterfaceRequest(transactionId, interfaceName);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "deleteAwareNetworkInterface: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "deleteAwareNetworkInterface: exception: " + e);
            return false;
        }
    }

    /**
     * Initiates setting up a data-path between device and peer. Security is provided by either
     * PMK or Passphrase (not both) - if both are null then an open (unencrypted) link is set up.
     * @param transactionId      Transaction ID for the transaction - used in the async callback to
     *                           match with the original request.
     * @param peerId             ID of the peer ID to associate the data path with. A value of 0
     *                           indicates that not associated with an existing session.
     * @param channelRequestType Indicates whether the specified channel is available, if available
     *                           requested or forced (resulting in failure if cannot be
     *                           accommodated).
     * @param channel            The channel on which to set up the data-path.
     * @param peer               The MAC address of the peer to create a connection with.
     * @param interfaceName      The interface on which to create the data connection.
     * @param isOutOfBand Is the data-path out-of-band (i.e. without a corresponding Aware discovery
     *                    session).
     * @param appInfo Arbitrary binary blob transmitted to the peer.
     * @param capabilities The capabilities of the firmware.
     * @param securityConfig Security config to encrypt the data-path
     */
    public boolean initiateDataPath(short transactionId, int peerId, int channelRequestType,
            int channel, byte[] peer, String interfaceName,
            boolean isOutOfBand, byte[] appInfo, Capabilities capabilities,
            WifiAwareDataPathSecurityConfig securityConfig) {
        if (mDbg) {
            Log.v(TAG, "initiateDataPath: transactionId=" + transactionId + ", peerId=" + peerId
                    + ", channelRequestType=" + channelRequestType + ", channel=" + channel
                    + ", peer=" + String.valueOf(HexEncoding.encode(peer)) + ", interfaceName="
                    + interfaceName + ", securityConfig=" + securityConfig
                    + ", isOutOfBand=" + isOutOfBand + ", appInfo.length="
                    + ((appInfo == null) ? 0 : appInfo.length) + ", capabilities=" + capabilities);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "initiateDataPath: null interface");
            return false;
        }

        if (capabilities == null) {
            Log.e(TAG, "initiateDataPath: null capabilities");
            return false;
        }

        android.hardware.wifi.V1_6.IWifiNanIface iface16 = mockableCastTo_1_6(iface);

        if (iface16 == null) {
            NanInitiateDataPathRequest req = new NanInitiateDataPathRequest();
            req.peerId = peerId;
            copyArray(peer, req.peerDiscMacAddr);
            req.channelRequestType = channelRequestType;
            req.channel = channel;
            req.ifaceName = interfaceName;
            req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            if (securityConfig != null) {
                req.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {

                    req.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.securityConfig.passphrase);
                }
            }

            if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
                convertNativeByteArrayToArrayList(
                        SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                        req.serviceNameOutOfBand);
            }
            convertNativeByteArrayToArrayList(appInfo, req.appInfo);

            try {
                WifiStatus status = iface.initiateDataPathRequest(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "initiateDataPath: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "initiateDataPath: exception: " + e);
                return false;
            }
        } else {
            android.hardware.wifi.V1_6.NanInitiateDataPathRequest req =
                    new android.hardware.wifi.V1_6.NanInitiateDataPathRequest();
            req.peerId = peerId;
            copyArray(peer, req.peerDiscMacAddr);
            req.channelRequestType = channelRequestType;
            req.channel = channel;
            req.ifaceName = interfaceName;
            req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            if (securityConfig != null) {
                req.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {
                    req.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.securityConfig.passphrase);
                }
                if (securityConfig.getPmkId() != null && securityConfig.getPmkId().length != 0) {
                    copyArray(securityConfig.getPmkId(), req.securityConfig.scid);
                }
            }

            if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
                convertNativeByteArrayToArrayList(
                        SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                        req.serviceNameOutOfBand);
            }
            convertNativeByteArrayToArrayList(appInfo, req.appInfo);

            try {
                WifiStatus status = iface16.initiateDataPathRequest_1_6(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "initiateDataPath_1_6: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "initiateDataPath_1_6: exception: " + e);
                return false;
            }
        }
    }



    /**
     * Responds to a data request from a peer. Security is provided by either PMK or Passphrase (not
     * both) - if both are null then an open (unencrypted) link is set up.
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param accept Accept (true) or reject (false) the original call.
     * @param ndpId The NDP (Aware data path) ID. Obtained from the request callback.
     * @param interfaceName The interface on which the data path will be setup. Obtained from the
*                      request callback.
     * @param appInfo Arbitrary binary blob transmitted to the peer.
     * @param isOutOfBand Is the data-path out-of-band (i.e. without a corresponding Aware discovery
*                    session).
     * @param capabilities The capabilities of the firmware.
     * @param securityConfig Security config to encrypt the data-path
     */
    public boolean respondToDataPathRequest(short transactionId, boolean accept, int ndpId,
            String interfaceName, byte[] appInfo,
            boolean isOutOfBand, Capabilities capabilities,
            WifiAwareDataPathSecurityConfig securityConfig) {
        if (mDbg) {
            Log.v(TAG, "respondToDataPathRequest: transactionId=" + transactionId + ", accept="
                    + accept + ", int ndpId=" + ndpId + ", interfaceName=" + interfaceName
                    + ", appInfo.length=" + ((appInfo == null) ? 0 : appInfo.length)
                    + ", securityConfig" + securityConfig);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "respondToDataPathRequest: null interface");
            return false;
        }

        if (capabilities == null) {
            Log.e(TAG, "respondToDataPathRequest: null capabilities");
            return false;
        }

        android.hardware.wifi.V1_6.IWifiNanIface iface16 = mockableCastTo_1_6(iface);

        if (iface16 == null) {
            NanRespondToDataPathIndicationRequest req = new NanRespondToDataPathIndicationRequest();
            req.acceptRequest = accept;
            req.ndpInstanceId = ndpId;
            req.ifaceName = interfaceName;
            req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            if (securityConfig != null) {
                req.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {

                    req.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.securityConfig.passphrase);
                }
            }

            if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
                convertNativeByteArrayToArrayList(
                        SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                        req.serviceNameOutOfBand);
            }
            convertNativeByteArrayToArrayList(appInfo, req.appInfo);

            try {
                WifiStatus status = iface.respondToDataPathIndicationRequest(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "respondToDataPathRequest: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "respondToDataPathRequest: exception: " + e);
                return false;
            }
        } else {
            android.hardware.wifi.V1_6.NanRespondToDataPathIndicationRequest req =
                    new android.hardware.wifi.V1_6.NanRespondToDataPathIndicationRequest();
            req.acceptRequest = accept;
            req.ndpInstanceId = ndpId;
            req.ifaceName = interfaceName;
            req.securityConfig.securityType = NanDataPathSecurityType.OPEN;
            if (securityConfig != null) {
                req.securityConfig.cipherType = getHalCipherSuiteType(
                        securityConfig.getCipherSuite());
                if (securityConfig.getPmk() != null && securityConfig.getPmk().length != 0) {

                    req.securityConfig.securityType = NanDataPathSecurityType.PMK;
                    copyArray(securityConfig.getPmk(), req.securityConfig.pmk);
                }
                if (securityConfig.getPskPassphrase() != null
                        && securityConfig.getPskPassphrase().length() != 0) {
                    req.securityConfig.securityType = NanDataPathSecurityType.PASSPHRASE;
                    convertNativeByteArrayToArrayList(securityConfig.getPskPassphrase().getBytes(),
                            req.securityConfig.passphrase);
                }
                if (securityConfig.getPmkId() != null && securityConfig.getPmkId().length != 0) {
                    copyArray(securityConfig.getPmkId(), req.securityConfig.scid);
                }
            }

            if (req.securityConfig.securityType != NanDataPathSecurityType.OPEN && isOutOfBand) {
                convertNativeByteArrayToArrayList(
                        SERVICE_NAME_FOR_OOB_DATA_PATH.getBytes(StandardCharsets.UTF_8),
                        req.serviceNameOutOfBand);
            }
            convertNativeByteArrayToArrayList(appInfo, req.appInfo);

            try {
                WifiStatus status = iface16
                        .respondToDataPathIndicationRequest_1_6(transactionId, req);
                if (status.code == WifiStatusCode.SUCCESS) {
                    return true;
                } else {
                    Log.e(TAG, "respondToDataPathRequest_1_6: error: " + statusString(status));
                    return false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "respondToDataPathRequest_1_6: exception: " + e);
                return false;
            }
        }
    }

    /**
     * Terminate an existing data-path (does not delete the interface).
     *
     * @param transactionId Transaction ID for the transaction - used in the async callback to
     *                      match with the original request.
     * @param ndpId The NDP (Aware data path) ID to be terminated.
     */
    public boolean endDataPath(short transactionId, int ndpId) {
        if (mDbg) {
            Log.v(TAG, "endDataPath: transactionId=" + transactionId + ", ndpId=" + ndpId);
        }
        recordTransactionId(transactionId);

        IWifiNanIface iface = mHal.getWifiNanIface();
        if (iface == null) {
            Log.e(TAG, "endDataPath: null interface");
            return false;
        }

        try {
            WifiStatus status = iface.terminateDataPathRequest(transactionId, ndpId);
            if (status.code == WifiStatusCode.SUCCESS) {
                return true;
            } else {
                Log.e(TAG, "endDataPath: error: " + statusString(status));
                return false;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "endDataPath: exception: " + e);
            return false;
        }
    }


    // utilities

    /**
     * Update the NAN configuration to reflect the current power settings (before V1.4)
     */
    private void updateConfigForPowerSettings(NanConfigRequest req,
            android.hardware.wifi.V1_2.NanConfigRequestSupplemental configSupplemental12,
            boolean isInteractive, boolean isIdle) {
        String key = POWER_PARAM_DEFAULT_KEY;
        if (isIdle) {
            key = POWER_PARAM_IDLE_KEY;
        } else if (!isInteractive) {
            key = POWER_PARAM_INACTIVE_KEY;
        }

        updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ],
                getSettablePowerParameters(key, PARAM_DW_5GHZ));
        updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ],
                getSettablePowerParameters(key, PARAM_DW_24GHZ));

        configSupplemental12.discoveryBeaconIntervalMs = getSettablePowerParameters(key,
                PARAM_DISCOVERY_BEACON_INTERVAL_MS);
        configSupplemental12.numberOfSpatialStreamsInDiscovery = getSettablePowerParameters(key,
                PARAM_NUM_SS_IN_DISCOVERY);
        configSupplemental12.enableDiscoveryWindowEarlyTermination = getSettablePowerParameters(key,
                PARAM_ENABLE_DW_EARLY_TERM) != 0;
    }

    /**
     * Update the NAN configuration to reflect the current power settings (V1.4)
     */
    private void updateConfigForPowerSettings14(android.hardware.wifi.V1_4.NanConfigRequest req,
            android.hardware.wifi.V1_2.NanConfigRequestSupplemental configSupplemental12,
            boolean isInteractive, boolean isIdle) {
        String key = POWER_PARAM_DEFAULT_KEY;
        if (isIdle) {
            key = POWER_PARAM_IDLE_KEY;
        } else if (!isInteractive) {
            key = POWER_PARAM_INACTIVE_KEY;
        }

        updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_5GHZ],
                getSettablePowerParameters(key, PARAM_DW_5GHZ));
        updateSingleConfigForPowerSettings(req.bandSpecificConfig[NanBandIndex.NAN_BAND_24GHZ],
                getSettablePowerParameters(key, PARAM_DW_24GHZ));
        updateSingleConfigForPowerSettings(req.bandSpecificConfig[
                android.hardware.wifi.V1_4.NanBandIndex.NAN_BAND_6GHZ],
                getSettablePowerParameters(key, PARAM_DW_6GHZ));

        configSupplemental12.discoveryBeaconIntervalMs = getSettablePowerParameters(key,
                PARAM_DISCOVERY_BEACON_INTERVAL_MS);
        configSupplemental12.numberOfSpatialStreamsInDiscovery = getSettablePowerParameters(key,
                PARAM_NUM_SS_IN_DISCOVERY);
        configSupplemental12.enableDiscoveryWindowEarlyTermination =
                getSettablePowerParameters(key, PARAM_ENABLE_DW_EARLY_TERM) != 0;
    }

    private int getSettablePowerParameters(String state, String key) {
        if (mExternalSetParams.containsKey(key)) {
            return mExternalSetParams.get(key);
        }
        return mSettablePowerParameters.get(state).get(key);
    }

    private void updateSingleConfigForPowerSettings(NanBandSpecificConfig cfg, int override) {
        if (override != -1) {
            cfg.validDiscoveryWindowIntervalVal = true;
            cfg.discoveryWindowIntervalVal = (byte) override;
        }
    }

    /**
     * Returns the HAL cipher suite.
     */
    private int getHalCipherSuiteType(int frameworkCipherSuites) {
        switch (frameworkCipherSuites) {
            case WIFI_AWARE_CIPHER_SUITE_NCS_SK_128:
                return NanCipherSuiteType.SHARED_KEY_128_MASK;
            case WIFI_AWARE_CIPHER_SUITE_NCS_SK_256:
                return NanCipherSuiteType.SHARED_KEY_256_MASK;
            case WIFI_AWARE_CIPHER_SUITE_NCS_PK_128:
                return NanCipherSuiteType.PUBLIC_KEY_128_MASK;
            case WIFI_AWARE_CIPHER_SUITE_NCS_PK_256:
                return NanCipherSuiteType.PUBLIC_KEY_256_MASK;
        }
        return NanCipherSuiteType.NONE;
    }

    /**
     * Converts a byte[] to an ArrayList<Byte>. Fills in the entries of the 'to' array if
     * provided (non-null), otherwise creates and returns a new ArrayList<>.
     *
     * @param from The input byte[] to convert from.
     * @param to An optional ArrayList<> to fill in from 'from'.
     *
     * @return A newly allocated ArrayList<> if 'to' is null, otherwise null.
     */
    private ArrayList<Byte> convertNativeByteArrayToArrayList(byte[] from, ArrayList<Byte> to) {
        if (from == null) {
            from = new byte[0];
        }

        if (to == null) {
            to = new ArrayList<>(from.length);
        } else {
            to.ensureCapacity(from.length);
        }
        for (int i = 0; i < from.length; ++i) {
            to.add(from[i]);
        }
        return to;
    }

    private void copyArray(byte[] from, byte[] to) {
        if (from == null || to == null || from.length != to.length) {
            Log.e(TAG, "copyArray error: from=" + Arrays.toString(from) + ", to="
                    + Arrays.toString(to));
            return;
        }
        for (int i = 0; i < from.length; ++i) {
            to[i] = from[i];
        }
    }

    private static String statusString(WifiStatus status) {
        if (status == null) {
            return "status=null";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.code).append(" (").append(status.description).append(")");
        return sb.toString();
    }

    /**
     * Dump the internal state of the class.
     */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("WifiAwareNativeApi:");
        pw.println("  mSettableParameters: " + mSettableParameters);
        pw.println("  mExternalSetParams" + mExternalSetParams);
        mHal.dump(fd, pw, args);
    }
}
