/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.bluetooth.a2dpsink.mbs;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.browse.MediaBrowser;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Parcelable;
import android.os.ResultReceiver;
import android.service.media.MediaBrowserService;
import android.util.Pair;
import android.util.Log;

import com.android.bluetooth.R;
import com.android.bluetooth.avrcpcontroller.AvrcpControllerService;
import com.android.bluetooth.avrcpcontroller.BrowseTree;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.a2dpsink.A2dpSinkService;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implements the MediaBrowserService interface to AVRCP and A2DP
 *
 * This service provides a means for external applications to access A2DP and AVRCP.
 * The applications are expected to use MediaBrowser (see API) and all the music
 * browsing/playback/metadata can be controlled via MediaBrowser and MediaController.
 *
 * The current behavior of MediaSession exposed by this service is as follows:
 * 1. MediaSession is active (i.e. SystemUI and other overview UIs can see updates) when device is
 * connected and first starts playing. Before it starts playing we do not active the session.
 * 1.1 The session is active throughout the duration of connection.
 * 2. The session is de-activated when the device disconnects. It will be connected again when (1)
 * happens.
 */
public class A2dpMediaBrowserService extends MediaBrowserService {
    private static final String TAG = "A2dpMediaBrowserService";
    private static final String UNKNOWN_BT_AUDIO = "__UNKNOWN_BT_AUDIO__";
    private static final float PLAYBACK_SPEED = 1.0f;

    // Message sent when A2DP device is disconnected.
    private static final int MSG_DEVICE_DISCONNECT = 0;
    // Message sent when A2DP device is connected.
    private static final int MSG_DEVICE_CONNECT = 2;
    // Message sent when we recieve a TRACK update from AVRCP profile over a connected A2DP device.
    private static final int MSG_TRACK = 4;
    // Internal message sent to trigger a AVRCP action.
    private static final int MSG_AVRCP_PASSTHRU = 5;
    // Internal message to trigger a getplaystatus command to remote.
    private static final int MSG_AVRCP_GET_PLAY_STATUS_NATIVE = 6;
    // Message sent when AVRCP browse is connected.
    private static final int MSG_DEVICE_BROWSE_CONNECT = 7;
    // Message sent when AVRCP browse is disconnected.
    private static final int MSG_DEVICE_BROWSE_DISCONNECT = 8;
    // Message sent when folder list is fetched.
    private static final int MSG_FOLDER_LIST = 9;
    // Internal message to trigger playing from media id.
    private static final int MSG_AVRCP_PLAY_FROM_MEDIA_ID = 0xF0;
    // Internal message sent when to issue pass-through command with key state (pressed/released).
    private static final int MSG_AVRCP_PASSTHRU_EXT = 0xF1;
    // Internal message to trigger a search command to remote.
    private static final int MSG_AVRCP_SEARCH = 0xF2;
    // Internal message to add item into NowPlaying
    private static final int MSG_AVRCP_ADD_TO_NOW_PLAYING = 0xF3;
    // Internal message to get item attributes
    private static final int MSG_AVRCP_GET_ITEM_ATTR = 0xF4;
    // Internal message to get element attributes
    private static final int MSG_AVRCP_GET_ELEMENT_ATTR = 0xF5;
    // Internal message to get folder items
    private static final int MSG_AVRCP_GET_FOLDER_ITEM = 0xF6;
    // Internal message to get total number of items
    private static final int MSG_AVRCP_GET_TOTAL_NUM_OF_ITEMS = 0xF7;
    // Internal message to set addressed player
    private static final int MSG_AVRCP_SET_ADDRESSED_PLAYER = 0xF8;
    // Internal message to request for continuing response
    private static final int MSG_AVRCP_REQUEST_CONTINUING_RESPONSE = 0xF9;
    // Internal message to abort continuing response
    private static final int MSG_AVRCP_ABORT_CONTINUING_RESPONSE = 0xFA;
    // Internal message to browse up
    private static final int MSG_AVRCP_BROWSE_UP = 0xFB;
    // Internal message to release AVRCP connection
    private static final int MSG_AVRCP_RELEASE_CONNECTION = 0xFC;

    // Custom actions for PTS testing.
    private String CUSTOM_ACTION_VOL_UP = "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_VOL_UP";
    private String CUSTOM_ACTION_VOL_DN = "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_VOL_DN";
    private String CUSTOM_ACTION_GET_PLAY_STATUS_NATIVE =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_GET_PLAY_STATUS_NATIVE";

    // [TODO] Move the common defintion for customer action into framework
    // +++ Custom action definition for AVRCP controller

    /**
     * Custom action to send pass through command (with key state).
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #KEY_CMD}, {@link #KEY_STATE}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_SEND_PASS_THRU_CMD =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_SEND_PASS_THRU_CMD";
    public static final String KEY_CMD = "cmd";
    public static final String KEY_STATE = "state";

    /**
     * Custom action to search.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     * {@link AvrcpControllerService} will also receive search result.
     * Application can find search list when to browse AVRCP folder.
     *
     * @param Bundle wrapped with {@link #KEY_SEARCH}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_SEARCH =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_SEARCH";
    public static final String KEY_SEARCH = "search";

    /**
     * Custom action to add item into NowPlaying.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     * {@link AvrcpControllerService} will update NowPlaying list if succeed.
     *
     * @param Bundle wrapped with {@link #MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_ADD_TO_NOW_PLAYING =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_ADD_TO_NOW_PLAYING";

    /**
     * Custom action to get item attributes.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.ACTION_TRACK_EVENT} will be broadcast.
     * to notify the item attributes retrieved.
     *
     * @param Bundle wrapped with {@link MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_ITEM_ATTR =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_GET_ITEM_ATTR";
    public static final String KEY_BROWSE_SCOPE = "scope";
    public static final String KEY_ATTRIBUTE_ID = "attribute_id";

    /**
     * Custom action to get element attributes.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.ACTION_TRACK_EVENT} will be broadcast.
     * to notify the item attributes retrieved.
     *
     * @param Bundle wrapped with KEY_ATTRIBUTE_ID
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_ELEMENT_ATTR =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_GET_ELEMENT_ATTR";

    /**
     * Custom action to get folder items.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link AvrcpControllerService.EXTRA_FOLDER_LIST} will be broadcast.
     * to notify the items(player or folder/item) retrieved.
     *
     * @param Bundle wrapped with KEY_BROWSE_SCOPE and KEY_ATTRIBUTE_ID
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_GET_FOLDER_ITEM =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_GET_FOLDER_ITEM";
    public static final String KEY_START = "start";
    public static final String KEY_END = "end";

    /**
     * Custom action to get total number of items.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     *
     * @param Bundle wrapped with {@link #KEY_BROWSE_SCOPE}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS";

    /**
     * Custom action to set addressed player
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * <p>Intent {@link #ACTION_CUSTOM_ACTION_RESULT} will be broadcast to notify the result.
     * {@link AvrcpControllerService} will update NowPlaying list if succeed.
     *
     * @param Bundle wrapped with {@link #KEY_PLAYER_ID}, {@link #MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_SET_ADDRESSED_PLAYER =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_SET_ADDRESSED_PLAYER";
    public static final String KEY_PLAYER_ID = "player_id";

    /**
     * Custom action to request for continuing response packets.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #KEY_PDU_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE";
    public static final String KEY_PDU_ID = "pdu_id";

    /**
     * Custom action to abort continuing response.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #KEY_PDU_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     */
    public static final String CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE";

    /**
     * Custom action to browse up.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link MediaMetadata.METADATA_KEY_MEDIA_ID}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_BROWSE_UP =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_BROWSE_UP";

    /**
     * Custom action to release AVRCP connection.
     *
     * <p>This is called in {@link MediaController.TransportControls.sendCustomAction}
     *
     * <p>This is an asynchronous call: it will return immediately.
     *
     * @param Bundle wrapped with {@link #BluetoothDevice.EXTRA_DEVICE}
     *
     * @return void
     *
     * @See {@link android.media.session.MediaController}
     *      {@link android.media.MediaMetadata}
     *      {@link com.android.bluetooth.avrcpcontroller.AvrcpControllerService}
     */
    public static final String CUSTOM_ACTION_RELEASE_CONNECTION =
        "com.android.bluetooth.a2dpsink.mbs.CUSTOM_ACTION_RELEASE_CONNECTION";

    // + Response for custom action

    /**
     * Intent used to broadcast A2DP/AVRCP custom action result
     *
     * <p>This intent will have 2 extras at least:
     * <ul>
     *   <li> {@link #EXTRA_CUSTOM_ACTION} - custom action command. </li>
     *
     *   <li> {@link #EXTRA_CUSTOM_ACTION_RESULT} - custom action result. </li>
     *
     *   <li> {@link #EXTRA_NUM_OF_ITEMS} - Number of items.
     *         Valid for {@link #CUSTOM_ACTION_SEARCH},
     *         {@link #CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS} </li>
     *
     * </ul>
     *
     * <p>Requires {@link android.Manifest.permission#BLUETOOTH} permission to
     * receive.
     */
    public static final String ACTION_CUSTOM_ACTION_RESULT =
        "com.android.bluetooth.a2dpsink.mbs.action.CUSTOM_ACTION_RESULT";

    public static final String EXTRA_CUSTOM_ACTION =
        "com.android.bluetooth.a2dpsink.mbs.extra.CUSTOM_ACTION";

    public static final String EXTRA_CUSTOM_ACTION_RESULT =
        "com.android.bluetooth.a2dpsink.mbs.extra.CUSTOM_ACTION_RESULT";

    public static final String EXTRA_NUM_OF_ITEMS =
        "com.android.bluetooth.a2dpsink.mbs.extra.NUM_OF_ITEMS";

    // Result code
    public static final int RESULT_SUCCESS = 0;
    public static final int RESULT_ERROR = 1;
    public static final int RESULT_INVALID_PARAMETER = 2;
    public static final int RESULT_NOT_SUPPORTED = 3;
    public static final int RESULT_TIMEOUT = 4;

    // - Response for custom action

    // --- Custom action definition for AVRCP controller

    private MediaSession mSession;
    private MediaMetadata mA2dpMetadata;

    private AvrcpControllerService mAvrcpCtrlSrvc;
    private boolean mBrowseConnected = false;
    private BluetoothDevice mA2dpDevice = null;
    private Handler mAvrcpCommandQueue;
    private final Map<String, Result<List<MediaItem>>> mParentIdToRequestMap = new HashMap<>();
    private static final List<MediaItem> mEmptyList = new ArrayList<MediaItem>();

    // Browsing related structures.
    private List<MediaItem> mNowPlayingList = null;

    private long mTransportControlFlags = PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
            | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_FAST_FORWARD;

    private static final class AvrcpCommandQueueHandler extends Handler {
        WeakReference<A2dpMediaBrowserService> mInst;

        AvrcpCommandQueueHandler(Looper looper, A2dpMediaBrowserService sink) {
            super(looper);
            mInst = new WeakReference<A2dpMediaBrowserService>(sink);
        }

        @Override
        public void handleMessage(Message msg) {
            A2dpMediaBrowserService inst = mInst.get();
            if (inst == null) {
                Log.e(TAG, "Parent class has died; aborting.");
                return;
            }

            switch (msg.what) {
                case MSG_DEVICE_CONNECT:
                    inst.msgDeviceConnect((BluetoothDevice) msg.obj);
                    break;
                case MSG_DEVICE_DISCONNECT:
                    inst.msgDeviceDisconnect((BluetoothDevice) msg.obj);
                    break;
                case MSG_TRACK:
                    Pair<PlaybackState, MediaMetadata> pair =
                        (Pair<PlaybackState, MediaMetadata>) (msg.obj);
                    inst.msgTrack(pair.first, pair.second);
                    break;
                case MSG_AVRCP_PASSTHRU:
                    inst.msgPassThru((int) msg.obj);
                    break;
                case MSG_AVRCP_GET_PLAY_STATUS_NATIVE:
                    inst.msgGetPlayStatusNative();
                    break;
                case MSG_DEVICE_BROWSE_CONNECT:
                    inst.msgDeviceBrowseConnect((BluetoothDevice) msg.obj);
                    break;
                case MSG_DEVICE_BROWSE_DISCONNECT:
                    inst.msgDeviceBrowseDisconnect((BluetoothDevice) msg.obj);
                    break;
                case MSG_FOLDER_LIST:
                    inst.msgFolderList((Intent) msg.obj);
                    break;
                case MSG_AVRCP_PLAY_FROM_MEDIA_ID:
                    inst.msgPlayFromMediaId((String) msg.obj);
                    break;
                case MSG_AVRCP_PASSTHRU_EXT:
                    inst.msgPassThru(msg.arg1, msg.arg2);
                    break;
                case MSG_AVRCP_SEARCH:
                    inst.msgSearch((String) msg.obj);
                    break;
                case MSG_AVRCP_ADD_TO_NOW_PLAYING:
                    inst.msgAddToNowPlaying(msg.arg1, (String) msg.obj);
                    break;
                case MSG_AVRCP_GET_ITEM_ATTR:
                    inst.msgGetItemAttributes((Bundle) msg.obj);
                    break;
                case MSG_AVRCP_GET_ELEMENT_ATTR:
                    inst.msgGetElementAttributes((Bundle) msg.obj);
                    break;
                case MSG_AVRCP_GET_FOLDER_ITEM:
                    inst.msgGetFolderItem((Bundle) msg.obj);
                    break;
                case MSG_AVRCP_GET_TOTAL_NUM_OF_ITEMS:
                    inst.msgGetTotalNumOfItems(msg.arg1);
                    break;
                case MSG_AVRCP_SET_ADDRESSED_PLAYER:
                    inst.msgSetAddressedPlayer(msg.arg1, (String) msg.obj);
                    break;
                case MSG_AVRCP_REQUEST_CONTINUING_RESPONSE:
                    inst.msgRequestContinuingResponse(msg.arg1);
                    break;
                case MSG_AVRCP_ABORT_CONTINUING_RESPONSE:
                    inst.msgAbortContinuingResponse(msg.arg1);
                    break;
                case MSG_AVRCP_BROWSE_UP:
                    inst.msgBrowseUp((String) msg.obj);
                    break;
                case MSG_AVRCP_RELEASE_CONNECTION:
                    inst.msgReleaseConnection((BluetoothDevice) msg.obj);
                    break;
                default:
                    Log.e(TAG, "Message not handled " + msg);
                    break;
            }
        }
    }

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate");
        super.onCreate();

        mSession = new MediaSession(this, TAG);
        setSessionToken(mSession.getSessionToken());
        mSession.setCallback(mSessionCallbacks);
        mSession.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setActive(true);
        mAvrcpCommandQueue = new AvrcpCommandQueueHandler(Looper.getMainLooper(), this);

        refreshInitialPlayingState();

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(AvrcpControllerService.ACTION_BROWSE_CONNECTION_STATE_CHANGED);
        filter.addAction(AvrcpControllerService.ACTION_TRACK_EVENT);
        filter.addAction(AvrcpControllerService.ACTION_FOLDER_LIST);
        registerReceiver(mBtReceiver, filter);

        synchronized (this) {
            mParentIdToRequestMap.clear();
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy");
        mSession.release();
        unregisterReceiver(mBtReceiver);
        super.onDestroy();
    }

    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(BrowseTree.ROOT, null);
    }

    @Override
    public synchronized void onLoadChildren(
            final String parentMediaId, final Result<List<MediaItem>> result) {
        if (mAvrcpCtrlSrvc == null) {
            Log.e(TAG, "AVRCP not yet connected.");
            result.sendResult(mEmptyList);
            return;
        }

        Log.d(TAG, "onLoadChildren parentMediaId=" + parentMediaId);
        if (!mAvrcpCtrlSrvc.getChildren(mA2dpDevice, parentMediaId, 0, 0xff)) {
            result.sendResult(mEmptyList);
            return;
        }

        // Since we are using this thread from a binder thread we should make sure that
        // we synchronize against other such asynchronous calls.
        synchronized (this) {
            mParentIdToRequestMap.put(parentMediaId, result);
        }
        result.detach();
    }

    @Override
    public void onLoadItem(String itemId, Result<MediaBrowser.MediaItem> result) {
    }

    // Media Session Stuff.
    private MediaSession.Callback mSessionCallbacks = new MediaSession.Callback() {
        @Override
        public void onPlay() {
            Log.d(TAG, "onPlay");
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PLAY).sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onPause() {
            Log.d(TAG, "onPause");
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PAUSE).sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onSkipToNext() {
            Log.d(TAG, "onSkipToNext");
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FORWARD)
                .sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onSkipToPrevious() {
            Log.d(TAG, "onSkipToPrevious");

            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_BACKWARD)
                .sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onStop() {
            Log.d(TAG, "onStop");
            mAvrcpCommandQueue.obtainMessage(
                    MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_STOP)
                    .sendToTarget();
        }

        @Override
        public void onRewind() {
            Log.d(TAG, "onRewind");
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_REWIND).sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onFastForward() {
            Log.d(TAG, "onFastForward");
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_FF).sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Log.d(TAG, "onPlayFromMediaId " + mediaId);
            mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PLAY_FROM_MEDIA_ID, mediaId).sendToTarget();
            // TRACK_EVENT should be fired eventually and the UI should be hence updated.
        }

        // Support VOL UP and VOL DOWN events for PTS testing.
        @Override
        public void onCustomAction(String action, Bundle extras) {
            Log.d(TAG, "onCustomAction " + action);
            if (CUSTOM_ACTION_VOL_UP.equals(action)) {
                mAvrcpCommandQueue.obtainMessage(
                    MSG_AVRCP_PASSTHRU,
                    AvrcpControllerService.PASS_THRU_CMD_ID_VOL_UP).sendToTarget();
            } else if (CUSTOM_ACTION_VOL_DN.equals(action)) {
                mAvrcpCommandQueue.obtainMessage(
                    MSG_AVRCP_PASSTHRU,
                    AvrcpControllerService.PASS_THRU_CMD_ID_VOL_DOWN).sendToTarget();
            } else if (CUSTOM_ACTION_GET_PLAY_STATUS_NATIVE.equals(action)) {
                mAvrcpCommandQueue.obtainMessage(
                    MSG_AVRCP_GET_PLAY_STATUS_NATIVE).sendToTarget();
            } else if (CUSTOM_ACTION_SEND_PASS_THRU_CMD.equals(action)) {
                handleCustomActionSendPassThruCmd(extras);
            } else if (CUSTOM_ACTION_SEARCH.equals(action)) {
                handleCustomActionSearch(extras);
            } else if (CUSTOM_ACTION_ADD_TO_NOW_PLAYING.equals(action)) {
                handleCustomActionAddToNowPlaying(extras);
            } else if (CUSTOM_ACTION_GET_ITEM_ATTR.equals(action)) {
                handleCustomActionGetItemAttributes(extras);
            } else if (CUSTOM_ACTION_GET_ELEMENT_ATTR.equals(action)) {
                handleCustomActionGetElementAttributes(extras);
            } else if(CUSTOM_ACTION_GET_FOLDER_ITEM.equals(action)) {
                handleCustomActionGetFolderItems(extras);
            } else if (CUSTOM_ACTION_GET_TOTAL_NUM_OF_ITEMS.equals(action)) {
                handleCustomActionGetTotalNumOfItems(extras);
            } else if (CUSTOM_ACTION_SET_ADDRESSED_PLAYER.equals(action)) {
                handleCustomActionSetAddressedPlayer(extras);
            } else if (CUSTOM_ACTION_REQUEST_CONTINUING_RESPONSE.equals(action)) {
                handleCustomActionRequestContinuingResponse(extras);
            } else if (CUSTOM_ACTION_ABORT_CONTINUING_RESPONSE.equals(action)) {
                handleCustomActionAbortContinuingResponse(extras);
            } else if (CUSTOM_ACTION_BROWSE_UP.equals(action)) {
                handleCustomActionBrowseUp(extras);
            } else if (CUSTOM_ACTION_RELEASE_CONNECTION.equals(action)) {
                handleCustomActionReleaseConnection(extras);
            } else {
                Log.w(TAG, "Custom action " + action + " not supported.");
            }
        }
    };

    private BroadcastReceiver mBtReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "onReceive intent=" + intent);
            String action = intent.getAction();
            BluetoothDevice btDev =
                    (BluetoothDevice) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            int state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1);

            if (BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                Log.d(TAG, "handleConnectionStateChange: newState="
                        + state + " btDev=" + btDev);

                // Connected state will be handled when AVRCP BluetoothProfile gets connected.
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    mAvrcpCommandQueue.obtainMessage(MSG_DEVICE_CONNECT, btDev).sendToTarget();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    // Set the playback state to unconnected.
                    mAvrcpCommandQueue.obtainMessage(MSG_DEVICE_DISCONNECT, btDev).sendToTarget();
                    // If we have been pushing updates via the session then stop sending them since
                    // we are not connected anymore.
                    if (mSession.isActive()) {
                        mSession.setActive(false);
                    }
                }
            } else if (AvrcpControllerService.ACTION_BROWSE_CONNECTION_STATE_CHANGED.equals(
                action)) {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    mAvrcpCommandQueue.obtainMessage(
                        MSG_DEVICE_BROWSE_CONNECT, btDev).sendToTarget();
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    mAvrcpCommandQueue.obtainMessage(
                        MSG_DEVICE_BROWSE_DISCONNECT, btDev).sendToTarget();
                }
            } else if (AvrcpControllerService.ACTION_TRACK_EVENT.equals(action)) {
                PlaybackState pbb =
                        intent.getParcelableExtra(AvrcpControllerService.EXTRA_PLAYBACK);
                MediaMetadata mmd =
                        intent.getParcelableExtra(AvrcpControllerService.EXTRA_METADATA);
                mAvrcpCommandQueue
                        .obtainMessage(MSG_TRACK, new Pair<PlaybackState, MediaMetadata>(pbb, mmd))
                        .sendToTarget();
            } else if (AvrcpControllerService.ACTION_FOLDER_LIST.equals(action)) {
                mAvrcpCommandQueue.obtainMessage(MSG_FOLDER_LIST, intent).sendToTarget();
            }
        }
    };

    private synchronized void msgDeviceConnect(BluetoothDevice device) {
        Log.d(TAG, "msgDeviceConnect");
        // We are connected to a new device via A2DP now.
        mA2dpDevice = device;
        mAvrcpCtrlSrvc = AvrcpControllerService.getAvrcpControllerService();
        if (mAvrcpCtrlSrvc == null) {
            Log.e(TAG, "!!!AVRCP Controller cannot be null");
            return;
        }
        refreshInitialPlayingState();
    }


    // Refresh the UI if we have a connected device and AVRCP is initialized.
    private synchronized void refreshInitialPlayingState() {
        if (mA2dpDevice == null) {
            Log.d(TAG, "device " + mA2dpDevice);
            return;
        }

        List<BluetoothDevice> devices = mAvrcpCtrlSrvc.getConnectedDevices();
        if (devices.size() == 0) {
            Log.w(TAG, "No devices connected yet");
            return;
        }

        if (mA2dpDevice != null && !mA2dpDevice.equals(devices.get(0))) {
            Log.e(TAG, "A2dp device : " + mA2dpDevice + " avrcp device " + devices.get(0));
            return;
        }
        mA2dpDevice = devices.get(0);

        PlaybackState playbackState = mAvrcpCtrlSrvc.getPlaybackState(mA2dpDevice);
        // Add actions required for playback and rebuild the object.
        PlaybackState.Builder pbb = new PlaybackState.Builder(playbackState);
        playbackState = pbb.setActions(mTransportControlFlags).build();

        MediaMetadata mediaMetadata = mAvrcpCtrlSrvc.getMetaData(mA2dpDevice);
        Log.d(TAG, "Media metadata " + mediaMetadata + " playback state " + playbackState);
        mSession.setMetadata(mAvrcpCtrlSrvc.getMetaData(mA2dpDevice));
        mSession.setPlaybackState(playbackState);
    }

    private void msgDeviceDisconnect(BluetoothDevice device) {
        Log.d(TAG, "msgDeviceDisconnect");
        if (mA2dpDevice == null) {
            Log.w(TAG, "Already disconnected - nothing to do here.");
            return;
        } else if (!mA2dpDevice.equals(device)) {
            Log.e(TAG, "Not the right device to disconnect current " +
                mA2dpDevice + " dc " + device);
            return;
        }

        // Unset the session.
        PlaybackState.Builder pbb = new PlaybackState.Builder();
        pbb = pbb.setState(PlaybackState.STATE_ERROR, PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    PLAYBACK_SPEED)
                .setActions(mTransportControlFlags)
                .setErrorMessage(getString(R.string.bluetooth_disconnected));
        mSession.setPlaybackState(pbb.build());

        // Set device to null.
        mA2dpDevice = null;
        mBrowseConnected = false;
        // update playerList.
        notifyChildrenChanged("__ROOT__");
    }

    private void msgTrack(PlaybackState pb, MediaMetadata mmd) {
        Log.d(TAG, "msgTrack: playback: " + pb + " mmd: " + mmd);
        // Log the current track position/content.
        MediaController controller = mSession.getController();
        PlaybackState prevPS = controller.getPlaybackState();
        MediaMetadata prevMM = controller.getMetadata();

        if (prevPS != null) {
            Log.d(TAG, "prevPS " + prevPS);
        }

        if (prevMM != null) {
            String title = prevMM.getString(MediaMetadata.METADATA_KEY_TITLE);
            long trackLen = prevMM.getLong(MediaMetadata.METADATA_KEY_DURATION);
            Log.d(TAG, "prev MM title " + title + " track len " + trackLen);
        }

        if (mmd != null) {
            Log.d(TAG, "msgTrack() mmd " + mmd.getDescription());
            mSession.setMetadata(mmd);
        }

        if (pb != null) {
            Log.d(TAG, "msgTrack() playbackstate " + pb);
            PlaybackState.Builder pbb = new PlaybackState.Builder(pb);
            pb = pbb.setActions(mTransportControlFlags).build();
            mSession.setPlaybackState(pb);

            // If we are now playing then we should start pushing updates via MediaSession so that
            // external UI (such as SystemUI) can show the currently playing music.
            if (pb.getState() == PlaybackState.STATE_PLAYING && !mSession.isActive()) {
                mSession.setActive(true);
            }
        }
    }

    private synchronized void msgPassThru(int cmd) {
        Log.d(TAG, "msgPassThru " + cmd);
        BluetoothDevice device = getConnectedDevice();
        if (device == null) {
            // We should have already disconnected - ignore this message.
            Log.e(TAG, "Already disconnected ignoring.");
            return;
        }

        // Send the pass through.
        mAvrcpCtrlSrvc.sendPassThroughCmd(
            device, cmd, AvrcpControllerService.KEY_STATE_PRESSED);
        mAvrcpCtrlSrvc.sendPassThroughCmd(
            device, cmd, AvrcpControllerService.KEY_STATE_RELEASED);
    }

    private synchronized void msgPassThru(int cmd, int state) {
        Log.d(TAG, "msgPassThru " + cmd + ", key state " + state);
        BluetoothDevice device = getConnectedDevice();
        if (device == null) {
            // We should have already disconnected - ignore this message.
            Log.e(TAG, "Already disconnected ignoring.");
            return;
        }

        // Send pass through command (pressed or released).
        mAvrcpCtrlSrvc.sendPassThroughCmd(device, cmd, state);
    }

    private synchronized void msgGetPlayStatusNative() {
        Log.d(TAG, "msgGetPlayStatusNative");
        BluetoothDevice device = getConnectedDevice();
        if (device == null) {
            // We should have already disconnected - ignore this message.
            Log.e(TAG, "Already disconnected ignoring.");
            return;
        }

        // Ask for a non cached version.
        mAvrcpCtrlSrvc.getPlaybackState(device, false);
    }

    private void msgDeviceBrowseConnect(BluetoothDevice device) {
        Log.d(TAG, "msgDeviceBrowseConnect device " + device);
        // We should already be connected to this device over A2DP.
        if (!device.equals(mA2dpDevice)) {
            Log.e(TAG, "Browse connected over different device a2dp " + mA2dpDevice +
                " browse " + device);
            return;
        }
        mBrowseConnected = true;
        // update playerList
        notifyChildrenChanged("__ROOT__");
    }

    private void msgFolderList(Intent intent) {
        // Parse the folder list for children list and id.
        List<Parcelable> extraParcelableList =
            (ArrayList<Parcelable>) intent.getParcelableArrayListExtra(
                AvrcpControllerService.EXTRA_FOLDER_LIST);
        List<MediaItem> folderList = new ArrayList<MediaItem>();
        for (Parcelable p : extraParcelableList) {
            folderList.add((MediaItem) p);
        }

        String id = intent.getStringExtra(AvrcpControllerService.EXTRA_FOLDER_ID);
        Log.d(TAG, "Parent: " + id + " Folder list: " + folderList);
        synchronized (this) {
            // If we have a result object then we should send the result back
            // to client since it is blocking otherwise we may have gotten more items
            // from remote device, hence let client know to fetch again.
            Result<List<MediaItem>> results = mParentIdToRequestMap.remove(id);
            if (results == null) {
                Log.w(TAG, "Request no longer exists, notifying that children changed.");
                notifyChildrenChanged(id);
            } else {
                results.sendResult(folderList);
            }
        }
    }

    private void msgDeviceBrowseDisconnect(BluetoothDevice device) {
        Log.d(TAG, "msgDeviceBrowseDisconnect device " + device);
        // Disconnect only if mA2dpDevice is non null
        if (!device.equals(mA2dpDevice)) {
            Log.w(TAG, "Browse disconnecting from different device a2dp " + mA2dpDevice +
                " browse " + device);
            return;
        }
        mBrowseConnected = false;
    }

    private synchronized void msgPlayFromMediaId(String mediaId) {
        BluetoothDevice device = getConnectedDevice();

        // Play the item if possible.
        mAvrcpCtrlSrvc.fetchAttrAndPlayItem(device, mediaId);

        // Since we request explicit playback here we should start the updates to UI.
        mAvrcpCtrlSrvc.startAvrcpUpdates();

        // Always send pass through command to play
        Log.d(TAG, "msgPlayFromMediaId mediaId " + mediaId + ", send pass thru cmd to play");
        mAvrcpCommandQueue.obtainMessage(
                MSG_AVRCP_PASSTHRU, AvrcpControllerService.PASS_THRU_CMD_ID_PLAY)
                .sendToTarget();
    }

    private synchronized void msgSearch(String searchQuery) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.search(device, searchQuery);
    }

    private synchronized void msgAddToNowPlaying(int scope, String mediaId) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.addToNowPlaying(device, scope, mediaId);
    }

    private synchronized void msgGetItemAttributes(Bundle extras) {
        BluetoothDevice device = getConnectedDevice();
        int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
        String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);
        mAvrcpCtrlSrvc.getItemAttributes(device, scope, mediaId, attributeId);
    }

    private synchronized void msgGetElementAttributes(Bundle extras) {
        BluetoothDevice device = getConnectedDevice();
        int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);
        mAvrcpCtrlSrvc.getElementAttributes(device, attributeId);
    }

    private synchronized void msgGetFolderItem(Bundle extras) {
        BluetoothDevice device = getConnectedDevice();
        int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
        int start = extras.getInt(KEY_START, 0);
        int end = extras.getInt(KEY_END, 0xFF);
        int [] attributeId = extras.getIntArray(KEY_ATTRIBUTE_ID);
        mAvrcpCtrlSrvc.getFolderItems(device, scope, start, end, attributeId);
    }

    private synchronized void msgGetTotalNumOfItems(int scope) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.getTotalNumOfItems(device, scope);
    }

    private synchronized void msgSetAddressedPlayer(int id, String mediaId) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.setAddressedPlayer(device, id, mediaId);
    }

    private synchronized void msgRequestContinuingResponse(int pduId) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.requestContinuingResponse(device, pduId);
    }

    private synchronized void msgAbortContinuingResponse(int pduId) {
        BluetoothDevice device = getConnectedDevice();
        mAvrcpCtrlSrvc.abortContinuingResponse(device, pduId);
    }

    private synchronized void msgBrowseUp(String mediaId) {
        BluetoothDevice device = getConnectedDevice();
        boolean result = mAvrcpCtrlSrvc.changeFolderPath(device,
                            AvrcpControllerService.FOLDER_NAVIGATION_DIRECTION_UP, null, mediaId);

        if (result) {
            broadCustomActionResult(CUSTOM_ACTION_BROWSE_UP, RESULT_SUCCESS);
        } else {
            broadCustomActionResult(CUSTOM_ACTION_BROWSE_UP, RESULT_ERROR);
        }
    }

    private synchronized void msgReleaseConnection(BluetoothDevice device) {
        mAvrcpCtrlSrvc.releaseConnection(device);
    }

    private BluetoothDevice getConnectedDevice() {
        if (null == mAvrcpCtrlSrvc)
            return null;
        return mAvrcpCtrlSrvc.getConnectedDevice(0);
    }

    void broadCustomActionResult(String cmd, int result) {
        Log.d(TAG, "broadCustomActionResult cmd: " + cmd + ", result: " + result);
        Intent intent = new Intent(ACTION_CUSTOM_ACTION_RESULT);
        intent.putExtra(EXTRA_CUSTOM_ACTION, cmd);
        intent.putExtra(EXTRA_CUSTOM_ACTION_RESULT, result);
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void handleCustomActionSendPassThruCmd(Bundle extras) {
        Log.d(TAG, "handleCustomActionSendPassThruCmd extras: " + extras);
        if (extras == null) {
            return;
        }

        int cmd = extras.getInt(KEY_CMD);
        int state = extras.getInt(KEY_STATE);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_PASSTHRU_EXT, cmd, state).sendToTarget();
    }

    private void handleCustomActionSearch(Bundle extras) {
        Log.d(TAG, "handleCustomActionSearch extras: " + extras);
        if (extras == null) {
            return;
        }

        String searchQuery = extras.getString(KEY_SEARCH);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_SEARCH, searchQuery).sendToTarget();
    }

    private void handleCustomActionAddToNowPlaying(Bundle extras) {
        Log.d(TAG, "handleCustomActionAddToNowPlaying extras: " + extras);
        if (extras == null) {
            return;
        }

        int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
        String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_ADD_TO_NOW_PLAYING, scope, 0, mediaId).sendToTarget();
    }

    private void handleCustomActionGetItemAttributes(Bundle extras) {
        Log.d(TAG, "handleCustomActionGetItemAttributes extras: " + extras);
        if (extras == null) {
            return;
        }

        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_GET_ITEM_ATTR, extras).sendToTarget();
    }

    private void handleCustomActionGetElementAttributes(Bundle extras) {
        Log.d(TAG, "handleCustomActionGetElementAttributes extras" + extras);
        if (extras == null) {
            return;
        }

        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_GET_ELEMENT_ATTR, extras).sendToTarget();
    }

    private void handleCustomActionGetFolderItems(Bundle extras) {
        Log.d(TAG, "handleCustomActionGetFolderItems extras: " + extras);
        if (extras == null) {
            return;
        }

        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_GET_FOLDER_ITEM, extras).sendToTarget();
    }

    private void handleCustomActionGetTotalNumOfItems(Bundle extras) {
        Log.d(TAG, "handleCustomActionGetTotalNumOfItems extras: " + extras);
        if (extras == null) {
            return;
        }

        int scope = extras.getInt(KEY_BROWSE_SCOPE, 0);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_GET_TOTAL_NUM_OF_ITEMS, scope, 0).sendToTarget();
    }

    private void handleCustomActionSetAddressedPlayer(Bundle extras) {
        Log.d(TAG, "handleCustomActionSetAddressedPlayer extras: " + extras);
        if (extras == null) {
            return;
        }

        int id = extras.getInt(KEY_PLAYER_ID, 0);
        String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_SET_ADDRESSED_PLAYER, id, 0, mediaId).sendToTarget();
    }

    private void handleCustomActionRequestContinuingResponse(Bundle extras) {
        Log.d(TAG, "handleCustomActionRequestContinuingResponse extras: " + extras);
        if (extras == null) {
            return;
        }

        int pduId = extras.getInt(KEY_PDU_ID, 0);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_REQUEST_CONTINUING_RESPONSE, pduId, 0).sendToTarget();
    }

    private void handleCustomActionAbortContinuingResponse(Bundle extras) {
        Log.d(TAG, "handleCustomActionAbortContinuingResponse extras: " + extras);
        if (extras == null) {
            return;
        }

        int pduId = extras.getInt(KEY_PDU_ID, 0);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_ABORT_CONTINUING_RESPONSE, pduId, 0).sendToTarget();
    }

    private void handleCustomActionBrowseUp(Bundle extras) {
        Log.d(TAG, "handleCustomActionBrowseUp extras: " + extras);
        if (extras == null) {
            return;
        }

        String mediaId = extras.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_BROWSE_UP, mediaId).sendToTarget();
    }

    private void handleCustomActionReleaseConnection(Bundle extras) {
        Log.d(TAG, "handleCustomActionReleaseConnection extras: " + extras);
        if (extras == null) {
            return;
        }

        BluetoothDevice device = (BluetoothDevice) extras.get(BluetoothDevice.EXTRA_DEVICE);
        mAvrcpCommandQueue.obtainMessage(MSG_AVRCP_RELEASE_CONNECTION, device).sendToTarget();
    }
}
