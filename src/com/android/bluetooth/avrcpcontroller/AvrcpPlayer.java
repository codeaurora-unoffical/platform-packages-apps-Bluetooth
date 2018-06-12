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

package com.android.bluetooth.avrcpcontroller;

import android.bluetooth.BluetoothAvrcpPlayerSettings;
import android.media.session.PlaybackState;
import android.util.Log;

import java.util.ArrayList;

/*
 * Contains information about remote player
 */
class AvrcpPlayer {
    private static final String TAG = "AvrcpPlayer";
    private static final boolean DBG = true;

    public static final int INVALID_ID = -1;

    public static final int BTRC_FEATURE_BIT_MASK_SIZE = 16;

    /* Octect value for Feature Bit Mask */
    public static final int UIDS_UNIQUE_OCTECT_VALUE = 7;

    /* Bit value for Feature Bit Mask */
    public static final int UIDS_UNIQUE_BIT_VALUE = 2 << 6;

    /* Octect value for Searching */
    public static final int SEARCHING_OCTECT_VALUE = 7;

    /* Bit value for Searching */
    public static final int SEARCHING_BIT_VALUE = 1 << 4;

    /* Octect value for NumberOfItems */
    public static final int NUMBER_OF_ITEMS_OCTECT_VALUE = 8;

    /* Bit value for NumberOfItems */
    public static final int NUMBER_OF_ITEMS_BIT_VALUE = 1 << 3;
    private int mPlayStatus = PlaybackState.STATE_NONE;
    private long mPlayTime = PlaybackState.PLAYBACK_POSITION_UNKNOWN;
    private int mId;
    private String mName = "";
    private byte[] mTransportFlags = new byte[BTRC_FEATURE_BIT_MASK_SIZE];
    private int mPlayerType;
    private TrackInfo mCurrentTrack = new TrackInfo();
    private PlayerApplicationSettings mPlayerAppSetting = new PlayerApplicationSettings();

    AvrcpPlayer() {
        mId = INVALID_ID;
    }

    AvrcpPlayer(int id, String name, byte[] transportFlags, int playStatus, int playerType) {
        mId = id;
        mName = name;
        mPlayerType = playerType;
        mPlayStatus = playStatus;

        System.arraycopy(transportFlags, 0, mTransportFlags, 0, BTRC_FEATURE_BIT_MASK_SIZE);
    }

    public void setId(int id) {
        mId = id;
    }
    public int getId() {
        return mId;
    }

    public void setName(String name) {
        mName = name;
    }
    public String getName() {
        return mName;
    }

    public void setTransportFlags(byte[] transportFlags) {
        System.arraycopy(transportFlags, 0, mTransportFlags, 0, BTRC_FEATURE_BIT_MASK_SIZE);
    }

    public byte[] getTransportFlags() {
        return mTransportFlags;
    }

    public boolean isDatabaseAwarePlayer() {
        if ((mTransportFlags[UIDS_UNIQUE_OCTECT_VALUE] &
            UIDS_UNIQUE_BIT_VALUE) == UIDS_UNIQUE_BIT_VALUE) {
            return true;
        }

        return false;
    }
    public boolean isSearchingSupported() {
        return isFeatureSupported(SEARCHING_OCTECT_VALUE,
                                  SEARCHING_BIT_VALUE);
    }

    public boolean isNumberOfItemsSupported() {
        return isFeatureSupported(NUMBER_OF_ITEMS_OCTECT_VALUE,
                                  NUMBER_OF_ITEMS_BIT_VALUE);
    }

    private boolean isFeatureSupported(int octVal, int bitVal) {
        if (octVal < BTRC_FEATURE_BIT_MASK_SIZE) {
            byte flag = mTransportFlags[octVal];
            return (flag & bitVal) == bitVal ? true : false;
        } else {
            return false;
        }
    }

    public void setPlayTime(int playTime) {
        mPlayTime = playTime;
    }

    public long getPlayTime() {
        return mPlayTime;
    }

    public void setPlayStatus(int playStatus) {
        mPlayStatus = playStatus;
    }

    public PlaybackState getPlaybackState() {
        if (DBG) {
            Log.d(TAG, "getPlayBackState state " + mPlayStatus + " time " + mPlayTime);
        }

        long position = mPlayTime;
        float speed = 1;
        switch (mPlayStatus) {
            case PlaybackState.STATE_STOPPED:
                position = 0;
                speed = 0;
                break;
            case PlaybackState.STATE_PAUSED:
                speed = 0;
                break;
            case PlaybackState.STATE_FAST_FORWARDING:
                speed = 3;
                break;
            case PlaybackState.STATE_REWINDING:
                speed = -3;
                break;
        }
        return new PlaybackState.Builder().setState(mPlayStatus, position, speed).build();
    }

    public void setSupportedPlayerAppSetting (byte[] btAvrcpAttributeList) {
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSupportedSettings(btAvrcpAttributeList);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
    }

    public void makePlayerAppSetting(byte[] btAvrcpAttributeList) {
        if (mPlayerAppSetting != null) {
            mPlayerAppSetting.makeSettings(btAvrcpAttributeList);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
        }
    }

    public BluetoothAvrcpPlayerSettings getAvrcpSettings() {
        /* Player App Setting has been cached when Avrcp connected */
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.getAvrcpSettings();
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return null;
        }
    }
    public boolean supportsSettings(BluetoothAvrcpPlayerSettings settingsToCheck) {
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.supportsSettings(settingsToCheck);
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return false;
        }
    }

    public ArrayList<Byte> getNativeSettings() {
        if (mPlayerAppSetting != null) {
            return mPlayerAppSetting.getNativeSettings();
        } else {
            Log.e(TAG, "mPlayerAppSetting is null");
            return null;
        }
    }

    public synchronized void updateCurrentTrack(TrackInfo update) {
        mCurrentTrack = update;
    }

    public synchronized TrackInfo getCurrentTrack() {
        return mCurrentTrack;
    }
}
