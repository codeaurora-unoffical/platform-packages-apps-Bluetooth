/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.bluetooth.hfp;

import static android.Manifest.permission.MODIFY_PHONE_STATE;

import android.annotation.Nullable;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothDevice;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.IBluetoothHeadset;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.telecom.PhoneAccount;
import android.util.Log;

import com.android.bluetooth.BluetoothMetricsProto;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.MetricsLogger;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import android.telecom.TelecomManager;


/**
 * Provides Bluetooth Headset and Handsfree profile, as a service in the Bluetooth application.
 *
 * Four modes for SCO audio:
 * 1. Raw audio through {@link #connectAudio()}
 * 2. Telecom call through {@link #phoneStateChanged(int, int, int, String, int, boolean)}
 * 3. Virtual call through {@link #startScoUsingVirtualVoiceCall()}
 * 4. Voice recognition through {@link #startVoiceRecognition(BluetoothDevice)}
 *
 * When they happen at the same time, the order of preference is:
 *   Raw audio > Telecom call > Virtual call > Voice Recognition
 * A higher preference mode will preempt lower preference mode
 */
public class HeadsetService extends ProfileService {
    private static final String TAG = "HeadsetService";
    private static final boolean DBG = true;
    private static final String DISABLE_INBAND_RINGING_PROPERTY =
            "persist.bluetooth.disableinbandringing";
    private static final ParcelUuid[] HEADSET_UUIDS = {BluetoothUuid.HSP, BluetoothUuid.Handsfree};
    private static final int[] CONNECTING_CONNECTED_STATES =
            {BluetoothProfile.STATE_CONNECTING, BluetoothProfile.STATE_CONNECTED};
    private static final int DIALING_OUT_TIMEOUT_MS = 10000;

    private int mMaxHeadsetConnections = 1;
    private BluetoothDevice mActiveDevice;
    private AdapterService mAdapterService;
    private HandlerThread mStateMachinesThread;
    // This is also used as a lock for shared data in HeadsetService
    private final HashMap<BluetoothDevice, HeadsetStateMachine> mStateMachines = new HashMap<>();
    private HeadsetNativeInterface mNativeInterface;
    private HeadsetSystemInterface mSystemInterface;
    private HeadsetA2dpSync mHfpA2dpSyncInterface;
    private boolean mAudioRouteAllowed = true;
    // Indicates whether SCO audio needs to be forced to open regardless ANY OTHER restrictions
    private boolean mForceScoAudio;
    private boolean mInbandRingingRuntimeDisable;
    // TODO: Need better handling of voice recognition events for Multi-HFP
    private boolean mVoiceRecognitionStarted;
    private boolean mVirtualCallStarted;
    private DialingOutTimeoutEvent mDialingOutTimeoutEvent;
    private boolean mStarted;
    private boolean mCreated;
    private static HeadsetService sHeadsetService;
    private boolean mDisconnectAll;

    @Override
    public IProfileServiceBinder initBinder() {
        return new BluetoothHeadsetBinder(this);
    }

    @Override
    protected void create() {
        Log.i(TAG, "create()");
        if (mCreated) {
            throw new IllegalStateException("create() called twice");
        }
        mCreated = true;
    }

    @Override
    protected boolean start() {
        Log.i(TAG, "start()");
        if (mStarted) {
            throw new IllegalStateException("start() called twice");
        }
        // Step 1: Get adapter service, should never be null
        mAdapterService = Objects.requireNonNull(AdapterService.getAdapterService(),
                "AdapterService cannot be null when HeadsetService starts");
        // Step 2: Start handler thread for state machines
        mStateMachinesThread = new HandlerThread("HeadsetService.StateMachines");
        mStateMachinesThread.start();
        // Step 3: Initialize system interface
        mSystemInterface = HeadsetObjectsFactory.getInstance().makeSystemInterface(this);
        mSystemInterface.init();
        // Step 4: Initialize native interface
        mMaxHeadsetConnections = mAdapterService.getMaxConnectedAudioDevices();
        if(mAdapterService.isVendorIntfEnabled()) {
            //Always set it to 2
            //to enable TWS+
            mMaxHeadsetConnections = 2;
            Log.d(TAG," Max_HFP_Connections  " + mMaxHeadsetConnections);
        }

        mNativeInterface = HeadsetObjectsFactory.getInstance().getNativeInterface();
        // Add 1 to allow a pending device to be connecting or disconnecting
        mNativeInterface.init(mMaxHeadsetConnections + 1, isInbandRingingEnabled());
        // Step 5: Check if state machine table is empty, crash if not
        if (mStateMachines.size() > 0) {
            throw new IllegalStateException(
                    "start(): mStateMachines is not empty, " + mStateMachines.size()
                            + " is already created. Was stop() called properly?");
        }
        // Step 6: Setup broadcast receivers
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(AudioManager.VOLUME_CHANGED_ACTION);
        filter.addAction(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
        registerReceiver(mHeadsetReceiver, filter);
        // Step 7: Mark service as started

        setHeadsetService(this);
        mStarted = true;

        mHfpA2dpSyncInterface = new HeadsetA2dpSync(mSystemInterface, this);
        Log.i(TAG, " HeadsetService Started ");
        return true;
    }

    @Override
    protected boolean stop() {
        Log.i(TAG, "stop()");
        if (!mStarted) {
            Log.w(TAG, "stop() called before start()");
            // Still return true because it is considered "stopped" and doesn't have any functional
            // impact on the user
            return true;
        }
        // Step 7: Mark service as stopped
        mStarted = false;
        setHeadsetService(null);
        // Step 6: Tear down broadcast receivers
        unregisterReceiver(mHeadsetReceiver);
        synchronized (mStateMachines) {
            // Reset active device to null
            mActiveDevice = null;
            mInbandRingingRuntimeDisable = false;
            mForceScoAudio = false;
            mAudioRouteAllowed = true;
            if(mAdapterService.isVendorIntfEnabled()) {
                //to enable TWS+
                mMaxHeadsetConnections = 2;
            } else {
                mMaxHeadsetConnections = 1;
            }
            mVoiceRecognitionStarted = false;
            mVirtualCallStarted = false;
            if (mDialingOutTimeoutEvent != null) {
                mStateMachinesThread.getThreadHandler().removeCallbacks(mDialingOutTimeoutEvent);
                mDialingOutTimeoutEvent = null;
            }
            // Step 5: Destroy state machines
            for (HeadsetStateMachine stateMachine : mStateMachines.values()) {
                HeadsetObjectsFactory.getInstance().destroyStateMachine(stateMachine);
            }
            mStateMachines.clear();
        }
        // Step 4: Destroy native interface
        mNativeInterface.cleanup();
        // Step 3: Destroy system interface
        mSystemInterface.stop();
        // Step 2: Stop handler thread
        mStateMachinesThread.quitSafely();
        mStateMachinesThread = null;
        // Step 1: Clear
        mAdapterService = null;
        return true;
    }

    @Override
    protected void cleanup() {
        Log.i(TAG, "cleanup");
        if (!mCreated) {
            Log.w(TAG, "cleanup() called before create()");
        }
        mCreated = false;
    }

    /**
     * Checks if this service object is able to accept binder calls
     *
     * @return True if the object can accept binder calls, False otherwise
     */
    public boolean isAlive() {
        return isAvailable() && mCreated && mStarted;
    }

    /**
     * Get the {@link Looper} for the state machine thread. This is used in testing and helper
     * objects
     *
     * @return {@link Looper} for the state machine thread
     */
    @VisibleForTesting
    public Looper getStateMachinesThreadLooper() {
        return mStateMachinesThread.getLooper();
    }

    interface StateMachineTask {
        void execute(HeadsetStateMachine stateMachine);
    }

    // send message to all statemachines in connecting and connected state.
    private void doForEachConnectedConnectingStateMachine(StateMachineTask task) {
        synchronized (mStateMachines) {
            for (BluetoothDevice device :
                         getDevicesMatchingConnectionStates(CONNECTING_CONNECTED_STATES)) {
                task.execute(mStateMachines.get(device));
            }
        }
    }

    private boolean doForStateMachine(BluetoothDevice device, StateMachineTask task) {
        synchronized (mStateMachines) {
            HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                return false;
            }
            task.execute(stateMachine);
        }
        return true;
    }

    private void doForEachConnectedStateMachine(StateMachineTask task) {
        synchronized (mStateMachines) {
            for (BluetoothDevice device : getConnectedDevices()) {
                task.execute(mStateMachines.get(device));
            }
        }
    }

    void onDeviceStateChanged(HeadsetDeviceState deviceState) {
        doForEachConnectedStateMachine(
                stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.DEVICE_STATE_CHANGED,
                        deviceState));
    }

    /**
     * Handle messages from native (JNI) to Java. This needs to be synchronized to avoid posting
     * messages to state machine before start() is done
     *
     * @param stackEvent event from native stack
     */
    void messageFromNative(HeadsetStackEvent stackEvent) {
        Objects.requireNonNull(stackEvent.device,
                "Device should never be null, event: " + stackEvent);
        synchronized (mStateMachines) {
            HeadsetStateMachine stateMachine = mStateMachines.get(stackEvent.device);
            if (stackEvent.type == HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED) {
                switch (stackEvent.valueInt) {
                    case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                    case HeadsetHalConstants.CONNECTION_STATE_CONNECTING: {
                        // Create new state machine if none is found
                        if (stateMachine == null) {
                            stateMachine = HeadsetObjectsFactory.getInstance()
                                    .makeStateMachine(stackEvent.device,
                                            mStateMachinesThread.getLooper(), this, mAdapterService,
                                            mNativeInterface, mSystemInterface);
                            mStateMachines.put(stackEvent.device, stateMachine);
                        }
                        break;
                    }
                }
            }
            if (stateMachine == null) {
                throw new IllegalStateException(
                        "State machine not found for stack event: " + stackEvent);
            }
            stateMachine.sendMessage(HeadsetStateMachine.STACK_EVENT, stackEvent);
        }
    }

    private final BroadcastReceiver mHeadsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "mHeadsetReceiver, action is null");
                return;
            }
            switch (action) {
                case Intent.ACTION_BATTERY_CHANGED: {
                    int batteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    if (batteryLevel < 0 || scale <= 0) {
                        Log.e(TAG, "Bad Battery Changed intent: batteryLevel=" + batteryLevel
                                + ", scale=" + scale);
                        return;
                    }
                    batteryLevel = batteryLevel * 5 / scale;
                    mSystemInterface.getHeadsetPhoneState().setCindBatteryCharge(batteryLevel);
                    break;
                }
                case AudioManager.VOLUME_CHANGED_ACTION: {
                    int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                    if (streamType == AudioManager.STREAM_BLUETOOTH_SCO) {
                        doForEachConnectedStateMachine(stateMachine -> stateMachine.sendMessage(
                                HeadsetStateMachine.INTENT_SCO_VOLUME_CHANGED, intent));
                    }
                    break;
                }
                case BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY: {
                    int requestType = intent.getIntExtra(BluetoothDevice.EXTRA_ACCESS_REQUEST_TYPE,
                            BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS);
                    BluetoothDevice device =
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    logD("Received BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY, device=" + device
                            + ", type=" + requestType);
                    if (requestType == BluetoothDevice.REQUEST_TYPE_PHONEBOOK_ACCESS) {
                        synchronized (mStateMachines) {
                            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
                            if (stateMachine == null) {
                                Log.wtfStack(TAG, "Cannot find state machine for " + device);
                                return;
                            }
                            stateMachine.sendMessage(
                                    HeadsetStateMachine.INTENT_CONNECTION_ACCESS_REPLY, intent);
                        }
                    }
                    break;
                }
                case BluetoothDevice.ACTION_BOND_STATE_CHANGED: {
                    int state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                            BluetoothDevice.ERROR);
                    BluetoothDevice device = Objects.requireNonNull(
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE),
                            "ACTION_BOND_STATE_CHANGED with no EXTRA_DEVICE");
                    logD("Bond state changed for device: " + device + " state: " + state);
                    if (state != BluetoothDevice.BOND_NONE) {
                        break;
                    }
                    synchronized (mStateMachines) {
                        HeadsetStateMachine stateMachine = mStateMachines.get(device);
                        if (stateMachine == null) {
                            break;
                        }
                        if (stateMachine.getConnectionState()
                                != BluetoothProfile.STATE_DISCONNECTED) {
                            break;
                        }
                        removeStateMachine(device);
                    }
                    break;
                }
                //TODO: below 2 should be done for all stateMachines ( doForEachConnectedStateMachine )
                case BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED: {
                    logD("Received BluetoothA2dp Play State changed");
                    mHfpA2dpSyncInterface.updateA2DPPlayingState(intent);
                    break;
                }
                case BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED: {
                    logD("Received BluetoothA2dp Connection State changed");
                    mHfpA2dpSyncInterface.updateA2DPConnectionState(intent);
                    break;
                }
                default:
                    Log.w(TAG, "Unknown action " + action);
            }
        }
    };

    /**
     * Handlers for incoming service calls
     */
    private static class BluetoothHeadsetBinder extends IBluetoothHeadset.Stub
            implements IProfileServiceBinder {
        private volatile HeadsetService mService;

        BluetoothHeadsetBinder(HeadsetService svc) {
            mService = svc;
        }

        @Override
        public void cleanup() {
            mService = null;
        }

        private HeadsetService getService() {
            final HeadsetService service = mService;
            if (!Utils.checkCallerAllowManagedProfiles(service)) {
                Log.w(TAG, "Headset call not allowed for non-active user");
                return null;
            }
            if (service == null) {
                Log.w(TAG, "Service is null");
                return null;
            }
            if (!service.isAlive()) {
                Log.w(TAG, "Service is not alive");
                return null;
            }
            return service;
        }

        @Override
        public boolean connect(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.connect(device);
        }

        @Override
        public boolean disconnect(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnect(device);
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            HeadsetService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            HeadsetService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getDevicesMatchingConnectionStates(states);
        }

        public List<BluetoothDevice> getAllDevicesMatchingConnectionStates(int[] states) {
            HeadsetService service = getService();
            if (service == null) {
                return new ArrayList<BluetoothDevice>(0);
            }
            return service.getAllDevicesMatchingConnectionStates(states);
        }


        @Override
        public int getConnectionState(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return service.getConnectionState(device);
        }

        @Override
        public boolean setPriority(BluetoothDevice device, int priority) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.setPriority(device, priority);
        }

        @Override
        public int getPriority(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return BluetoothProfile.PRIORITY_UNDEFINED;
            }
            return service.getPriority(device);
        }

        @Override
        public boolean startVoiceRecognition(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.startVoiceRecognition(device);
        }

        @Override
        public boolean stopVoiceRecognition(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.stopVoiceRecognition(device);
        }

        @Override
        public boolean isAudioOn() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.isAudioOn();
        }

        @Override
        public boolean isAudioConnected(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.isAudioConnected(device);
        }

        @Override
        public int getAudioState(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
            }
            return service.getAudioState(device);
        }

        @Override
        public boolean connectAudio() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.connectAudio();
        }

        @Override
        public boolean disconnectAudio() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.disconnectAudio();
        }

        @Override
        public void setAudioRouteAllowed(boolean allowed) {
            HeadsetService service = getService();
            if (service == null) {
                return;
            }
            service.setAudioRouteAllowed(allowed);
        }

        @Override
        public boolean getAudioRouteAllowed() {
            HeadsetService service = getService();
            if (service != null) {
                return service.getAudioRouteAllowed();
            }
            return false;
        }

        @Override
        public void setForceScoAudio(boolean forced) {
            HeadsetService service = getService();
            if (service == null) {
                return;
            }
            service.setForceScoAudio(forced);
        }

        @Override
        public boolean startScoUsingVirtualVoiceCall() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.startScoUsingVirtualVoiceCall();
        }

        @Override
        public boolean stopScoUsingVirtualVoiceCall() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.stopScoUsingVirtualVoiceCall();
        }

        @Override
        public void phoneStateChanged(int numActive, int numHeld, int callState, String number,
                int type) {
            HeadsetService service = getService();
            if (service == null) {
                return;
            }
            service.phoneStateChanged(numActive, numHeld, callState, number, type, false);
        }

        @Override
        public void clccResponse(int index, int direction, int status, int mode, boolean mpty,
                String number, int type) {
            HeadsetService service = getService();
            if (service == null) {
                return;
            }
            service.clccResponse(index, direction, status, mode, mpty, number, type);
        }

        @Override
        public boolean sendVendorSpecificResultCode(BluetoothDevice device, String command,
                String arg) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.sendVendorSpecificResultCode(device, command, arg);
        }

        @Override
        public boolean setActiveDevice(BluetoothDevice device) {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.setActiveDevice(device);
        }

        @Override
        public BluetoothDevice getActiveDevice() {
            HeadsetService service = getService();
            if (service == null) {
                return null;
            }
            return service.getActiveDevice();
        }

        @Override
        public boolean isInbandRingingEnabled() {
            HeadsetService service = getService();
            if (service == null) {
                return false;
            }
            return service.isInbandRingingEnabled();
        }
    }

    // API methods
    public static synchronized HeadsetService getHeadsetService() {
        if (sHeadsetService == null) {
            Log.w(TAG, "getHeadsetService(): service is NULL");
            return null;
        }
        if (!sHeadsetService.isAvailable()) {
            Log.w(TAG, "getHeadsetService(): service is not available");
            return null;
        }
        logD("getHeadsetService(): returning " + sHeadsetService);
        return sHeadsetService;
    }

    private static synchronized void setHeadsetService(HeadsetService instance) {
        logD("setHeadsetService(): set to: " + instance);
        sHeadsetService = instance;
    }

    public BluetoothDevice getTwsPlusConnectedPeer(BluetoothDevice device) {
        AdapterService adapterService = AdapterService.getAdapterService();
        if (!adapterService.isTwsPlusDevice(device)) {
            logD("getTwsPlusConnectedPeer: Not a TWS+ device");
            return null;
        }
        List<BluetoothDevice> connDevices = getConnectedDevices();

        int size = connDevices.size();
        for(int i = 0; i < size; i++) {
            BluetoothDevice ConnectedDevice = connDevices.get(i);
            if (adapterService.getTwsPlusPeerAddress(device).equals(ConnectedDevice.getAddress())) {
                return ConnectedDevice;
            }
        }
        return null;
    }

    private boolean isConnectionAllowed(BluetoothDevice device,
                                           List<BluetoothDevice> connDevices
                                           ) {
        AdapterService adapterService = AdapterService.getAdapterService();
        boolean allowSecondHfConnection = false;

        if (connDevices.size() == 0) {
            allowSecondHfConnection = true;
        } else {
            BluetoothDevice connectedDev = connDevices.get(0);
            if (adapterService.isTwsPlusDevice(connectedDev)) {
                //If connected device is TWSPlus device
                //Allow connection only if the outgoing is peer of TWS connected earbud
                if (adapterService.isTwsPlusDevice(device)&&
                   adapterService.getTwsPlusPeerAddress(device).equals(connectedDev.getAddress())) {
                   allowSecondHfConnection = true;
                } else {
                   allowSecondHfConnection = false;
                }
            } else {
                //if Connected device is not TWS
                if (adapterService.isTwsPlusDevice(device)) {
                    //outgoing connection is TWSP
                    allowSecondHfConnection = false;
                    if (connDevices.size() == 1) {
                        //only if connected devices is 1
                        //disconnect it and override it with tws+
                        mDisconnectAll = true;
                    }
                } else {
                    //Incoming connection is legacy device
                   if (connDevices.size() < mMaxHeadsetConnections) {
                      allowSecondHfConnection = true;
                   } else {
                      allowSecondHfConnection = false;
                   }
               }
            }
            Log.v(TAG, "isTwsPlusDevice for " + device +
                     "is"+ adapterService.isTwsPlusDevice(device));
            Log.v(TAG, "TWS Peer Addr: " +
                      adapterService.getTwsPlusPeerAddress(device));
            Log.v(TAG, "Connected device" + connectedDev.getAddress());
        }

        Log.v(TAG, "allowSecondHfConnection: " + allowSecondHfConnection);
        Log.v(TAG, "DisconnectAll: " + mDisconnectAll);
        return allowSecondHfConnection;
    }

    public boolean connect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH ADMIN permission");
        if (getPriority(device) == BluetoothProfile.PRIORITY_OFF) {
            Log.w(TAG, "connect: PRIORITY_OFF, device=" + device + ", " + Utils.getUidPidString());
            return false;
        }
        ParcelUuid[] featureUuids = mAdapterService.getRemoteUuids(device);
        if (!BluetoothUuid.containsAnyUuid(featureUuids, HEADSET_UUIDS)) {
            Log.e(TAG, "connect: Cannot connect to " + device + ": no headset UUID, "
                    + Utils.getUidPidString());
            return false;
        }
        synchronized (mStateMachines) {
            Log.i(TAG, "connect: device=" + device + ", " + Utils.getUidPidString());
            HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                stateMachine = HeadsetObjectsFactory.getInstance()
                        .makeStateMachine(device, mStateMachinesThread.getLooper(), this,
                                mAdapterService, mNativeInterface, mSystemInterface);
                mStateMachines.put(device, stateMachine);
            }
            int connectionState = stateMachine.getConnectionState();
            if (connectionState == BluetoothProfile.STATE_CONNECTED
                    || connectionState == BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "connect: device " + device
                        + " is already connected/connecting, connectionState=" + connectionState);
                return false;
            }
            List<BluetoothDevice> connectingConnectedDevices =
                    getAllDevicesMatchingConnectionStates(CONNECTING_CONNECTED_STATES);
            boolean disconnectExisting = false;
            mDisconnectAll = false;
            if (connectingConnectedDevices.size() == 0) {
                 Log.e(TAG, "No Connected devices!");
            }
            if (!isConnectionAllowed(device, connectingConnectedDevices)) {
                // When there is maximum one device, we automatically disconnect the current one
                if (mMaxHeadsetConnections == 1) {
                    disconnectExisting = true;
                } else if (mDisconnectAll) {
                    //In Dual HF case
                    disconnectExisting = true;
                } else {
                    Log.w(TAG, "Max connection has reached, rejecting connection to " + device);
                    return false;
                }
            }
            if (disconnectExisting) {
                for (BluetoothDevice connectingConnectedDevice : connectingConnectedDevices) {
                    disconnect(connectingConnectedDevice);
                }
                setActiveDevice(null);
            }
            stateMachine.sendMessage(HeadsetStateMachine.CONNECT, device);
        }
        return true;
    }

    boolean disconnect(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "disconnect: device=" + device + ", " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "disconnect: device " + device + " not ever connected/connecting");
                return false;
            }
            int connectionState = stateMachine.getConnectionState();
            if (connectionState != BluetoothProfile.STATE_CONNECTED
                    && connectionState != BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "disconnect: device " + device
                        + " not connected/connecting, connectionState=" + connectionState);
                return false;
            }
            stateMachine.sendMessage(HeadsetStateMachine.DISCONNECT, device);
        }
        return true;
    }

    public boolean isInCall() {
        boolean isCallOngoing = mSystemInterface.isInCall();
        Log.d(TAG," isInCall " + isCallOngoing);
        return isCallOngoing;
    }

    public boolean isRinging() {
        boolean isRingOngoing = mSystemInterface.isRinging();
        Log.d(TAG," isRinging " + isRingOngoing);
        return isRingOngoing;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (HeadsetStateMachine stateMachine : mStateMachines.values()) {
                if (stateMachine.getConnectionState() == BluetoothProfile.STATE_CONNECTED) {
                    devices.add(stateMachine.getDevice());
                }
            }
        }
        return devices;
    }

    /**
     * Helper method to get all devices with matching connection state
     *
     */
    private List<BluetoothDevice> getAllDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            Log.e(TAG, "->States is null");
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            Log.e(TAG, "->Bonded device is null");
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {

                int connectionState = getConnectionState(device);
                Log.e(TAG, "Connec state for: " + device + "is" + connectionState);
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        Log.e(TAG, "Adding device: " + device);
                        break;
                    }
                }
            }
        }
        return devices;
    }

    /**
     * Same as the API method {@link BluetoothHeadset#getDevicesMatchingConnectionStates(int[])}
     *
     * @param states an array of states from {@link BluetoothProfile}
     * @return a list of devices matching the array of connection states
     */
    @VisibleForTesting
    public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        if (states == null) {
            return devices;
        }
        final BluetoothDevice[] bondedDevices = mAdapterService.getBondedDevices();
        if (bondedDevices == null) {
            return devices;
        }
        synchronized (mStateMachines) {
            for (BluetoothDevice device : bondedDevices) {

                int connectionState = getConnectionState(device);
                for (int state : states) {
                    if (connectionState == state) {
                        devices.add(device);
                        break;
                    }
                }
            }
        }
        return devices;
    }

    public int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                return BluetoothProfile.STATE_DISCONNECTED;
            }
            return stateMachine.getConnectionState();
        }
    }

    public boolean setPriority(BluetoothDevice device, int priority) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Settings.Global.putInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()), priority);
        Log.i(TAG, "setPriority: device=" + device + ", priority=" + priority + ", "
                + Utils.getUidPidString());
        return true;
    }

    public int getPriority(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return Settings.Global.getInt(getContentResolver(),
                Settings.Global.getBluetoothHeadsetPriorityKey(device.getAddress()),
                BluetoothProfile.PRIORITY_UNDEFINED);
    }

    boolean startVoiceRecognition(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Log.i(TAG, "startVoiceRecognition: device=" + device + ", " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "startVoiceRecognition: " + device + " is never connected, "
                        + Utils.getUidPidString());
                return false;
            }
            int connectionState = stateMachine.getConnectionState();
            if (connectionState != BluetoothProfile.STATE_CONNECTED
                    && connectionState != BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "startVoiceRecognition: " + device + " is not connected or connecting "
                        + Utils.getUidPidString());
                return false;
            }
            if (isAudioOn()) {
                Log.w(TAG, "startVoiceRecognition: audio is not idle, " + Utils.getUidPidString());
                return false;
            }
            if (mVoiceRecognitionStarted) {
                Log.w(TAG, "startVoiceRecognition: voice recognition has already started, "
                        + Utils.getUidPidString());
                return false;
            }
            mVoiceRecognitionStarted = true;
            stateMachine.sendMessage(HeadsetStateMachine.VOICE_RECOGNITION_START, device);
        }
        return true;
    }

    boolean stopVoiceRecognition(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Log.i(TAG, "stopVoiceRecognition: device=" + device + ", " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "stopVoiceRecognition: " + device + " is never connected, "
                        + Utils.getUidPidString());
                return false;
            }
            // It seem that we really need to check the AudioOn state.
            // But since we allow startVoiceRecognition in STATE_CONNECTED and
            // STATE_CONNECTING state, we do these 2 in this method
            int connectionState = stateMachine.getConnectionState();
            if (connectionState != BluetoothProfile.STATE_CONNECTED
                    && connectionState != BluetoothProfile.STATE_CONNECTING) {
                Log.w(TAG, "stopVoiceRecognition: " + device + " is not connected or connecting "
                        + Utils.getUidPidString());
                return false;
            }
            if (!mVoiceRecognitionStarted) {
                Log.w(TAG, "stopVoiceRecognition: voice recognition was not started, "
                        + Utils.getUidPidString());
                return false;
            }
            mVoiceRecognitionStarted = false;
            stateMachine.sendMessage(HeadsetStateMachine.VOICE_RECOGNITION_STOP, device);
        }
        return true;
    }

    boolean isAudioOn() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        int numConnectedAudioDevices = getNonIdleAudioDevices().size();
        Log.d(TAG," isAudioOn: The number of audio connected devices "
                 + numConnectedAudioDevices);
        return numConnectedAudioDevices > 0;
    }

    public boolean isScoOrCallActive() {
      Log.d(TAG, "isScoOrCallActive(): Call Active:" + mSystemInterface.isInCall() +
                                       "Call is Ringing:" + mSystemInterface.isInCall() +
                                       "SCO is Active:" + isAudioOn());
      if (mSystemInterface.isInCall() || (mSystemInterface.isRinging()) || isAudioOn()) {
          return true;
      } else {
          return false;
      }
    }
    boolean isAudioConnected(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                return false;
            }
            return stateMachine.getAudioState() == BluetoothHeadset.STATE_AUDIO_CONNECTED;
        }
    }

    int getAudioState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
            }
            return stateMachine.getAudioState();
        }
    }

    public void setAudioRouteAllowed(boolean allowed) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "setAudioRouteAllowed: allowed=" + allowed + ", " + Utils.getUidPidString());
        mAudioRouteAllowed = allowed;
        mNativeInterface.setScoAllowed(allowed);
    }

    public boolean getAudioRouteAllowed() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mAudioRouteAllowed;
    }

    public void setForceScoAudio(boolean forced) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "setForceScoAudio: forced=" + forced + ", " + Utils.getUidPidString());
        mForceScoAudio = forced;
    }

    @VisibleForTesting
    public boolean getForceScoAudio() {
        return mForceScoAudio;
    }

    /**
     * Get first available device for SCO audio
     *
     * @return first connected headset device
     */
    @VisibleForTesting
    @Nullable
    public BluetoothDevice getFirstConnectedAudioDevice() {
        ArrayList<HeadsetStateMachine> stateMachines = new ArrayList<>();
        synchronized (mStateMachines) {
            List<BluetoothDevice> availableDevices =
                    getDevicesMatchingConnectionStates(CONNECTING_CONNECTED_STATES);
            for (BluetoothDevice device : availableDevices) {
                final HeadsetStateMachine stateMachine = mStateMachines.get(device);
                if (stateMachine == null) {
                    continue;
                }
                stateMachines.add(stateMachine);
            }
        }
        stateMachines.sort(Comparator.comparingLong(HeadsetStateMachine::getConnectingTimestampMs));
        if (stateMachines.size() > 0) {
            return stateMachines.get(0).getDevice();
        }
        return null;
    }

    /**
     * Set the active device.
     *
     * @param device the active device
     * @return true on success, otherwise false
     */
    public boolean setActiveDevice(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "setActiveDevice: device=" + device + ", " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            if (device == null) {
                // Clear the active device
                if (mVoiceRecognitionStarted) {
                    if (!stopVoiceRecognition(mActiveDevice)) {
                        Log.w(TAG, "setActiveDevice: fail to stopVoiceRecognition from "
                                + mActiveDevice);
                    }
                }
                if (mVirtualCallStarted) {
                    if (!stopScoUsingVirtualVoiceCall()) {
                        Log.w(TAG, "setActiveDevice: fail to stopScoUsingVirtualVoiceCall from "
                                + mActiveDevice);
                    }
                }
                if (getAudioState(mActiveDevice) != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    if (!disconnectAudio(mActiveDevice)) {
                        Log.w(TAG,
                                "setActiveDevice: fail to disconnectAudio from " + mActiveDevice);
                    }
                }
                mActiveDevice = null;
                broadcastActiveDevice(null);
                return true;
            }
            if (device.equals(mActiveDevice)) {
                Log.i(TAG, "setActiveDevice: device " + device + " is already active");
                return true;
            }
            if (getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) {
                Log.e(TAG, "setActiveDevice: Cannot set " + device
                        + " as active, device is not connected");
                return false;
            }
            if (mActiveDevice != null && mAdapterService.isTwsPlusDevice(device) &&
                mAdapterService.isTwsPlusDevice(mActiveDevice) &&
                !Objects.equals(device, mActiveDevice) &&
                getConnectionState(mActiveDevice) == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG,"Ignore setActiveDevice request");
                return true;
            }

            if (!mNativeInterface.setActiveDevice(device)) {
                Log.e(TAG, "setActiveDevice: Cannot set " + device + " as active in native layer");
                return false;
            }
            BluetoothDevice previousActiveDevice = mActiveDevice;
            mActiveDevice = device;
            if (getAudioState(previousActiveDevice) != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                if (!disconnectAudio(previousActiveDevice)) {
                    Log.e(TAG, "setActiveDevice: fail to disconnectAudio from "
                            + previousActiveDevice);
                    mActiveDevice = previousActiveDevice;
                    mNativeInterface.setActiveDevice(previousActiveDevice);
                    return false;
                }
                broadcastActiveDevice(mActiveDevice);
            } else if (shouldPersistAudio()) {
                broadcastActiveDevice(mActiveDevice);
                if (!connectAudio(mActiveDevice)) {
                    Log.e(TAG, "setActiveDevice: fail to connectAudio to " + mActiveDevice);
                    mActiveDevice = previousActiveDevice;
                    mNativeInterface.setActiveDevice(previousActiveDevice);
                    return false;
                }
            } else {
                broadcastActiveDevice(mActiveDevice);
            }
        }
        return true;
    }

    /**
     * Get the active device.
     *
     * @return the active device or null if no device is active
     */
    public BluetoothDevice getActiveDevice() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            return mActiveDevice;
        }
    }

    boolean connectAudio() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        synchronized (mStateMachines) {
            BluetoothDevice device = mActiveDevice;
            if (device == null) {
                Log.w(TAG, "connectAudio: no active device, " + Utils.getUidPidString());
                return false;
            }
            return connectAudio(device);
        }
    }

    boolean connectAudio(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        if (device == null) {
            Log.w(TAG, "connectAudio: device is null, " + Utils.getUidPidString());
            return false;
        }
        if (!device.equals(mActiveDevice)) {
            Log.w(TAG, "connectAudio: device " + device + " is not active, active device is "
                    + mActiveDevice + ", " + Utils.getUidPidString());
            return false;
        }
        Log.i(TAG, "connectAudio: device=" + device + ", " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "connectAudio: device " + device + " was never connected/connecting");
                return false;
            }
            if (stateMachine.getConnectionState() != BluetoothProfile.STATE_CONNECTED) {
                Log.w(TAG, "connectAudio: profile not connected");
                return false;
            }
            if (stateMachine.getAudioState() != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                logD("connectAudio: audio is not idle for device " + device);
                return true;
            }
            if (isAudioOn()) {
                Log.w(TAG, "connectAudio: audio is not idle, current audio devices are "
                        + Arrays.toString(getNonIdleAudioDevices().toArray()));

                BluetoothDevice activeScoDevice = getNonIdleAudioDevices().get(0);
                AdapterService adapterService = AdapterService.getAdapterService();
                //If activeScoDevice present and It is TWSP device
                //and PeerDevice is still connected
                //return true to telePhony
                if (activeScoDevice != null
                    && adapterService.isTwsPlusDevice(activeScoDevice)
                    && getTwsPlusConnectedPeer(activeScoDevice) != null) {
                    Log.d(TAG, "is Tws case, keeping Audioon is Success");
                    return true;
                } else {
                    return false;
                }
            }
            if (!isScoAcceptable(device)) {
                Log.w(TAG, "connectAudio, rejected SCO request to " + device);
                return true;
            }
            stateMachine.sendMessage(HeadsetStateMachine.CONNECT_AUDIO, device);
        }
        return true;
    }

    private List<BluetoothDevice> getNonIdleAudioDevices() {
        ArrayList<BluetoothDevice> devices = new ArrayList<>();
        synchronized (mStateMachines) {
            for (HeadsetStateMachine stateMachine : mStateMachines.values()) {
                if (stateMachine.getAudioState() != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    devices.add(stateMachine.getDevice());
                }
            }
        }
        return devices;
    }

    boolean disconnectAudio() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        boolean result = false;
        synchronized (mStateMachines) {
            for (BluetoothDevice device : getNonIdleAudioDevices()) {
                if (disconnectAudio(device)) {
                    result = true;
                } else {
                    Log.e(TAG, "disconnectAudio() from " + device + " failed");
                }
            }
            if (mVirtualCallStarted) {
                stopScoUsingVirtualVoiceCall();
            }
            if (mVoiceRecognitionStarted) {
                stopVoiceRecognition(mActiveDevice);
            }
        }
        if (!result) {
            logD("disconnectAudio() no active audio connection");
        }
        return result;
    }

    boolean disconnectAudio(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        synchronized (mStateMachines) {
            Log.i(TAG, "disconnectAudio: device=" + device + ", " + Utils.getUidPidString());
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "disconnectAudio: device " + device + " was never connected/connecting");
                return false;
            }
            if (stateMachine.getAudioState() == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                Log.w(TAG, "disconnectAudio, audio is already disconnected for " + device);
                return false;
            }
            stateMachine.sendMessage(HeadsetStateMachine.DISCONNECT_AUDIO, device);
        }
        return true;
    }

    boolean isVirtualCallStarted() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            return mVirtualCallStarted;
        }
    }

    private boolean startScoUsingVirtualVoiceCall() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "startScoUsingVirtualVoiceCall: " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            // Check if the SCO state is connecting or disconnected
            if (isAudioOn()) {
                Log.w(TAG, "startScoUsingVirtualVoiceCall: audio is not idle");
                return false;
            }
            if (mActiveDevice == null) {
                Log.w(TAG, "startScoUsingVirtualVoiceCall: no active device");
                return false;
            }
            // Reject the request if a telecom call is going on
            boolean inTelecomCall = !mVirtualCallStarted && (mSystemInterface.isInCall()
                    || mSystemInterface.isRinging());
            if (inTelecomCall) {
                Log.w(TAG, "startScoUsingVirtualVoiceCall: Telecom call in progress");
                return false;
            }
            if (mVirtualCallStarted) {
                Log.w(TAG, "startScoUsingVirtualVoiceCall: has already started");
                return false;
            }
            mVirtualCallStarted = true;
            // Send virtual phone state changed to initialize SCO
            phoneStateChanged(0, 0, HeadsetHalConstants.CALL_STATE_DIALING, "", 0, true);
            phoneStateChanged(0, 0, HeadsetHalConstants.CALL_STATE_ALERTING, "", 0, true);
            phoneStateChanged(1, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, true);
            return true;
        }
    }

    boolean stopScoUsingVirtualVoiceCall() {
        enforceCallingOrSelfPermission(BLUETOOTH_ADMIN_PERM, "Need BLUETOOTH_ADMIN permission");
        Log.i(TAG, "stopScoUsingVirtualVoiceCall: " + Utils.getUidPidString());
        synchronized (mStateMachines) {
            // 1. Check if virtual call has already started
            if (!mVirtualCallStarted) {
                Log.w(TAG, "stopScoUsingVirtualVoiceCall: virtual call not started");
                return false;
            }
            mVirtualCallStarted = false;
            // 2. Send virtual phone state changed to close SCO
            phoneStateChanged(0, 0, HeadsetHalConstants.CALL_STATE_IDLE, "", 0, true);
        }
        return true;
    }

    class DialingOutTimeoutEvent implements Runnable {
        BluetoothDevice mDialingOutDevice;

        DialingOutTimeoutEvent(BluetoothDevice fromDevice) {
            mDialingOutDevice = fromDevice;
        }

        @Override
        public void run() {
            synchronized (mStateMachines) {
                mDialingOutTimeoutEvent = null;
                doForStateMachine(mDialingOutDevice, stateMachine -> stateMachine.sendMessage(
                        HeadsetStateMachine.DIALING_OUT_RESULT, 0 /* fail */, 0,
                        mDialingOutDevice));
            }
        }

        @Override
        public String toString() {
            return "DialingOutTimeoutEvent[" + mDialingOutDevice + "]";
        }
    }

    /**
     * Dial an outgoing call as requested by the remote device
     *
     * @param fromDevice remote device that initiated this dial out action
     * @param dialNumber number to dial
     * @return true on successful dial out
     */
    boolean dialOutgoingCall(BluetoothDevice fromDevice, String dialNumber) {
        synchronized (mStateMachines) {
            if (!isOnStateMachineThread()) {
                Log.e(TAG, "dialOutgoingCall must be called from state machine thread");
                return false;
            }
            if (mDialingOutTimeoutEvent != null) {
                Log.e(TAG, "dialOutgoingCall, already dialing by " + mDialingOutTimeoutEvent);
                return false;
            }
            if (isVirtualCallStarted()) {
                if (!stopScoUsingVirtualVoiceCall()) {
                    Log.e(TAG, "dialOutgoingCall failed to stop current virtual call");
                    return false;
                }
            }
            if (!setActiveDevice(fromDevice)) {
                Log.e(TAG, "dialOutgoingCall failed to set active device to " + fromDevice);
                return false;
            }
            Intent intent = new Intent(Intent.ACTION_CALL_PRIVILEGED,
                    Uri.fromParts(PhoneAccount.SCHEME_TEL, dialNumber, null));
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            mDialingOutTimeoutEvent = new DialingOutTimeoutEvent(fromDevice);
            mStateMachinesThread.getThreadHandler()
                    .postDelayed(mDialingOutTimeoutEvent, DIALING_OUT_TIMEOUT_MS);
            return true;
        }
    }

    boolean hasDeviceInitiatedDialingOut() {
        synchronized (mStateMachines) {
            return mDialingOutTimeoutEvent != null;
        }
    }

    private void phoneStateChanged(int numActive, int numHeld, int callState, String number,
            int type, boolean isVirtualCall) {
        enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, "Need MODIFY_PHONE_STATE permission");
        synchronized (mStateMachines) {
            if ((numActive + numHeld) > 0 || callState != HeadsetHalConstants.CALL_STATE_IDLE) {
                if (!isVirtualCall && mVirtualCallStarted) {
                    // stop virtual voice call if there is an incoming Telecom call update
                    stopScoUsingVirtualVoiceCall();
                }
            }
            if (mDialingOutTimeoutEvent != null) {
                // Send result to state machine when dialing starts
                if (callState == HeadsetHalConstants.CALL_STATE_DIALING) {
                    mStateMachinesThread.getThreadHandler()
                            .removeCallbacks(mDialingOutTimeoutEvent);
                    doForStateMachine(mDialingOutTimeoutEvent.mDialingOutDevice,
                            stateMachine -> stateMachine.sendMessage(
                                    HeadsetStateMachine.DIALING_OUT_RESULT, 1 /* success */, 0,
                                    mDialingOutTimeoutEvent.mDialingOutDevice));
                } else if (callState == HeadsetHalConstants.CALL_STATE_ACTIVE
                        || callState == HeadsetHalConstants.CALL_STATE_IDLE) {
                    // Clear the timeout event when the call is connected or disconnected
                    if (!mStateMachinesThread.getThreadHandler()
                            .hasCallbacks(mDialingOutTimeoutEvent)) {
                        mDialingOutTimeoutEvent = null;
                    }
                }
            }
        }
        mStateMachinesThread.getThreadHandler().post(() -> {
            mSystemInterface.getHeadsetPhoneState().setNumActiveCall(numActive);
            mSystemInterface.getHeadsetPhoneState().setNumHeldCall(numHeld);
            mSystemInterface.getHeadsetPhoneState().setCallState(callState);
        });
        doForEachConnectedConnectingStateMachine(
                stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.CALL_STATE_CHANGED,
                        new HeadsetCallState(numActive, numHeld, callState, number, type)));

    }

    private void clccResponse(int index, int direction, int status, int mode, boolean mpty,
            String number, int type) {
        enforceCallingOrSelfPermission(MODIFY_PHONE_STATE, "Need MODIFY_PHONE_STATE permission");
        doForEachConnectedStateMachine(
                stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.SEND_CCLC_RESPONSE,
                        new HeadsetClccResponse(index, direction, status, mode, mpty, number,
                                type)));
    }

    private boolean sendVendorSpecificResultCode(BluetoothDevice device, String command,
            String arg) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        synchronized (mStateMachines) {
            final HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "sendVendorSpecificResultCode: device " + device
                        + " was never connected/connecting");
                return false;
            }
            int connectionState = stateMachine.getConnectionState();
            if (connectionState != BluetoothProfile.STATE_CONNECTED) {
                return false;
            }
            // Currently we support only "+ANDROID".
            if (!command.equals(BluetoothHeadset.VENDOR_RESULT_CODE_COMMAND_ANDROID)) {
                Log.w(TAG, "Disallowed unsolicited result code command: " + command);
                return false;
            }
            stateMachine.sendMessage(HeadsetStateMachine.SEND_VENDOR_SPECIFIC_RESULT_CODE,
                    new HeadsetVendorSpecificResultCode(device, command, arg));
        }
        return true;
    }

    boolean isInbandRingingEnabled() {
        boolean returnVal;
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        returnVal = BluetoothHeadset.isInbandRingingSupported(this) && !SystemProperties.getBoolean(
                DISABLE_INBAND_RINGING_PROPERTY, false) && !mInbandRingingRuntimeDisable;
        Log.d(TAG, "isInbandRingingEnabled returning: " + returnVal);
        return returnVal;
    }

    /**
     * Called from {@link HeadsetStateMachine} in state machine thread when there is a connection
     * state change
     *
     * @param device remote device
     * @param fromState from which connection state is the change
     * @param toState to which connection state is the change
     */
    @VisibleForTesting
    public void onConnectionStateChangedFromStateMachine(BluetoothDevice device, int fromState,
            int toState) {
        synchronized (mStateMachines) {
            List<BluetoothDevice> audioConnectableDevices =
                    getDevicesMatchingConnectionStates(CONNECTING_CONNECTED_STATES);
            if (fromState != BluetoothProfile.STATE_CONNECTED
                    && toState == BluetoothProfile.STATE_CONNECTED) {
                if (audioConnectableDevices.size() > 1) {
                    mInbandRingingRuntimeDisable = true;
                    doForEachConnectedStateMachine(
                            stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.SEND_BSIR,
                                    0));
                }
                MetricsLogger.logProfileConnectionEvent(BluetoothMetricsProto.ProfileId.HEADSET);
            }
            if (fromState != BluetoothProfile.STATE_DISCONNECTED
                    && toState == BluetoothProfile.STATE_DISCONNECTED) {
                if (audioConnectableDevices.size() <= 1) {
                    mInbandRingingRuntimeDisable = false;
                    doForEachConnectedStateMachine(
                            stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.SEND_BSIR,
                                    1));
                }
                if (device.equals(mActiveDevice)) {
                    AdapterService adapterService = AdapterService.getAdapterService();
                    if (adapterService.isTwsPlusDevice(device)) {
                        //if the disconnected device is a tws+ device
                        // and if peer device is connected, set the peer
                        // as an active device
                        BluetoothDevice peerDevice = getTwsPlusConnectedPeer(device);
                        if (peerDevice != null) {
                            setActiveDevice(peerDevice);
                        }
                    } else {
                        setActiveDevice(null);
                    }
                }
            }
        }
    }

    public HeadsetA2dpSync getHfpA2DPSyncInterface(){
        return mHfpA2dpSyncInterface;
    }

    public void sendA2dpStateChangeUpdate(int state) {
        Log.d(TAG," sendA2dpStateChange newState = " + state);
        doForEachConnectedConnectingStateMachine(
              stateMachine -> stateMachine.sendMessage(HeadsetStateMachine.A2DP_STATE_CHANGED,
                                    state));
    }

    private boolean shouldCallAudioBeActive() {
        return mSystemInterface.isInCall() || (mSystemInterface.isRinging()
                && isInbandRingingEnabled());
    }

    /**
     * Only persist audio during active device switch when call audio is supposed to be active and
     * virtual call has not been started. Virtual call is ignored because AudioService and
     * applications should reconnect SCO during active device switch and forcing SCO connection
     * here will make AudioService think SCO is started externally instead of by one of its SCO
     * clients.
     *
     * @return true if call audio should be active and no virtual call is going on
     */
    private boolean shouldPersistAudio() {
        return !mVirtualCallStarted && shouldCallAudioBeActive();
    }

    /**
     * Called from {@link HeadsetStateMachine} in state machine thread when there is a audio
     * connection state change
     *
     * @param device remote device
     * @param fromState from which audio connection state is the change
     * @param toState to which audio connection state is the change
     */
    @VisibleForTesting
    public void onAudioStateChangedFromStateMachine(BluetoothDevice device, int fromState,
            int toState) {
        synchronized (mStateMachines) {
            if (toState == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                if (fromState != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    if (mActiveDevice != null && !mActiveDevice.equals(device)
                            && shouldPersistAudio()) {
                        if (!connectAudio(mActiveDevice)) {
                            Log.w(TAG, "onAudioStateChangedFromStateMachine, failed to connect"
                                    + " audio to new " + "active device " + mActiveDevice
                                    + ", after " + device + " is disconnected from SCO");
                        }
                    }
                }
            }
        }
    }

    private void broadcastActiveDevice(BluetoothDevice device) {
        logD("broadcastActiveDevice: " + device);
        Intent intent = new Intent(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT
                | Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
        sendBroadcastAsUser(intent, UserHandle.ALL, HeadsetService.BLUETOOTH_PERM);
    }

    /**
     * Check whether it is OK to accept a headset connection from a remote device
     *
     * @param device remote device that initiates the connection
     * @return true if the connection is acceptable
     */
    public boolean okToAcceptConnection(BluetoothDevice device) {
        // Check if this is an incoming connection in Quiet mode.
        boolean isPts = SystemProperties.getBoolean("bt.pts.certification", false);
        if (mAdapterService.isQuietModeEnabled()) {
            Log.w(TAG, "okToAcceptConnection: return false as quiet mode enabled");
            return false;
        }
        if(!isPts) {
            // Check priority and accept or reject the connection.
            // Note: Logic can be simplified, but keeping it this way for readability
            int priority = getPriority(device);
            int bondState = mAdapterService.getBondState(device);
            // If priority is undefined, it is likely that service discovery has not completed and peer
            // initiated the connection. Allow this connection only if the device is bonded or bonding
            boolean serviceDiscoveryPending = (priority == BluetoothProfile.PRIORITY_UNDEFINED) && (
                    bondState == BluetoothDevice.BOND_BONDING
                        || bondState == BluetoothDevice.BOND_BONDED);
            // Also allow connection when device is bonded/bonding and priority is ON/AUTO_CONNECT.
            boolean isEnabled = (priority == BluetoothProfile.PRIORITY_ON
                    || priority == BluetoothProfile.PRIORITY_AUTO_CONNECT) && (
                    bondState == BluetoothDevice.BOND_BONDED
                        || bondState == BluetoothDevice.BOND_BONDING);
            if (!serviceDiscoveryPending && !isEnabled) {
                // Otherwise, reject the connection if no service discovery is pending and priority is
                // neither PRIORITY_ON nor PRIORITY_AUTO_CONNECT
                Log.w(TAG, "okToConnect: return false, priority=" + priority + ", bondState="
                        + bondState);
                return false;
            }
        }
        List<BluetoothDevice> connectingConnectedDevices =
                getAllDevicesMatchingConnectionStates(CONNECTING_CONNECTED_STATES);
        if (!isConnectionAllowed(device, connectingConnectedDevices)) {
            Log.w(TAG, "Maximum number of connections " + mMaxHeadsetConnections
                    + " was reached, rejecting connection from " + device);
            return false;
        }
        return true;
    }
    /**
     * Checks if SCO should be connected at current system state
     *
     * @param device device for SCO to be connected
     * @return true if SCO is allowed to be connected
     */
    public boolean isScoAcceptable(BluetoothDevice device) {
        synchronized (mStateMachines) {
            if (mForceScoAudio) {
                return true;
            }
            if (device == null || !device.equals(mActiveDevice)) {
                Log.w(TAG, "isScoAcceptable: rejected SCO since " + device
                        + " is not the current active device " + mActiveDevice);
                return false;
            }
            if (!mAudioRouteAllowed) {
                Log.w(TAG, "isScoAcceptable: rejected SCO since audio route is not allowed");
                return false;
            }
            // if in-band ringtone is not enabled, return false
            if (isRinging() && !isInbandRingingEnabled()) {
                Log.w(TAG, "isScoAcceptable: rejected SCO since MT call in ringing," +
                            "in-band ringing not enabled");
                return false;
            }
            if (mVoiceRecognitionStarted || mVirtualCallStarted) {
                return true;
            }
            if (shouldCallAudioBeActive()) {
                return true;
            }
            Log.w(TAG, "isScoAcceptable: rejected SCO, inCall=" + mSystemInterface.isInCall()
                    + ", voiceRecognition=" + mVoiceRecognitionStarted + ", ringing="
                    + mSystemInterface.isRinging() + ", inbandRinging=" + isInbandRingingEnabled()
                    + ", isVirtualCallStarted=" + mVirtualCallStarted);
            return false;
        }
    }

    /**
     * Remove state machine in {@link #mStateMachines} for a {@link BluetoothDevice}
     *
     * @param device device whose state machine is to be removed.
     */
    void removeStateMachine(BluetoothDevice device) {
        synchronized (mStateMachines) {
            HeadsetStateMachine stateMachine = mStateMachines.get(device);
            if (stateMachine == null) {
                Log.w(TAG, "removeStateMachine(), " + device + " does not have a state machine");
                return;
            }
            Log.i(TAG, "removeStateMachine(), removing state machine for device: " + device);
            HeadsetObjectsFactory.getInstance().destroyStateMachine(stateMachine);
            mStateMachines.remove(device);
        }
    }

    private boolean isOnStateMachineThread() {
        final Looper myLooper = Looper.myLooper();
        return myLooper != null && (mStateMachinesThread != null) && (myLooper.getThread().getId()
                == mStateMachinesThread.getId());
    }

    @Override
    public void dump(StringBuilder sb) {
        synchronized (mStateMachines) {
            super.dump(sb);
            ProfileService.println(sb, "mMaxHeadsetConnections: " + mMaxHeadsetConnections);
            ProfileService.println(sb, "DefaultMaxHeadsetConnections: "
                    + mAdapterService.getMaxConnectedAudioDevices());
            ProfileService.println(sb, "mActiveDevice: " + mActiveDevice);
            ProfileService.println(sb, "isInbandRingingEnabled: " + isInbandRingingEnabled());
            ProfileService.println(sb,
                    "isInbandRingingSupported: " + BluetoothHeadset.isInbandRingingSupported(this));
            ProfileService.println(sb,
                    "mInbandRingingRuntimeDisable: " + mInbandRingingRuntimeDisable);
            ProfileService.println(sb, "mAudioRouteAllowed: " + mAudioRouteAllowed);
            ProfileService.println(sb, "mVoiceRecognitionStarted: " + mVoiceRecognitionStarted);
            ProfileService.println(sb, "mVirtualCallStarted: " + mVirtualCallStarted);
            ProfileService.println(sb, "mDialingOutTimeoutEvent: " + mDialingOutTimeoutEvent);
            ProfileService.println(sb, "mForceScoAudio: " + mForceScoAudio);
            ProfileService.println(sb, "mCreated: " + mCreated);
            ProfileService.println(sb, "mStarted: " + mStarted);
            ProfileService.println(sb,
                    "AudioManager.isBluetoothScoOn(): " + mSystemInterface.getAudioManager()
                            .isBluetoothScoOn());
            ProfileService.println(sb, "Telecom.isInCall(): " + mSystemInterface.isInCall());
            ProfileService.println(sb, "Telecom.isRinging(): " + mSystemInterface.isRinging());
            for (HeadsetStateMachine stateMachine : mStateMachines.values()) {
                ProfileService.println(sb,
                        "==== StateMachine for " + stateMachine.getDevice() + " ====");
                stateMachine.dump(sb);
            }
        }
    }

    private static void logD(String message) {
        if (DBG) {
            Log.d(TAG, message);
        }
    }
}
