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

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.IDeviceIdleController;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.support.annotation.VisibleForTesting;
import android.telephony.PhoneNumberUtils;
import android.telephony.PhoneStateListener;
import android.util.Log;
import android.os.SystemProperties;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.internal.util.State;
import com.android.internal.util.StateMachine;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Scanner;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Iterator;

/**
 * A Bluetooth Handset StateMachine
 *                        (Disconnected)
 *                           |      ^
 *                   CONNECT |      | DISCONNECTED
 *                           V      |
 *                  (Connecting)   (Disconnecting)
 *                           |      ^
 *                 CONNECTED |      | DISCONNECT
 *                           V      |
 *                          (Connected)
 *                           |      ^
 *             CONNECT_AUDIO |      | AUDIO_DISCONNECTED
 *                           V      |
 *             (AudioConnecting)   (AudioDiconnecting)
 *                           |      ^
 *           AUDIO_CONNECTED |      | DISCONNECT_AUDIO
 *                           V      |
 *                           (AudioOn)
 */
@VisibleForTesting
public class HeadsetStateMachine extends StateMachine {
    private static final String TAG = "HeadsetStateMachine";
    private static final boolean DBG = true;

    private static final String HEADSET_NAME = "bt_headset_name";
    private static final String HEADSET_NREC = "bt_headset_nrec";
    private static final String HEADSET_WBS = "bt_wbs";
    private static final String HEADSET_AUDIO_FEATURE_ON = "on";
    private static final String HEADSET_AUDIO_FEATURE_OFF = "off";

    static final int CONNECT = 1;
    static final int DISCONNECT = 2;
    static final int CONNECT_AUDIO = 3;
    static final int DISCONNECT_AUDIO = 4;
    static final int VOICE_RECOGNITION_START = 5;
    static final int VOICE_RECOGNITION_STOP = 6;

    // message.obj is an intent AudioManager.VOLUME_CHANGED_ACTION
    // EXTRA_VOLUME_STREAM_TYPE is STREAM_BLUETOOTH_SCO
    static final int INTENT_SCO_VOLUME_CHANGED = 7;
    static final int INTENT_CONNECTION_ACCESS_REPLY = 8;
    static final int CALL_STATE_CHANGED = 9;
    static final int DEVICE_STATE_CHANGED = 10;
    static final int SEND_CCLC_RESPONSE = 11;
    static final int SEND_VENDOR_SPECIFIC_RESULT_CODE = 12;
    static final int SEND_BSIR = 13;
    static final int DIALING_OUT_RESULT = 14;

    static final int UPDATE_A2DP_PLAY_STATE = 16;
    static final int UPDATE_A2DP_CONN_STATE = 17;
    static final int QUERY_PHONE_STATE_AT_SLC = 18;
    static final int SEND_INCOMING_CALL_IND = 19;
    static final int VOIP_CALL_STATE_CHANGED_ALERTING = 20;
    static final int VOIP_CALL_STATE_CHANGED_ACTIVE = 21;
    static final int CS_CALL_STATE_CHANGED_ALERTING = 22;
    static final int CS_CALL_STATE_CHANGED_ACTIVE = 23;
    static final int A2DP_STATE_CHANGED = 24;

    static final int STACK_EVENT = 101;
    private static final int START_VR_TIMEOUT = 103;
    private static final int CLCC_RSP_TIMEOUT = 104;
    private static final int PROCESS_CPBR = 105;

    private static final int CONNECT_TIMEOUT = 201;

    private static final int START_VR_TIMEOUT_MS = 5000;
    private static final int CLCC_RSP_TIMEOUT_MS = 5000;
    private static final int QUERY_PHONE_STATE_CHANGED_DELAYED = 100;
    // NOTE: the value is not "final" - it is modified in the unit tests
    @VisibleForTesting static int sConnectTimeoutMs = 30000;

    private static final HeadsetAgIndicatorEnableState DEFAULT_AG_INDICATOR_ENABLE_STATE =
            new HeadsetAgIndicatorEnableState(true, true, true, true);

    // delay call indicators and some remote devices are not able to handle
    // indicators back to back, especially in VOIP scenarios.
    /* Delay between call dialling, alerting updates for VOIP call */
    private static final int VOIP_CALL_ALERTING_DELAY_TIME_MSEC = 800;
    /* Delay between call alerting, active updates for VOIP call */
    private static final int VOIP_CALL_ACTIVE_DELAY_TIME_MSEC =
                               VOIP_CALL_ALERTING_DELAY_TIME_MSEC + 50;
    private static final int CS_CALL_ALERTING_DELAY_TIME_MSEC = 800;
    private static final int CS_CALL_ACTIVE_DELAY_TIME_MSEC = 10;
    private static final int INCOMING_CALL_IND_DELAY = 200;
    private static final int MAX_RETRY_CONNECT_COUNT = 2;
    // Blacklist remote device addresses to send incoimg call indicators with delay of 200ms
    private static final String [] BlacklistDeviceAddrToDelayCallInd =
                                                               {"00:15:83", /* Beiqi Carkit */
                                                                "2a:eb:00", /* BIAC Carkit */
                                                                "30:53:00", /* BIAC series */
                                                                "00:17:53", /* ADAYO Carkit */
                                                                "40:ef:4c", /* Road Rover Carkit */
                                                               };
    private static final String VOIP_CALL_NUMBER = "10000000";

    private final BluetoothDevice mDevice;

    // State machine states
    private final Disconnected mDisconnected = new Disconnected();
    private final Connecting mConnecting = new Connecting();
    private final Disconnecting mDisconnecting = new Disconnecting();
    private final Connected mConnected = new Connected();
    private final AudioOn mAudioOn = new AudioOn();
    private final AudioConnecting mAudioConnecting = new AudioConnecting();
    private final AudioDisconnecting mAudioDisconnecting = new AudioDisconnecting();
    private HeadsetStateBase mPrevState;

    // Run time dependencies
    private final HeadsetService mHeadsetService;
    private final AdapterService mAdapterService;
    private final HeadsetNativeInterface mNativeInterface;
    private final HeadsetSystemInterface mSystemInterface;

    // Runtime states
    private boolean mVoiceRecognitionStarted;
    private boolean mWaitingForVoiceRecognition;
    private int mSpeakerVolume;
    private int mMicVolume;
    private HeadsetAgIndicatorEnableState mAgIndicatorEnableState;
    private boolean mA2dpSuspend;
    private boolean mIsCsCall = true;
    private boolean mPendingScoForVR = false;
    private boolean mIsCallIndDelay = false;
    private boolean mIsBlacklistedDevice = false;
    private int retryConnectCount = 0;
    //ConcurrentLinkeQueue is used so that it is threadsafe
    private ConcurrentLinkedQueue<HeadsetCallState> mPendingCallStates =
                             new ConcurrentLinkedQueue<HeadsetCallState>();
    private ConcurrentLinkedQueue<HeadsetCallState> mDelayedCSCallStates =
                             new ConcurrentLinkedQueue<HeadsetCallState>();
    // The timestamp when the device entered connecting/connected state
    private long mConnectingTimestampMs = Long.MIN_VALUE;
    // Audio Parameters like NREC
    private final HashMap<String, String> mAudioParams = new HashMap<>();
    // AT Phone book keeps a group of states used by AT+CPBR commands
    private final AtPhonebook mPhonebook;
    // HSP specific
    private boolean mNeedDialingOutReply;

    // Hash for storing the A2DP connection states
    private HashMap<BluetoothDevice, Integer> mA2dpConnState =
                                          new HashMap<BluetoothDevice, Integer>();
    // Hash for storing the A2DP play states
    private HashMap<BluetoothDevice, Integer> mA2dpPlayState =
                                          new HashMap<BluetoothDevice, Integer>();

    // Keys are AT commands, and values are the company IDs.
    private static final Map<String, Integer> VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID;
    // Intent that get sent during voice recognition events.
    private static final Intent VOICE_COMMAND_INTENT;

    /* Retry outgoing connection after this time if the first attempt fails */
    private static final int RETRY_CONNECT_TIME_SEC = 2500;

    static {
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID = new HashMap<>();
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT,
                BluetoothAssignedNumbers.PLANTRONICS);
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put(
                BluetoothHeadset.VENDOR_RESULT_CODE_COMMAND_ANDROID,
                BluetoothAssignedNumbers.GOOGLE);
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XAPL,
                BluetoothAssignedNumbers.APPLE);
        VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.put(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE);
        VOICE_COMMAND_INTENT = new Intent(Intent.ACTION_VOICE_COMMAND);
        VOICE_COMMAND_INTENT.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private HeadsetStateMachine(BluetoothDevice device, Looper looper,
            HeadsetService headsetService, AdapterService adapterService,
            HeadsetNativeInterface nativeInterface, HeadsetSystemInterface systemInterface) {
        super(TAG, Objects.requireNonNull(looper, "looper cannot be null"));
        // Enable/Disable StateMachine debug logs
        setDbg(DBG);
        mDevice = Objects.requireNonNull(device, "device cannot be null");
        mHeadsetService = Objects.requireNonNull(headsetService, "headsetService cannot be null");
        mNativeInterface =
                Objects.requireNonNull(nativeInterface, "nativeInterface cannot be null");
        mSystemInterface =
                Objects.requireNonNull(systemInterface, "systemInterface cannot be null");
        mAdapterService = Objects.requireNonNull(adapterService, "AdapterService cannot be null");
        // Create phonebook helper
        mPhonebook = new AtPhonebook(mHeadsetService, mNativeInterface);
        // Initialize state machine
        addState(mDisconnected);
        addState(mConnecting);
        addState(mDisconnecting);
        addState(mConnected);
        addState(mAudioOn);
        addState(mAudioConnecting);
        addState(mAudioDisconnecting);
        setInitialState(mDisconnected);
        Log.i(TAG," Exiting HeadsetStateMachine constructor for device :" + device);
    }

    static HeadsetStateMachine make(BluetoothDevice device, Looper looper,
            HeadsetService headsetService, AdapterService adapterService,
            HeadsetNativeInterface nativeInterface, HeadsetSystemInterface systemInterface) {
        HeadsetStateMachine stateMachine =
                new HeadsetStateMachine(device, looper, headsetService, adapterService,
                        nativeInterface, systemInterface);
        Log.i(TAG," Starting StateMachine  device: " + device);
        stateMachine.start();
        Log.i(TAG, "Created state machine " + stateMachine + " for " + device);
        return stateMachine;
    }

    static void destroy(HeadsetStateMachine stateMachine) {
        Log.i(TAG, "destroy");
        if (stateMachine == null) {
            Log.w(TAG, "destroy(), stateMachine is null");
            return;
        }
        stateMachine.cleanup();
        stateMachine.quitNow();
    }

    public void cleanup() {
        Log.i(TAG," destroy, current state " + getCurrentState());
        if (getCurrentState() == mAudioOn) {
            mAudioOn.broadcastAudioState(mDevice, BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                                BluetoothHeadset.STATE_AUDIO_CONNECTED);
            mAudioOn.broadcastConnectionState(mDevice, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_CONNECTED);
        }
        if(getCurrentState() == mConnected){
            mConnected.broadcastConnectionState(mDevice, BluetoothProfile.STATE_DISCONNECTED,
                                     BluetoothProfile.STATE_CONNECTED);
        }
        if(getCurrentState() == mConnecting){
            mConnecting.broadcastConnectionState(mDevice, BluetoothProfile.STATE_DISCONNECTED,
                                     BluetoothProfile.STATE_CONNECTING);
        }
        if (mPhonebook != null) {
            mPhonebook.cleanup();
        }
        mAudioParams.clear();
    }

    public void dump(StringBuilder sb) {
        ProfileService.println(sb, "  mCurrentDevice: " + mDevice);
        ProfileService.println(sb, "  mCurrentState: " + getCurrentState());
        ProfileService.println(sb, "  mPrevState: " + mPrevState);
        ProfileService.println(sb, "  mConnectionState: " + getConnectionState());
        ProfileService.println(sb, "  mAudioState: " + getAudioState());
        ProfileService.println(sb, "  mVoiceRecognitionStarted: " + mVoiceRecognitionStarted);
        ProfileService.println(sb, "  mWaitingForVoiceRecognition: " + mWaitingForVoiceRecognition);
        ProfileService.println(sb, "  mNeedDialingOutReply: " + mNeedDialingOutReply);
        ProfileService.println(sb, "  mSpeakerVolume: " + mSpeakerVolume);
        ProfileService.println(sb, "  mMicVolume: " + mMicVolume);
        ProfileService.println(sb,
                "  mConnectingTimestampMs(uptimeMillis): " + mConnectingTimestampMs);
        ProfileService.println(sb, "  StateMachine: " + this);
        // Dump the state machine logs
        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        super.dump(new FileDescriptor(), printWriter, new String[]{});
        printWriter.flush();
        stringWriter.flush();
        ProfileService.println(sb, "  StateMachineLog:");
        Scanner scanner = new Scanner(stringWriter.toString());
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            ProfileService.println(sb, "    " + line);
        }
        scanner.close();
    }

    /**
     * Base class for states used in this state machine to share common infrastructures
     */
    private abstract class HeadsetStateBase extends State {
        @Override
        public void enter() {
            // Crash if mPrevState is null and state is not Disconnected
            if (!(this instanceof Disconnected) && mPrevState == null) {
                throw new IllegalStateException("mPrevState is null on enter()");
            }
            enforceValidConnectionStateTransition();
        }

        @Override
        public void exit() {
            mPrevState = this;
        }

        @Override
        public String toString() {
            return getName();
        }

        /**
         * Broadcast audio and connection state changes to the system. This should be called at the
         * end of enter() method after all the setup is done
         */
        void broadcastStateTransitions() {
            if (mPrevState == null) {
                return;
            }
            // TODO: Add STATE_AUDIO_DISCONNECTING constant to get rid of the 2nd part of this logic
            if (getAudioStateInt() != mPrevState.getAudioStateInt() || (
                    mPrevState instanceof AudioDisconnecting && this instanceof AudioOn)) {
                stateLogD("audio state changed: " + mDevice + ": " + mPrevState + " -> " + this);
                broadcastAudioState(mDevice, mPrevState.getAudioStateInt(), getAudioStateInt());
            }
            if (getConnectionStateInt() != mPrevState.getConnectionStateInt()) {
                stateLogD(
                        "connection state changed: " + mDevice + ": " + mPrevState + " -> " + this);
                broadcastConnectionState(mDevice, mPrevState.getConnectionStateInt(),
                        getConnectionStateInt());
            }
        }

        // Should not be called from enter() method
        void broadcastConnectionState(BluetoothDevice device, int fromState, int toState) {
            stateLogD("broadcastConnectionState " + device + ": " + fromState + "->" + toState);
            mHeadsetService.onConnectionStateChangedFromStateMachine(device, fromState, toState);
            Intent intent = new Intent(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, fromState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, toState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
            mHeadsetService.sendBroadcastAsUser(intent, UserHandle.ALL,
                    HeadsetService.BLUETOOTH_PERM);
        }

        // Should not be called from enter() method
        void broadcastAudioState(BluetoothDevice device, int fromState, int toState) {
            stateLogD("broadcastAudioState: " + device + ": " + fromState + "->" + toState);
            mHeadsetService.onAudioStateChangedFromStateMachine(device, fromState, toState);
            Intent intent = new Intent(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED);
            intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, fromState);
            intent.putExtra(BluetoothProfile.EXTRA_STATE, toState);
            intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
            mHeadsetService.sendBroadcastAsUser(intent, UserHandle.ALL,
                    HeadsetService.BLUETOOTH_PERM);
        }

        /**
         * Verify if the current state transition is legal. This is supposed to be called from
         * enter() method and crash if the state transition is out of the specification
         *
         * Note:
         * This method uses state objects to verify transition because these objects should be final
         * and any other instances are invalid
         */
        void enforceValidConnectionStateTransition() {
            boolean result = false;
            if (this == mDisconnected) {
                result = mPrevState == null || mPrevState == mConnecting
                        || mPrevState == mDisconnecting
                        // TODO: edges to be removed after native stack refactoring
                        // all transitions to disconnected state should go through a pending state
                        // also, states should not go directly from an active audio state to
                        // disconnected state
                        || mPrevState == mConnected || mPrevState == mAudioOn
                        || mPrevState == mAudioConnecting || mPrevState == mAudioDisconnecting;
            } else if (this == mConnecting) {
                result = mPrevState == mDisconnected;
            } else if (this == mDisconnecting) {
                result = mPrevState == mConnected
                        // TODO: edges to be removed after native stack refactoring
                        // all transitions to disconnecting state should go through connected state
                        || mPrevState == mAudioConnecting || mPrevState == mAudioOn
                        || mPrevState == mAudioDisconnecting;
            } else if (this == mConnected) {
                result = mPrevState == mConnecting || mPrevState == mAudioDisconnecting
                        || mPrevState == mDisconnecting || mPrevState == mAudioConnecting
                        // TODO: edges to be removed after native stack refactoring
                        // all transitions to connected state should go through a pending state
                        || mPrevState == mAudioOn || mPrevState == mDisconnected;
            } else if (this == mAudioConnecting) {
                result = mPrevState == mConnected;
            } else if (this == mAudioDisconnecting) {
                result = mPrevState == mAudioOn;
            } else if (this == mAudioOn) {
                result = mPrevState == mAudioConnecting || mPrevState == mAudioDisconnecting
                        // TODO: edges to be removed after native stack refactoring
                        // all transitions to audio connected state should go through a pending
                        // state
                        || mPrevState == mConnected;
            }
            if (!result) {
                throw new IllegalStateException(
                        "Invalid state transition from " + mPrevState + " to " + this
                                + " for device " + mDevice);
            }
        }

        void stateLogD(String msg) {
            log(getName() + ": currentDevice=" + mDevice + ", msg=" + msg);
        }

        void stateLogW(String msg) {
            logw(getName() + ": currentDevice=" + mDevice + ", msg=" + msg);
        }

        void stateLogE(String msg) {
            loge(getName() + ": currentDevice=" + mDevice + ", msg=" + msg);
        }

        void stateLogV(String msg) {
            logv(getName() + ": currentDevice=" + mDevice + ", msg=" + msg);
        }

        void stateLogI(String msg) {
            logi(getName() + ": currentDevice=" + mDevice + ", msg=" + msg);
        }

        void stateLogWtfStack(String msg) {
            Log.wtfStack(TAG, getName() + ": " + msg);
        }

        /**
         * Process connection event
         *
         * @param message the current message for the event
         * @param state connection state to transition to
         */
        public abstract void processConnectionEvent(Message message, int state);

        /**
         * Get a state value from {@link BluetoothProfile} that represents the connection state of
         * this headset state
         *
         * @return a value in {@link BluetoothProfile#STATE_DISCONNECTED},
         * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_CONNECTED}, or
         * {@link BluetoothProfile#STATE_DISCONNECTING}
         */
        abstract int getConnectionStateInt();

        /**
         * Get an audio state value from {@link BluetoothHeadset}
         * @return a value in {@link BluetoothHeadset#STATE_AUDIO_DISCONNECTED},
         * {@link BluetoothHeadset#STATE_AUDIO_CONNECTING}, or
         * {@link BluetoothHeadset#STATE_AUDIO_CONNECTED}
         */
        abstract int getAudioStateInt();

    }

    class Disconnected extends HeadsetStateBase {
        @Override
        int getConnectionStateInt() {
            return BluetoothProfile.STATE_DISCONNECTED;
        }

        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            mConnectingTimestampMs = Long.MIN_VALUE;
            mPhonebook.resetAtState();
            updateAgIndicatorEnableState(null);
            mVoiceRecognitionStarted = false;
            mWaitingForVoiceRecognition = false;
            mNeedDialingOutReply = false;
            mAudioParams.clear();
            broadcastStateTransitions();
            // Remove the state machine for unbonded devices
            if (mPrevState != null
                    && mAdapterService.getBondState(mDevice) == BluetoothDevice.BOND_NONE) {
                getHandler().post(() -> mHeadsetService.removeStateMachine(mDevice));
            }
            mIsBlacklistedDevice = false;
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    stateLogD("Connecting to " + device);
                    if (!mDevice.equals(device)) {
                        stateLogE(
                                "CONNECT failed, device=" + device + ", currentDevice=" + mDevice);
                        break;
                    }

                    stateLogD(" retryConnectCount = " + retryConnectCount);
                    if (retryConnectCount >= MAX_RETRY_CONNECT_COUNT) {
                        // max attempts reached, reset it to 0
                        retryConnectCount = 0;
                        break;
                    }
                    if (!mNativeInterface.connectHfp(device)) {
                        stateLogE("CONNECT failed for connectHfp(" + device + ")");
                        // No state transition is involved, fire broadcast immediately
                        broadcastConnectionState(device, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                        break;
                    }
                    retryConnectCount++;
                    transitionTo(mConnecting);
                    break;
                case DISCONNECT:
                    // ignore
                    break;
                case CALL_STATE_CHANGED:
                    stateLogD("Ignoring CALL_STATE_CHANGED event");
                    break;
                case DEVICE_STATE_CHANGED:
                    stateLogD("Ignoring DEVICE_STATE_CHANGED event");
                    break;
                case STACK_EVENT:
                    HeadsetStackEvent event = (HeadsetStackEvent) message.obj;
                    stateLogD("STACK_EVENT: " + event);
                    if (!mDevice.equals(event.device)) {
                        stateLogE("Event device does not match currentDevice[" + mDevice
                                + "], event: " + event);
                        break;
                    }
                    switch (event.type) {
                        case HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(message, event.valueInt);
                            break;
                        default:
                            stateLogE("Unexpected stack event: " + event);
                            break;
                    }
                    break;
                default:
                    stateLogE("Unexpected msg " + getMessageName(message.what) + ": " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void processConnectionEvent(Message message, int state) {
            stateLogD("processConnectionEvent, state=" + state);
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    stateLogW("ignore DISCONNECTED event");
                    break;
                // Both events result in Connecting state as SLC establishment is still required
                case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                case HeadsetHalConstants.CONNECTION_STATE_CONNECTING:
                    if (mHeadsetService.okToAcceptConnection(mDevice)) {
                        stateLogI("accept incoming connection");
                        transitionTo(mConnecting);
                    } else {
                        stateLogI("rejected incoming HF, priority=" + mHeadsetService.getPriority(
                                mDevice) + " bondState=" + mAdapterService.getBondState(mDevice));
                        // Reject the connection and stay in Disconnected state itself
                        if (!mNativeInterface.disconnectHfp(mDevice)) {
                            stateLogE("failed to disconnect");
                        }
                        // Indicate rejection to other components.
                        broadcastConnectionState(mDevice, BluetoothProfile.STATE_DISCONNECTED,
                                BluetoothProfile.STATE_DISCONNECTED);
                    }
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING:
                    stateLogW("Ignore DISCONNECTING event");
                    break;
                default:
                    stateLogE("Incorrect state: " + state);
                    break;
            }
        }
    }

    // Per HFP 1.7.1 spec page 23/144, Pending state needs to handle
    //      AT+BRSF, AT+CIND, AT+CMER, AT+BIND, +CHLD
    // commands during SLC establishment
    class Connecting extends HeadsetStateBase {
        @Override
        int getConnectionStateInt() {
            return BluetoothProfile.STATE_CONNECTING;
        }

        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            mConnectingTimestampMs = SystemClock.uptimeMillis();
            sendMessageDelayed(CONNECT_TIMEOUT, mDevice, sConnectTimeoutMs);
            mSystemInterface.queryPhoneState();
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT: {
                    // We timed out trying to connect, transition to Disconnected state
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogE("Unknown device timeout " + device);
                        break;
                    }
                    stateLogW("CONNECT_TIMEOUT");
                    transitionTo(mDisconnected);
                    break;
                }
                case DEVICE_STATE_CHANGED:
                    stateLogD("ignoring DEVICE_STATE_CHANGED event");
                    break;
                case STACK_EVENT:
                    HeadsetStackEvent event = (HeadsetStackEvent) message.obj;
                    stateLogD("STACK_EVENT: " + event);
                    if (!mDevice.equals(event.device)) {
                        stateLogE("Event device does not match currentDevice[" + mDevice
                                + "], event: " + event);
                        break;
                    }
                    switch (event.type) {
                        case HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(message, event.valueInt);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CHLD:
                            processAtChld(event.valueInt, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CIND:
                            processAtCind(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_WBS:
                            processWBSEvent(event.valueInt);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_BIND:
                            processAtBind(event.valueString, event.device);
                            break;
                        // Unexpected AT commands, we only handle them for comparability reasons
                        case HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED:
                            stateLogW("Unexpected VR event, device=" + event.device + ", state="
                                    + event.valueInt);
                            processVrEvent(event.valueInt, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_DIAL_CALL:
                            stateLogW("Unexpected dial event, device=" + event.device);
                            processDialCall(event.valueString);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST:
                            stateLogW("Unexpected subscriber number event for" + event.device
                                    + ", state=" + event.valueInt);
                            processSubscriberNumberRequest(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_COPS:
                            stateLogW("Unexpected COPS event for " + event.device);
                            processAtCops(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CLCC:
                            stateLogW("Connecting: Unexpected CLCC event for" + event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT:
                            stateLogW("Unexpected unknown AT event for" + event.device + ", cmd="
                                    + event.valueString);
                            processUnknownAt(event.valueString, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED:
                            stateLogW("Unexpected key-press event for " + event.device);
                            processKeyPressed(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_BIEV:
                            stateLogW("Unexpected BIEV event for " + event.device + ", indId="
                                    + event.valueInt + ", indVal=" + event.valueInt2);
                            processAtBiev(event.valueInt, event.valueInt2, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_VOLUME_CHANGED:
                            stateLogW("Unexpected volume event for " + event.device);
                            processVolumeEvent(event.valueInt, event.valueInt2);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_ANSWER_CALL:
                            stateLogW("Unexpected answer event for " + event.device);
                            mSystemInterface.answerCall(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_HANGUP_CALL:
                            stateLogW("Unexpected hangup event for " + event.device);
                            mSystemInterface.hangupCall(event.device);
                            break;
                        default:
                            stateLogE("Unexpected event: " + event);
                            break;
                    }
                    break;
                default:
                    stateLogE("Unexpected msg " + getMessageName(message.what) + ": " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void processConnectionEvent(Message message, int state) {
            stateLogD("processConnectionEvent, state=" + state);
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    stateLogW("Disconnected");
                    processWBSEvent(HeadsetHalConstants.BTHF_WBS_NO);
                    stateLogD(" retryConnectCount = " + retryConnectCount);
                    if(retryConnectCount == 1) {
                        Log.d(TAG," retry once more ");
                        sendMessageDelayed(CONNECT, mDevice, RETRY_CONNECT_TIME_SEC);
                    } else if (retryConnectCount >= MAX_RETRY_CONNECT_COUNT) {
                        // we already tried twice.
                        retryConnectCount = 0;
                    }
                    transitionTo(mDisconnected);
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                    stateLogD("RFCOMM connected");
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    stateLogD("SLC connected");
                    retryConnectCount = 0;
                    transitionTo(mConnected);
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_CONNECTING:
                    // Ignored
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING:
                    stateLogW("Disconnecting");
                    break;
                default:
                    stateLogE("Incorrect state " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            removeMessages(CONNECT_TIMEOUT);
            super.exit();
        }
    }

    class Disconnecting extends HeadsetStateBase {
        @Override
        int getConnectionStateInt() {
            return BluetoothProfile.STATE_DISCONNECTING;
        }

        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(CONNECT_TIMEOUT, mDevice, sConnectTimeoutMs);
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogE("Unknown device timeout " + device);
                        break;
                    }
                    stateLogE("timeout");
                    transitionTo(mDisconnected);
                    break;
                }
                case STACK_EVENT:
                    HeadsetStackEvent event = (HeadsetStackEvent) message.obj;
                    stateLogD("STACK_EVENT: " + event);
                    if (!mDevice.equals(event.device)) {
                        stateLogE("Event device does not match currentDevice[" + mDevice
                                + "], event: " + event);
                        break;
                    }
                    switch (event.type) {
                        case HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(message, event.valueInt);
                            break;
                        default:
                            stateLogE("Unexpected event: " + event);
                            break;
                    }
                    break;
                default:
                    stateLogE("Unexpected msg " + getMessageName(message.what) + ": " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        // in Disconnecting state
        @Override
        public void processConnectionEvent(Message message, int state) {
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    stateLogD("processConnectionEvent: Disconnected");
                    transitionTo(mDisconnected);
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    stateLogD("processConnectionEvent: Connected");
                    transitionTo(mConnected);
                    break;
                default:
                    stateLogE("processConnectionEvent: Bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            removeMessages(CONNECT_TIMEOUT);
            super.exit();
        }
    }

    /**
     * Base class for Connected, AudioConnecting, AudioOn, AudioDisconnecting states
     */
    private abstract class ConnectedBase extends HeadsetStateBase {
        @Override
        int getConnectionStateInt() {
            return BluetoothProfile.STATE_CONNECTED;
        }

        /**
         * Handle common messages in connected states. However, state specific messages must be
         * handled individually.
         *
         * @param message Incoming message to handle
         * @return True if handled successfully, False otherwise
         */
        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                case DISCONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT_AUDIO:
                case CONNECT_TIMEOUT:
                    throw new IllegalStateException(
                            "Illegal message in generic handler: " + message);
                case VOICE_RECOGNITION_START: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("VOICE_RECOGNITION_START failed " + device
                                + " is not currentDevice");
                        break;
                    }
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STARTED);
                    break;
                }
                case VOICE_RECOGNITION_STOP: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("VOICE_RECOGNITION_STOP failed " + device
                                + " is not currentDevice");
                        break;
                    }
                    processLocalVrEvent(HeadsetHalConstants.VR_STATE_STOPPED);
                    break;
                }
/*
                case VOIP_CALL_STATE_CHANGED_ALERTING:
                    // intentional fall through
                case VOIP_CALL_STATE_CHANGED_ACTIVE:
                    processCallState((HeadsetCallState) message.obj, message.arg1 == 1);
                    break;
                case CALL_STATE_CHANGED: {
                    boolean isPts = SystemProperties.getBoolean("bt.pts.certification", false);

                    // for PTS, VOIP calls, send the indicators as is
                    if(isPts || isVirtualCallInProgress())
                        processCallState((HeadsetCallState) message.obj,
                                              ((message.arg1==1)?true:false));
                    else
                        processCallStatesDelayed((HeadsetCallState) message.obj, false);
                    break;
                }
                case CS_CALL_STATE_CHANGED_ALERTING: {
                    // get the top of the Q
                    HeadsetCallState tempCallState = mDelayedCSCallStates.peek();
                    // top of the queue is call alerting
                    if(tempCallState != null &&
                        tempCallState.mCallState == HeadsetHalConstants.CALL_STATE_ALERTING)
                    {
                        stateLogD("alerting message timer expired, send alerting update");
                        //dequeue the alerting call state;
                        mDelayedCSCallStates.poll();
                        processCallState(tempCallState, false);
                    }

                    // top of the queue == call active
                    tempCallState = mDelayedCSCallStates.peek();
                    if (tempCallState != null &&
                         tempCallState.mCallState == HeadsetHalConstants.CALL_STATE_IDLE)
                    {
                        stateLogD("alerting message timer expired, send delayed active mesg");
                        //send delayed message for call active;
                        Message msg = obtainMessage(CS_CALL_STATE_CHANGED_ACTIVE);
                        msg.arg1 = 0;
                        sendMessageDelayed(msg, CS_CALL_ACTIVE_DELAY_TIME_MSEC);
                    }
                    break;
                }
                case CS_CALL_STATE_CHANGED_ACTIVE: {
                    // get the top of the Q
                    // top of the queue == call active
                    HeadsetCallState tempCallState = mDelayedCSCallStates.peek();
                    if (tempCallState != null &&
                         tempCallState.mCallState == HeadsetHalConstants.CALL_STATE_IDLE)
                    {
                        stateLogD("active message timer expired, send active update");
                        //dequeue the active call state;
                        mDelayedCSCallStates.poll();
                        processCallState(tempCallState, false);
                    }
                    break;
*/
                case CALL_STATE_CHANGED: {
                    HeadsetCallState callState = (HeadsetCallState) message.obj;
                    if (!mNativeInterface.phoneStateChange(mDevice, callState)) {
                        stateLogW("processCallState: failed to update call state " + callState);
                        break;
                    }
                    break;
                }
                case DEVICE_STATE_CHANGED:
                    mNativeInterface.notifyDeviceStatus(mDevice, (HeadsetDeviceState) message.obj);
                    break;
                case SEND_CCLC_RESPONSE:
                    processSendClccResponse((HeadsetClccResponse) message.obj);
                    break;
                case CLCC_RSP_TIMEOUT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("CLCC_RSP_TIMEOUT failed " + device + " is not currentDevice");
                        break;
                    }
                    mNativeInterface.clccResponse(device, 0, 0, 0, 0, false, "", 0);
                }
                break;
                case SEND_VENDOR_SPECIFIC_RESULT_CODE:
                    processSendVendorSpecificResultCode(
                            (HeadsetVendorSpecificResultCode) message.obj);
                    break;
                case SEND_BSIR:
                    mNativeInterface.sendBsir(mDevice, message.arg1 == 1);
                    break;
                case DIALING_OUT_RESULT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("DIALING_OUT_TIMEOUT failed " + device + " is not currentDevice");
                        break;
                    }
                    if (mNeedDialingOutReply) {
                        mNeedDialingOutReply = false;
                        mNativeInterface.atResponseCode(mDevice,
                                message.arg1 == 1 ? HeadsetHalConstants.AT_RESPONSE_OK
                                        : HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                }
                break;
                case START_VR_TIMEOUT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("START_VR_TIMEOUT failed " + device + " is not currentDevice");
                        break;
                    }
                    if (mWaitingForVoiceRecognition) {
                        mWaitingForVoiceRecognition = false;
                        stateLogE("Timeout waiting for voice recognition to start");
                        mNativeInterface.atResponseCode(device,
                                HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                    }
                }
                break;
                case INTENT_CONNECTION_ACCESS_REPLY:
                    handleAccessPermissionResult((Intent) message.obj);
                    break;
                case PROCESS_CPBR:
                    Intent intent = (Intent) message.obj;
                    processCpbr(intent);
                    break;
                case SEND_INCOMING_CALL_IND:
                    HeadsetCallState callState =
                        new HeadsetCallState(0, 0, HeadsetHalConstants.CALL_STATE_INCOMING,
                                 mSystemInterface.getHeadsetPhoneState().getNumber(),
                                 mSystemInterface.getHeadsetPhoneState().getType());
                    mNativeInterface.phoneStateChange(mDevice, callState);
                    break;
                case QUERY_PHONE_STATE_AT_SLC:
                    stateLogD("Update call states after SLC is up");
                    mSystemInterface.queryPhoneState();
                    break;
                case STACK_EVENT:
                    HeadsetStackEvent event = (HeadsetStackEvent) message.obj;
                    stateLogD("STACK_EVENT: " + event);
                    if (!mDevice.equals(event.device)) {
                        stateLogE("Event device does not match currentDevice[" + mDevice
                                + "], event: " + event);
                        break;
                    }
                    switch (event.type) {
                        case HeadsetStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED:
                            processConnectionEvent(message, event.valueInt);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AUDIO_STATE_CHANGED:
                            processAudioEvent(event.valueInt);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_VR_STATE_CHANGED:
                            processVrEvent(event.valueInt, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_ANSWER_CALL:
                            mSystemInterface.answerCall(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_HANGUP_CALL:
                            mSystemInterface.hangupCall(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_VOLUME_CHANGED:
                            processVolumeEvent(event.valueInt, event.valueInt2);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_DIAL_CALL:
                            processDialCall(event.valueString);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_SEND_DTMF:
                            mSystemInterface.sendDtmf(event.valueInt, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_NOICE_REDUCTION:
                            processNoiseReductionEvent(event.valueInt == 1);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_WBS:
                            processWBSEvent(event.valueInt);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CHLD:
                            processAtChld(event.valueInt, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_SUBSCRIBER_NUMBER_REQUEST:
                            processSubscriberNumberRequest(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CIND:
                            processAtCind(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_COPS:
                            processAtCops(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_AT_CLCC:
                            processAtClcc(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_UNKNOWN_AT:
                            processUnknownAt(event.valueString, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_KEY_PRESSED:
                            processKeyPressed(event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_BIND:
                            processAtBind(event.valueString, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_BIEV:
                            processAtBiev(event.valueInt, event.valueInt2, event.device);
                            break;
                        case HeadsetStackEvent.EVENT_TYPE_BIA:
                            updateAgIndicatorEnableState(
                                    (HeadsetAgIndicatorEnableState) event.valueObject);
                            break;
                        default:
                            stateLogE("Unknown stack event: " + event);
                            break;
                    }
                    break;
                default:
                    stateLogE("Unexpected msg " + getMessageName(message.what) + ": " + message);
                    return NOT_HANDLED;
            }
            return HANDLED;
        }

        @Override
        public void processConnectionEvent(Message message, int state) {
            stateLogD("processConnectionEvent, state=" + state);
            switch (state) {
                case HeadsetHalConstants.CONNECTION_STATE_CONNECTED:
                    stateLogE("processConnectionEvent: RFCOMM connected again, shouldn't happen");
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_SLC_CONNECTED:
                    stateLogE("processConnectionEvent: SLC connected again, shouldn't happen");
                    retryConnectCount = 0;
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTING:
                    stateLogI("processConnectionEvent: Disconnecting");
                    transitionTo(mDisconnecting);
                    break;
                case HeadsetHalConstants.CONNECTION_STATE_DISCONNECTED:
                    stateLogI("processConnectionEvent: Disconnected");
                    processWBSEvent(HeadsetHalConstants.BTHF_WBS_NO);
                    transitionTo(mDisconnected);
                    break;
                default:
                    stateLogE("processConnectionEvent: bad state: " + state);
                    break;
            }
        }

        /**
         * Each state should handle audio events differently
         *
         * @param state audio state
         */
        public abstract void processAudioEvent(int state);
    }

    class Connected extends ConnectedBase {
        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            if (mConnectingTimestampMs == Long.MIN_VALUE) {
                mConnectingTimestampMs = SystemClock.uptimeMillis();
            }
            updateAgIndicatorEnableState(DEFAULT_AG_INDICATOR_ENABLE_STATE);
            if (mPrevState == mConnecting) {
                // Reset NREC on connect event. Headset will override later
                processNoiseReductionEvent(true);
                // Query phone state for initial setup
                sendMessageDelayed(QUERY_PHONE_STATE_AT_SLC, QUERY_PHONE_STATE_CHANGED_DELAYED);
                // Checking for the Blacklisted device Addresses
                mIsBlacklistedDevice = isConnectedDeviceBlacklistedforIncomingCall();
/*
                if (mSystemInterface.isInCall() || mSystemInterface.isRinging()) {
                   stateLogW("Connected: enter: suspending A2DP for Call since SLC connected");
                   // suspend A2DP since call is there
                   mHeadsetService.getHfpA2DPSyncInterface().suspendA2DP(
                           HeadsetA2dpSync.A2DP_SUSPENDED_BY_CS_CALL, mDevice);
                }
*/
                // Remove pending connection attempts that were deferred during the pending
                // state. This is to prevent auto connect attempts from disconnecting
                // devices that previously successfully connected.
                removeDeferredMessages(CONNECT);
            }
/*
            if ((mPrevState == mAudioOn) || (mPrevState == mAudioDisconnecting)||
                 (mPrevState == mAudioConnecting)) {
                if (!(mSystemInterface.isInCall() || mSystemInterface.isRinging()) &&
                        mSystemInterface.getHeadsetPhoneState().getNumber().isEmpty() ) {
                        // SCO disconnected, resume A2DP if there is no call
                        stateLogD("SCO disconnected, set A2DPsuspended to false");
                        mHeadsetService.getHfpA2DPSyncInterface().releaseA2DP(mDevice);
                }
            }
*/
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    stateLogW("CONNECT, ignored, device=" + device + ", currentDevice" + mDevice);
                    break;
                }
                case DISCONNECT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    stateLogD("DISCONNECT from device=" + device);
                    if (!mDevice.equals(device)) {
                        stateLogW("DISCONNECT, device " + device + " not connected");
                        break;
                    }
                    if (!mNativeInterface.disconnectHfp(device)) {
                        // broadcast immediately as no state transition is involved
                        stateLogE("DISCONNECT from " + device + " failed");
                        broadcastConnectionState(device, BluetoothProfile.STATE_CONNECTED,
                                BluetoothProfile.STATE_CONNECTED);
                        break;
                    }
                    transitionTo(mDisconnecting);
                }
                break;
                case CONNECT_AUDIO:
                    stateLogD("CONNECT_AUDIO, device=" + mDevice);
/*
                    int a2dpState = mHeadsetService.getHfpA2DPSyncInterface().isA2dpPlaying();
                    if (!isScoAcceptable()|| (a2dpState == HeadsetA2dpSync.A2DP_PLAYING)) {
                        stateLogW("No Active/Held call, no call setup,and no in-band ringing,"
                                  + " or A2Dp is playing, not allowing SCO, device=" + mDevice);
                        break;
                    }
*/
                    if (!mNativeInterface.connectAudio(mDevice)) {
                        stateLogE("Failed to connect SCO audio for " + mDevice);
                        // No state change involved, fire broadcast immediately
                        broadcastAudioState(mDevice, BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                        break;
                    }
                    transitionTo(mAudioConnecting);
                    break;
                case DISCONNECT_AUDIO:
                    stateLogD("ignore DISCONNECT_AUDIO, device=" + mDevice);
                    // ignore
                    break;
                default:
                    return super.processMessage(message);
            }
            return HANDLED;
        }

        @Override
        public void processAudioEvent(int state) {
            stateLogD("processAudioEvent, state=" + state);
            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_CONNECTED:
                    if (!mHeadsetService.isScoAcceptable(mDevice)) {
                        stateLogW("processAudioEvent: reject incoming audio connection");
                        if (!mNativeInterface.disconnectAudio(mDevice)) {
                            stateLogE("processAudioEvent: failed to disconnect audio");
                        }
                        // Indicate rejection to other components.
                        broadcastAudioState(mDevice, BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                        break;
                    }
                    stateLogI("processAudioEvent: audio connected");
                    transitionTo(mAudioOn);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTING:
                    if (!mHeadsetService.isScoAcceptable(mDevice)) {
                        stateLogW("processAudioEvent: reject incoming pending audio connection");
                        if (!mNativeInterface.disconnectAudio(mDevice)) {
                            stateLogE("processAudioEvent: failed to disconnect pending audio");
                        }
                        // Indicate rejection to other components.
                        broadcastAudioState(mDevice, BluetoothHeadset.STATE_AUDIO_DISCONNECTED,
                                BluetoothHeadset.STATE_AUDIO_DISCONNECTED);
                        break;
                    }
                    stateLogI("processAudioEvent: audio connecting");
                    transitionTo(mAudioConnecting);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTED:
/*
                    if (!(mSystemInterface.isInCall() || mSystemInterface.isRinging()) &&
                        mSystemInterface.getHeadsetPhoneState().getNumber().isEmpty() ) {
                        // SCO disconnected, resume A2DP if there is no call
                        stateLogD("SCO disconnected, set A2DPsuspended to false");
                        mHeadsetService.getHfpA2DPSyncInterface().releaseA2DP(mDevice);
                    }
*/
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTING:
                    // ignore
                    break;
                default:
                    stateLogE("processAudioEvent: bad state: " + state);
                    break;
            }
        }
    }

    class AudioConnecting extends ConnectedBase {
        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_CONNECTING;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(CONNECT_TIMEOUT, mDevice, sConnectTimeoutMs);
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                case DISCONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT_AUDIO:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("CONNECT_TIMEOUT for unknown device " + device);
                        break;
                    }
                    stateLogW("CONNECT_TIMEOUT");
                    transitionTo(mConnected);
                    break;
                }
                default:
                    return super.processMessage(message);
            }
            return HANDLED;
        }

        @Override
        public void processAudioEvent(int state) {
            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTED:
                    stateLogW("processAudioEvent: audio connection failed");
                    transitionTo(mConnected);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTING:
                    // ignore, already in audio connecting state
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTING:
                    // ignore, there is no BluetoothHeadset.STATE_AUDIO_DISCONNECTING
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTED:
                    stateLogI("processAudioEvent: audio connected");
                    transitionTo(mAudioOn);
                    break;
                default:
                    stateLogE("processAudioEvent: bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            removeMessages(CONNECT_TIMEOUT);
            super.exit();
        }
    }

    class AudioOn extends ConnectedBase {
        @Override
        int getAudioStateInt() {
            return BluetoothHeadset.STATE_AUDIO_CONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            removeDeferredMessages(CONNECT_AUDIO);
            // Set active device to current active SCO device when the current active device
            // is different from mCurrentDevice. This is to accommodate active device state
            // mis-match between native and Java.
            if (!mDevice.equals(mHeadsetService.getActiveDevice())) {
                mHeadsetService.setActiveDevice(mDevice);
            }
            setAudioParameters();
            mSystemInterface.getAudioManager().setParameters("BT_SCO=on");
            mSystemInterface.getAudioManager().setBluetoothScoOn(true);
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    stateLogW("CONNECT, ignored, device=" + device + ", currentDevice" + mDevice);
                    break;
                }
                case DISCONNECT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    stateLogD("DISCONNECT, device=" + device);
                    if (!mDevice.equals(device)) {
                        stateLogW("DISCONNECT, device " + device + " not connected");
                        break;
                    }
                    // Disconnect BT SCO first
                    if (!mNativeInterface.disconnectAudio(mDevice)) {
                        stateLogW("DISCONNECT failed, device=" + mDevice);
                        // if disconnect BT SCO failed, transition to mConnected state to force
                        // disconnect device
                    }
                    deferMessage(obtainMessage(DISCONNECT, mDevice));
                    transitionTo(mAudioDisconnecting);
                    break;
                }
                case CONNECT_AUDIO: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("CONNECT_AUDIO device is not connected " + device);
                        break;
                    }
                    stateLogW("CONNECT_AUDIO device auido is already connected " + device);
                    break;
                }
                case DISCONNECT_AUDIO: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("DISCONNECT_AUDIO, failed, device=" + device + ", currentDevice="
                                + mDevice);
                        break;
                    }
                    if (mNativeInterface.disconnectAudio(mDevice)) {
                        stateLogD("DISCONNECT_AUDIO, device=" + mDevice);
                        transitionTo(mAudioDisconnecting);
                    } else {
                        stateLogW("DISCONNECT_AUDIO failed, device=" + mDevice);
                        broadcastAudioState(mDevice, BluetoothHeadset.STATE_AUDIO_CONNECTED,
                                BluetoothHeadset.STATE_AUDIO_CONNECTED);
                    }
                    break;
                }
                case INTENT_SCO_VOLUME_CHANGED:
                    processIntentScoVolume((Intent) message.obj, mDevice);
                    break;
                case STACK_EVENT:
                    HeadsetStackEvent event = (HeadsetStackEvent) message.obj;
                    stateLogD("STACK_EVENT: " + event);
                    if (!mDevice.equals(event.device)) {
                        stateLogE("Event device does not match currentDevice[" + mDevice
                                + "], event: " + event);
                        break;
                    }
                    switch (event.type) {
                        case HeadsetStackEvent.EVENT_TYPE_WBS:
                            stateLogE("Cannot change WBS state when audio is connected: " + event);
                            break;
                        default:
                            super.processMessage(message);
                            break;
                    }
                    break;
                default:
                    return super.processMessage(message);
            }
            return HANDLED;
        }

        @Override
        public void processAudioEvent(int state) {
            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTED:
                    stateLogI("processAudioEvent: audio disconnected by remote");
                    if(mSystemInterface.getAudioManager().isSpeakerphoneOn()) {
                        mSystemInterface.getAudioManager().setParameters("BT_SCO=off");
                        mSystemInterface.getAudioManager().setBluetoothScoOn(false);
                        mSystemInterface.getAudioManager().setSpeakerphoneOn(true);
                    } else {
                        mSystemInterface.getAudioManager().setParameters("BT_SCO=off");
                        mSystemInterface.getAudioManager().setBluetoothScoOn(false);
                    }
                    transitionTo(mConnected);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTING:
                    stateLogI("processAudioEvent: audio being disconnected by remote");
                    transitionTo(mAudioDisconnecting);
                    break;
                default:
                    stateLogE("processAudioEvent: bad state: " + state);
                    break;
            }
        }

        private void processIntentScoVolume(Intent intent, BluetoothDevice device) {
            int volumeValue = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, 0);      
            boolean ptsEnabled = SystemProperties.getBoolean("bt.pts.certification", false);
            stateLogD(" mSpeakerVolume = " + mSpeakerVolume + " volValue = " + volumeValue
                      +" PTS_ENABLED = " + ptsEnabled);
            if (mSpeakerVolume != volumeValue) {
                mSpeakerVolume = volumeValue;
                if(!ptsEnabled) {
                    mNativeInterface.setVolume(device, HeadsetHalConstants.VOLUME_TYPE_SPK,
                            mSpeakerVolume);
                } else {
                    mNativeInterface.setVolume(device, HeadsetHalConstants.VOLUME_TYPE_SPK,
                            0);
                }
            }
        }
    }

    class AudioDisconnecting extends ConnectedBase {
        @Override
        int getAudioStateInt() {
            // TODO: need BluetoothHeadset.STATE_AUDIO_DISCONNECTING
            return BluetoothHeadset.STATE_AUDIO_CONNECTED;
        }

        @Override
        public void enter() {
            super.enter();
            sendMessageDelayed(CONNECT_TIMEOUT, mDevice, sConnectTimeoutMs);
            broadcastStateTransitions();
        }

        @Override
        public boolean processMessage(Message message) {
            switch (message.what) {
                case CONNECT:
                case DISCONNECT:
                case CONNECT_AUDIO:
                case DISCONNECT_AUDIO:
                    deferMessage(message);
                    break;
                case CONNECT_TIMEOUT: {
                    BluetoothDevice device = (BluetoothDevice) message.obj;
                    if (!mDevice.equals(device)) {
                        stateLogW("CONNECT_TIMEOUT for unknown device " + device);
                        break;
                    }
                    stateLogW("CONNECT_TIMEOUT");
                    transitionTo(mConnected);
                    break;
                }
                default:
                    return super.processMessage(message);
            }
            return HANDLED;
        }

        @Override
        public void processAudioEvent(int state) {
            switch (state) {
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTED:
                    stateLogI("processAudioEvent: audio disconnected");
                    if(mSystemInterface.getAudioManager().isSpeakerphoneOn()) {
                        mSystemInterface.getAudioManager().setParameters("BT_SCO=off");
                        mSystemInterface.getAudioManager().setBluetoothScoOn(false);
                        mSystemInterface.getAudioManager().setSpeakerphoneOn(true);
                    } else {
                        mSystemInterface.getAudioManager().setParameters("BT_SCO=off");
                        mSystemInterface.getAudioManager().setBluetoothScoOn(false);
                    }
                    transitionTo(mConnected);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_DISCONNECTING:
                    // ignore
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTED:
                    stateLogW("processAudioEvent: audio disconnection failed");
                    transitionTo(mAudioOn);
                    break;
                case HeadsetHalConstants.AUDIO_STATE_CONNECTING:
                    // ignore, see if it goes into connected state, otherwise, timeout
                    break;
                default:
                    stateLogE("processAudioEvent: bad state: " + state);
                    break;
            }
        }

        @Override
        public void exit() {
            removeMessages(CONNECT_TIMEOUT);
            super.exit();
        }
    }

    /**
     * Get the underlying device tracked by this state machine
     *
     * @return device in focus
     */
    @VisibleForTesting
    public synchronized BluetoothDevice getDevice() {
        return mDevice;
    }

    /**
     * Get the current connection state of this state machine
     *
     * @return current connection state, one of {@link BluetoothProfile#STATE_DISCONNECTED},
     * {@link BluetoothProfile#STATE_CONNECTING}, {@link BluetoothProfile#STATE_CONNECTED}, or
     * {@link BluetoothProfile#STATE_DISCONNECTING}
     */
    @VisibleForTesting
    public synchronized int getConnectionState() {
        HeadsetStateBase state = (HeadsetStateBase) getCurrentState();
        if (state == null) {
            return BluetoothHeadset.STATE_DISCONNECTED;
        }
        return state.getConnectionStateInt();
    }

    /**
     * Get the current audio state of this state machine
     *
     * @return current audio state, one of {@link BluetoothHeadset#STATE_AUDIO_DISCONNECTED},
     * {@link BluetoothHeadset#STATE_AUDIO_CONNECTING}, or
     * {@link BluetoothHeadset#STATE_AUDIO_CONNECTED}
     */
    public synchronized int getAudioState() {
        HeadsetStateBase state = (HeadsetStateBase) getCurrentState();
        if (state == null) {
            return BluetoothHeadset.STATE_AUDIO_DISCONNECTED;
        }
        return state.getAudioStateInt();
    }

    private void processVrEvent(int state, BluetoothDevice device) {
        Log.d(TAG, "processVrEvent: state=" + state + " mVoiceRecognitionStarted: "
                + mVoiceRecognitionStarted + " mWaitingforVoiceRecognition: "
                + mWaitingForVoiceRecognition + " isInCall: " + mSystemInterface.isInCall());
        if (state == HeadsetHalConstants.VR_STATE_STARTED) {
            if (!mHeadsetService.isVirtualCallStarted() && !mSystemInterface.isInCall()) {
                IDeviceIdleController dic = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(Context.DEVICE_IDLE_CONTROLLER));
                if (dic != null) {
                    try {
                        dic.exitIdle("voice-command");
                    } catch (RemoteException e) {
                    }
                }
                try {
                    mHeadsetService.startActivity(VOICE_COMMAND_INTENT);
                } catch (ActivityNotFoundException e) {
                    mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR,
                            0);
                    return;
                }
                expectVoiceRecognition(device);
            } else {
                // send error response if call is ongoing
                mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        } else if (state == HeadsetHalConstants.VR_STATE_STOPPED) {
            if (mVoiceRecognitionStarted || mWaitingForVoiceRecognition) {
                mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
                mVoiceRecognitionStarted = false;
                mWaitingForVoiceRecognition = false;
                if (!mSystemInterface.isInCall() && (getAudioState()
                        != BluetoothHeadset.STATE_AUDIO_DISCONNECTED)) {
                    mNativeInterface.disconnectAudio(mDevice);
                }
            } else {
                mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        } else {
            Log.e(TAG, "Bad Voice Recognition state: " + state);
        }
    }

    private void processLocalVrEvent(int state) {
        if (state == HeadsetHalConstants.VR_STATE_STARTED) {
            boolean needAudio = true;
            if (mVoiceRecognitionStarted || mSystemInterface.isInCall()) {
                Log.e(TAG, "Voice recognition started when call is active. isInCall:"
                        + mSystemInterface.isInCall() + " mVoiceRecognitionStarted: "
                        + mVoiceRecognitionStarted);
                return;
            }
            mVoiceRecognitionStarted = true;

            if (mWaitingForVoiceRecognition) {
                if (!hasMessages(START_VR_TIMEOUT)) {
                    return;
                }
                Log.d(TAG, "Voice recognition started successfully");
                mWaitingForVoiceRecognition = false;
                mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_OK, 0);
                removeMessages(START_VR_TIMEOUT);
            } else {
                Log.d(TAG, "Voice recognition started locally");
                needAudio = mNativeInterface.startVoiceRecognition(mDevice);
            }

            if (needAudio && getAudioState() == BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                Log.d(TAG, "Initiating audio connection for Voice Recognition");
                // At this stage, we need to be sure that AVDTP is not streaming. This is needed
                // to be compliant with the AV+HFP Whitepaper as we cannot have A2DP in
                // streaming state while a SCO connection is established.
                // This is needed for VoiceDial scenario alone and not for
                // incoming call/outgoing call scenarios as the phone enters MODE_RINGTONE
                // or MODE_IN_CALL which shall automatically suspend the AVDTP stream if needed.
                // Whereas for VoiceDial we want to activate the SCO connection but we are still
                // in MODE_NORMAL and hence the need to explicitly suspend the A2DP stream
/*
                // if A2DP is playing, wait for A2DP to suspend, else continue
                if (mHeadsetService.getHfpA2DPSyncInterface().suspendA2DP(
                      HeadsetA2dpSync.A2DP_SUSPENDED_BY_VR, mDevice) == true) {
                   Log.d(TAG, "processLocalVREvent: A2DP is playing,"+
                             " return and establish SCO after A2DP supended");
                   if (mSystemInterface.getVoiceRecognitionWakeLock().isHeld()) {
                      mSystemInterface.getVoiceRecognitionWakeLock().release();
                   }
                   return;
                }
*/
                mSystemInterface.getAudioManager().setParameters("A2dpSuspended=true");
                mNativeInterface.connectAudio(mDevice);
            }

            if (mSystemInterface.getVoiceRecognitionWakeLock().isHeld()) {
                mSystemInterface.getVoiceRecognitionWakeLock().release();
            }
        } else {
            Log.d(TAG, "Voice Recognition stopped. mVoiceRecognitionStarted: "
                    + mVoiceRecognitionStarted + " mWaitingForVoiceRecognition: "
                    + mWaitingForVoiceRecognition);
            if (mVoiceRecognitionStarted || mWaitingForVoiceRecognition) {
                mVoiceRecognitionStarted = false;
                mWaitingForVoiceRecognition = false;

                if (mNativeInterface.stopVoiceRecognition(mDevice) &&
                        !mSystemInterface.isInCall() &&
                        getAudioState() != BluetoothHeadset.STATE_AUDIO_DISCONNECTED) {
                    mNativeInterface.disconnectAudio(mDevice);
                }
            }
        }
    }

    private synchronized void expectVoiceRecognition(BluetoothDevice device) {
        mWaitingForVoiceRecognition = true;
        mHeadsetService.setActiveDevice(device);
        sendMessageDelayed(START_VR_TIMEOUT, device, START_VR_TIMEOUT_MS);
        if (!mSystemInterface.getVoiceRecognitionWakeLock().isHeld()) {
            mSystemInterface.getVoiceRecognitionWakeLock().acquire(START_VR_TIMEOUT_MS);
        }
    }

    public long getConnectingTimestampMs() {
        return mConnectingTimestampMs;
    }

    /*
     * Put the AT command, company ID, arguments, and device in an Intent and broadcast it.
     */
    private void broadcastVendorSpecificEventIntent(String command, int companyId, int commandType,
            Object[] arguments, BluetoothDevice device) {
        log("broadcastVendorSpecificEventIntent(" + command + ")");
        Intent intent = new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);

        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "."
                + Integer.toString(companyId));

        mHeadsetService.sendBroadcastAsUser(intent, UserHandle.ALL, HeadsetService.BLUETOOTH_PERM);
    }

    private void setAudioParameters() {
        String keyValuePairs = String.join(";", new String[]{
                HEADSET_NAME + "=" + getCurrentDeviceName(),
                HEADSET_NREC + "=" + mAudioParams.getOrDefault(HEADSET_NREC,
                        HEADSET_AUDIO_FEATURE_OFF),
                HEADSET_WBS + "=" + mAudioParams.getOrDefault(HEADSET_WBS,
                        HEADSET_AUDIO_FEATURE_OFF)
        });
        Log.i(TAG, "setAudioParameters for " + mDevice + ": " + keyValuePairs);
        mSystemInterface.getAudioManager().setParameters(keyValuePairs);
    }

    private String parseUnknownAt(String atString) {
        StringBuilder atCommand = new StringBuilder(atString.length());

        for (int i = 0; i < atString.length(); i++) {
            char c = atString.charAt(i);
            if (c == '"') {
                int j = atString.indexOf('"', i + 1); // search for closing "
                if (j == -1) { // unmatched ", insert one.
                    atCommand.append(atString.substring(i, atString.length()));
                    atCommand.append('"');
                    break;
                }
                atCommand.append(atString.substring(i, j + 1));
                i = j;
            } else if (c != ' ') {
                atCommand.append(Character.toUpperCase(c));
            }
        }
        return atCommand.toString();
    }

    private int getAtCommandType(String atCommand) {
        int commandType = AtPhonebook.TYPE_UNKNOWN;
        String atString = null;
        atCommand = atCommand.trim();
        if (atCommand.length() > 5) {
            atString = atCommand.substring(5);
            if (atString.startsWith("?")) { // Read
                commandType = AtPhonebook.TYPE_READ;
            } else if (atString.startsWith("=?")) { // Test
                commandType = AtPhonebook.TYPE_TEST;
            } else if (atString.startsWith("=")) { // Set
                commandType = AtPhonebook.TYPE_SET;
            } else {
                commandType = AtPhonebook.TYPE_UNKNOWN;
            }
        }
        return commandType;
    }

    private void processDialCall(String number) {
        String dialNumber;
        if (mHeadsetService.hasDeviceInitiatedDialingOut()) {
            Log.w(TAG, "processDialCall, already dialling");
            mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }
        if ((number == null) || (number.length() == 0)) {
            dialNumber = mPhonebook.getLastDialledNumber();
            log("dialNumber: " + dialNumber);
            if ((dialNumber == null) || (dialNumber.length() == 0)) {
                Log.w(TAG, "processDialCall, last dial number null or empty ");
                mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
        } else if (number.charAt(0) == '>') {
            // Yuck - memory dialling requested.
            // Just dial last number for now
            if (number.startsWith(">9999")) { // for PTS test
                Log.w(TAG, "Number is too big");
                mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
            log("processDialCall, memory dial do last dial for now");
            dialNumber = mPhonebook.getLastDialledNumber();
            if (dialNumber == null) {
                Log.w(TAG, "processDialCall, last dial number null");
                mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
                return;
            }
        } else {
            // Remove trailing ';'
            if (number.charAt(number.length() - 1) == ';') {
                number = number.substring(0, number.length() - 1);
            }
            dialNumber = PhoneNumberUtils.convertPreDial(number);
        }
        if (!mHeadsetService.dialOutgoingCall(mDevice, dialNumber)) {
            Log.w(TAG, "processDialCall, failed to dial in service");
            mNativeInterface.atResponseCode(mDevice, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }
        mNeedDialingOutReply = true;
    }

    private void processVolumeEvent(int volumeType, int volume) {
        // Only current active device can change SCO volume
        if (!mDevice.equals(mHeadsetService.getActiveDevice())) {
            Log.w(TAG, "processVolumeEvent, ignored because " + mDevice + " is not active");
            return;
        }
        if (volumeType == HeadsetHalConstants.VOLUME_TYPE_SPK) {
            mSpeakerVolume = volume;
            int flag = (getCurrentState() == mAudioOn) ? AudioManager.FLAG_SHOW_UI : 0;
            mSystemInterface.getAudioManager()
                    .setStreamVolume(AudioManager.STREAM_BLUETOOTH_SCO, volume, flag);
        } else if (volumeType == HeadsetHalConstants.VOLUME_TYPE_MIC) {
            // Not used currently
            mMicVolume = volume;
        } else {
            Log.e(TAG, "Bad volume type: " + volumeType);
        }
    }

    private void processNoiseReductionEvent(boolean enable) {
        String prevNrec = mAudioParams.getOrDefault(HEADSET_NREC, HEADSET_AUDIO_FEATURE_OFF);
        String newNrec = enable ? HEADSET_AUDIO_FEATURE_ON : HEADSET_AUDIO_FEATURE_OFF;
        mAudioParams.put(HEADSET_NREC, newNrec);
        log("processNoiseReductionEvent: " + HEADSET_NREC + " change " + prevNrec + " -> "
                + newNrec);
        if (getAudioState() == BluetoothHeadset.STATE_AUDIO_CONNECTED) {
            setAudioParameters();
        }
    }

    private void processWBSEvent(int wbsConfig) {
        String prevWbs = mAudioParams.getOrDefault(HEADSET_WBS, HEADSET_AUDIO_FEATURE_OFF);
        switch (wbsConfig) {
            case HeadsetHalConstants.BTHF_WBS_YES:
                mAudioParams.put(HEADSET_WBS, HEADSET_AUDIO_FEATURE_ON);
                break;
            case HeadsetHalConstants.BTHF_WBS_NO:
            case HeadsetHalConstants.BTHF_WBS_NONE:
                mAudioParams.put(HEADSET_WBS, HEADSET_AUDIO_FEATURE_OFF);
                break;
            default:
                Log.e(TAG, "processWBSEvent: unknown wbsConfig " + wbsConfig);
                return;
        }
        log("processWBSEvent: " + HEADSET_NREC + " change " + prevWbs + " -> " + mAudioParams.get(
                HEADSET_WBS));
    }

    private void processAtChld(int chld, BluetoothDevice device) {
        if (mSystemInterface.processChld(chld)) {
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        } else {
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processSubscriberNumberRequest(BluetoothDevice device) {
        String number = mSystemInterface.getSubscriberNumber();
        if (number != null) {
            mNativeInterface.atResponseString(device,
                    "+CNUM: ,\"" + number + "\"," + PhoneNumberUtils.toaFromString(number) + ",,4");
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
        } else {
            Log.e(TAG, "getSubscriberNumber returns null");
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processAtCind(BluetoothDevice device) {
        int call, callSetup;
         // get the top of the Q 
        //HeadsetCallState tempCallState = mDelayedCSCallStates.peek();
        final HeadsetPhoneState phoneState = mSystemInterface.getHeadsetPhoneState();

        /* Handsfree carkits expect that +CIND is properly responded to
         Hence we ensure that a proper response is sent
         for the virtual call too.*/
        if (mHeadsetService.isVirtualCallStarted()) {
            call = 1;
            callSetup = 0;
        } else {
            // regular phone call
            call = phoneState.getNumActiveCall();
            callSetup = phoneState.getNumHeldCall();
        }
/*
        if(tempCallState != null &&
            tempCallState.mCallState == HeadsetHalConstants.CALL_STATE_ALERTING)
              call_state = HeadsetHalConstants.CALL_STATE_DIALING;
        else
              call_state = mSystemInterface.getHeadsetPhoneState().getCallState();
        log("sending call state in CIND resp as " + call_state);
*/
        mNativeInterface.cindResponse(device, phoneState.getCindService(), call, callSetup,
                phoneState.getCallState(), phoneState.getCindSignal(), phoneState.getCindRoam(),
                phoneState.getCindBatteryCharge());
        log("Exit processAtCind()");
    }

    private void processAtCops(BluetoothDevice device) {
        String operatorName = mSystemInterface.getNetworkOperator();
        if (operatorName == null || operatorName.equals("")) {
            operatorName = "No operator";
        }
        mNativeInterface.copsResponse(device, operatorName);
    }

    private void processAtClcc(BluetoothDevice device) {
        if (mHeadsetService.isVirtualCallStarted()) {
            // In virtual call, send our phone number instead of remote phone number
            // some carkits cross-check subscriber number( fetched by AT+CNUM) against
            // number sent in clcc and reject sco connection.
            String phoneNumber = VOIP_CALL_NUMBER;
            final HeadsetPhoneState phoneState = mSystemInterface.getHeadsetPhoneState();
            if (phoneNumber == null) {
                phoneNumber = "";
            }
            int type = PhoneNumberUtils.toaFromString(phoneNumber);
            log(" processAtClcc phonenumber = "+ phoneNumber + " type = " + type);
            // call still in dialling or alerting state 
            if (phoneState.getNumActiveCall() == 0) {
                mNativeInterface.clccResponse(device, 1, 0, phoneState.getCallState(), 0,
                                              false, phoneNumber, type);
            } else {
                mNativeInterface.clccResponse(device, 1, 0, 0, 0, false, phoneNumber, type);
            }
            mNativeInterface.clccResponse(device, 0, 0, 0, 0, false, "", 0);
        } else {
            // In Telecom call, ask Telecom to send send remote phone number
            if (!mSystemInterface.listCurrentCalls()) {
                Log.e(TAG, "processAtClcc: failed to list current calls for " + device);
                mNativeInterface.clccResponse(device, 0, 0, 0, 0, false, "", 0);
            } else {
                sendMessageDelayed(CLCC_RSP_TIMEOUT, device, CLCC_RSP_TIMEOUT_MS);
            }
        }
    }

    private void processAtCscs(String atString, int type, BluetoothDevice device) {
        log("processAtCscs - atString = " + atString);
        if (mPhonebook != null) {
            mPhonebook.handleCscsCommand(atString, type, device);
        } else {
            Log.e(TAG, "Phonebook handle null for At+CSCS");
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processAtCpbs(String atString, int type, BluetoothDevice device) {
        log("processAtCpbs - atString = " + atString);
        if (mPhonebook != null) {
            mPhonebook.handleCpbsCommand(atString, type, device);
        } else {
            Log.e(TAG, "Phonebook handle null for At+CPBS");
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    private void processAtCpbr(String atString, int type, BluetoothDevice device) {
        log("processAtCpbr - atString = " + atString);
        if (mPhonebook != null) {
            mPhonebook.handleCpbrCommand(atString, type, device);
        } else {
            Log.e(TAG, "Phonebook handle null for At+CPBR");
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        }
    }

    /**
     * Find a character ch, ignoring quoted sections.
     * Return input.length() if not found.
     */
    private static int findChar(char ch, String input, int fromIndex) {
        for (int i = fromIndex; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '"') {
                i = input.indexOf('"', i + 1);
                if (i == -1) {
                    return input.length();
                }
            } else if (c == ch) {
                return i;
            }
        }
        return input.length();
    }

    /**
     * Break an argument string into individual arguments (comma delimited).
     * Integer arguments are turned into Integer objects. Otherwise a String
     * object is used.
     */
    private static Object[] generateArgs(String input) {
        int i = 0;
        int j;
        ArrayList<Object> out = new ArrayList<Object>();
        while (i <= input.length()) {
            j = findChar(',', input, i);

            String arg = input.substring(i, j);
            try {
                out.add(new Integer(arg));
            } catch (NumberFormatException e) {
                out.add(arg);
            }

            i = j + 1; // move past comma
        }
        return out.toArray();
    }

    /**
     * Process vendor specific AT commands
     *
     * @param atString AT command after the "AT+" prefix
     * @param device Remote device that has sent this command
     */
    private void processVendorSpecificAt(String atString, BluetoothDevice device) {
        log("processVendorSpecificAt - atString = " + atString);

        // Currently we accept only SET type commands.
        int indexOfEqual = atString.indexOf("=");
        if (indexOfEqual == -1) {
            Log.w(TAG, "processVendorSpecificAt: command type error in " + atString);
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }

        String command = atString.substring(0, indexOfEqual);
        Integer companyId = VENDOR_SPECIFIC_AT_COMMAND_COMPANY_ID.get(command);
        if (companyId == null) {
            Log.i(TAG, "processVendorSpecificAt: unsupported command: " + atString);
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }

        String arg = atString.substring(indexOfEqual + 1);
        if (arg.startsWith("?")) {
            Log.w(TAG, "processVendorSpecificAt: command type error in " + atString);
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            return;
        }

        Object[] args = generateArgs(arg);
        if (command.equals(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XAPL)) {
            processAtXapl(args, device);
        }
        broadcastVendorSpecificEventIntent(command, companyId, BluetoothHeadset.AT_CMD_TYPE_SET,
                args, device);
        mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_OK, 0);
    }

    /**
     * Process AT+XAPL AT command
     *
     * @param args command arguments after the equal sign
     * @param device Remote device that has sent this command
     */
    private void processAtXapl(Object[] args, BluetoothDevice device) {
        if (args.length != 2) {
            Log.w(TAG, "processAtXapl() args length must be 2: " + String.valueOf(args.length));
            return;
        }
        if (!(args[0] instanceof String) || !(args[1] instanceof Integer)) {
            Log.w(TAG, "processAtXapl() argument types not match");
            return;
        }
        // feature = 2 indicates that we support battery level reporting only
        mNativeInterface.atResponseString(device, "+XAPL=iPhone," + String.valueOf(2));
    }

    private void processUnknownAt(String atString, BluetoothDevice device) {
        if (device == null) {
            Log.w(TAG, "processUnknownAt device is null");
            return;
        }
        log("processUnknownAt - atString = " + atString);
        String atCommand = parseUnknownAt(atString);
        int commandType = getAtCommandType(atCommand);
        if (atCommand.startsWith("+CSCS")) {
            processAtCscs(atCommand.substring(5), commandType, device);
        } else if (atCommand.startsWith("+CPBS")) {
            processAtCpbs(atCommand.substring(5), commandType, device);
        } else if (atCommand.startsWith("+CPBR")) {
            processAtCpbr(atCommand.substring(5), commandType, device);
        } else if (atCommand.startsWith("+CSQ")) {
            mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
        } else {
            processVendorSpecificAt(atCommand, device);
        }
    }

    // HSP +CKPD command
    private void processKeyPressed(BluetoothDevice device) {
        final HeadsetPhoneState phoneState = mSystemInterface.getHeadsetPhoneState();
        if (phoneState.getCallState() == HeadsetHalConstants.CALL_STATE_INCOMING) {
            mSystemInterface.answerCall(device);
        } else if (phoneState.getNumActiveCall() > 0) {
            if (getAudioState() != BluetoothHeadset.STATE_AUDIO_CONNECTED) {
                mHeadsetService.setActiveDevice(mDevice);
                mNativeInterface.connectAudio(mDevice);
            } else {
                mSystemInterface.hangupCall(device);
            }
        } else {
            // We have already replied OK to this HSP command, no feedback is needed
            if (mHeadsetService.hasDeviceInitiatedDialingOut()) {
                Log.w(TAG, "processKeyPressed, already dialling");
                return;
            }
            String dialNumber = mPhonebook.getLastDialledNumber();
            if (dialNumber == null) {
                Log.w(TAG, "processKeyPressed, last dial number null");
                return;
            }
            if (!mHeadsetService.dialOutgoingCall(mDevice, dialNumber)) {
                Log.w(TAG, "processKeyPressed, failed to call in service");
                return;
            }
        }
    }

    /**
     * Send HF indicator value changed intent
     *
     * @param device Device whose HF indicator value has changed
     * @param indId Indicator ID [0-65535]
     * @param indValue Indicator Value [0-65535], -1 means invalid but indId is supported
     */
    private void sendIndicatorIntent(BluetoothDevice device, int indId, int indValue) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_HF_INDICATORS_VALUE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, indId);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, indValue);

        mHeadsetService.sendBroadcast(intent, HeadsetService.BLUETOOTH_PERM);
    }

    private void processAtBind(String atString, BluetoothDevice device) {
        log("processAtBind: " + atString);

        // Parse the AT String to find the Indicator Ids that are supported
        int indId = 0;
        int iter = 0;
        int iter1 = 0;

        while (iter < atString.length()) {
            iter1 = findChar(',', atString, iter);
            String id = atString.substring(iter, iter1);

            try {
                indId = Integer.valueOf(id);
            } catch (NumberFormatException e) {
                Log.e(TAG, Log.getStackTraceString(new Throwable()));
            }

            switch (indId) {
                case HeadsetHalConstants.HF_INDICATOR_ENHANCED_DRIVER_SAFETY:
                    log("Send Broadcast intent for the Enhanced Driver Safety indicator.");
                    sendIndicatorIntent(device, indId, -1);
                    break;
                case HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS:
                    log("Send Broadcast intent for the Battery Level indicator.");
                    sendIndicatorIntent(device, indId, -1);
                    break;
                default:
                    log("Invalid HF Indicator Received");
                    break;
            }

            iter = iter1 + 1; // move past comma
        }
    }

    private void processAtBiev(int indId, int indValue, BluetoothDevice device) {
        log("processAtBiev: ind_id=" + indId + ", ind_value=" + indValue);
        sendIndicatorIntent(device, indId, indValue);
    }

    private void processCpbr(Intent intent)
    {
        int atCommandResult = 0;
        int atCommandErrorCode = 0;
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        log("Enter processCpbr()");
        // ASSERT: (headset != null) && headSet.isConnected()
        // REASON: mCheckingAccessPermission is true, otherwise resetAtState
        // has set mCheckingAccessPermission to false
        if (intent.getAction().equals(BluetoothDevice.ACTION_CONNECTION_ACCESS_REPLY)) {
            if (intent.getIntExtra(BluetoothDevice.EXTRA_CONNECTION_ACCESS_RESULT,
                                   BluetoothDevice.CONNECTION_ACCESS_NO)
                    == BluetoothDevice.CONNECTION_ACCESS_YES) {
                if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    mDevice.setPhonebookAccessPermission(BluetoothDevice.ACCESS_ALLOWED);
                }
                atCommandResult = mPhonebook.processCpbrCommand(device);
            } else {
                if (intent.getBooleanExtra(BluetoothDevice.EXTRA_ALWAYS_ALLOWED, false)) {
                    mDevice.setPhonebookAccessPermission(
                            BluetoothDevice.ACCESS_REJECTED);
                }
            }
        }
        mPhonebook.setCpbrIndex(-1);
        mPhonebook.setCheckingAccessPermission(false);

        if (atCommandResult >= 0) {
            mNativeInterface.atResponseCode(device, atCommandResult, atCommandErrorCode);
        } else {
            log("processCpbr - RESULT_NONE");
        }
        Log.d(TAG, "Exit processCpbr()");
    }

    private void processSendClccResponse(HeadsetClccResponse clcc) {
        if (!hasMessages(CLCC_RSP_TIMEOUT)) {
            return;
        }
        if (clcc.mIndex == 0) {
            removeMessages(CLCC_RSP_TIMEOUT);
        }
        // get the top of the Q
        HeadsetCallState tempCallState = mDelayedCSCallStates.peek();

        /* Send call state DIALING if call alerting update is still in the Q */
        if (clcc.mStatus == HeadsetHalConstants.CALL_STATE_ALERTING &&
            tempCallState != null &&
            tempCallState.mCallState == HeadsetHalConstants.CALL_STATE_ALERTING) {
            log("sending call status as DIALING");
            mNativeInterface.clccResponse(mDevice, clcc.mIndex, clcc.mDirection,
                                          HeadsetHalConstants.CALL_STATE_DIALING,
                                   clcc.mMode, clcc.mMpty, clcc.mNumber, clcc.mType);
        } else {
            log("sending call status as " + clcc.mStatus);
            mNativeInterface.clccResponse(mDevice, clcc.mIndex, clcc.mDirection,
                                          clcc.mStatus,
                                   clcc.mMode, clcc.mMpty, clcc.mNumber, clcc.mType);
        }
        log("Exit processSendClccResponse()");
    }

    private void processSendVendorSpecificResultCode(HeadsetVendorSpecificResultCode resultCode) {
        String stringToSend = resultCode.mCommand + ": ";
        if (resultCode.mArg != null) {
            stringToSend += resultCode.mArg;
        }
        mNativeInterface.atResponseString(resultCode.mDevice, stringToSend);
    }

    private String getCurrentDeviceName() {
        String deviceName = mAdapterService.getRemoteName(mDevice);
        if (deviceName == null) {
            return "<unknown>";
        }
        return deviceName;
    }

    private void updateAgIndicatorEnableState(
            HeadsetAgIndicatorEnableState agIndicatorEnableState) {
        if (Objects.equals(mAgIndicatorEnableState, agIndicatorEnableState)) {
            Log.i(TAG, "updateAgIndicatorEnableState, no change in indicator state "
                    + mAgIndicatorEnableState);
            return;
        }
        mAgIndicatorEnableState = agIndicatorEnableState;
        int events = PhoneStateListener.LISTEN_NONE;
        if (mAgIndicatorEnableState != null && mAgIndicatorEnableState.service) {
            events |= PhoneStateListener.LISTEN_SERVICE_STATE;
        }
        if (mAgIndicatorEnableState != null && mAgIndicatorEnableState.signal) {
            events |= PhoneStateListener.LISTEN_SIGNAL_STRENGTHS;
        }
        mSystemInterface.getHeadsetPhoneState().listenForPhoneState(mDevice, events);
    }

    boolean isConnectedDeviceBlacklistedforIncomingCall() {
        // Checking for the Blacklisted device Addresses
        for (int j = 0; j < BlacklistDeviceAddrToDelayCallInd.length;j++) {
            String addr = BlacklistDeviceAddrToDelayCallInd[j];
            if (mDevice.toString().toLowerCase().startsWith(addr.toLowerCase())) {
                log("Remote device address Blacklisted for sending delay");
                return true;
            }
        }
        return false;
    }

    @Override
    protected void log(String msg) {
        if (DBG) {
            super.log(msg);
        }
    }

    @Override
    protected String getLogRecString(Message msg) {
        StringBuilder builder = new StringBuilder();
        builder.append(getMessageName(msg.what));
        builder.append(": ");
        builder.append("arg1=")
                .append(msg.arg1)
                .append(", arg2=")
                .append(msg.arg2)
                .append(", obj=");
        if (msg.obj instanceof HeadsetMessageObject) {
            HeadsetMessageObject object = (HeadsetMessageObject) msg.obj;
            object.buildString(builder);
        } else {
            builder.append(msg.obj);
        }
        return builder.toString();
    }

    private void handleAccessPermissionResult(Intent intent) {
        log("Enter handleAccessPermissionResult");
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
        if (mPhonebook != null) {
            if (!mPhonebook.getCheckingAccessPermission()) {
                return;
            }

            Message m = obtainMessage(PROCESS_CPBR);
            m.obj = intent;
            sendMessage(m);
        } else {
            Log.e(TAG, "Phonebook handle null");
            if (device != null) {
                mNativeInterface.atResponseCode(device, HeadsetHalConstants.AT_RESPONSE_ERROR, 0);
            }
        }
        log("Exit handleAccessPermissionResult()");
    }

    private static String getMessageName(int what) {
        switch (what) {
            case CONNECT:
                return "CONNECT";
            case DISCONNECT:
                return "DISCONNECT";
            case CONNECT_AUDIO:
                return "CONNECT_AUDIO";
            case DISCONNECT_AUDIO:
                return "DISCONNECT_AUDIO";
            case VOICE_RECOGNITION_START:
                return "VOICE_RECOGNITION_START";
            case VOICE_RECOGNITION_STOP:
                return "VOICE_RECOGNITION_STOP";
            case INTENT_SCO_VOLUME_CHANGED:
                return "INTENT_SCO_VOLUME_CHANGED";
            case INTENT_CONNECTION_ACCESS_REPLY:
                return "INTENT_CONNECTION_ACCESS_REPLY";
            case CALL_STATE_CHANGED:
                return "CALL_STATE_CHANGED";
            case DEVICE_STATE_CHANGED:
                return "DEVICE_STATE_CHANGED";
            case SEND_CCLC_RESPONSE:
                return "SEND_CCLC_RESPONSE";
            case SEND_VENDOR_SPECIFIC_RESULT_CODE:
                return "SEND_VENDOR_SPECIFIC_RESULT_CODE";
            case STACK_EVENT:
                return "STACK_EVENT";
            case DIALING_OUT_RESULT:
                return "DIALING_OUT_RESULT";
            case START_VR_TIMEOUT:
                return "START_VR_TIMEOUT";
            case CLCC_RSP_TIMEOUT:
                return "CLCC_RSP_TIMEOUT";
            case CONNECT_TIMEOUT:
                return "CONNECT_TIMEOUT";
            default:
                return "UNKNOWN";
        }
    }
}
