/*
 * Copyright (c) 2010-2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *        * Redistributions of source code must retain the above copyright
 *          notice, this list of conditions and the following disclaimer.
 *        * Redistributions in binary form must reproduce the above copyright
 *          notice, this list of conditions and the following disclaimer in the
 *          documentation and/or other materials provided with the distribution.
 *        * Neither the name of Code Aurora nor
 *          the names of its contributors may be used to endorse or promote
 *          products derived from this software without specific prior written
 *          permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.    IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.map;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Message;
import android.os.ParcelUuid;
import android.text.format.Time;
import android.util.Log;
import android.util.Pair;

import com.android.bluetooth.map.MapUtils.MapUtils;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.obex.ObexTransport;

/**
 * This class run an MNS session.
 */
public class BluetoothMns {
    private static final String TAG = "BtMns";

    private static final boolean V = BluetoothMasService.VERBOSE;

    public static final int RFCOMM_ERROR = 10;

    public static final int RFCOMM_CONNECTED = 11;

    public static final int MNS_CONNECT = 13;

    public static final int MNS_DISCONNECT = 14;

    public static final int MNS_SEND_EVENT = 15;

    public static final int MNS_SEND_EVENT_DONE = 16;

    public static final int MNS_SEND_TIMEOUT = 17;

    public static final int MNS_SEND_TIMEOUT_DURATION = 30000; // 30 secs

    private static final short MNS_UUID16 = 0x1133;

    public static final String NEW_MESSAGE = "NewMessage";

    public static final String DELIVERY_SUCCESS = "DeliverySuccess";

    public static final String SENDING_SUCCESS = "SendingSuccess";

    public static final String DELIVERY_FAILURE = "DeliveryFailure";

    public static final String SENDING_FAILURE = "SendingFailure";

    public static final String MEMORY_FULL = "MemoryFull";

    public static final String MEMORY_AVAILABLE = "MemoryAvailable";

    public static final String MESSAGE_DELETED = "MessageDeleted";

    public static final String MESSAGE_SHIFT = "MessageShift";

    public static final int MMS_HDLR_CONSTANT = 100000;

    public static final int EMAIL_HDLR_CONSTANT = 200000;

    private Context mContext;

    private BluetoothAdapter mAdapter;

    private BluetoothMnsObexSession mSession;

    private EventHandler mSessionHandler;

    private BluetoothMnsZero bmz = null;
    private BluetoothMnsOne bmo = null;
    public static final ParcelUuid BluetoothUuid_ObexMns = ParcelUuid
            .fromString("00001133-0000-1000-8000-00805F9B34FB");

    private HashSet<Integer> mWaitingMasId = new HashSet<Integer>();
    private final Queue<Pair<Integer, String>> mEventQueue = new ConcurrentLinkedQueue<Pair<Integer, String>>();
    private boolean mSendingEvent = false;

    public BluetoothMns(Context context) {
        /* check Bluetooth enable status */
        /*
         * normally it's impossible to reach here if BT is disabled. Just check
         * for safety
         */

        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mContext = context;

        bmz = new BluetoothMnsZero(mContext, this);
        bmo = new BluetoothMnsOne(mContext, this);

        if (!mAdapter.isEnabled()) {
            Log.e(TAG, "Can't send event when Bluetooth is disabled ");
            return;
        }

        mSessionHandler = new EventHandler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_LOW);
        filter.addAction(Intent.ACTION_DEVICE_STORAGE_OK);
        mContext.registerReceiver(mStorageStatusReceiver, filter);
    }

    public Handler getHandler() {
        return mSessionHandler;
    }

    private void register(final int masId) {
        new Thread(new Runnable() {
            public void run() {
                if (masId == 0) {
                    bmz.register();
                } else if (masId == 1) {
                    bmo.register();
                }
            }
        }).start();
    }

    private void deregister(final int masId) {
        new Thread(new Runnable() {
            public void run() {
                if (masId == 0) {
                    bmz.deregister();
                } else if (masId == 1) {
                    bmo.deregister();
                }
            }
        }).start();
    }

    private void deregisterAll() {
        bmz.deregister();
        bmo.deregister();
    }

    /*
     * Receives events from mConnectThread & mSession back in the main thread.
     */
    private class EventHandler extends Handler {
        public EventHandler() {
            super();
        }

        @Override
        public void handleMessage(Message msg) {
            if (V){
                Log.v(TAG, " Handle Message " + msg.what);
            }
            switch (msg.what) {
                case MNS_CONNECT:
                {
                    final int masId = msg.arg1;
                    final BluetoothDevice device = (BluetoothDevice)msg.obj;
                    if (mSession != null) {
                        if (V) Log.v(TAG, "is MNS session connected? " + mSession.isConnected());
                        if (mSession.isConnected()) {
                            register(masId);
                            break;
                        }
                    }
                    if (mWaitingMasId.isEmpty()) {
                        mWaitingMasId.add(masId);
                        mConnectThread = new SocketConnectThread(device);
                        mConnectThread.start();
                    } else {
                        mWaitingMasId.add(masId);
                    }
                    break;
                }
                case MNS_DISCONNECT:
                {
                    final int masId = msg.arg1;
                    deregister(masId);
                    if (!bmz.isRegistered() && !bmo.isRegistered()) {
                        stop();
                    }
                    break;
                }
                /*
                 * RFCOMM connect fail is for outbound share only! Mark batch
                 * failed, and all shares in batch failed
                 */
                case RFCOMM_ERROR:
                    if (V) Log.v(TAG, "receive RFCOMM_ERROR msg");
                    deregisterAll();
                    break;
                /*
                 * RFCOMM connected. Do an OBEX connect by starting the session
                 */
                case RFCOMM_CONNECTED:
                {
                    if (V) Log.v(TAG, "Transfer receive RFCOMM_CONNECTED msg");
                    ObexTransport transport = (ObexTransport) msg.obj;
                    try {
                        startObexSession(transport);
                    } catch (NullPointerException ne) {
                        sendEmptyMessage(RFCOMM_ERROR);
                        return;
                    }
                    for (int masId : mWaitingMasId) {
                        register(masId);
                    }
                    mWaitingMasId.clear();
                    break;
                }
                /* Handle the error state of an Obex session */
                case BluetoothMnsObexSession.MSG_SESSION_ERROR:
                    if (V) Log.v(TAG, "receive MSG_SESSION_ERROR");
                    deregisterAll();
                    if (mSession != null) {
                        mSession.disconnect();
                        mSession = null;
                    }
                    break;
                case MNS_SEND_EVENT:
                {
                    final String xml = (String)msg.obj;
                    final int masId = msg.arg1;
                    if (mSendingEvent) {
                        mEventQueue.add(new Pair<Integer, String>(masId, xml));
                    } else {
                        mSendingEvent = true;
                        new Thread(new SendEventTask(xml, masId)).start();
                    }
                    break;
                }
                case MNS_SEND_EVENT_DONE:
                    if (mEventQueue.isEmpty()) {
                        mSendingEvent = false;
                    } else {
                        final Pair<Integer, String> p = mEventQueue.remove();
                        final int masId = p.first;
                        final String xml = p.second;
                        new Thread(new SendEventTask(xml, masId)).start();
                    }
                    break;
                case MNS_SEND_TIMEOUT:
                {
                    final int masId = msg.arg1;
                    if (V) Log.v(TAG, "MNS_SEND_TIMEOUT disconnecting.");
                    if (masId == 0) {
                        bmz.deregister();
                    } else if (masId == 1){
                        bmo.deregister();
                    }
                    deregister(masId);
                    if (!bmz.isRegistered() && !bmo.isRegistered()) {
                        stop();
                    }
                    break;
                }
            }
        }

        private void setTimeout(int masId) {
            if (V) Log.v(TAG, "setTimeout MNS_SEND_TIMEOUT for instance " + masId);
            sendMessageDelayed(obtainMessage(MNS_SEND_TIMEOUT, masId, -1),
                    MNS_SEND_TIMEOUT_DURATION);
        }

        private void removeTimeout() {
            if (hasMessages(MNS_SEND_TIMEOUT)) {
                removeMessages(MNS_SEND_TIMEOUT);
                sendEventDone();
            }
        }

        private void sendEventDone() {
            if (V) Log.v(TAG, "post MNS_SEND_EVENT_DONE");
            obtainMessage(MNS_SEND_EVENT_DONE).sendToTarget();
        }

        class SendEventTask implements Runnable {
            final String mXml;
            final int mMasId;
            SendEventTask (String xml, int masId) {
                mXml = xml;
                mMasId = masId;
            }

            public void run() {
                if (V) Log.v(TAG, "MNS_SEND_EVENT started");
                setTimeout(mMasId);
                sendEvent(mXml, mMasId);
                removeTimeout();
                if (V) Log.v(TAG, "MNS_SEND_EVENT finished");
            }
        }
    }

    /*
     * Class to hold message handle for MCE Initiated operation
     */
    public class BluetoothMnsMsgHndlMceInitOp {
        public String msgHandle;
        Time time;
    }

    /*
     * Keep track of Message Handles on which the operation was
     * initiated by MCE
     */
    List<BluetoothMnsMsgHndlMceInitOp> opList = new ArrayList<BluetoothMnsMsgHndlMceInitOp>();

    /*
     * Adds the Message Handle to the list for tracking
     * MCE initiated operation
     */
    public void addMceInitiatedOperation(String msgHandle) {
        BluetoothMnsMsgHndlMceInitOp op = new BluetoothMnsMsgHndlMceInitOp();
        op.msgHandle = msgHandle;
        op.time = new Time();
        op.time.setToNow();
        opList.add(op);
    }
    /*
     * Removes the Message Handle from the list for tracking
     * MCE initiated operation
     */
    public void removeMceInitiatedOperation(int location) {
        opList.remove(location);
    }

    /*
     * Finds the location in the list of the given msgHandle, if
     * available. "+" indicates the next (any) operation
     */
    public int findLocationMceInitiatedOperation( String msgHandle) {
        int location = -1;

        Time currentTime = new Time();
        currentTime.setToNow();

        List<BluetoothMnsMsgHndlMceInitOp> staleOpList = new ArrayList<BluetoothMnsMsgHndlMceInitOp>();
        for (BluetoothMnsMsgHndlMceInitOp op: opList) {
            if (currentTime.toMillis(false) - op.time.toMillis(false) > 10000) {
                // add stale entries
                staleOpList.add(op);
            }
        }
        if (!staleOpList.isEmpty()) {
            for (BluetoothMnsMsgHndlMceInitOp op: staleOpList) {
                // Remove stale entries
                opList.remove(op);
            }
        }

        for (BluetoothMnsMsgHndlMceInitOp op: opList) {
            if (op.msgHandle.equalsIgnoreCase(msgHandle)){
                location = opList.indexOf(op);
                break;
            }
        }

        if (location == -1) {
            for (BluetoothMnsMsgHndlMceInitOp op: opList) {
                if (op.msgHandle.equalsIgnoreCase("+")) {
                    location = opList.indexOf(op);
                    break;
                }
            }
        }
        return location;
    }


    /**
     * Post a MNS Event to the MNS thread
     */
    public void sendMnsEvent(String msg, String handle, String folder,
            String old_folder, String msgType) {
        if (V) {
            Log.v(TAG, "sendMnsEvent()");
            Log.v(TAG, "msg: " + msg);
            Log.v(TAG, "handle: " + handle);
            Log.v(TAG, "folder: " + folder);
            Log.v(TAG, "old_folder: " + old_folder);
            Log.v(TAG, "msgType: " + msgType);
        }
        int location = -1;
        int masId = -1;

        /* Send the notification, only if it was not initiated
         * by MCE. MEMORY_FULL and MEMORY_AVAILABLE cannot be
         * MCE initiated
         */
        if (msg.equals(MEMORY_AVAILABLE) || msg.equals(MEMORY_FULL)) {
            location = -1;
        } else {
            location = findLocationMceInitiatedOperation(handle);
        }

        if (location == -1) {
            String str = MapUtils.mapEventReportXML(msg, handle, folder, old_folder,
                    msgType);

            if(msgType != null && msgType.equalsIgnoreCase("SMS_GSM") ||
                        msgType.equalsIgnoreCase("MMS")){
                Log.d(TAG, "SMS and MMS notifications sent");
                masId = 0;
            }
            else if(msgType != null && msgType.equalsIgnoreCase("EMAIL")){
                Log.d(TAG, "EMAIL notifications sent");
                masId = 1;
            }

            mSessionHandler.obtainMessage(MNS_SEND_EVENT, masId, -1, str)
                    .sendToTarget();
        } else {
            removeMceInitiatedOperation(location);
        }
    }

    /**
     * Push the message over Obex client session
     */
    private void sendEvent(String str, int masId) {
        if (str != null && (str.length() > 0)) {
            if (V){
                Log.v(TAG, "--------------");
                Log.v(TAG, " CONTENT OF EVENT REPORT FILE: " + str);
            }

            final String FILENAME = "EventReport";
            FileOutputStream fos = null;
            File file = new File(mContext.getFilesDir() + "/" + FILENAME);
            file.delete();
            try {
                fos = mContext.openFileOutput(FILENAME, Context.MODE_PRIVATE);
                fos.write(str.getBytes());
                fos.flush();
                fos.close();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            File fileR = new File(mContext.getFilesDir() + "/" + FILENAME);
            if (fileR.exists() == true) {
                if (V){
                    Log.v(TAG, " Sending event report file ");
                }

                if(masId == 0){
                    Log.d(TAG, "notification for Mas 0::");
                    mSession.sendEvent(fileR, (byte) 0);
                }
                else if(masId == 1){
                    Log.d(TAG, "notification for Mas 1::");
                    mSession.sendEvent(fileR, (byte) 1);
                }
            } else {
                if (V){
                    Log.v(TAG, " ERROR IN CREATING SEND EVENT OBJ FILE");
                }
            }
        } else if (V) {
            Log.v(TAG, "sendEvent(null, " + masId + ")");
        }
    }

    private BroadcastReceiver mStorageStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && mSession != null) {
                final String action = intent.getAction();
                if (Intent.ACTION_DEVICE_STORAGE_LOW.equals(action)) {
                    Log.d(TAG, " Memory Full ");
                    sendMnsEvent(MEMORY_FULL, null, null, null, null);
                } else if (Intent.ACTION_DEVICE_STORAGE_OK.equals(action)) {
                    Log.d(TAG, " Memory Available ");
                    sendMnsEvent(MEMORY_AVAILABLE, null, null, null, null);
                }
            }
        }
    };

    /**
     * Stop the transfer
     */
    public void stop() {
        if (V) Log.v(TAG, "stop");
        if (mSession != null) {
            if (V) Log.v(TAG, "Stop mSession");
            mSession.disconnect();
            mSession = null;
        }
    }

    /**
     * Connect the MNS Obex client to remote server
     */
    private void startObexSession(ObexTransport transport) throws NullPointerException {
        if (V) Log.v(TAG, "Create Client session with transport " + transport.toString());
        mSession = new BluetoothMnsObexSession(mContext, transport);
        new Thread(new Runnable() {
            public void run() {
                mSession.connect();
            }
        }).start();
    }

    private SocketConnectThread mConnectThread;
    /**
     * This thread is used to establish rfcomm connection to
     * remote device
     */
    private class SocketConnectThread extends Thread {
        private final BluetoothDevice device;

        private long timestamp;

        /* create a Rfcomm Socket */
        public SocketConnectThread(BluetoothDevice device) {
            super("Socket Connect Thread");
            this.device = device;
        }

        public void interrupt() {
        }

        @Override
        public void run() {
            timestamp = System.currentTimeMillis();

            BluetoothSocket btSocket = null;
            try {
                btSocket = device.createInsecureRfcommSocketToServiceRecord(
                        BluetoothUuid_ObexMns.getUuid());
                try{
                    btSocket.connect();
                }
                catch(Exception e){
                    Log.d(TAG, "BtSocket Connect error::"+e.toString());
                }
                if (V) Log.v(TAG, "Rfcomm socket connection attempt took "
                        + (System.currentTimeMillis() - timestamp) + " ms");
                ObexTransport transport;
                transport = new BluetoothMnsRfcommTransport(btSocket);
                if (V) Log.v(TAG, "Send transport message " + transport.toString());

                mSessionHandler.obtainMessage(RFCOMM_CONNECTED, transport).sendToTarget();
            } catch (IOException e) {
                Log.e(TAG, "Rfcomm socket connect exception " + e.getMessage());
                markConnectionFailed(btSocket);
                return;
            }
        }

        /**
         * RFCOMM connection failed
         */
        private void markConnectionFailed(BluetoothSocket s) {
            try {
                if (s != null) {
                    s.close();
                }
            } catch (IOException e) {
                if (V) Log.e(TAG, "Error when close socket");
            }
            mSessionHandler.obtainMessage(RFCOMM_ERROR).sendToTarget();
            return;
        }
    }
}
