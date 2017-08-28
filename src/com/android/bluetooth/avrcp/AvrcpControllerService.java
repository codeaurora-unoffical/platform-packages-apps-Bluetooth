/*
 * Copyright (c) 2015, The Linux Foundation. All rights reserved.
 * Not a Contribution
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.bluetooth.avrcp;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAvrcpController;
import android.bluetooth.BluetoothAvrcpPlayerSettings;
import android.bluetooth.BluetoothAvrcpRemoteMediaPlayers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothAvrcpController;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.browse.MediaBrowser;
import android.os.Bundle;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import com.android.bluetooth.avrcp.Avrcp.Metadata;
import com.android.bluetooth.a2dp.A2dpSinkService;
import com.android.bluetooth.btservice.ProfileService;
import com.android.bluetooth.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashMap;
import android.util.Log;
import java.nio.charset.Charset;
import java.nio.ByteBuffer;
/**
 * Provides Bluetooth AVRCP Controller profile, as a service in the Bluetooth application.
 * @hide
 */
public class AvrcpControllerService extends ProfileService {
    private static final boolean DBG = AvrcpControllerConstants.DBG;
    private static final boolean VDBG = AvrcpControllerConstants.VDBG;
    private static final String TAG = "AvrcpControllerService";

/*
 *  Messages handled by mHandler
 */

    RemoteDevice mAvrcpRemoteDevice;
    RemoteFileSystem mRemoteFileSystem;
    RemoteMediaPlayers mRemoteMediaPlayers;
    NowPlaying mRemoteNowPlayingList;
    AppProperties mAppProperties;

    private AvrcpMessageHandler mHandler;
    private static AvrcpControllerService sAvrcpControllerService;
    private static AudioManager mAudioManager;

    private final ArrayList<BluetoothDevice> mConnectedDevices
            = new ArrayList<BluetoothDevice>();

    static {
        classInitNative();
    }

    public AvrcpControllerService() {
        initNative();
    }

    protected String getName() {
        return TAG;
    }

    protected IProfileServiceBinder initBinder() {
        Log.d(TAG," initBinder Called ");
        return new BluetoothAvrcpControllerBinder(this);
    }

    protected boolean start() {
        HandlerThread thread = new HandlerThread("BluetoothAvrcpHandler");
        thread.start();
        Looper looper = thread.getLooper();
        mHandler = new AvrcpMessageHandler(looper);
        setAvrcpControllerService(this);
        mAudioManager = (AudioManager)sAvrcpControllerService.
                                  getSystemService(Context.AUDIO_SERVICE);
        mAppProperties = new AppProperties();
        return true;
    }

    protected void resetRemoteData() {
        try {
            unregisterReceiver(mBroadcastReceiver);
        }
        catch (IllegalArgumentException e) {
            Log.e(TAG,"Receiver not registered");
        }
        if(mAvrcpRemoteDevice != null) {
            mAvrcpRemoteDevice.cleanup();
            mAvrcpRemoteDevice = null;
        }
        if(mRemoteFileSystem != null) {
            mRemoteFileSystem.cleanup();
            mRemoteFileSystem = null;
        }
        if(mRemoteMediaPlayers != null) {
            mRemoteMediaPlayers.cleanup();
            mRemoteMediaPlayers = null;
        }
        if(mRemoteNowPlayingList != null) {
            mRemoteNowPlayingList.cleanup();
            mRemoteNowPlayingList = null;
        }
        if (mAppProperties != null)
            mAppProperties.resetAppProperties();
        AvrcpControllerBrowseService avrcpBrowseService = AvrcpControllerBrowseService.
                getAvrcpControllerBrowseService();
        if(avrcpBrowseService != null) {
            Log.d(TAG," Close Browsing Service");
            avrcpBrowseService.cleanup();
        }
    }
    protected boolean stop() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
        }
        resetRemoteData();
        return true;
    }

    protected boolean cleanup() {
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            Looper looper = mHandler.getLooper();
            if (looper != null) {
                looper.quit();
            }
        }
        resetRemoteData();
        clearAvrcpControllerService();
        cleanupNative();
        return true;
    }

    public static synchronized AvrcpControllerService getAvrcpControllerService(){
        if (sAvrcpControllerService != null && sAvrcpControllerService.isAvailable()) {
            if (DBG) Log.d(TAG, "getAvrcpControllerService(): returning "
                    + sAvrcpControllerService);
            return sAvrcpControllerService;
        }
        if (DBG)  {
            if (sAvrcpControllerService == null) {
                Log.d(TAG, "getAvrcpControllerService(): service is NULL");
            } else if (!(sAvrcpControllerService.isAvailable())) {
                Log.d(TAG,"getAvrcpControllerService(): service is not available");
            }
        }
        return null;
    }

    private static synchronized void setAvrcpControllerService(AvrcpControllerService instance) {
        if (instance != null && instance.isAvailable()) {
            if (DBG) Log.d(TAG, "setAvrcpControllerService(): set to: " + sAvrcpControllerService);
            sAvrcpControllerService = instance;
        } else {
            if (DBG)  {
                if (sAvrcpControllerService == null) {
                    Log.d(TAG, "setAvrcpControllerService(): service not available");
                } else if (!sAvrcpControllerService.isAvailable()) {
                    Log.d(TAG,"setAvrcpControllerService(): service is cleaning up");
                }
            }
        }
    }

    private static synchronized void clearAvrcpControllerService() {
        sAvrcpControllerService = null;
    }

    public List<BluetoothDevice> getConnectedDevices() {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return mConnectedDevices;
    }

    List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        for (int i = 0; i < states.length; i++) {
            if (states[i] == BluetoothProfile.STATE_CONNECTED) {
                return mConnectedDevices;
            }
        }
        return new ArrayList<BluetoothDevice>();
    }

    int getConnectionState(BluetoothDevice device) {
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return (mConnectedDevices.contains(device) ? BluetoothProfile.STATE_CONNECTED
                                                : BluetoothProfile.STATE_DISCONNECTED);
    }

    public void onBipConnected(BluetoothDevice mDevice) {
        Log.v(TAG, " onBipConnected device = " + mDevice);
        if ((mAvrcpRemoteDevice != null) && (mAvrcpRemoteDevice.mBTDevice.equals(mDevice))) {
            mHandler.sendEmptyMessage(AvrcpControllerConstants.MESSAGE_PROCESS_BIP_CONNECTED);
        }
    }
    public void onBipDisconnected() {
        Log.d(TAG," onBipDisconnected");
        mHandler.sendEmptyMessageDelayed(AvrcpControllerConstants.MESSAGE_PROCESS_BIP_DISCONNECTED,
                50);
    }
    public void onThumbNailFetched(String mImageHandle, String mImageLocation) {
        Log.v(TAG, " onBipImageFetched HDL = " + mImageHandle + " Location" + mImageLocation);
        if (mAvrcpRemoteDevice != null) {
            ArrayList<String> bipResults = new ArrayList<String>();
            bipResults.add(mImageHandle);
            bipResults.add(mImageLocation);
            Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                                     MESSAGE_PROCESS_THUMB_NAIL_FETCHED, bipResults);
            mHandler.sendMessage(msg);
        }
    }
    public void onImageFetched(String mImageHandle, String mImageLocation) {
        Log.v(TAG, " onBipImageFetched HDL = " + mImageHandle + " Location " + mImageLocation);
        if (mAvrcpRemoteDevice != null) {
            ArrayList<String> bipResults = new ArrayList<String>();
            bipResults.add(mImageHandle);
            bipResults.add(mImageLocation);
            Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                                     MESSAGE_PROCESS_IMAGE_FETCHED, bipResults);
            mHandler.sendMessage(msg);
        }
    }
    public void sendGroupNavigationCmd(BluetoothDevice device, int keyCode, int keyState) {
        Log.v(TAG, "sendGroupNavigationCmd keyCode: " + keyCode + " keyState: " + keyState);
        if (device == null) {
            throw new NullPointerException("device == null");
        }
        if (!(mConnectedDevices.contains(device))) {
            Log.d(TAG," Device does not match");
            return;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_SEND_GROUP_NAVIGATION_CMD,keyCode, keyState, device);
        mHandler.sendMessage(msg);
    }

    public void sendPassThroughCmd(BluetoothDevice device, int keyCode, int keyState) {
        Log.v(TAG, "sendPassThroughCmd keyCode: " + keyCode + " keyState: " + keyState);
        if (device == null) {
            throw new NullPointerException("device == null");
        }
        if (!(mConnectedDevices.contains(device))) {
            Log.d(TAG," Device does not match");
            return;
        }
        if ((mAvrcpRemoteDevice == null)||
            (mAvrcpRemoteDevice.mRemoteFeatures == AvrcpControllerConstants.BTRC_FEAT_NONE)||
            (mRemoteMediaPlayers == null) ||
            (keyCode == AvrcpControllerConstants.PTS_GET_ELEMENT_ATTRIBUTE_ID)||
            (keyCode == AvrcpControllerConstants.PTS_VFS_CA_ID)||
            (keyCode == AvrcpControllerConstants.PTS_GET_ITEM_VFS_ID)||
            (keyCode == AvrcpControllerConstants.PTS_GET_PLAY_STATUS_ID)||
            (mRemoteMediaPlayers.getAddressedPlayer() == null)){
            Log.d(TAG," Device connected but PlayState not present ");
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_PASS_THROUGH_CMD,
                    keyCode, keyState, device);
            mHandler.sendMessage(msg);
            return;
        }
        boolean sendCommand = false;
        switch(keyCode) {
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_PLAY:
                sendCommand  = (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_STOPPED)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_PAUSED)||
                                (mRemoteMediaPlayers.getPlayStatus() ==
                                       AvrcpControllerConstants.PLAY_STATUS_PLAYING);
                break;
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_PAUSE:
            /*
             * allowing pause command in pause state to handle A2DP Sink Concurrency
             * If call is ongoing and Start is initiated from remote, we will send pause again
             * If acquireFocus fails, we will send Pause again
             * To Stop sending multiple Pause, check in BT-TestApp
             */
                sendCommand  = (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_PLAYING)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_FWD_SEEK)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_STOPPED)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_PAUSED)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_REV_SEEK);
                break;
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_STOP:
                sendCommand  = (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_PLAYING)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_FWD_SEEK)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_REV_SEEK)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_STOPPED)||
                               (mRemoteMediaPlayers.getPlayStatus() == 
                                       AvrcpControllerConstants.PLAY_STATUS_PAUSED);
                break;
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_BACKWARD:
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_FORWARD:
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_FF:
            case BluetoothAvrcpController.PASS_THRU_CMD_ID_REWIND:
                sendCommand = true; // we can send this command in all states
                break;
        }
        if (sendCommand) {
            enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
            Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_PASS_THROUGH_CMD,
                keyCode, keyState, device);
            mHandler.sendMessage(msg);
        }
        else {
            Log.e(TAG," Not in right state, don't send Pass Thru cmd ");
        }
    }

    public MediaMetadata getMetaData(BluetoothDevice device) {
        Log.d(TAG, "getMetaData = ");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        if((mRemoteNowPlayingList != null) && (mRemoteNowPlayingList.getCurrentTrack() != null)) {
            return getCurrentMetaData(AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING, 0);
        }
        else
            return null;
    }
    public PlaybackState getPlaybackState(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getPlayBackState device = "+ device);
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getCurrentPlayBackState();
    }
    public BluetoothAvrcpPlayerSettings getPlayerSettings(BluetoothDevice device) {
        if (DBG) Log.d(TAG, "getPlayerApplicationSetting ");
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        return getCurrentPlayerAppSetting();
    }
    public boolean setPlayerApplicationSetting(BluetoothAvrcpPlayerSettings plAppSetting) {
        if ((mAvrcpRemoteDevice == null)||(mRemoteMediaPlayers == null)) {
            return false;
        }
        enforceCallingOrSelfPermission(BLUETOOTH_PERM, "Need BLUETOOTH permission");
        /*
         * We have to extract values from BluetoothAvrcpPlayerSettings
         */
        int mSettings = plAppSetting.getSettings();
        int numAttributes = 0;
        /* calculate number of attributes in request */
        while(mSettings > 0) {
            numAttributes += ((mSettings & 0x01)!= 0)?1: 0;
            mSettings = mSettings >> 1;
        }
        byte[] attribArray = new byte [2*numAttributes];
        mSettings = plAppSetting.getSettings();
        /*
         * Now we will flatten it <id, val>
         */
        int i = 0;
        if((mSettings & BluetoothAvrcpPlayerSettings.SETTING_EQUALIZER) != 0) {
            attribArray[i++] = AvrcpControllerConstants.ATTRIB_EQUALIZER_STATUS;
            attribArray[i++] = (byte)AvrcpUtils.mapAvrcpPlayerSettingstoBTAttribVal(
                    BluetoothAvrcpPlayerSettings.SETTING_EQUALIZER, plAppSetting.
                    getSettingValue(BluetoothAvrcpPlayerSettings.SETTING_EQUALIZER));
        }
        if((mSettings & BluetoothAvrcpPlayerSettings.SETTING_REPEAT) != 0) {
            attribArray[i++] = AvrcpControllerConstants.ATTRIB_REPEAT_STATUS;
            attribArray[i++] = (byte)AvrcpUtils.mapAvrcpPlayerSettingstoBTAttribVal(
                    BluetoothAvrcpPlayerSettings.SETTING_REPEAT, plAppSetting.
                    getSettingValue(BluetoothAvrcpPlayerSettings.SETTING_REPEAT));
        }
        if((mSettings & BluetoothAvrcpPlayerSettings.SETTING_SHUFFLE) != 0) {
            attribArray[i++] = AvrcpControllerConstants.ATTRIB_SHUFFLE_STATUS;
            attribArray[i++] = (byte)AvrcpUtils.mapAvrcpPlayerSettingstoBTAttribVal(
                    BluetoothAvrcpPlayerSettings.SETTING_SHUFFLE, plAppSetting.
                    getSettingValue(BluetoothAvrcpPlayerSettings.SETTING_SHUFFLE));
        }
        if((mSettings & BluetoothAvrcpPlayerSettings.SETTING_SCAN) != 0) {
            attribArray[i++] = AvrcpControllerConstants.ATTRIB_SCAN_STATUS;
            attribArray[i++] = (byte)AvrcpUtils.mapAvrcpPlayerSettingstoBTAttribVal(
                    BluetoothAvrcpPlayerSettings.SETTING_SCAN, plAppSetting.
                    getSettingValue(BluetoothAvrcpPlayerSettings.SETTING_SCAN));
        }
        boolean isSettingSupported = mRemoteMediaPlayers.getAddressedPlayer().
                                   isPlayerAppSettingSupported((byte)numAttributes, attribArray);
        if(isSettingSupported) {
            ByteBuffer bb = ByteBuffer.wrap(attribArray, 0, (2*numAttributes));
             Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_SEND_SET_CURRENT_PLAYER_APPLICATION_SETTINGS, numAttributes, 0, bb);
            mHandler.sendMessage(msg);
        }
        return isSettingSupported;
    }

    public void startFetchingAlbumArt(String mimeType, int height, int width, long maxSize) {
        Log.d(TAG," startFetchingAlbumArt mimeType " + mimeType + " pixel " + height + " * "
                + width + " maxSize: " + maxSize);
        if (mAppProperties == null) return;
        mAppProperties.isCoverArtRequested = true;
        mAppProperties.mSupportedCoverArtMimetype = mimeType;
        mAppProperties.mSupportedCoverArtWidth = width;
        mAppProperties.mSupportedCovertArtHeight = height;
        mAppProperties.mSupportedCoverArtMaxSize = maxSize;
        mHandler.sendEmptyMessage(AvrcpControllerConstants.MESSAGE_CONNECT_BIP);
    }
    public boolean SetBrowsedPlayer(int playerId) {
        Log.d(TAG," SetBrowsedPlayer id = " + playerId);
        if (!mRemoteMediaPlayers.isPlayerBrowsable(playerId))
            return false;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SET_BROWSED_PLAYER,
                playerId, 0);
        mHandler.sendMessage(msg);
        return true;
    }
    public boolean SetAddressedPlayer(int playerId) {
        Log.d(TAG," SetAddressedPlayer id = " + playerId);
        if(!mRemoteMediaPlayers.isPlayerInList(playerId))
            return false;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SET_ADDRESSED_PLAYER,
                playerId, 0);
        mHandler.sendMessage(msg);
        return true;
    }
    public BluetoothAvrcpRemoteMediaPlayers GetRemoteAvailableMediaPlayer() {
        if (mRemoteMediaPlayers == null) return null;
        return mRemoteMediaPlayers.getTotalMediaPlayerList();
    }
    public BluetoothAvrcpRemoteMediaPlayers GetAddressedPlayer() {
        if (mRemoteMediaPlayers == null) return null;
        return mRemoteMediaPlayers.getAddressedMediaPlayerList();
    }
    public BluetoothAvrcpRemoteMediaPlayers GetBrowsedPlayer() {
        if (mRemoteMediaPlayers == null) return null;
        return mRemoteMediaPlayers.getBrowsedMediaPlayerList();
    }
    public int getSupportedFeatures(BluetoothDevice device) {
        if (mAvrcpRemoteDevice == null) return AvrcpControllerConstants.BTRC_FEAT_NONE;
        return mAvrcpRemoteDevice.mRemoteFeatures;
    }
    /* returns folderDepth from root folder
    *  0 - in case we don't have to send change path
    *  STATUS_INVALID in case of error
     *  +ve value of folderdepth in case changepath required*/
    public int browseToRoot() {
        Log.d(TAG," browseToRoot ");
        if (GetBrowsedPlayer() == null){
            Log.d(TAG," browseToRoot called when Browsed player is not set");
            return AvrcpControllerConstants.STATUS_INVALID;
        }
        if (mRemoteFileSystem == null) return AvrcpControllerConstants.STATUS_INVALID;
        mRemoteFileSystem.clearSearchList();
        mRemoteNowPlayingList.clearNowPlayingList();
        mAvrcpRemoteDevice.setCurrentScope(AvrcpControllerConstants.AVRCP_SCOPE_VFS);
        if (mRemoteFileSystem.mFolderStack.isEmpty()) {
            FolderStackInfo stackInfo = new FolderStackInfo();
            stackInfo.folderUid = AvrcpControllerConstants.AVRCP_BROWSE_ROOT_FOLDER;
            stackInfo.numItems = AvrcpControllerConstants.DEFAULT_BROWSE_END_INDEX;
            mRemoteFileSystem.mFolderStack.add(stackInfo);
            return 0;
        }
        /* We have to perform changePath to Root */
        int changePathDepth = -1 * (mRemoteFileSystem.mFolderStack.size() - 1);
        if (changePathDepth != 0) {
            Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH,
                    changePathDepth, 0);
            mHandler.sendMessage(msg);
        }
        return (-1 * changePathDepth);
    }
    public boolean loadFolderUp(final String folderId) {
        if ((mAvrcpRemoteDevice == null) || (mAvrcpRemoteDevice.getCurrentScope() !=
                AvrcpControllerConstants.AVRCP_SCOPE_VFS)) {
            Log.e(TAG, " loadFolderUp, wrong currScope = " + mAvrcpRemoteDevice.getCurrentScope());
            return false;
        }
        Message msg;
        /* check in folderStack */
        int folderDepth = mRemoteFileSystem.isIdInFolderStack(folderId);
        Log.d(TAG," Folder Up depth = " + folderDepth);
        if (folderDepth == 0) {
            return false;
        }
        if (folderDepth != AvrcpControllerConstants.DEFAULT_LIST_INDEX) {
            folderDepth = (-1) * folderDepth;
            msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH,
                    folderDepth, 0);
            mHandler.sendMessage(msg);
            mRemoteFileSystem.clearVFSList();
            msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST, 0, 0);
            mHandler.sendMessage(msg);
            return true;
        }
        return false;
    }
    public boolean loadFolderDown(final String parentMediaId) {
        if ((mAvrcpRemoteDevice == null) || (mAvrcpRemoteDevice.getCurrentScope() !=
                AvrcpControllerConstants.AVRCP_SCOPE_VFS)) {
            Log.e(TAG, "loadFolderDown, wrong currScope = " + mAvrcpRemoteDevice.getCurrentScope());
            return false;
        }
        Message msg;
        /* check in VFS List, its given first priority */
        long uid = mRemoteFileSystem.isIdInVFSFolderList(parentMediaId);
        if (uid != AvrcpControllerConstants.DEFAULT_FOLDER_ID) {
            /* Found ID in VFS list, from here we can go only 1 step down */
            FolderStackInfo stackInfo = new FolderStackInfo();
            stackInfo.folderUid = parentMediaId;
            stackInfo.uid = uid;
            mRemoteFileSystem.mFolderStack.add(stackInfo);
            msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH, 1, 0);
            mHandler.sendMessage(msg);
            mRemoteFileSystem.clearVFSList();
            msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST, 0, 0);
            mHandler.sendMessage(msg);
            return true;
        }
        return false;
    }
    public boolean refreshCurrentFolder(final String folderId) {
        if ((mAvrcpRemoteDevice == null)||(folderId == null)) {
            Log.e(TAG, " refreshCurrentFolder, remoteDevice null " );
            return false;
        }
        /* Fetch first element of folderStack */
        Message msg;
        if (folderId.equals(AvrcpControllerConstants.AVRCP_BROWSE_ROOT_FOLDER)) {
            mAvrcpRemoteDevice.setCurrentScope(AvrcpControllerConstants.AVRCP_SCOPE_VFS);
            if (mRemoteFileSystem.mFolderStack.isEmpty()) {
                /* This will be the case after doing SetAddressedPlayer */
                if (browseToRoot() == AvrcpControllerConstants.STATUS_INVALID) {
                    return false;
                }
            }
        }
        String topStackItemUid = mRemoteFileSystem.mFolderStack.
                get(mRemoteFileSystem.mFolderStack.size() - 1).folderUid;
        Log.d(TAG," refreshCurrentFolder parentId = " + folderId + " topStackId = "
                + topStackItemUid);
        /* If request is for same element as top of stack, just fetch the items */
        if (folderId.equals(topStackItemUid)) {
            mRemoteFileSystem.clearVFSList();
            msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST, 0,
                    AvrcpControllerConstants.DEFAULT_BROWSE_END_INDEX);
            mHandler.sendMessage(msg);
            return true;
        }
        else if(folderId.equals(AvrcpControllerConstants.AVRCP_BROWSE_ROOT_FOLDER)) {
            /* This might be the case of BrowseToRoot */
            return loadFolderUp(folderId);
        }
        return false;
    }
    public void addToNowPlayingList(long id) {
        if ((mAvrcpRemoteDevice == null) || (mRemoteMediaPlayers == null)) return;
        PlayerInfo mAdrPlayer = mRemoteMediaPlayers.getAddressedPlayer();
        if(mAdrPlayer == null) return;
        if (!mRemoteMediaPlayers.isPlayerSupportAddToNowPlaying(mAdrPlayer.mPlayerId)) {
            Log.e(TAG," remote player does not support Add to Now Playing");
            return;
        }
        Log.d(TAG," addtoNowPlayingList id = " + id + "scope = " + mAvrcpRemoteDevice.getCurrentScope());
        Bundle data = new Bundle();
        data.putLong("id", id);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_ADD_TO_NPL, 0,0);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }
    public void fetchNowPlayingList() {
        Log.d(TAG, "fetchNowPLayingList ");
        if ((mAvrcpRemoteDevice == null) || (mRemoteMediaPlayers == null)) return;
        PlayerInfo mAdrPlayer = mRemoteMediaPlayers.getAddressedPlayer();
        if(mAdrPlayer == null) return;
        if (!mRemoteMediaPlayers.isPlayerSupportNowPlaying(mAdrPlayer.mPlayerId)) {
            Log.e(TAG," remote player does not support Now Playing id = " + mAdrPlayer.mPlayerId);
            completeBrowseFolderRequest(AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST);
            return;
        }
        mRemoteFileSystem.clearVFSList();
        mRemoteFileSystem.clearSearchList();
        mRemoteNowPlayingList.clearNowPlayingList();
        mAvrcpRemoteDevice.setCurrentScope(AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING);
        mHandler.sendEmptyMessage(AvrcpControllerConstants.
                MESSAGE_FETCH_NOW_PLAYING_LIST);
    }
    public void skipToQueItem(long id) {
        if (mAvrcpRemoteDevice == null) return;
        Log.d(TAG," skipToQueItem id " + id + " scope " + mAvrcpRemoteDevice.getCurrentScope());
        Message msg;
        Bundle data = new Bundle();
        int currScope = mAvrcpRemoteDevice.getCurrentScope();
        if (currScope == AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING) {
            if (mRemoteNowPlayingList.getTrackFromId(id) != null) {
                msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_PLAY_ITEM,
                        currScope,0);
                data.putLong("id", id);
                msg.setData(data);mHandler.sendMessage(msg);
            }
        }
        if (currScope == AvrcpControllerConstants.AVRCP_SCOPE_SEARCH) {
            if (mRemoteFileSystem.isIdInSearchList(id)) {
                msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_PLAY_ITEM,
                        currScope,0);
                data.putLong("id", id);
                msg.setData(data);mHandler.sendMessage(msg);
            }
        }
    }
    public void playFromMediaId(String id) {
        Log.d(TAG," playFromMediaId id = " + id);
        Message msg;
        Bundle data = new Bundle();
        int currScope = mAvrcpRemoteDevice.getCurrentScope();
        if (currScope == AvrcpControllerConstants.AVRCP_SCOPE_VFS) {
            long uid = mRemoteFileSystem.isIdInVFSMediaList(id);
            if (uid != AvrcpControllerConstants.DEFAULT_FOLDER_ID) {
                msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_SEND_PLAY_ITEM,
                        currScope,0);
                data.putLong("id", uid);
                msg.setData(data);mHandler.sendMessage(msg);
            }
        }
    }
    public void fetchBySearchString(String searchQuery) {
        Log.d(TAG, " playFromSearch = " + searchQuery);
        if ((mAvrcpRemoteDevice == null) || (mRemoteMediaPlayers == null)) return;
        PlayerInfo mBrowsedPlayer = mRemoteMediaPlayers.getBrowsedPlayer();
        if(mBrowsedPlayer == null) return;
        if (!mRemoteMediaPlayers.isPlayerSearchable(mBrowsedPlayer.mPlayerId)) {
            Log.e(TAG," remote player does not support search");
            completeBrowseFolderRequest(AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST);
            return;
        }
        mRemoteFileSystem.clearVFSList();
        mRemoteFileSystem.clearSearchList();
        mRemoteNowPlayingList.clearNowPlayingList();
        mAvrcpRemoteDevice.setCurrentScope(AvrcpControllerConstants.AVRCP_SCOPE_SEARCH);
        int numQueCommands = 0;
        if(mAppProperties.isCoverArtRequested && mAvrcpRemoteDevice.isCoverArtSupported()
                &&!mAvrcpRemoteDevice.isBipConnected()) {
            Log.d(TAG," BIP Not connected, que command");
            mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                    MESSAGE_BROWSE_CONNECT_OBEX, AvrcpControllerConstants.
                    AVRCP_SCOPE_SEARCH, null);
            numQueCommands++;
        }
        Bundle data = new Bundle();
        data.putString("pattern", searchQuery);
        mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                MESSAGE_GET_NUM_ITEMS_SEARCH, mAvrcpRemoteDevice.getCurrentScope(), data);
        numQueCommands ++;
        Bundle searchListData = new Bundle();
        searchListData.putInt("start", 0);
        searchListData.putInt("end", AvrcpControllerConstants.DEFAULT_BROWSE_END_INDEX);
        mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                MESSAGE_FETCH_SEARCH_LIST, mAvrcpRemoteDevice.getCurrentScope(), searchListData);
        numQueCommands++;
        checkAndProcessBrowsingCommand(numQueCommands);
    }
    //Binder object: Must be static class or memory leak may occur
    private static class BluetoothAvrcpControllerBinder extends IBluetoothAvrcpController.Stub
        implements IProfileServiceBinder {
        private AvrcpControllerService mService;

        private AvrcpControllerService getService() {
            if (!Utils.checkCaller()) {
                Log.w(TAG,"AVRCP call not allowed for non-active user");
                return null;
            }

            if (mService != null && mService.isAvailable()) {
                return mService;
            }
            return null;
        }

        BluetoothAvrcpControllerBinder(AvrcpControllerService svc) {
            mService = svc;
        }

        public boolean cleanup()  {
            mService = null;
            return true;
        }

        @Override
        public List<BluetoothDevice> getConnectedDevices() {
            AvrcpControllerService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getConnectedDevices();
        }

        @Override
        public List<BluetoothDevice> getDevicesMatchingConnectionStates(int[] states) {
            AvrcpControllerService service = getService();
            if (service == null) return new ArrayList<BluetoothDevice>(0);
            return service.getDevicesMatchingConnectionStates(states);
        }

        @Override
        public int getConnectionState(BluetoothDevice device) {
            AvrcpControllerService service = getService();
            if (service == null) return BluetoothProfile.STATE_DISCONNECTED;
            return service.getConnectionState(device);
        }

        @Override
        public void sendPassThroughCmd(BluetoothDevice device, int keyCode, int keyState) {
            Log.v(TAG,"Binder Call: sendPassThroughCmd");
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.sendPassThroughCmd(device, keyCode, keyState);
        }
        public void sendGroupNavigationCmd(BluetoothDevice device, int keyCode, int keyState) {
            Log.v(TAG,"Binder Call: sendGroupNavigationCmd");
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.sendGroupNavigationCmd(device, keyCode, keyState);
        }

        public MediaMetadata getMetadata(BluetoothDevice device) {
            Log.v(TAG,"Binder Call: getMetaData ");
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.getMetaData(device);
        }
        public PlaybackState getPlaybackState(BluetoothDevice device) {
            Log.v(TAG,"Binder Call: getPlaybackState");
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.getPlaybackState(device);
        }
        public BluetoothAvrcpPlayerSettings getPlayerSettings(BluetoothDevice device) {
            Log.v(TAG,"Binder Call: getPlayerApplicationSetting ");
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.getPlayerSettings(device);
        }
        public boolean setPlayerApplicationSetting(BluetoothAvrcpPlayerSettings plAppSetting) {
            Log.v(TAG,"Binder Call: setPlayerApplicationSetting " );
            AvrcpControllerService service = getService();
            if (service == null) return false;
            return service.setPlayerApplicationSetting(plAppSetting);
        }
        public void startFetchingAlbumArt(String mimeType, int height, int width, long maxSize) {
            AvrcpControllerService service = getService();
            if (service == null) return;
            service.startFetchingAlbumArt(mimeType, height, width, maxSize);
        }
        public boolean SetBrowsedPlayer(int playerId) {
            AvrcpControllerService service = getService();
            if (service == null) return false;
            return service.SetBrowsedPlayer(playerId);
        }
        public boolean SetAddressedPlayer(int playerId) {
            AvrcpControllerService service = getService();
            if (service == null) return false;
            return service.SetAddressedPlayer(playerId);
        }
        public BluetoothAvrcpRemoteMediaPlayers GetRemoteAvailableMediaPlayer() {
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.GetRemoteAvailableMediaPlayer();
        }
        public BluetoothAvrcpRemoteMediaPlayers GetAddressedPlayer() {
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.GetAddressedPlayer();
        }
        public BluetoothAvrcpRemoteMediaPlayers GetBrowsedPlayer() {
            AvrcpControllerService service = getService();
            if (service == null) return null;
            return service.GetBrowsedPlayer();
        }
        public int getSupportedFeatures(BluetoothDevice mDevice) {
            AvrcpControllerService service = getService();
            if (service == null) return AvrcpControllerConstants.BTRC_FEAT_NONE;
            return service.getSupportedFeatures(mDevice);
        }
    };

    private String utf8ToString(byte[] input)
    {
        Charset UTF8_CHARSET = Charset.forName("UTF-8");
        return new String(input,UTF8_CHARSET);
    }
    private int asciiToInt(int len, byte[] array)
    {
        return Integer.parseInt(utf8ToString(array));
    }
    private BluetoothAvrcpPlayerSettings getCurrentPlayerAppSetting() {
        if((mRemoteMediaPlayers == null) || (mRemoteMediaPlayers.getAddressedPlayer() == null))
            return null;
        return mRemoteMediaPlayers.getAddressedPlayer().getSupportedPlayerAppSetting();
    }
    private PlaybackState getCurrentPlayBackState() {
        if ((mRemoteMediaPlayers == null) || (mRemoteMediaPlayers.getAddressedPlayer() == null)) {
            return new PlaybackState.Builder().setState(PlaybackState.STATE_ERROR,
                                                        PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                                                        0).build();
        }
        return AvrcpUtils.mapBtPlayStatustoPlayBackState(
                mRemoteMediaPlayers.getAddressedPlayer().mPlayStatus,
                mRemoteMediaPlayers.getAddressedPlayer().mPlayTime);
    }
    private MediaMetadata getCurrentMetaData(int scope, long trackId) {
        /* if scope is now playing */
        if(scope == AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING) {
            if((mRemoteNowPlayingList == null) || (mRemoteNowPlayingList.
                                                           getTrackFromId(trackId) == null))
                return null;
            TrackInfo mNowPlayingTrack = mRemoteNowPlayingList.getTrackFromId(trackId);
            return AvrcpUtils.getMediaMetaData(mNowPlayingTrack);
        }
        /* if scope is now playing */
        else if(scope == AvrcpControllerConstants.AVRCP_SCOPE_VFS) {
            /* TODO for browsing */
        }
        return null;
    }
    private void broadcastThumbNailUpdate() {
        Log.d(TAG," broadcastThumbNailUpdate  = ");
        int index = 0;
        int scope = mAvrcpRemoteDevice.getCurrentScope();
        long [] mediaIdList =  null; String [] thumbNailList;
        if (scope == AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING) {
            mediaIdList = new long[mRemoteNowPlayingList.mNowPlayingList.size() - 1];
            thumbNailList = new String[mRemoteNowPlayingList.mNowPlayingList.size() - 1];
            for (TrackInfo mTrackinfo : mRemoteNowPlayingList.mNowPlayingList) {
                if (mTrackinfo.mItemUid == 0) // 0 represents currently playing track.
                    continue;
                mediaIdList[index] = mTrackinfo.mItemUid;
                thumbNailList[index++] = mTrackinfo.mThumbNailLocation;
            }
        }
        else if(scope == AvrcpControllerConstants.AVRCP_SCOPE_SEARCH) {
            mediaIdList = new long[mRemoteFileSystem.mSearchList.size()];
            thumbNailList = new String[mRemoteFileSystem.mSearchList.size()];
            for (TrackInfo mTrackinfo : mRemoteFileSystem.mSearchList) {
                mediaIdList[index] = mTrackinfo.mItemUid;
                thumbNailList[index++] = mTrackinfo.mThumbNailLocation;
            }
        }
        else if(scope == AvrcpControllerConstants.AVRCP_SCOPE_VFS) {
            mediaIdList = new long[mRemoteFileSystem.mMediaItemList.size()];
            thumbNailList = new String[mRemoteFileSystem.mMediaItemList.size()];
            for (TrackInfo mTrackinfo : mRemoteFileSystem.mMediaItemList) {
                mediaIdList[index] = mTrackinfo.mItemUid;
                thumbNailList[index++] = mTrackinfo.mThumbNailLocation;
            }
        }
        else
            return;
        Intent intent = new Intent(BluetoothAvrcpController.AVRCP_BROWSE_THUMBNAILS_UPDATE);
        intent.putExtra(BluetoothAvrcpController.EXTRA_MEDIA_IDS, mediaIdList);
        intent.putExtra(BluetoothAvrcpController.EXTRA_THUMBNAILS, thumbNailList);
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }
    private void broadcastMetaDataChanged(MediaMetadata mMetaData) {
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_TRACK_EVENT);
        intent.putExtra(BluetoothAvrcpController.EXTRA_METADATA, mMetaData);
        if(DBG) Log.d(TAG," broadcastMetaDataChanged = " +
                                                   AvrcpUtils.displayMetaData(mMetaData));
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        AvrcpControllerBrowseService avrcpBrowseService = AvrcpControllerBrowseService.
                getAvrcpControllerBrowseService();
        if(avrcpBrowseService != null) {
            avrcpBrowseService.updateMetaData(mMetaData);
        }
    }
    private void broadcastPlayBackStateChanged(PlaybackState mState) {
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_TRACK_EVENT);
        intent.putExtra(BluetoothAvrcpController.EXTRA_PLAYBACK, mState);
        if(DBG) Log.d(TAG," broadcastPlayBackStateChanged = " + mState.toString());
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
        AvrcpControllerBrowseService avrcpBrowseService = AvrcpControllerBrowseService.
                getAvrcpControllerBrowseService();
        if(avrcpBrowseService != null) {
            avrcpBrowseService.updatePlayBackState(mState);
        }
    }
    private void broadcastPlayerAppSettingChanged(BluetoothAvrcpPlayerSettings mPlAppSetting) {
        Intent intent = new Intent(BluetoothAvrcpController.ACTION_PLAYER_SETTING);
        intent.putExtra(BluetoothAvrcpController.EXTRA_PLAYER_SETTING, mPlAppSetting);
        if(DBG) Log.d(TAG," broadcastPlayerAppSettingChanged = " +
                AvrcpUtils.displayBluetoothAvrcpSettings(mPlAppSetting));
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastRemoteMediaPlayerList(BluetoothAvrcpRemoteMediaPlayers mMediaPlayers) {
        Intent intent = new Intent(BluetoothAvrcpController.AVAILABLE_MEDIA_PLAYERS_UPDATE);
        intent.putExtra(BluetoothAvrcpController.EXTRA_REMOTE_PLAYERS, mMediaPlayers);
        if(DBG) Log.d(TAG, " broadcastRemoteMediaPlayerList ");
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }

    private void broadcastBrowsedPlayerChanged(boolean status,
                                            BluetoothAvrcpRemoteMediaPlayers mBrowsedMediaPlayer) {
        Intent intent = new Intent(BluetoothAvrcpController.BROWSED_PLAYER_CHANGED);
        intent.putExtra(BluetoothAvrcpController.EXTRA_REMOTE_PLAYERS, mBrowsedMediaPlayer);
        intent.putExtra(BluetoothAvrcpController.EXTRA_PLAYER_UPDATE_STATUS, status);
        if(DBG) Log.d(TAG, " broadcastBrowsedPlayerChanged ");
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }
    private void broadcastAddressedPlayerChanged(boolean status,
                                          BluetoothAvrcpRemoteMediaPlayers mAddressedMediaPlayer) {
        Intent intent = new Intent(BluetoothAvrcpController.ADDRESSED_PLAYER_CHANGED);
        intent.putExtra(BluetoothAvrcpController.EXTRA_REMOTE_PLAYERS, mAddressedMediaPlayer);
        intent.putExtra(BluetoothAvrcpController.EXTRA_PLAYER_UPDATE_STATUS, status);
        if(DBG) Log.d(TAG, " broadcastAddressedPlayerChanged ");
        sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
    }
    /** Handles Avrcp messages. */
    private final class AvrcpMessageHandler extends Handler {
        private AvrcpMessageHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            int playerId = AvrcpControllerConstants.DEFAULT_PLAYER_ID;
            boolean retVal = false; Bundle data = null; int ret = 0; int cmdId = 0;
            int cmdScope;
            Log.d(TAG," HandleMessage: "+ AvrcpControllerConstants.dumpMessageString(msg.what) +
                  " Remote Connected " + !mConnectedDevices.isEmpty());
            if (mAvrcpRemoteDevice != null) {
                Log.d(TAG," Current Scope " + mAvrcpRemoteDevice.getCurrentScope());
            }
            switch (msg.what) {
            case AvrcpControllerConstants.MESSAGE_SEND_PASS_THROUGH_CMD:
                BluetoothDevice device = (BluetoothDevice)msg.obj;
                if (msg.arg1 == AvrcpControllerConstants.PTS_GET_ELEMENT_ATTRIBUTE_ID) {
                    boolean isGetItemEnabled = SystemProperties.getBoolean
                                               ("persist.bt.avrcp_ct.sendItem", false);
                    if (isGetItemEnabled) {
                        byte numAttr = 3;// we have to send uidCOunter and scope
                        int[] attributes = new int[numAttr];
                        attributes[0] = mAvrcpRemoteDevice.muidCounter;
                        attributes[1] = mAvrcpRemoteDevice.getCurrentScope();
                        attributes[2] = 0x00000009;
                        if (mAvrcpRemoteDevice.getCurrentScope() ==
                                AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING) {
                            if (mRemoteNowPlayingList.mNowPlayingList.size() > 1)
                                attributes[2] = (int) mRemoteNowPlayingList.mNowPlayingList.
                                        get(1).mItemUid;
                            else
                                attributes[2] = 0x00000022;
                        }
                        if (mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                                                                                AVRCP_SCOPE_VFS) {
                            if(!mRemoteFileSystem.mMediaItemList.isEmpty()) {
                                attributes[2] = (int) mRemoteFileSystem.mMediaItemList.
                                        get(0).mItemUid;
                            }
                            else {
                                attributes[2] = 0x00000009;
                            }
                        }
                        if (mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                                AVRCP_SCOPE_SEARCH) {
                            if(!mRemoteFileSystem.mSearchList.isEmpty()) {
                                attributes[2] = (int) mRemoteFileSystem.mSearchList.
                                        get(0).mItemUid;
                            }
                            else {
                                attributes[2] = 0x00000022;
                            }
                        }
                        getElementAttributesNative(getByteAddress(device), numAttr, attributes);
                    }
                    else {
                        byte numAttribs = 2;
                        int[] attribs = new int[numAttribs];
                        attribs[0] = AvrcpControllerConstants.MEDIA_ATTRIBUTE_TITLE;
                        attribs[1] = AvrcpControllerConstants.MEDIA_ATTRIBUTE_COVER_ART_HANDLE;
                        getElementAttributesNative(getByteAddress(device), numAttribs, attribs);
                    }
                    break;
                }
                if (msg.arg1 == AvrcpControllerConstants.PTS_VFS_CA_ID) {
                    // Hack for PTS
                    byte numAttribs = 1;
                    byte[] attribs = new byte[numAttribs];
                    attribs[0] = AvrcpControllerConstants.MEDIA_ATTRIBUTE_COVER_ART_HANDLE;
                    browseFolderNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                            (byte) mAvrcpRemoteDevice.getCurrentScope(), 0, 1, numAttribs, attribs);
                    break;
                }
                sendPassThroughCommandNative(getByteAddress(device), msg.arg1, msg.arg2);
                break;
            case AvrcpControllerConstants.MESSAGE_SEND_GROUP_NAVIGATION_CMD:
                BluetoothDevice peerDevice = (BluetoothDevice)msg.obj;
                sendGroupNavigationCommandNative(getByteAddress(peerDevice), msg.arg1, msg.arg2);
                break;
            case AvrcpControllerConstants.MESSAGE_SEND_SET_CURRENT_PLAYER_APPLICATION_SETTINGS:
                byte numAttributes = (byte)msg.arg1;
                ByteBuffer bbRsp = (ByteBuffer)msg.obj;
                byte[] attributeIds = new byte [numAttributes];
                byte[] attributeVals = new byte [numAttributes];
                for(int i = 0; (bbRsp.hasRemaining())&&(i < numAttributes); i++) {
                    attributeIds[i] = bbRsp.get();
                    attributeVals[i] = bbRsp.get();
                }
                setPlayerApplicationSettingValuesNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        numAttributes, attributeIds, attributeVals);
                break;
            case AvrcpControllerConstants.MESSAGE_CONNECT_BIP:
                /*
                 * If Avrcp Connection is UP and we have psm, try connecting BIP connection.
                 */
                if ((mAvrcpRemoteDevice == null) || (!mAvrcpRemoteDevice.isCoverArtSupported()))
                    return;
                if (!mAvrcpRemoteDevice.isBipConnected()) {
                    if (mAvrcpRemoteDevice.mBipL2capPsm != AvrcpControllerConstants.DEFAULT_PSM) {
                        mAvrcpRemoteDevice.connectBip(mAvrcpRemoteDevice.mBipL2capPsm);
                    }
                }
                else {
                    /*
                     * BIP is already connected in this case. Fetch Image
                     */
                    mHandler.sendEmptyMessage(AvrcpControllerConstants.
                                                        MESSAGE_PROCESS_BIP_CONNECTED);
                }
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST:
                if (mAvrcpRemoteDevice.getCurrentScope() != AvrcpControllerConstants.
                        AVRCP_SCOPE_NOW_PLAYING) {
                    Log.e(TAG," Scope not proper currscope " + mAvrcpRemoteDevice.
                            getCurrentScope());
                    break;
                }
                manageFetchListCommands(msg.what, AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING);
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST:
                if (mAvrcpRemoteDevice.getCurrentScope() != AvrcpControllerConstants.
                        AVRCP_SCOPE_VFS) {
                    Log.e(TAG," Scope not proper currscope " + mAvrcpRemoteDevice.
                            getCurrentScope());
                    break;
                }
                data = new Bundle();
                data.putInt("start", 0);data.putInt("end", msg.arg2);
                mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(msg.what,
                        mAvrcpRemoteDevice.getCurrentScope(), data);
                checkAndProcessBrowsingCommand(1);
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST:
                if (mAvrcpRemoteDevice.getCurrentScope() != AvrcpControllerConstants.
                        AVRCP_SCOPE_SEARCH) {
                    Log.e(TAG," Scope not proper currscope " + mAvrcpRemoteDevice.
                            getCurrentScope());
                    break;
                }
                data = new Bundle();
                data.putInt("start", msg.arg1);
                data.putInt("end", msg.arg2);
                mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                        MESSAGE_FETCH_SEARCH_LIST, mAvrcpRemoteDevice.getCurrentScope(), data);
                checkAndProcessBrowsingCommand(1);
                break;
            case AvrcpControllerConstants.MESSAGE_ADD_TO_NPL:
                addToNowPlayingListNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        (byte)mAvrcpRemoteDevice.getCurrentScope(), msg.getData().getLong("id"),
                        mAvrcpRemoteDevice.muidCounter);
                break;
            case AvrcpControllerConstants.MESSAGE_SET_ADDRESSED_PLAYER:
            case AvrcpControllerConstants.MESSAGE_SET_BROWSED_PLAYER:
                data = new Bundle();
                data.putInt("playerId", msg.arg1);
                mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(msg.what, mAvrcpRemoteDevice.
                        getCurrentScope(), data);
                checkAndProcessBrowsingCommand(1);
                break;
            case AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH:
                data = new Bundle();
                data.putInt("delta",msg.arg1);
                mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(msg.what, mAvrcpRemoteDevice.
                        getCurrentScope(), data);
                checkAndProcessBrowsingCommand(1);
                break;
            case AvrcpControllerConstants.MESSAGE_SEND_PLAY_ITEM:
                data = msg.getData();
                playItemNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice), (byte)msg.arg1,
                        mAvrcpRemoteDevice.muidCounter, data.getLong("id"));
                break;

            case AvrcpControllerConstants.MESSAGE_PROCESS_CONNECTION_CHANGE:
                int newState = msg.arg1;
                int oldState = msg.arg2;
                BluetoothDevice rtDevice =  (BluetoothDevice)msg.obj;
                if ((newState == BluetoothProfile.STATE_CONNECTED) &&
                    (oldState == BluetoothProfile.STATE_DISCONNECTED)) {
                    /* We create RemoteDevice and MediaPlayerList here 
                     * Now playing list after RC features
                     */
                    if(mAvrcpRemoteDevice == null){
                        mAvrcpRemoteDevice =  new RemoteDevice(rtDevice);
                        /* Remote will have a player irrespective of AVRCP Version
                         * Create a Default player, we will add entries in case Browsing
                         * is supported by remote
                         */
                        if(mRemoteMediaPlayers == null) {
                            mRemoteMediaPlayers = new RemoteMediaPlayers(mAvrcpRemoteDevice);
                            /* At this stage we create a default player */
                            PlayerInfo mPlayer = new PlayerInfo();
                            mPlayer.mPlayerId = AvrcpControllerConstants.DEFAULT_PLAYER_ID;
                            mRemoteMediaPlayers.addPlayer(mPlayer);
                            mRemoteMediaPlayers.setAddressedPlayer(mPlayer);
                        }
                    }
                }
                else if ((newState == BluetoothProfile.STATE_DISCONNECTED) &&
                        (oldState == BluetoothProfile.STATE_CONNECTED)) /* connection down */
                {
                    resetRemoteData();
                    mHandler.removeCallbacksAndMessages(null);
                }
                /*
                 * Send intent now
                 */
                Intent intent = new Intent(BluetoothAvrcpController.ACTION_CONNECTION_STATE_CHANGED);
                intent.putExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, oldState);
                intent.putExtra(BluetoothProfile.EXTRA_STATE, newState);
                intent.putExtra(BluetoothDevice.EXTRA_DEVICE, rtDevice);
//              intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
                sendBroadcast(intent, ProfileService.BLUETOOTH_PERM);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_RC_FEATURES:
                if(mAvrcpRemoteDevice == null)
                    break;
                Log.d(TAG," rc features " + msg.arg1);
                mAvrcpRemoteDevice.mRemoteFeatures = msg.arg1;
                /* in case of AVRCP version < 1.3, no need to add track info */
                if(mAvrcpRemoteDevice.isMetaDataSupported()) {
                    if(mRemoteNowPlayingList == null)
                        mRemoteNowPlayingList = new NowPlaying(mAvrcpRemoteDevice);
                    TrackInfo mTrack = new TrackInfo();
                    /* First element of NowPlayingList will be current Track 
                     * for 1.3 this will be the only song
                     * for >= 1.4, others songs will have non-zero UID
                     */
                    mTrack.mItemUid = 0;
                    mRemoteNowPlayingList.addTrack(mTrack);
                    mRemoteNowPlayingList.setCurrTrack(mTrack);
                    if (mAvrcpRemoteDevice.isBrowsingSupported()) {
                        Log.d(TAG," Remote supports Browsing");
                        mRemoteFileSystem = new RemoteFileSystem(mAvrcpRemoteDevice);
                    }
                    if(mAvrcpRemoteDevice.isCoverArtSupported()) {
                        mAvrcpRemoteDevice.mBipL2capPsm = msg.arg2;
                        Log.d(TAG," Remote supports coverart psm " + mAvrcpRemoteDevice.mBipL2capPsm);
                        /*
                         * We schedule BIP connect here, to get coverart Handle of current
                         * track in case App launched later connect at later point of time.
                         */
                         mAvrcpRemoteDevice.connectBip(mAvrcpRemoteDevice.mBipL2capPsm);
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SET_ABS_VOL_CMD:
                mAvrcpRemoteDevice.mAbsVolNotificationState = 
                                         AvrcpControllerConstants.DEFER_VOLUME_CHANGE_RSP;
                setAbsVolume(msg.arg1, msg.arg2);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION:
                /* start BroadcastReceiver now */
                IntentFilter filter = new IntentFilter(AudioManager.VOLUME_CHANGED_ACTION);
                mAvrcpRemoteDevice.mNotificationLabel = msg.arg1;
                mAvrcpRemoteDevice.mAbsVolNotificationState =
                        AvrcpControllerConstants.SEND_VOLUME_CHANGE_RSP;
                registerReceiver(mBroadcastReceiver, filter);
                int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int currIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int percentageVol = ((currIndex* AvrcpControllerConstants.ABS_VOL_BASE)/maxVolume);
                Log.d(TAG," Sending Interim Response = "+ percentageVol + " label " + msg.arg1);
                sendRegisterAbsVolRspNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        (byte)AvrcpControllerConstants.NOTIFICATION_RSP_TYPE_INTERIM, percentageVol,
                        mAvrcpRemoteDevice.mNotificationLabel);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_TRACK_CHANGED:
                if(mRemoteNowPlayingList != null) {
                    mRemoteNowPlayingList.updateCurrentTrack((TrackInfo)msg.obj);
                    broadcastMetaDataChanged(AvrcpUtils.getMediaMetaData
                            (mRemoteNowPlayingList.getCurrentTrack()));
                    if ((!mAppProperties.isCoverArtRequested) ||
                            (!mAvrcpRemoteDevice.isCoverArtSupported()))
                        break;
                    if ((mAvrcpRemoteDevice != null) && (!mAvrcpRemoteDevice.isBipConnected())) {
                        Log.e(TAG," BIP Not connected");
                    }
                    else {
                        mRemoteNowPlayingList.fetchCoverArtImage(mAppProperties.
                                mSupportedCoverArtMimetype,
                                mAppProperties.mSupportedCovertArtHeight,
                                mAppProperties.mSupportedCoverArtWidth,
                                mAppProperties.mSupportedCoverArtMaxSize);
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_PLAY_POS_CHANGED:
                if(mRemoteMediaPlayers != null) {
                    data = msg.getData();
                    mRemoteMediaPlayers.getAddressedPlayer().mPlayTime =
                                                   data.getInt("curposition");
                    mRemoteMediaPlayers.getAddressedPlayer().mPlayStatus =
                                                        data.getByte("Playstatus");
                    broadcastPlayBackStateChanged(AvrcpUtils.mapBtPlayStatustoPlayBackState
                            (mRemoteMediaPlayers.getAddressedPlayer().mPlayStatus,
                                    mRemoteMediaPlayers.getAddressedPlayer().mPlayTime));
                }
                if(mRemoteNowPlayingList != null) {
                    mRemoteNowPlayingList.getCurrentTrack().mTrackLen =
                                                     data.getInt("songlen");
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_PLAY_STATUS_CHANGED:
                if(mRemoteMediaPlayers != null) {
                    mRemoteMediaPlayers.getAddressedPlayer().mPlayStatus = (byte)msg.arg1;
                    broadcastPlayBackStateChanged(AvrcpUtils.mapBtPlayStatustoPlayBackState
                            (mRemoteMediaPlayers.getAddressedPlayer().mPlayStatus, 
                                    mRemoteMediaPlayers.getAddressedPlayer().mPlayTime));
                    if(mRemoteMediaPlayers.getPlayStatus() == AvrcpControllerConstants.
                                                                        PLAY_STATUS_PLAYING) {
                        A2dpSinkService a2dpSinkService = A2dpSinkService.getA2dpSinkService();
                        if((a2dpSinkService != null)&&(!mConnectedDevices.isEmpty())) {
                            Log.d(TAG," State = PLAYING, inform A2DP SINK");
                            a2dpSinkService.informAvrcpStatePlaying(mConnectedDevices.get(0));
                        }
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SUPPORTED_PLAYER_APP_SETTING:
                if(mRemoteMediaPlayers != null)
                    mRemoteMediaPlayers.getAddressedPlayer().
                                           setSupportedPlayerAppSetting((ByteBuffer)msg.obj);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_PLAYER_APP_SETTING_CHANGED:
                if(mRemoteMediaPlayers != null) {
                    mRemoteMediaPlayers.getAddressedPlayer().
                                           updatePlayerAppSetting((ByteBuffer)msg.obj);
                    broadcastPlayerAppSettingChanged(getCurrentPlayerAppSetting());
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_BIP_CONNECTED:
                // BIP is connected
                if (!mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty() &&
                        (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) ==
                        AvrcpControllerConstants.MESSAGE_BROWSE_CONNECT_OBEX)) {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                if (mRemoteNowPlayingList != null) {
                    ret = mRemoteNowPlayingList.fetchCoverArtImage(mAppProperties.
                            mSupportedCoverArtMimetype,
                            mAppProperties.mSupportedCovertArtHeight,
                            mAppProperties.mSupportedCoverArtWidth,
                            mAppProperties.mSupportedCoverArtMaxSize);
                    if (ret == AvrcpControllerConstants.ERROR_BIP_HANDLE_NOT_VALID) {
                        /* Send a getElementAttrib command again */
                        getElementAttributesNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                                (byte)0, null);
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_BIP_DISCONNECTED:
                /*
                 * BIP Disconnected, We have to clear off imageLocation and
                 * ImageHandles.
                 */
                if (mRemoteNowPlayingList != null) {
                    mRemoteNowPlayingList.clearCoverArtData();
                }
                if ((mAvrcpRemoteDevice == null)||(mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty()))
                    break;
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) ==
                        AvrcpControllerConstants.MESSAGE_BROWSE_DISCONNECT_OBEX) {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_THUMB_NAIL_FETCHED:
                ArrayList<String> bipResults = (ArrayList<String>)msg.obj;
                String mImageHndl = bipResults.get(0);
                String mImageLoc = bipResults.get(1);
                switch(mAvrcpRemoteDevice.getCurrentScope()) {
                    case AvrcpControllerConstants.AVRCP_SCOPE_VFS:
                        if(mRemoteFileSystem != null) {
                            mRemoteFileSystem.updateVFSThumbNail(mImageHndl, mImageLoc);
                            ret = mRemoteFileSystem.fetchVFSThumbNail();
                        }
                        break;
                    case AvrcpControllerConstants.AVRCP_SCOPE_SEARCH:
                        if(mRemoteFileSystem != null) {
                            mRemoteFileSystem.updateSearchListThumbNail(mImageHndl, mImageLoc);
                            ret = mRemoteFileSystem.fetchSearchThumbNail();
                        }
                        break;
                    case AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING:
                        if (mRemoteNowPlayingList != null) {
                            mRemoteNowPlayingList.updateThumbNail(mImageHndl, mImageLoc);
                            ret = mRemoteNowPlayingList.fetchThumbNail();
                        }
                        break;
                    default:
                        ret = AvrcpControllerConstants.ERROR_BIP_FETCH_LIST_EMPTY;
                        break;
                }

                if (ret == AvrcpControllerConstants.ALL_THUMBNAILS_FETCHED) {
                    mRemoteNowPlayingList.fetchCoverArtImage(mAppProperties.
                            mSupportedCoverArtMimetype, mAppProperties.
                            mSupportedCovertArtHeight, mAppProperties.
                            mSupportedCoverArtWidth, mAppProperties.mSupportedCoverArtMaxSize);
                    if (mAppProperties.isCoverArtRequested) {
                        broadcastThumbNailUpdate();
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_IMAGE_FETCHED:
                ArrayList<String> bipImageResults = (ArrayList<String>)msg.obj;
                String mImageHandle = bipImageResults.get(0);
                String mImageLocation = bipImageResults.get(1);
                if (mRemoteNowPlayingList != null) {
                    mRemoteNowPlayingList.updateImage(mImageHandle, mImageLocation);
                    /* It is possible that during last image fetch request, BIP fetch operation
                     * was in progress, we need to checkif CA Handle has changed.
                     */
                    ret = mRemoteNowPlayingList.fetchCoverArtImage(mAppProperties.
                                    mSupportedCoverArtMimetype,
                            mAppProperties.mSupportedCovertArtHeight,
                            mAppProperties.mSupportedCoverArtWidth,
                            mAppProperties.mSupportedCoverArtMaxSize);
                    if (ret == AvrcpControllerConstants.IMAGE_FETCHED) {
                        if (mAppProperties.isCoverArtRequested)
                            broadcastMetaDataChanged(AvrcpUtils.getMediaMetaData
                                    (mRemoteNowPlayingList.getCurrentTrack()));
                        /* Image is fetched, try fetching Thumbnail */
                        if (mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                                AVRCP_SCOPE_NOW_PLAYING) {
                            /* If there is only 1 element (currentTrack), do not fetch thumbnail */
                            mRemoteNowPlayingList.fetchThumbNail();
                        }
                        if ((mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                                AVRCP_SCOPE_VFS) && (mRemoteFileSystem != null)) {
                            mRemoteFileSystem.fetchVFSThumbNail();
                        }
                        if ((mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                                AVRCP_SCOPE_SEARCH) && (mRemoteFileSystem != null)) {
                            mRemoteFileSystem.fetchSearchThumbNail();
                        }
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SUPPORTED_PLAYER:
                if (mAvrcpRemoteDevice == null)
                    break;
                mAvrcpRemoteDevice.muidCounter = msg.arg1;
                byte mNumPlayers = (byte)msg.arg2;
                if (mRemoteMediaPlayers != null) {
                    mRemoteMediaPlayers.updatePlayerList(mNumPlayers, msg.getData());
                    broadcastRemoteMediaPlayerList(mRemoteMediaPlayers.
                            getTotalMediaPlayerList());
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_GET_TOTAL_NUM_ITEMS:
                /* Check pending command */
                mAvrcpRemoteDevice.muidCounter =  msg.arg1;
                retVal = mAvrcpRemoteDevice.mPendingBrwCmds.checkAndClearCommand(
                        AvrcpControllerConstants.MESSAGE_GET_TOTAL_NUM_ITEMS,
                        mAvrcpRemoteDevice.getCurrentScope());
                cmdId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);
                if ((retVal)&&(cmdId == AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST)) {
                    data = new Bundle();
                    data.putInt("start", 0);data.putInt("end", (msg.arg2 - 1));
                    mAvrcpRemoteDevice.mPendingBrwCmds.updateCommand(0,
                            AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST,
                            mAvrcpRemoteDevice.getCurrentScope(), data);
                }
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_PENDING_BROWSING_COMMANDS:
                sendBrowsingCommands();
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_BROWSE_FOLDER_RESPONSE:
                data = msg.getData();
                byte status = data.getByte("status");
                if (status == AvrcpControllerConstants.AVRC_RET_NO_ERROR)
                    mAvrcpRemoteDevice.muidCounter = data.getInt("uidCounter");
                if(mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty()) {
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                /* If we get this, check topmost pending command */
                int commandId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);

                if (!((commandId == AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST) ||
                    (commandId == AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST) ||
                    (commandId == AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST))) {
                    Log.e(TAG," Unexpected Command cmdID " + commandId);
                    // something wrong happened, drop this Callback.
                    break;
                }
                /* If there is diff in scope */
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdScope(0) !=
                        mAvrcpRemoteDevice.getCurrentScope()) {
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                /* If we get Status out of bound or no avail players, we are done, remove this
                 * command and move to next command from pending browsing commands
                 */
                Log.d(TAG, " Last command = "+ commandId + " status = " + status);
                if ((status == AvrcpControllerConstants.AVRC_RET_BAD_RANGE) ||
                    (status == AvrcpControllerConstants.AVRC_RET_NO_AVAL_PLAYER) ||
                    (status == AvrcpControllerConstants.AVRC_RET_STS_TIMEOUT)) {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    completeBrowseFolderRequest(commandId);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                int currentSize = 0;
                switch (commandId) {
                    case AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST:
                        currentSize = mRemoteNowPlayingList.updateNowPlayingList(data);
                        break;
                    case AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST:
                        currentSize = mRemoteFileSystem.updateSearchList(data);
                        break;
                    case AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST:
                        currentSize = mRemoteFileSystem.updateVFSList(data);
                        break;
                }
                Bundle cmdData = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0);
                int newStartIndex = cmdData.getInt("start") + 1;
                newStartIndex = Math.max(newStartIndex, currentSize);
                cmdData.putInt("start", newStartIndex);
                mAvrcpRemoteDevice.mPendingBrwCmds.updateCommand(0, commandId,
                        mAvrcpRemoteDevice.getCurrentScope(), cmdData);
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SET_BROWSED_PLAYER_RSP:
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) != AvrcpControllerConstants.
                        MESSAGE_SET_BROWSED_PLAYER) {
                    Log.e(TAG," UnExpected Command, process pending ones");
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                playerId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0).getInt("playerId");
                boolean browsedPlayerSet = false;
                mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                if (msg.arg1 == AvrcpControllerConstants.AVRC_RET_NO_ERROR) {
                    mAvrcpRemoteDevice.muidCounter  = msg.arg2;
                    browsedPlayerSet = mRemoteMediaPlayers.setBrowsedPlayerFromList(playerId);
                    Log.d(TAG," browsedPlayerSet = " + browsedPlayerSet + " playerId = " + playerId);
                    if (browsedPlayerSet) {
                        mRemoteFileSystem.clearVFSList();mRemoteFileSystem.clearFolderStack();
                        mRemoteFileSystem.clearSearchList();
                    }
                    broadcastBrowsedPlayerChanged(browsedPlayerSet, mRemoteMediaPlayers.
                            getBrowsedMediaPlayerList());
                }
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SET_ADDRESSED_PLAYER_RSP:
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) != AvrcpControllerConstants.
                        MESSAGE_SET_ADDRESSED_PLAYER) {
                    Log.e(TAG," UnExpected Command, process pending ones");
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                playerId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0).getInt("playerId");
                boolean addressedPlayerSet = false;
                if (msg.arg1 == AvrcpControllerConstants.AVRC_RET_NO_ERROR) {
                    addressedPlayerSet = mRemoteMediaPlayers.setAddressedPlayerFromList(playerId);
                    Log.d(TAG," addressed player set " + addressedPlayerSet);
                    if (addressedPlayerSet) {
                        mRemoteNowPlayingList.clearNowPlayingList();
                    }
                    else {
                        /*
                         * This is the case of playerId not found in list. setDefault player ID
                         * as addressed player.
                         */
                        addressedPlayerSet = mRemoteMediaPlayers.setAddressedPlayerFromList
                                (AvrcpControllerConstants.DEFAULT_PLAYER_ID);
                        Log.d(TAG," Default player id Set " + addressedPlayerSet);
                    }
                    broadcastAddressedPlayerChanged(addressedPlayerSet, mRemoteMediaPlayers.
                            getAddressedMediaPlayerList());
                }
                mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED:
                if (mRemoteNowPlayingList == null) break;
                mAvrcpRemoteDevice.muidCounter  = msg.arg2;
                if (mRemoteMediaPlayers.setAddressedPlayerFromList((char)msg.arg1)) {
                    mRemoteNowPlayingList.clearNowPlayingList();
                    int cmdIndex= mAvrcpRemoteDevice.mPendingBrwCmds.getCmdIndex(
                            AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST,
                            mAvrcpRemoteDevice.getCurrentScope());
                    if (cmdIndex != AvrcpControllerConstants.DEFAULT_LIST_INDEX) {
                        Log.w(TAG," NPL was Queued, deque it and complete request ");
                        mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(cmdIndex);
                        completeBrowseFolderRequest(AvrcpControllerConstants.
                                MESSAGE_FETCH_NOW_PLAYING_LIST);
                    }
                    broadcastAddressedPlayerChanged(true, mRemoteMediaPlayers.
                            getAddressedMediaPlayerList());
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_PATH_CHANGED:
                int returnStatus = msg.arg1;
                if ((returnStatus == AvrcpControllerConstants.AVRC_RET_BAD_DIR) ||
                   (returnStatus == AvrcpControllerConstants.AVRC_RET_NOT_EXIST) ||
                   (returnStatus == AvrcpControllerConstants.AVRC_RET_NO_AVAL_PLAYER) ||
                   (returnStatus == AvrcpControllerConstants.AVRC_RET_NOT_DIR) ||
                   (returnStatus == AvrcpControllerConstants.AVRC_RET_STS_TIMEOUT)) {
                    Log.e(TAG, " Change Path Error = " + returnStatus);
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    int pendingCommandId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);
                    Log.d(TAG, "Pending command = " + pendingCommandId);
                    if (pendingCommandId == AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST) {
                        mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                        /* App subscribe for browsing data, complete it with empty list */
                        completeBrowseFolderRequest(AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST);
                    }
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                /* path change is successful */
                cmdId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);
                cmdScope = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdScope(0);
                if ((cmdId != AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH) || (mAvrcpRemoteDevice.
                        getCurrentScope() != AvrcpControllerConstants.AVRCP_SCOPE_VFS)) {
                    Log.e(TAG," Unexpected Command ");
                    mAvrcpRemoteDevice.mPendingBrwCmds.checkAndClearCommand(cmdId, cmdScope);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                data = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0);
                int folderDepth = data.getInt("delta");
                Log.d(TAG, " folderDepth = " + folderDepth + " numItems = " + msg.arg2);
                if (folderDepth > 0) {
                    /* new entry is already there in folderStack */
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    resetBipForBrowsing();
                }
                else if (folderDepth < 0) {
                    folderDepth = folderDepth + 1;
                    mRemoteFileSystem.mFolderStack.remove(mRemoteFileSystem.mFolderStack.size() - 1);
                    if (folderDepth == 0) {
                        mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                        resetBipForBrowsing();
                        AvrcpControllerBrowseService avrcpBrService = AvrcpControllerBrowseService.
                                getAvrcpControllerBrowseService();
                        String topmostFolderUid = mRemoteFileSystem.mFolderStack.get(mRemoteFileSystem.mFolderStack.size() - 1).folderUid;
                        Log.d(TAG," current top folder " + topmostFolderUid);
                        if(topmostFolderUid == AvrcpControllerConstants.AVRCP_BROWSE_ROOT_FOLDER) {
                            avrcpBrService.notifyResetRootDone();
                        }
                    }
                    else {
                        data.putInt("delta", folderDepth);
                        mAvrcpRemoteDevice.mPendingBrwCmds.updateCommand(0, cmdId, cmdScope, data);
                    }
                }
                mRemoteFileSystem.mFolderStack.get(mRemoteFileSystem.mFolderStack.size() - 1)
                        .numItems = msg.arg2;
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_SEARCH_ITEM_RSP:
                mAvrcpRemoteDevice.muidCounter = msg.getData().getInt("uidCounter");
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) !=
                        AvrcpControllerConstants.MESSAGE_GET_NUM_ITEMS_SEARCH) {
                    Log.e(TAG," Unexpected Command ");
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                if (msg.arg1 != AvrcpControllerConstants.AVRC_RET_NO_ERROR) {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0) == AvrcpControllerConstants.
                                                                      MESSAGE_FETCH_SEARCH_LIST) {
                    mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0).putInt("end", msg.arg2);
                }
                checkAndProcessBrowsingCommand(0);
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_NPL_LIST_UPDATE:
                AvrcpControllerBrowseService avrcpBrowseService;
                if (mAvrcpRemoteDevice.getCurrentScope() == AvrcpControllerConstants.
                        AVRCP_SCOPE_NOW_PLAYING) {
                    avrcpBrowseService = AvrcpControllerBrowseService.
                            getAvrcpControllerBrowseService();
                    if(avrcpBrowseService != null) {
                        handleListChangeUpdate(AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING,
                                avrcpBrowseService);
                        avrcpBrowseService.refreshListCallback();
                    }
                }
                break;
            case AvrcpControllerConstants.MESSAGE_PROCESS_UIDS_CHANGED:
                if (mRemoteFileSystem != null) {
                    mRemoteFileSystem.clearSearchList();
                    mRemoteFileSystem.clearVFSList();
                }
                if(mRemoteNowPlayingList != null) {
                    mRemoteNowPlayingList.clearNowPlayingList();
                }
                mAvrcpRemoteDevice.disconnectBip();
                avrcpBrowseService = AvrcpControllerBrowseService.
                        getAvrcpControllerBrowseService();
                if(avrcpBrowseService != null) {
                    handleListChangeUpdate(mAvrcpRemoteDevice.getCurrentScope(), avrcpBrowseService);
                    avrcpBrowseService.refreshListCallback();
                }
                break;
            }
        }
    }
    /* this api handles changes in UIDS/NPL update while we are fetching lists */
    private void handleListChangeUpdate (int scope, AvrcpControllerBrowseService browseService) {
        Log.d(TAG,"handleListChangeUpdates for scope "+ scope);
        boolean isFetchInProgress = false;
        if ((mAvrcpRemoteDevice == null) || (mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty())) {
            Log.d(TAG," list empty nothing to do");
            return;
        }
        int commandId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);
        Log.d(TAG," topmost command = " + commandId);
        int numcommandsToRemove = 0; int index = 0; int checkForCommand = 0;
        switch(scope) {
            case AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING:
                if ((commandId == AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST) ||
                   (commandId == AvrcpControllerConstants.MESSAGE_GET_TOTAL_NUM_ITEMS)) {
                    checkForCommand = AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST;
                }
                break;
            case AvrcpControllerConstants.AVRCP_SCOPE_SEARCH:
                if ((commandId == AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST) ||
                        (commandId == AvrcpControllerConstants.MESSAGE_GET_NUM_ITEMS_SEARCH)) {
                    checkForCommand = AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST;
                }
                break;
            case AvrcpControllerConstants.AVRCP_SCOPE_VFS:
                if (commandId == AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST) {
                    checkForCommand = AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST;
                } else if (commandId == AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH) {
                    checkForCommand = AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH;
                }
                break;
        }
        Log.d(TAG," check for Command = " + checkForCommand);
        if (checkForCommand == 0) return;
        // iterate through browsing command and find first instance of fetch command
        for (index = 0; index < mAvrcpRemoteDevice.mPendingBrwCmds.getListSize();
                                                      index ++ ) {
            if (mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(index) != checkForCommand) {
                numcommandsToRemove++ ;continue;
            }
            else {
                 if (checkForCommand != AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH) {
                     //we found the check
                     numcommandsToRemove ++;
                 }
                break;
            }
        }
        Log.d(TAG," numCommandsToRemove = " + numcommandsToRemove);
        if(numcommandsToRemove == 0) return;
        for (index = 0; index < numcommandsToRemove; index++) {
            mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
        }
        switch (scope) {
            case AvrcpControllerConstants.AVRCP_SCOPE_NOW_PLAYING:
                browseService.updateNowPlayingList(mRemoteNowPlayingList);
                break;
            case AvrcpControllerConstants.AVRCP_SCOPE_SEARCH:
                browseService.updateSearchList(mRemoteFileSystem);
                break;
            case AvrcpControllerConstants.AVRCP_SCOPE_VFS:
                browseService.updateVFSList(mRemoteFileSystem);
                break;
        }
        checkAndProcessBrowsingCommand(0);
    }
    private void manageFetchListCommands( int commandId, int scope) {
        Log.d(TAG," manageFetchLIstCommands cmd = "+ commandId + "scope = "+ scope );
        int numCheckForCommands = 0;
        if (mAvrcpRemoteDevice.isCoverArtSupported()) {
                    /* Que Get Total Num Items and put NPL request in pending command */
            if(mAppProperties.isCoverArtRequested && mAvrcpRemoteDevice.isCoverArtSupported()
                    &&!mAvrcpRemoteDevice.isBipConnected()) {
                Log.d(TAG," BIP not connected ");
                mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                        MESSAGE_BROWSE_CONNECT_OBEX, AvrcpControllerConstants.
                        AVRCP_SCOPE_NOW_PLAYING, null);
                numCheckForCommands++;
            }
            mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(AvrcpControllerConstants.
                            MESSAGE_GET_TOTAL_NUM_ITEMS, mAvrcpRemoteDevice.getCurrentScope(),
                    null);
            numCheckForCommands++;
                    /* Que FetchNPLCommand */
            mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(commandId,
                    mAvrcpRemoteDevice.getCurrentScope(), null);
            numCheckForCommands++;
        }
        else {
            Bundle fetchNPLCmd = new Bundle();
            fetchNPLCmd.putInt("start", 0);
            fetchNPLCmd.putInt("end", AvrcpControllerConstants.DEFAULT_BROWSE_END_INDEX);
            mAvrcpRemoteDevice.mPendingBrwCmds.addCommand(commandId,
                    mAvrcpRemoteDevice.getCurrentScope(), fetchNPLCmd);
            numCheckForCommands++;
        }
        checkAndProcessBrowsingCommand(numCheckForCommands);
    }
    /*
     * This queues MESSAGE_PROCESS_BROWSING_COMMAND if these are the only commands to send
     * returns false if other messages are also there, true otherwise.
     */
    private boolean checkAndProcessBrowsingCommand(int checkForSize) {
        if ((mAvrcpRemoteDevice == null) || (mAvrcpRemoteDevice.mPendingBrwCmds == null))
            return false;
        /* If these are the only commands, then que msg to process them
         * otherwise wait for a callback to come and process further
         */
        if (mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty())
            return false;
        if (mHandler.hasMessages(AvrcpControllerConstants.MESSAGE_PROCESS_PENDING_BROWSING_COMMANDS))
            return false;
        /* 0 is for the cases where we want to handle any pending command */
        if ((checkForSize == 0) ||
           (mAvrcpRemoteDevice.mPendingBrwCmds.getListSize() == checkForSize)) {
                mHandler.sendEmptyMessage(AvrcpControllerConstants.
                        MESSAGE_PROCESS_PENDING_BROWSING_COMMANDS);
            return true;
        }
        return false;
    }
    private void completeBrowseFolderRequest(int commandId) {
        Log.d(TAG," completeBrowseFolderRequest cmdId = " + commandId);
        AvrcpControllerBrowseService avrcpBrowseService;
        switch (commandId) {
            case AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST:
                avrcpBrowseService = AvrcpControllerBrowseService.
                        getAvrcpControllerBrowseService();
                if(avrcpBrowseService != null) {
                    avrcpBrowseService.updateNowPlayingList(mRemoteNowPlayingList);
                }
                /* Start fetching Thumbnails */
                if ((!mAppProperties.isCoverArtRequested) ||
                        (!mAvrcpRemoteDevice.isCoverArtSupported()))
                    break;
                mRemoteNowPlayingList.fetchThumbNail();
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST:
                avrcpBrowseService = AvrcpControllerBrowseService.
                        getAvrcpControllerBrowseService();
                if(avrcpBrowseService != null) {
                    avrcpBrowseService.updateSearchList(mRemoteFileSystem);
                }
                if ((!mAppProperties.isCoverArtRequested) ||
                        (!mAvrcpRemoteDevice.isCoverArtSupported()))
                    break;
                mRemoteFileSystem.fetchSearchThumbNail();
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST:
                avrcpBrowseService = AvrcpControllerBrowseService.
                        getAvrcpControllerBrowseService();
                Log.d(TAG," avrcpBrowseService = " + avrcpBrowseService);
                if(avrcpBrowseService != null) {
                    Log.d(TAG," update VFS list to browsing service");
                    avrcpBrowseService.updateVFSList(mRemoteFileSystem);
                }
                if ((!mAppProperties.isCoverArtRequested) ||
                        (!mAvrcpRemoteDevice.isCoverArtSupported()))
                    break;
                mRemoteFileSystem.fetchVFSThumbNail();
                break;
        }
    }
    private void resetBipForBrowsing() {
    /*
     * here will first disconnect BIP and then reconnect BIP
     */
        if (!mAppProperties.isCoverArtRequested) return;
        if (!mAvrcpRemoteDevice.isCoverArtSupported()) return;
        if(mRemoteMediaPlayers.isPlayerDatabaseAware(mRemoteMediaPlayers.getBrowsedPlayer().
                mPlayerId)) return;
        mAvrcpRemoteDevice.mPendingBrwCmds.addCommandFirst(AvrcpControllerConstants.
            MESSAGE_BROWSE_CONNECT_OBEX, AvrcpControllerConstants.AVRCP_SCOPE_VFS, null);
        mAvrcpRemoteDevice.mPendingBrwCmds.addCommandFirst(AvrcpControllerConstants.
            MESSAGE_BROWSE_DISCONNECT_OBEX, AvrcpControllerConstants.AVRCP_SCOPE_VFS, null);
    }
    private void sendBrowsingCommands () {
        if (mAvrcpRemoteDevice.mPendingBrwCmds.isListEmpty()) {
            Log.d(TAG," Browsing command list empty ");
            return;
        }
        int commandId = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdId(0);
        int cmdScope = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdScope(0);
        Bundle cmdData = mAvrcpRemoteDevice.mPendingBrwCmds.getCmdData(0);
        int startIndex, endIndex;
        Log.d(TAG," SendBrowsingCommands id " + AvrcpControllerConstants.
                dumpMessageString(commandId) + " scope = " + cmdScope);
        /*
         * For following commands we don't have to check scope
         */
        if ((commandId != AvrcpControllerConstants.MESSAGE_SET_BROWSED_PLAYER)||
            (commandId != AvrcpControllerConstants.MESSAGE_BROWSE_CONNECT_OBEX)||
            (commandId != AvrcpControllerConstants.MESSAGE_BROWSE_DISCONNECT_OBEX)||
            (commandId != AvrcpControllerConstants.MESSAGE_SET_ADDRESSED_PLAYER)){
            if (cmdScope != mAvrcpRemoteDevice.getCurrentScope()) {
                Log.e(TAG," sendBrowsingCommands, scope did not match, clearing this command ");
                completeBrowseFolderRequest(commandId); // complete pending request if any
                mAvrcpRemoteDevice.mPendingBrwCmds.checkAndClearCommand(commandId, cmdScope);
                checkAndProcessBrowsingCommand(0);
                return;
            }
        }
        switch(commandId) {
            case AvrcpControllerConstants.MESSAGE_BROWSE_DISCONNECT_OBEX:
                if ((mAppProperties.isCoverArtRequested) &&
                    (mAvrcpRemoteDevice.isCoverArtSupported()) &&
                    (mAvrcpRemoteDevice.isBipConnected())) {
                        mAvrcpRemoteDevice.disconnectBip();
                }
                else {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                }
                break;
            case AvrcpControllerConstants.MESSAGE_BROWSE_CONNECT_OBEX:
                if ((mAppProperties.isCoverArtRequested) &&
                    (mAvrcpRemoteDevice.isCoverArtSupported()) &&
                    (!mAvrcpRemoteDevice.isBipConnected())) {
                    mAvrcpRemoteDevice.connectBip(mAvrcpRemoteDevice.mBipL2capPsm);
                }
                else {
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                }
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_SEARCH_LIST:
            case AvrcpControllerConstants.MESSAGE_FETCH_NOW_PLAYING_LIST:
                if(cmdData == null) break;
                startIndex = cmdData.getInt("start");
                endIndex = cmdData.getInt("end");
                Log.d(TAG," FETCH_NPL/Search start " + startIndex + " end = " + endIndex);
                if (endIndex < startIndex) {
                    Log.d(TAG, " All items fetched");
                    completeBrowseFolderRequest(commandId);
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                browseFolderNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice), (byte)cmdScope,
                        startIndex, endIndex, (byte)0, null);
                break;
            case AvrcpControllerConstants.MESSAGE_FETCH_VFS_LIST:
                startIndex = cmdData.getInt("start");
                endIndex = cmdData.getInt("end");
                if (startIndex == 0) {
                    /* First iteration of VFS */
                    endIndex = mRemoteFileSystem.mFolderStack.get
                            (mRemoteFileSystem.mFolderStack.size() - 1).numItems - 1;
                    cmdData.putInt("end", endIndex);
                    mAvrcpRemoteDevice.mPendingBrwCmds.updateCommand(0,commandId,cmdScope,cmdData);
                }
                Log.d(TAG," FETCH_VFS start " + startIndex + " end = " + endIndex);
                if (endIndex < startIndex) {
                    Log.d(TAG, " All items fetched");
                    completeBrowseFolderRequest(commandId);
                    mAvrcpRemoteDevice.mPendingBrwCmds.removeCmd(0);
                    checkAndProcessBrowsingCommand(0);
                    break;
                }
                browseFolderNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice), (byte)cmdScope,
                        startIndex, endIndex, (byte)0, null);
                break;
            case AvrcpControllerConstants.MESSAGE_GET_TOTAL_NUM_ITEMS:
                getTotalNumberOfItemsNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        (byte)cmdScope);
                break;
            case AvrcpControllerConstants.MESSAGE_SET_BROWSED_PLAYER:
                setBrowsedPlayerNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        cmdData.getInt("playerId"));
                break;
            case AvrcpControllerConstants.MESSAGE_SET_ADDRESSED_PLAYER:
                setAddressedPlayerNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        cmdData.getInt("playerId"));
                break;
            case AvrcpControllerConstants.MESSAGE_SEND_CHANGE_PATH:
                int folderDepth = cmdData.getInt("delta");
                if (folderDepth == 0) break;
                byte direction = (folderDepth > 0)? AvrcpControllerConstants.DIRECTION_DOWN:
                                                   AvrcpControllerConstants.DIRECTION_UP;
                long uid = 0;
                if (direction == AvrcpControllerConstants.DIRECTION_DOWN) {
                    uid = mRemoteFileSystem.mFolderStack.get
                            (mRemoteFileSystem.mFolderStack.size() - 1).uid;
                }
                else if(direction == AvrcpControllerConstants.DIRECTION_UP) {
                    /* If we are moving up, we have to send uid of parent one down folder */
                    uid = mRemoteFileSystem.mFolderStack.
                            get(mRemoteFileSystem.mFolderStack.size() - 2).uid;
                }
                changePathNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                        mAvrcpRemoteDevice.muidCounter, direction, uid);
                break;
            case AvrcpControllerConstants.MESSAGE_GET_NUM_ITEMS_SEARCH:
                searchNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),AvrcpControllerConstants.
                        AVRC_CHARSET_UTF8, cmdData.getString("pattern").length(),
                        cmdData.getString("pattern"));
                break;
        }
    }
    private void setAbsVolume(int absVol, int label)
    {
        int maxVolume = mAudioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int currIndex = mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int newIndex = (maxVolume*absVol)/AvrcpControllerConstants.ABS_VOL_BASE;
        Log.d(TAG," setAbsVolume ="+absVol + " maxVol = " + maxVolume + " cur = " + currIndex +
                                              " new = "+newIndex);
        /*
         * In some cases change in percentage is not sufficient enough to warrant
         * change in index values which are in range of 0-15. For such cases
         * no action is required
         */
        if (newIndex != currIndex) {
            mAudioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newIndex,
                                                     AudioManager.FLAG_SHOW_UI);
        }
        sendAbsVolRspNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice), absVol, label);
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(AudioManager.VOLUME_CHANGED_ACTION)) {
                int streamType = intent.getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, -1);
                if (streamType == AudioManager.STREAM_MUSIC) {
                    int streamValue = intent
                            .getIntExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, -1);
                    int streamPrevValue = intent.getIntExtra(
                            AudioManager.EXTRA_PREV_VOLUME_STREAM_VALUE, -1);
                    if (streamValue != -1 && streamValue != streamPrevValue) {
                        if ((mAvrcpRemoteDevice == null)
                            ||((mAvrcpRemoteDevice.mRemoteFeatures & 
                                    AvrcpControllerConstants.BTRC_FEAT_ABSOLUTE_VOLUME) == 0)
                            ||(mConnectedDevices.isEmpty()))
                            return;
                        if(mAvrcpRemoteDevice.mAbsVolNotificationState == 
                                AvrcpControllerConstants.SEND_VOLUME_CHANGE_RSP) {
                            int maxVol = mAudioManager.
                                                  getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                            int currIndex = mAudioManager.
                                                  getStreamVolume(AudioManager.STREAM_MUSIC);
                            int percentageVol = ((currIndex*
                                            AvrcpControllerConstants.ABS_VOL_BASE)/maxVol);
                            Log.d(TAG," Abs Vol Notify Rsp Changed vol = "+ percentageVol);
                            sendRegisterAbsVolRspNative(getByteAddress(mAvrcpRemoteDevice.mBTDevice),
                                (byte)AvrcpControllerConstants.NOTIFICATION_RSP_TYPE_CHANGED,
                                    percentageVol, mAvrcpRemoteDevice.mNotificationLabel);
                        }
                        else if (mAvrcpRemoteDevice.mAbsVolNotificationState == 
                                AvrcpControllerConstants.DEFER_VOLUME_CHANGE_RSP) {
                            Log.d(TAG," Don't Complete Notification Rsp. ");
                            mAvrcpRemoteDevice.mAbsVolNotificationState = 
                                              AvrcpControllerConstants.SEND_VOLUME_CHANGE_RSP;
                        }
                    }
                }
            }
        }
    };

    private void handlePassthroughRsp(int id, int keyState) {
        Log.d(TAG, "passthrough response received as: key: "
                                + id + " state: " + keyState);
    }

    private void onConnectionStateChanged(boolean connected, byte[] address) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
            (Utils.getAddressStringFromByte(address));
        Log.d(TAG, "onConnectionStateChanged " + connected + " " + device+ " size "+
                    mConnectedDevices.size());
        if (device == null)
            return;
        int oldState = (mConnectedDevices.contains(device) ? BluetoothProfile.STATE_CONNECTED
                                                        : BluetoothProfile.STATE_DISCONNECTED);
        int newState = (connected ? BluetoothProfile.STATE_CONNECTED
                                  : BluetoothProfile.STATE_DISCONNECTED);

        if (connected && oldState == BluetoothProfile.STATE_DISCONNECTED) {
            /* AVRCPControllerService supports single connection */
            if(mConnectedDevices.size() > 0) {
                Log.d(TAG,"A Connection already exists, returning");
                return;
            }
            mConnectedDevices.add(device);
            Message msg =  mHandler.obtainMessage(
                    AvrcpControllerConstants.MESSAGE_PROCESS_CONNECTION_CHANGE, newState,
                        oldState, device);
            mHandler.sendMessage(msg);
        } else if (!connected && oldState == BluetoothProfile.STATE_CONNECTED) {
            mConnectedDevices.remove(device);
            Message msg =  mHandler.obtainMessage(
                    AvrcpControllerConstants.MESSAGE_PROCESS_CONNECTION_CHANGE, newState,
                        oldState, device);
            mHandler.sendMessage(msg);
        }
    }

    private void getRcFeatures(byte[] address, int features, int ca_psm) {
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        Message msg = mHandler.obtainMessage(
                AvrcpControllerConstants.MESSAGE_PROCESS_RC_FEATURES, features, ca_psm, device);
        mHandler.sendMessage(msg);
    }
    private void setPlayerAppSettingRsp(byte[] address, byte accepted) {
              /* TODO do we need to do anything here */
    }
    private void handleRegisterNotificationAbsVol(byte[] address, byte label) {
        Log.d(TAG, "handleRegisterNotificationAbsVol ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_REGISTER_ABS_VOL_NOTIFICATION, label, 0);
        mHandler.sendMessage(msg);
    }

    private void handleSetAbsVolume(byte[] address, byte absVol, byte label) {
        Log.d(TAG,"handleSetAbsVolume ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(
                AvrcpControllerConstants.MESSAGE_PROCESS_SET_ABS_VOL_CMD, absVol, label);
        mHandler.sendMessage(msg);
    }

    private void onTrackChanged(byte[] address, byte numAttributes, int[] attributes,
                                               String[] attribVals)
    {
        Log.d(TAG,"onTrackChanged ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        TrackInfo mTrack = new TrackInfo(0, numAttributes, attributes, attribVals);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_TRACK_CHANGED, numAttributes, 0, mTrack);
        mHandler.sendMessage(msg);
    }

    private void onElementAttributeUpdate(byte[] address, byte numAttributes, int[] attributes,
            String[] attribVals)
    {
        Log.d(TAG,"onTrackChanged ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
            (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        TrackInfo mTrack = new TrackInfo(0, numAttributes, attributes, attribVals);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_TRACK_CHANGED, numAttributes, 0, mTrack);
        mHandler.sendMessage(msg);
     }

    private void onPlayPositionChanged(byte[] address, int songLen, int currSongPosition, byte playStatus) {
        Log.d(TAG,"onPlayPositionChanged ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Bundle data = new Bundle();

        data.putByte("Playstatus", playStatus);
        data.putInt("songlen", songLen);
        data.putInt("curposition", currSongPosition);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                               MESSAGE_PROCESS_PLAY_POS_CHANGED);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }

    private void onPlayStatusChanged(byte[] address, byte playStatus) {
        if(DBG) Log.d(TAG,"onPlayStatusChanged " + playStatus);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_PLAY_STATUS_CHANGED, playStatus, 0);
        mHandler.sendMessage(msg);
    }

    private void handlePlayerAppSetting(byte[] address, byte[] playerAttribRsp, int rspLen) {
        Log.d(TAG,"handlePlayerAppSetting rspLen = " + rspLen);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        ByteBuffer bb = ByteBuffer.wrap(playerAttribRsp, 0, rspLen);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_SUPPORTED_PLAYER_APP_SETTING, 0, 0, bb);
        mHandler.sendMessage(msg);
    }

    private void onPlayerAppSettingChanged(byte[] address, byte[] playerAttribRsp, int rspLen) {
        Log.d(TAG,"onPlayerAppSettingChanged ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        ByteBuffer bb = ByteBuffer.wrap(playerAttribRsp, 0, rspLen);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_PLAYER_APP_SETTING_CHANGED, 0, 0, bb);
        mHandler.sendMessage(msg);
    }

    private void handleGroupNavigationRsp(int id, int keyState) {
        Log.d(TAG, "group navigation response received as: key: "
                + id + " state: " + keyState);
    }
    private void handleAvailablePlayersList (byte[] address, int uidCounter, byte numPlayers,
        int[] subType, int[] playerId, byte[] majorType, byte[] playStatus, byte[] featureMask, String[] name) {
        Log.d(TAG,"handleAvailablePlayersList numPlayers = " + numPlayers );
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Bundle data = new Bundle();
        data.putIntArray("subtype", subType); data.putIntArray("playerId", playerId);
        data.putByteArray("majorType", majorType); data.putByteArray("playStatus", playStatus);
        data.putByteArray("featureMask", featureMask); data.putStringArray("name", name);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_SUPPORTED_PLAYER, uidCounter, numPlayers);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }
    private void onGetTotalNumItems(byte[] address, byte status, int uidCounter, int numItems) {
        Log.d(TAG,"onGetTotalNumItems ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;

        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_GET_TOTAL_NUM_ITEMS, uidCounter, numItems);
        mHandler.sendMessage(msg);
    }
    private void handleBrowseFolderResponse (byte[] address, byte status, int uidCounter, int numItems,
                                             byte[] itemType, long[] uids, byte[] type,
                                             byte[] playable, String[] itemName, byte[] numAttrs,
                                             int[] attrs, String[] attributes) {
        Log.d(TAG," handleBrowseFolderResponse staus = " + status + " uidCounter = " + uidCounter);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg;
        if ((status != AvrcpControllerConstants.AVRC_RET_NO_ERROR) || (numItems == 0)) {
            Bundle data = new Bundle();
            data.putByte("status", status);
            data.putInt("uidCounter", uidCounter);
            data.putInt("numItems", numItems);
            msg = mHandler.obtainMessage(AvrcpControllerConstants.
                    MESSAGE_PROCESS_BROWSE_FOLDER_RESPONSE);
            msg.setData(data);
            mHandler.sendMessage(msg);
            return;
        }
        Bundle data = new Bundle();
        data.putByte("status", status);
        data.putInt("uidCounter", uidCounter);
        data.putInt("numItems", numItems);
        data.putByteArray("itemType", itemType);
        data.putLongArray("uids", uids);
        data.putByteArray("type", type);
        data.putByteArray("playable", playable);
        data.putStringArray("itemName", itemName);
        data.putByteArray("numAttrs", numAttrs);
        data.putIntArray("attrs", attrs);
        data.putStringArray("attributes", attributes);
        msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_BROWSE_FOLDER_RESPONSE);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }
    private void onAddressedPlayerChanged(byte[] address, int playerId, int uidCounter) {
        Log.d(TAG," onAddressedPlayerChanged playerId = " +playerId );
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_ADDRESSED_PLAYER_CHANGED, playerId, uidCounter);
        mHandler.sendMessage(msg);
    }
    private void handleSetBrowsedPlayerRsp (byte[] address, byte status, int uidCounter) {
        Log.d(TAG," handleSetBrowsedPlayerRsp status = " + status + " uidCounter = " + uidCounter);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_SET_BROWSED_PLAYER_RSP, status, uidCounter);
        mHandler.sendMessage(msg);
    }
    private void handleSetAddressedPlayerRsp( byte[] address, byte status) {
        Log.d(TAG," handleSetAddressedPlayerRsp status = " + status );
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_SET_ADDRESSED_PLAYER_RSP, status, 0);
        mHandler.sendMessage(msg);
    }
    private void handleChangePathRsp(byte[] address, byte status, int numItems) {
        Log.d(TAG," handleChangePathRsp status = " + status + " numItems = " + numItems);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.MESSAGE_PROCESS_PATH_CHANGED,
                status, numItems);
        mHandler.sendMessage(msg);
    }
    private void onNowPlayingListUpdate(byte[] address) {
        Log.d(TAG," onNowPlayingListUpdate ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        mHandler.sendEmptyMessage(AvrcpControllerConstants.MESSAGE_PROCESS_NPL_LIST_UPDATE);
    }
    private void handleAddToNPLRsp(byte[] address, byte status) {
        Log.d(TAG," handleAddToNPLRsp status = " + status );
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
    }
    private  void handlePlayItemRsp(byte[] address, byte status) {
        Log.d(TAG," handlePlayItemRsp status = " + status );
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
    }
    private void handleSearchRsp(byte[] address, byte status, int uidCounter, int numItems) {
        Log.d(TAG," handleSearchRsp status = " + status + " numItems = " + numItems);
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        Bundle data = new Bundle();
        data.putInt("uidCounter", uidCounter);
        Message msg = mHandler.obtainMessage(AvrcpControllerConstants.
                MESSAGE_PROCESS_SEARCH_ITEM_RSP, status, numItems);
        msg.setData(data);
        mHandler.sendMessage(msg);
    }
    private void OnUidsChanged(byte[] address, int uidCounter) {
        Log.d(TAG," handleSearchRsp ");
        BluetoothDevice device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice
                (Utils.getAddressStringFromByte(address));
        if (!mConnectedDevices.contains(device))
            return;
        mHandler.sendEmptyMessage(AvrcpControllerConstants.MESSAGE_PROCESS_UIDS_CHANGED);
    }

    private byte[] getByteAddress(BluetoothDevice device) {
        return Utils.getBytesFromAddress(device.getAddress());
    }

    @Override
    public void dump(StringBuilder sb) {
        super.dump(sb);
    }

    private native static void classInitNative();
    private native void initNative();
    private native void cleanupNative();
    private native boolean sendPassThroughCommandNative(byte[] address, int keyCode, int keyState);
    private native boolean sendGroupNavigationCommandNative(byte[] address, int keyCode,
                                                                                     int keyState);
    private native void setPlayerApplicationSettingValuesNative(byte[] address, byte numAttrib,
                                                    byte[] atttibIds, byte[]attribVal);
    /* This api is used to send response to SET_ABS_VOL_CMD */
    private native void sendAbsVolRspNative(byte[] address, int absVol, int label);
    /* This api is used to inform remote for any volume level changes */
    private native void sendRegisterAbsVolRspNative(byte[] address, byte rspType, int absVol,
                                                    int label);
    private native void getElementAttributesNative(byte[] address, byte numAttributes,
            int[] attribIds);
    private native void getTotalNumberOfItemsNative(byte[] address, byte scope);
    private native void browseFolderNative(byte[] address, byte scope, int startIndex, int endIndex,
                                                   byte numAttributes, byte[] attribIds);
    private native void setBrowsedPlayerNative(byte[] address, int playerId);
    private native void setAddressedPlayerNative(byte[] address, int playerId);
    private native void changePathNative(byte[] address, int uidCounter, byte direction, long uid);
    private native void addToNowPlayingListNative(byte[] address, byte scope, long uid,
                                                  int uidCounter);
    private native void playItemNative(byte[] address, byte scope, int uidCounter, long uid);
    private native void searchNative(byte[] address, int charSet, int strLen, String pattern);

}
