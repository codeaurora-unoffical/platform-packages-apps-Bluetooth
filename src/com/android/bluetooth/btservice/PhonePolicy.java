/*
 * Copyright (C) 2017, The Linux Foundation. All rights reserved.
 * Not a Contribution.
 */
/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.bluetooth.btservice;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothA2dpSink;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothSap;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.os.SystemProperties;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.ParcelUuid;
import android.util.Log;

import com.android.bluetooth.a2dp.A2dpService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;
import com.android.bluetooth.hid.HidService;
import com.android.bluetooth.hfp.HeadsetService;
import com.android.bluetooth.pan.PanService;
import com.android.internal.R;

import java.util.List;
import java.util.ArrayList;
// Describes the phone policy
//
// The policy should be as decoupled from the stack as possible. In an ideal world we should not
// need to have this policy talk with any non-public APIs and one way to enforce that would be to
// keep this file outside the Bluetooth process. Unfortunately, keeping a separate process alive is
// an expensive and a tedious task.
//
// Best practices:
// a) PhonePolicy should be ALL private methods
//    -- Use broadcasts which can be listened in on the BroadcastReceiver
// b) NEVER call from the PhonePolicy into the Java stack, unless public APIs. It is OK to call into
// the non public versions as long as public versions exist (so that a 3rd party policy can mimick)
// us.
//
// Policy description:
//
// Policies are usually governed by outside events that may warrant an action. We talk about various
// events and the resulting outcome from this policy:
//
// 1. Adapter turned ON: At this point we will try to auto-connect the (device, profile) pairs which
// have PRIORITY_AUTO_CONNECT. The fact that we *only* auto-connect Headset and A2DP is something
// that is hardcoded and specific to phone policy (see autoConnect() function)
// 2. When the profile connection-state changes: At this point if a new profile gets CONNECTED we
// will try to connect other profiles on the same device. This is to avoid collision if devices
// somehow end up trying to connect at same time or general connection issues.
class PhonePolicy {
    final private static boolean DBG = true;
    final private static String TAG = "BluetoothPhonePolicy";

    // Message types for the handler (internal messages generated by intents or timeouts)
    final private static int MESSAGE_PROFILE_CONNECTION_STATE_CHANGED = 1;
    final private static int MESSAGE_PROFILE_INIT_PRIORITIES = 2;
    final private static int MESSAGE_CONNECT_OTHER_PROFILES = 3;
    final private static int MESSAGE_ADAPTER_STATE_TURNED_ON = 4;
    private static final int MESSAGE_AUTO_CONNECT_PROFILES = 50;

    public static final int PROFILE_CONN_CONNECTED = 1;
    private static final String delayConnectTimeoutDevice[] = {"00:23:3D"}; // volkswagen carkit
    private static final String delayReducedConnectTimeoutDevice[] = {"10:4F:A8"}; //h.ear (MDR-EX750BT)

    // Timeouts
    final private static int CONNECT_OTHER_PROFILES_TIMEOUT = 6000; // 6s
    private static final int CONNECT_OTHER_PROFILES_TIMEOUT_DELAYED = 10000;
    private static final int AUTO_CONNECT_PROFILES_TIMEOUT= 500;
    private static final int CONNECT_OTHER_PROFILES_REDUCED_TIMEOUT_DELAYED = 2000;

    final private AdapterService mAdapterService;
    final private ServiceFactory mFactory;
    final private Handler mHandler;
    private ArrayList<BluetoothDevice> mQueuedDevicesList =
            new ArrayList<BluetoothDevice>();

    // Broadcast receiver for all changes to states of various profiles
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                errorLog("Received intent with null action");
                return;
            }
            switch (action) {
                case BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                                    BluetoothProfile.HEADSET,
                                    -1, // No-op argument
                                    intent)
                            .sendToTarget();
                    break;
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                                    BluetoothProfile.A2DP,
                                    -1, // No-op argument
                                    intent)
                            .sendToTarget();
                    break;
                case BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED:
                    mHandler.obtainMessage(MESSAGE_PROFILE_CONNECTION_STATE_CHANGED,
                                BluetoothProfile.A2DP_SINK,
                                -1, // No-op argument
                                intent)
                            .sendToTarget();
                    break;
                case BluetoothAdapter.ACTION_STATE_CHANGED:
                    // Only pass the message on if the adapter has actually changed state from
                    // non-ON to ON. NOTE: ON is the state depicting BREDR ON and not just BLE ON.
                    int newState = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
                    if (newState == BluetoothAdapter.STATE_ON) {
                        mHandler.obtainMessage(MESSAGE_ADAPTER_STATE_TURNED_ON).sendToTarget();
                    }
                    break;
                case BluetoothDevice.ACTION_UUID:
                    mHandler.obtainMessage(MESSAGE_PROFILE_INIT_PRIORITIES, intent).sendToTarget();
                    break;
                default:
                    Log.e(TAG, "Received unexpected intent, action=" + action);
                    break;
            }
        }
    };

    // ONLY for testing
    public BroadcastReceiver getBroadcastReceiver() {
        return mReceiver;
    }

    // Handler to handoff intents to class thread
    class PhonePolicyHandler extends Handler {
        PhonePolicyHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_PROFILE_INIT_PRIORITIES: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    Parcelable[] uuids = intent.getParcelableArrayExtra(BluetoothDevice.EXTRA_UUID);
                    debugLog("Received ACTION_UUID for device " + device);
                    if (uuids != null) {
                        ParcelUuid[] uuidsToSend = new ParcelUuid[uuids.length];
                        for (int i = 0; i < uuidsToSend.length; i++) {
                            uuidsToSend[i] = (ParcelUuid) uuids[i];
                            debugLog("index=" + i + "uuid=" + uuidsToSend[i]);
                        }
                        processInitProfilePriorities(device, uuidsToSend);
                    }
                } break;

                case MESSAGE_PROFILE_CONNECTION_STATE_CHANGED: {
                    Intent intent = (Intent) msg.obj;
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    int prevState = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1);
                    int nextState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);
                    processProfileStateChanged(device, msg.arg1, nextState, prevState);
                } break;

                case MESSAGE_CONNECT_OTHER_PROFILES:
                    // Called when we try connect some profiles in processConnectOtherProfiles but
                    // we send a delayed message to try connecting the remaining profiles
                    processConnectOtherProfiles((BluetoothDevice) msg.obj);
                    break;

                case MESSAGE_ADAPTER_STATE_TURNED_ON:
                    // Call auto connect when adapter switches state to ON
                    autoConnect();
                    break;
                case MESSAGE_AUTO_CONNECT_PROFILES: {
                    if (DBG) debugLog( "MESSAGE_AUTO_CONNECT_PROFILES");
                    autoConnectProfilesDelayed();
                    break;
                }
            }
        }
    };

    // Policy API functions for lifecycle management (protected)
    protected void start() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothA2dpSink.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_UUID);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        mAdapterService.registerReceiver(mReceiver, filter);
    }
    protected void cleanup() {
        mAdapterService.unregisterReceiver(mReceiver);
    }

    PhonePolicy(AdapterService service, ServiceFactory factory) {
        mAdapterService = service;
        mFactory = factory;
        mHandler = new PhonePolicyHandler(service.getMainLooper());
    }

    // Policy implementation, all functions MUST be private
    private void processInitProfilePriorities(BluetoothDevice device, ParcelUuid[] uuids) {
        debugLog("processInitProfilePriorities() - device " + device);
        HidService hidService = mFactory.getHidService();
        A2dpService a2dpService = mFactory.getA2dpService();
        HeadsetService headsetService = mFactory.getHeadsetService();
        PanService panService = mFactory.getPanService();

        // Set profile priorities only for the profiles discovered on the remote device.
        // This avoids needless auto-connect attempts to profiles non-existent on the remote device
        if ((hidService != null)
                && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hid)
                           || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Hogp))
                && (hidService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)) {
            hidService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        // If we do not have a stored priority for HFP/A2DP (all roles) then default to on.
        if ((headsetService != null)
                && ((BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.HSP)
                            || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.Handsfree))
                           && (headsetService.getPriority(device)
                                      == BluetoothProfile.PRIORITY_UNDEFINED))) {
            headsetService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((a2dpService != null)
                && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AudioSink)
                           || BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.AdvAudioDist))
                && (a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_UNDEFINED)) {
            a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }

        if ((panService != null)
                && (BluetoothUuid.isUuidPresent(uuids, BluetoothUuid.PANU)
                           && (panService.getPriority(device)
                                      == BluetoothProfile.PRIORITY_UNDEFINED)
                           && mAdapterService.getResources().getBoolean(
                                      R.bool.config_bluetooth_pan_enable_autoconnect))) {
            panService.setPriority(device, BluetoothProfile.PRIORITY_ON);
        }
    }

    private void processProfileStateChanged(
            BluetoothDevice device, int profileId, int nextState, int prevState) {
        debugLog("processProfileStateChanged, device=" + device + ", profile=" + profileId + ", "
                + prevState + " -> " + nextState);
        // Profiles relevant to phones.
        if (((profileId == BluetoothProfile.A2DP) || (profileId == BluetoothProfile.HEADSET)
                || profileId == BluetoothProfile.A2DP_SINK)
                && (nextState == BluetoothProfile.STATE_CONNECTED)) {
            connectOtherProfile(device);
            setProfileAutoConnectionPriority(device, profileId);
        }
    }

    // Delaying Auto Connect to make sure that all clients
    // are up and running, specially BluetoothHeadset.
    public void autoConnect() {
        debugLog( "delay auto connect by 500 ms");
        if ((mHandler.hasMessages(MESSAGE_AUTO_CONNECT_PROFILES) == false) &&
            (mAdapterService.isQuietModeEnabled()== false)) {
            Message m = mHandler.obtainMessage(MESSAGE_AUTO_CONNECT_PROFILES);
            mHandler.sendMessageDelayed(m,AUTO_CONNECT_PROFILES_TIMEOUT);
        }
    }

    private void autoConnectProfilesDelayed() {
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            errorLog("autoConnect() - BT is not ON. Exiting autoConnect");
            return;
        }

        if (!mAdapterService.isQuietModeEnabled()) {
            debugLog("autoConnect() - Initiate auto connection on BT on...");
            // Phone profiles.
            autoConnectHeadset();
            autoConnectA2dp();
            //Remote Device Profiles
            autoConnectA2dpSink();
        } else {
            debugLog("autoConnect() - BT is in quiet mode. Not initiating auto connections");
        }
    }

    private void cancelDiscoveryforautoConnect(){
        if (mAdapterService.isDiscovering() == true) {
            mAdapterService.cancelDiscovery();
        }
    }

    private void autoConnectHeadset() {
        final HeadsetService hsService = mFactory.getHeadsetService();
        if (hsService == null) {
            errorLog("autoConnectHeadset, service is null");
            return;
        }
        final BluetoothDevice bondedDevices[] = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            errorLog("autoConnectHeadset, bondedDevices are null");
            return;
        }
        for (BluetoothDevice device : bondedDevices) {
            debugLog("autoConnectHeadset, attempt auto-connect with device " + device);
            if (hsService.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
                cancelDiscoveryforautoConnect();
                debugLog("autoConnectHeadset, Connecting HFP with " + device);
                hsService.connect(device);
            }
        }
    }

    private void autoConnectA2dp() {
        final A2dpService a2dpService = mFactory.getA2dpService();
        if (a2dpService == null) {
            errorLog("autoConnectA2dp, service is null");
            return;
        }
        final BluetoothDevice bondedDevices[] = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            errorLog("autoConnectA2dp, bondedDevices are null");
            return;
        }
        for (BluetoothDevice device : bondedDevices) {
            debugLog("autoConnectA2dp, attempt auto-connect with device " + device);
            if (a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
                cancelDiscoveryforautoConnect();
                debugLog("autoConnectA2dp, connecting A2DP with " + device);
                a2dpService.connect(device);
            }
        }
    }

    private void autoConnectA2dpSink() {
         A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
         BluetoothDevice bondedDevices[] =  mAdapterService.getBondedDevices();
         if ((bondedDevices == null) || (a2dpSinkService == null)) {
             return;
         }

         for (BluetoothDevice device : bondedDevices) {
             if (a2dpSinkService.getPriority(device) == BluetoothProfile.PRIORITY_AUTO_CONNECT) {
                 cancelDiscoveryforautoConnect();
                 debugLog("autoConnectA2dpSink() - Connecting A2DP Sink with " + device.toString());
                 a2dpSinkService.connect(device);
             }
         }
     }

    private boolean isConnectTimeoutDelayApplicable(BluetoothDevice device){
        boolean isConnectionTimeoutDelayed = false;
        String deviceAddress = device.getAddress();
        for (int i = 0; i < delayConnectTimeoutDevice.length;i++) {
            if (deviceAddress.indexOf(delayConnectTimeoutDevice[i]) == 0) {
                isConnectionTimeoutDelayed = true;
            }
        }
        return isConnectionTimeoutDelayed;
    }

    private boolean isConnectReducedTimeoutDelayApplicable(BluetoothDevice device){
        boolean isConnectionReducedTimeoutDelayed = false;
        String deviceAddress = device.getAddress();
        for (int i = 0; i < delayReducedConnectTimeoutDevice.length;i++) {
            if (deviceAddress.indexOf(delayReducedConnectTimeoutDevice[i]) == 0) {
                isConnectionReducedTimeoutDelayed = true;
            }
        }
        return isConnectionReducedTimeoutDelayed;
    }

    public void connectOtherProfile(BluetoothDevice device) {
        debugLog("connectOtherProfile - device " + device);
        if ((!mAdapterService.isQuietModeEnabled()) &&
                !mQueuedDevicesList.contains(device)) {
            mQueuedDevicesList.add(device);
            Message m = mHandler.obtainMessage(MESSAGE_CONNECT_OTHER_PROFILES);
            m.obj = device;
            if (isConnectTimeoutDelayApplicable(device))
                mHandler.sendMessageDelayed(m,CONNECT_OTHER_PROFILES_TIMEOUT_DELAYED);
            else if (isConnectReducedTimeoutDelayApplicable(device))
                mHandler.sendMessageDelayed(m,CONNECT_OTHER_PROFILES_REDUCED_TIMEOUT_DELAYED);
            else
                mHandler.sendMessageDelayed(m, CONNECT_OTHER_PROFILES_TIMEOUT);
        }
    }

    // This function is called whenever a profile is connected.  This allows any other bluetooth
    // profiles which are not already connected or in the process of connecting to attempt to
    // connect to the device that initiated the connection.  In the event that this function is
    // invoked and there are no current bluetooth connections no new profiles will be connected.
    private void processConnectOtherProfiles(BluetoothDevice device) {
        debugLog("processConnectOtherProfiles, device=" + device);
        if (mQueuedDevicesList.contains(device)) {
            debugLog("processConnectOtherProfiles() remove device from queued list " + device);
            mQueuedDevicesList.remove(device);
        }
        if (mAdapterService.getState() != BluetoothAdapter.STATE_ON) {
            warnLog("processConnectOtherProfiles, adapter is not ON " + mAdapterService.getState());
            return;
        }
        HeadsetService hsService = mFactory.getHeadsetService();
        A2dpService a2dpService = mFactory.getA2dpService();
        PanService panService = mFactory.getPanService();

        boolean a2dpConnected = false;
        boolean hsConnected = false;

        boolean allProfilesEmpty = true;
        List<BluetoothDevice> a2dpConnDevList = null;
        List<BluetoothDevice> hsConnDevList = null;
        List<BluetoothDevice> panConnDevList = null;

        if (hsService != null) {
            hsConnDevList = hsService.getConnectedDevices();
            allProfilesEmpty = allProfilesEmpty && !hsConnDevList.contains(device);
        }
        if (a2dpService != null) {
            a2dpConnDevList = a2dpService.getConnectedDevices();
            allProfilesEmpty = allProfilesEmpty && !a2dpConnDevList.contains(device);
        }
        if (panService != null) {
            panConnDevList = panService.getConnectedDevices();
            allProfilesEmpty = allProfilesEmpty && !panConnDevList.contains(device);
        }

        if (allProfilesEmpty) {
            debugLog("processConnectOtherProfiles, all profiles disconnected for " + device);
            // must have connected then disconnected, don't bother connecting others.
            return;
        }

        if(a2dpConnDevList != null && !a2dpConnDevList.isEmpty()) {
            for (BluetoothDevice a2dpDevice : a2dpConnDevList)
            {
                if(a2dpDevice.equals(device))
                {
                    a2dpConnected = true;
                }
            }
        }

        if(hsConnDevList != null && !hsConnDevList.isEmpty()) {
            for (BluetoothDevice hsDevice : hsConnDevList)
            {
                if(hsDevice.equals(device))
                {
                    hsConnected = true;
                }
            }
        }

        // This change makes sure that we try to re-connect
        // the profile if its connection failed and priority
        // for desired profile is ON.
        debugLog("HF connected for device : " + device + " " +
                (hsConnDevList == null ? false : hsConnDevList.contains(device)));
        debugLog("A2DP connected for device : " + device + " " +
                (a2dpConnDevList == null ? false : a2dpConnDevList.contains(device)));

        if (hsService != null) {
            if ((hsConnDevList.isEmpty() || !(hsConnDevList.contains(device)))
                    && (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)
                    && (hsService.getConnectionState(device)
                               == BluetoothProfile.STATE_DISCONNECTED)
                    && (a2dpConnected || (a2dpService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
                debugLog("Retrying connection to HS with device " + device);
                int maxConnections = 1;
                int maxHfpConnectionSysProp =
                        SystemProperties.getInt("persist.bt.max.hs.connections", 1);
                if (maxHfpConnectionSysProp == 2)
                        maxConnections = maxHfpConnectionSysProp;

                if (!hsConnDevList.isEmpty() && maxConnections == 1) {
                    Log.v(TAG,"HFP is already connected, ignore");
                    return;
                }

                // proceed connection only if a2dp is connected to this device
                // add here as if is already overloaded
                if (((a2dpConnDevList != null) && a2dpConnDevList.contains(device)) ||
                     (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)) {
                    debugLog("Retrying connection to HS with device " + device);
                    hsService.connect(device);
                } else {
                    debugLog("do not initiate connect as A2dp is not connected");
                }
            }
        }

        if (a2dpService != null) {
            if ((a2dpConnDevList.isEmpty() || !(a2dpConnDevList.contains(device)))
                    && (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)
                    && (a2dpService.getConnectionState(device)
                               == BluetoothProfile.STATE_DISCONNECTED)
                    && (hsConnected || (hsService.getPriority(device) == BluetoothProfile.PRIORITY_OFF))) {
                debugLog("Retrying connection to A2DP with device " + device);
                int maxConnections = 1;
                int maxA2dpConnectionSysProp =
                        SystemProperties.getInt("persist.bt.max.a2dp.connections", 1);
                if (maxA2dpConnectionSysProp == 2)
                        maxConnections = maxA2dpConnectionSysProp;

                if (!a2dpConnDevList.isEmpty() && maxConnections == 1) {
                    Log.v(TAG,"a2dp is already connected, ignore");
                    return;
                }

                // proceed connection only if HFP is connected to this device
                // add here as if is already overloaded
                if (((hsConnDevList != null) && hsConnDevList.contains(device)) ||
                    (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)) {
                    debugLog("Retrying connection to A2DP with device " + device);
                    a2dpService.connect(device);
                } else {
                    debugLog("do not initiate connect as HFP is not connected");
                }
            }
        }
        if (panService != null) {
            if (panConnDevList.isEmpty()
                    && (panService.getPriority(device) >= BluetoothProfile.PRIORITY_ON)
                    && (panService.getConnectionState(device)
                               == BluetoothProfile.STATE_DISCONNECTED)) {
                debugLog("Retrying connection to PAN with device " + device);
                panService.connect(device);
            }
        }
    }

    private void setProfileAutoConnectionPriority(BluetoothDevice device, int profileId) {
        List<BluetoothDevice> deviceList;
        switch (profileId) {
            case BluetoothProfile.HEADSET:
                HeadsetService hsService = mFactory.getHeadsetService();
                if (hsService != null) {
                    deviceList = hsService.getConnectedDevices();
                    if (BluetoothProfile.PRIORITY_AUTO_CONNECT != hsService.getPriority(device)) {
                        adjustOtherHeadsetPriorities(hsService, deviceList);
                        hsService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                    }
                }
                break;

            case BluetoothProfile.A2DP:
                A2dpService a2dpService = mFactory.getA2dpService();
                if (a2dpService != null) {
                    deviceList = a2dpService.getConnectedDevices();
                    if (BluetoothProfile.PRIORITY_AUTO_CONNECT != a2dpService.getPriority(device)) {
                        adjustOtherSinkPriorities(a2dpService, deviceList);
                        a2dpService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                    }
                }
                break;

            case BluetoothProfile.A2DP_SINK:
                A2dpSinkService a2dpSinkService = mFactory.getA2dpSinkService();
                if (a2dpSinkService != null) {
                    deviceList = a2dpSinkService.getConnectedDevices();
                    if (BluetoothProfile.PRIORITY_AUTO_CONNECT != a2dpSinkService.getPriority(
                            device)) {
                        adjustOtherSourcePriorities(a2dpSinkService, deviceList);
                        a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_AUTO_CONNECT);
                    }
                }
                break;

            default:
                Log.w(TAG, "Tried to set AutoConnect priority on invalid profile " + profileId);
                break;
        }
    }

    private void adjustOtherHeadsetPriorities(
            HeadsetService hsService, List<BluetoothDevice> connectedDeviceList) {
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (hsService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                hsService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private void adjustOtherSinkPriorities(
            A2dpService a2dpService, List<BluetoothDevice> connectedDeviceList) {
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (a2dpService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                a2dpService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private void adjustOtherSourcePriorities(
            A2dpSinkService a2dpSinkService, List<BluetoothDevice> connectedDeviceList) {
        for (BluetoothDevice device : mAdapterService.getBondedDevices()) {
            if (a2dpSinkService.getPriority(device) >= BluetoothProfile.PRIORITY_AUTO_CONNECT
                    && !connectedDeviceList.contains(device)) {
                a2dpSinkService.setPriority(device, BluetoothProfile.PRIORITY_ON);
            }
        }
    }

    private static void debugLog(String msg) {
        if (DBG) Log.d(TAG, msg);
    }

    private static void warnLog(String msg) {
        Log.w(TAG, msg);
    }

    private static void errorLog(String msg) {
        Log.e(TAG, msg);
    }
}
