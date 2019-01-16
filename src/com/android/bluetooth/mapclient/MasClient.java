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

package com.android.bluetooth.mapclient;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothUuid;
import android.bluetooth.SdpMasRecord;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;

import com.android.bluetooth.BluetoothObexTransport;
import com.android.internal.util.StateMachine;

import java.io.IOException;
import java.lang.ref.WeakReference;

import javax.obex.ClientSession;
import javax.obex.ClientOperation;
import javax.obex.HeaderSet;
import javax.obex.ObexHelper;
import javax.obex.ResponseCodes;

/* MasClient is a one time use connection to a server defined by the SDP record passed in at
 * construction.  After use shutdown() must be called to properly clean up.
 */
public class MasClient {
    private static final int CONNECT = 0;
    private static final int DISCONNECT = 1;
    private static final int REQUEST = 2;
    private static final int ABORT = 3;
    private static final String TAG = "MasClient";
    private static final boolean DBG = MapClientService.DBG;
    private static final boolean VDBG = MapClientService.VDBG;
    private static final byte[] BLUETOOTH_UUID_OBEX_MAS = new byte[]{
            (byte) 0xbb,
            0x58,
            0x2b,
            0x40,
            0x42,
            0x0c,
            0x11,
            (byte) 0xdb,
            (byte) 0xb0,
            (byte) 0xde,
            0x08,
            0x00,
            0x20,
            0x0c,
            (byte) 0x9a,
            0x66
    };
    private static final byte OAP_TAGID_MAP_SUPPORTED_FEATURES = 0x29;
    /* MAP features */
    static final int MAP_FEATURE_NOTIFICATION_REGISTRATION_BIT      = 1 << 0;
    static final int MAP_FEATURE_NOTIFICATION_BIT                   = 1 << 1;
    static final int MAP_FEATURE_BROWSING_BIT                       = 1 << 2;
    static final int MAP_FEATURE_UPLOADING_BIT                      = 1 << 3;
    static final int MAP_FEATURE_DELETE_BIT                         = 1 << 4;
    static final int MAP_FEATURE_INSTANCE_INFORMATION_BIT           = 1 << 5;
    /* Event Report Version 1.1 */
    static final int MAP_FEATURE_EXTENDED_EVENT_REPORT_V11_BIT      = 1 << 6;
    /* Event Report Version 1.2 */
    static final int MAP_FEATURE_EXTENDED_EVENT_REPORT_V12_BIT      = 1 << 7;
    /* Message Format Version 1.1 */
    static final int MAP_FEATURE_MESSAGE_FORMAT_V11_BIT             = 1 << 8;
    /* Messages-Listing Format Version 1.1 */
    static final int MAP_FEATURE_MESSAGES_LISTING_FORMAT_V11_BIT    = 1 << 9;
    static final int MAP_FEATURE_PERSISTENT_HANDLE_BIT              = 1 << 10;
    static final int MAP_FEATURE_DATABASE_IDENTIFIER_BIT            = 1 << 11;
    static final int MAP_FEATURE_FOLDER_VERSION_COUNTER_BIT         = 1 << 12;
    static final int MAP_FEATURE_COVERSATION_VERSION_COUNTER_BIT    = 1 << 13;
    static final int MAP_FEATURE_PARTICIPANT_PRESENCE_CHANGE_NOTIFICATION_BIT = 1 << 14;
    static final int MAP_FEATURE_PARTICIPANT_CHAT_STATE_CHANGE_NOTIFICATION_BIT = 1 << 15;
    static final int MAP_FEATURE_PBAP_CONTACT_CROSS_REFERENCE_BIT   = 1 << 16;
    static final int MAP_FEATURE_NOTIFICATION_FILTERING_BIT         = 1 << 17;
    static final int MAP_FEATURE_UTC_OFFSET_TIMESTAMP_BIT           = 1 << 18;
    static final int MAP_FEATURE_CONVERSATION_LISTING_BIT           = 1 << 20;
    static final int MAP_FEATURE_OWNER_STATUS_BIT                   = 1 << 21;

    static final int MAP_SUPPORTED_FEATURES = MAP_FEATURE_NOTIFICATION_REGISTRATION_BIT |
            MAP_FEATURE_NOTIFICATION_BIT |
            MAP_FEATURE_BROWSING_BIT |
            MAP_FEATURE_UPLOADING_BIT |
            MAP_FEATURE_DELETE_BIT |
            MAP_FEATURE_EXTENDED_EVENT_REPORT_V11_BIT |
            MAP_FEATURE_MESSAGES_LISTING_FORMAT_V11_BIT;

    private final StateMachine mCallback;
    private Handler mHandler;
    private BluetoothSocket mSocket;
    private BluetoothObexTransport mTransport;
    private BluetoothDevice mRemoteDevice;
    private ClientSession mSession;
    private HandlerThread mThread;
    private boolean mConnected = false;
    private boolean mAborting = false;
    SdpMasRecord mSdpMasRecord;

    public MasClient(BluetoothDevice remoteDevice, StateMachine callback,
            SdpMasRecord sdpMasRecord) {
        if (remoteDevice == null) {
            throw new NullPointerException("Obex transport is null");
        }
        mRemoteDevice = remoteDevice;
        mCallback = callback;
        mSdpMasRecord = sdpMasRecord;
        mThread = new HandlerThread("Client");
        mThread.start();
        /* This will block until the looper have started, hence it will be safe to use it,
           when the constructor completes */
        Looper looper = mThread.getLooper();
        mHandler = new MasClientHandler(looper, this);

        mHandler.obtainMessage(CONNECT).sendToTarget();
    }

    /* Utilize SDP, if available, to create a socket connection over L2CAP, RFCOMM specified
     * channel, or RFCOMM default channel. */
    private boolean connectSocket() {
        try {
            // Use BluetoothSocket to connect
            if (mSdpMasRecord == null) {
                // BackWardCompatability: Fall back to create RFCOMM through UUID.
                if (DBG) {
                    Log.d(TAG, "connectSocket: UUID: " + BluetoothUuid.MAS.getUuid());
                }
                mSocket =
                        mRemoteDevice.createRfcommSocketToServiceRecord(BluetoothUuid.MAS.getUuid());
            } else if (mSdpMasRecord.getL2capPsm() != -1) {
                if (DBG) {
                    Log.d(TAG, "connectSocket: PSM: " + mSdpMasRecord.getL2capPsm());
                }
                mSocket = mRemoteDevice.createL2capSocket(mSdpMasRecord.getL2capPsm());
            } else {
                if (DBG) {
                    Log.d(TAG, "connectSocket: channel: " + mSdpMasRecord.getRfcommCannelNumber());
                }
                mSocket = mRemoteDevice.createRfcommSocket(mSdpMasRecord.getRfcommCannelNumber());
            }

            if (mSocket != null) {
                mSocket.connect();
                return true;
            } else {
                Log.w(TAG, "Could not create socket");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error while connecting socket", e);
        }
        return false;
    }

    private void connect() {
        try {
            if (!connectSocket()) {
                Log.w(TAG, "connect failed");
                return;
            }
            mTransport = new BluetoothObexTransport(mSocket);

            mSession = new ClientSession(mTransport);
            HeaderSet headerset = new HeaderSet();
            headerset.setHeader(HeaderSet.TARGET, BLUETOOTH_UUID_OBEX_MAS);
            ObexAppParameters oap = new ObexAppParameters();

            oap.add(OAP_TAGID_MAP_SUPPORTED_FEATURES, MAP_SUPPORTED_FEATURES);

            oap.addToHeaderSet(headerset);

            headerset = mSession.connect(headerset);

            if (DBG) Log.d(TAG, "Connection results" + headerset.getResponseCode());

            if (headerset.getResponseCode() == ResponseCodes.OBEX_HTTP_OK) {
                if (DBG) {
                    Log.d(TAG, "Connection Successful");
                }
                mConnected = true;
                mCallback.sendMessage(MceStateMachine.MSG_MAS_CONNECTED);
            } else {
                disconnect();
            }

        } catch (IOException e) {
            Log.e(TAG, "Caught an exception " + e.toString());
            disconnect();
        }
    }

    private void disconnect() {
        if (mSession != null) {
            try {
                mSession.disconnect(null);
            } catch (IOException e) {
                Log.e(TAG, "Caught an exception while disconnecting:" + e.toString());
            }

            try {
                mSession.close();
            } catch (IOException e) {
                Log.e(TAG, "Caught an exception while closing:" + e.toString());
            }
        }

        mConnected = false;
        mCallback.sendMessage(MceStateMachine.MSG_MAS_DISCONNECTED);
    }

    private void executeRequest(Request request) {
        try {
            request.execute(mSession);
            mCallback.sendMessage(MceStateMachine.MSG_MAS_REQUEST_COMPLETED, request);
        } catch (IOException e) {
            if (DBG) {
                Log.d(TAG, "Request failed: " + request);
            }
            // Disconnect to cleanup.
            disconnect();
        }
    }

    public boolean makeRequest(Request request) {
        if (DBG) {
            Log.d(TAG, "makeRequest called with: " + request);
        }
        boolean status = mHandler.sendMessage(mHandler.obtainMessage(REQUEST, request));
        if (!status) {
            Log.e(TAG, "Adding messages failed, state: " + mConnected);
            return false;
        }
        return true;
    }

    public void abort() {
        mAborting = true;
        if (mSession != null) {
            if (DBG) {
                Log.d(TAG, "abort");
            }
            mHandler.obtainMessage(ABORT).sendToTarget();
        }
    }

    public void sendAbort() {
        /* Send obex abort here to abort the REQUEST commands in MasClientHandler
         * If there is a ongoing REQUEST command, all the following REQUEST commands will
         * be cleared after response for the ongoing REQUEST received, and then obex abort is sent.
         */
        HeaderSet replyHeader = new HeaderSet();
        try {
            mSession.sendRequest(ObexHelper.OBEX_OPCODE_ABORT, null, replyHeader, null, false);
        } catch (IOException e) {
            Log.e(TAG, "Send abort request failed " + e);
            return;
        }
        if (replyHeader.responseCode != ResponseCodes.OBEX_HTTP_OK) {
            Log.e(TAG, "Invalid response code from server");
        }
        mCallback.sendMessage(MceStateMachine.MSG_ABORTED);
        clearAbort();
    }

    public boolean isAborting() {
        return mAborting;
    }

    public void clearAbort() {
        if (DBG) {
            Log.d(TAG, "clearAbort");
        }
        mAborting = false;
    }

    public void shutdown() {
        mHandler.obtainMessage(DISCONNECT).sendToTarget();
        mThread.quitSafely();
    }

    public enum CharsetType {
        NATIVE, UTF_8;
    }

    SdpMasRecord getSdpMasRecord() {
        return mSdpMasRecord;
    }

    private static class MasClientHandler extends Handler {
        WeakReference<MasClient> mInst;

        MasClientHandler(Looper looper, MasClient inst) {
            super(looper);
            mInst = new WeakReference<>(inst);
        }

        @Override
        public void handleMessage(Message msg) {
            MasClient inst = mInst.get();

            if (DBG) {
                Log.d(TAG, "message " + msg.what);
            }

            switch (msg.what) {
                case CONNECT:
                    if (!inst.mConnected) {
                        inst.connect();
                    }
                    break;

                case DISCONNECT:
                    if (inst.mConnected) {
                        inst.disconnect();
                    }
                    break;

                case REQUEST:
                    if (inst.mConnected && !inst.isAborting()) {
                        inst.executeRequest((Request) msg.obj);
                    }

                    /* mAborting may be set during executeRequest, clear it when excute finished */
                    if (inst.isAborting()) {
                        /* Remove all REQUEST messages */
                        if (DBG) {
                            Log.d(TAG, "Remove all REQUEST messages");
                        }
                        removeMessages(REQUEST);
                    }
                    break;
                case ABORT:
                    if (inst.mConnected) {
                        /* Abort operation has been done and there is no more REQUEST */
                        inst.sendAbort();
                    }
                    break;
            }
        }
    }
}
