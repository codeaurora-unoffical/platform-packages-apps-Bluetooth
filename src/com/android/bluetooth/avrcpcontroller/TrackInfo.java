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

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class TrackInfo {
    private static final String TAG = "AvrcpTrackInfo";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);

    /*
     * Default values for each of the items from JNI
     */
    private static final int TRACK_NUM_INVALID = -1;
    private static final int TOTAL_TRACKS_INVALID = -1;
    private static final int TOTAL_TRACK_TIME_INVALID = -1;
    private static final String UNPOPULATED_ATTRIBUTE = "";

    /*
     *Element Id Values for GetMetaData  from JNI
     */
    private static final int MEDIA_ATTRIBUTE_TITLE = 0x01;
    private static final int MEDIA_ATTRIBUTE_ARTIST_NAME = 0x02;
    private static final int MEDIA_ATTRIBUTE_ALBUM_NAME = 0x03;
    private static final int MEDIA_ATTRIBUTE_TRACK_NUMBER = 0x04;
    private static final int MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER = 0x05;
    private static final int MEDIA_ATTRIBUTE_GENRE = 0x06;
    private static final int MEDIA_ATTRIBUTE_PLAYING_TIME = 0x07;
    private static final int MEDIA_ATTRIBUTE_COVER_ART_HANDLE = 0x08;

    private final String mArtistName;
    private final String mTrackTitle;
    private final String mAlbumTitle;
    private final String mGenre;
    private final long mTrackNum; // Number of audio file on original recording.
    private final long mTotalTracks;// Total number of tracks on original recording
    private final long mTrackLen;// Full length of AudioFile.
    /* Bip values are not final. Obex can disconnect in between and as per spec
       we should clear all CA related data in that case. */
    private String mCoverArtHandle;
    private String mImageLocation;
    private String mThumbNailLocation;

    TrackInfo() {
        this(new ArrayList<Integer>(), new ArrayList<String>());
    }

    TrackInfo(List<Integer> attrIds, List<String> attrMap) {
        Map<Integer, String> attributeMap = new HashMap<>();
        for (int i = 0; i < attrIds.size(); i++) {
            attributeMap.put(attrIds.get(i), attrMap.get(i));
        }

        String attribute;
        mTrackTitle = attributeMap.getOrDefault(MEDIA_ATTRIBUTE_TITLE, UNPOPULATED_ATTRIBUTE);

        mArtistName = attributeMap.getOrDefault(MEDIA_ATTRIBUTE_ARTIST_NAME, UNPOPULATED_ATTRIBUTE);

        mAlbumTitle = attributeMap.getOrDefault(MEDIA_ATTRIBUTE_ALBUM_NAME, UNPOPULATED_ATTRIBUTE);

        attribute = attributeMap.get(MEDIA_ATTRIBUTE_TRACK_NUMBER);
        mTrackNum = (attribute != null && !attribute.isEmpty()) ? Long.valueOf(attribute)
                : TRACK_NUM_INVALID;

        attribute = attributeMap.get(MEDIA_ATTRIBUTE_TOTAL_TRACK_NUMBER);
        mTotalTracks = (attribute != null && !attribute.isEmpty()) ? Long.valueOf(attribute)
                : TOTAL_TRACKS_INVALID;

        mGenre = attributeMap.getOrDefault(MEDIA_ATTRIBUTE_GENRE, UNPOPULATED_ATTRIBUTE);

        attribute = attributeMap.get(MEDIA_ATTRIBUTE_PLAYING_TIME);
        mTrackLen = (attribute != null && !attribute.isEmpty()) ? Long.valueOf(attribute)
                : TOTAL_TRACK_TIME_INVALID;

        mCoverArtHandle = attributeMap.getOrDefault(MEDIA_ATTRIBUTE_COVER_ART_HANDLE,
                UNPOPULATED_ATTRIBUTE);
        mImageLocation = UNPOPULATED_ATTRIBUTE;
        mThumbNailLocation = UNPOPULATED_ATTRIBUTE;
    }

    public String getArtistName() {
        return mArtistName;
    }

    public String getTrackTitle() {
        return mTrackTitle;
    }

    public String getAlbumTitle() {
        return mAlbumTitle;
    }

    public String getGenre() {
        return mGenre;
    }

    public long getTrackNum() {
        return mTrackNum;
    }

    public long getTotalTracks() {
        return mTotalTracks;
    }

    public long getTrackLen() {
        return mTrackLen;
    }

    public String getCoverArtHandle() {
        return mCoverArtHandle;
    }

    public void clearCoverArtData() {
        mCoverArtHandle = UNPOPULATED_ATTRIBUTE;
        mImageLocation = UNPOPULATED_ATTRIBUTE;
        mThumbNailLocation = UNPOPULATED_ATTRIBUTE;
    }

    public String getImageLocation() {
        return mImageLocation;
    }

    public boolean updateImageLocation(String mLocation) {
        return updateImageLocation(mCoverArtHandle, mLocation);
    }

    public boolean updateImageLocation(String mCAHandle, String mLocation) {
        if (DBG) {
            Log.d(TAG, " updateImageLocation hndl " + mCAHandle + " location " + mLocation);
        }
        if (!mCAHandle.equals(mCoverArtHandle) || (mLocation == null)) {
            return false;
        }

        if (mLocation.equals(mImageLocation)) {
            Log.i(TAG, "mLocation: " + mLocation + " mImageLocation: " + mImageLocation);
            return false;
        }

        mImageLocation = mLocation;
        return true;
    }

    public String getThumbNailLocation() {
        return mThumbNailLocation;
    }

    public boolean updateThumbNailLocation(String mLocation) {
        return updateThumbNailLocation(mCoverArtHandle, mLocation);
    }

    public boolean updateThumbNailLocation(String mCAHandle, String mLocation) {
        if (DBG) {
            Log.d(TAG, " mCAHandle " + mCAHandle + " location " + mLocation);
        }
        if (!mCAHandle.equals(mCoverArtHandle) || (mLocation == null)) {
            return false;
        }
        mThumbNailLocation = mLocation;
        return true;
    }

    @Override
    public String toString() {
        return "TrackInfo [mArtistName=" + mArtistName + ", mTrackTitle=" + mTrackTitle
               + ", mAlbumTitle=" + mAlbumTitle + ", mGenre=" + mGenre + ", mTrackNum="
               + mTrackNum + ", mTotalTracks=" + mTotalTracks + ", mTrackLen="
               + mTrackLen + ", mCoverArtHandle=" + mCoverArtHandle
               + ", mImageLocation=" + mImageLocation + ", mThumbNailLocation="
               + mThumbNailLocation + "]";
    }

    public MediaMetadata getMetadata() {
        MediaMetadata.Builder metaDataBuilder = new MediaMetadata.Builder();

        metaDataBuilder.putString(MediaMetadata.METADATA_KEY_ARTIST, mArtistName);
        metaDataBuilder.putString(MediaMetadata.METADATA_KEY_TITLE, mTrackTitle);
        metaDataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM, mAlbumTitle);
        metaDataBuilder.putString(MediaMetadata.METADATA_KEY_GENRE, mGenre);
        metaDataBuilder.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, mTrackNum);
        metaDataBuilder.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, mTotalTracks);
        metaDataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, mTrackLen);
        if (mImageLocation != UNPOPULATED_ATTRIBUTE) {
            Uri imageUri = Uri.fromFile(new File(mImageLocation));
            if (DBG) {
                Log.d(TAG," updating image uri = " + imageUri.toString());
            }
            metaDataBuilder.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                    imageUri.toString());

            Bitmap Bitmap = BitmapFactory.decodeFile(mImageLocation);
            metaDataBuilder.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART,
                    Bitmap);
        }
        if (mThumbNailLocation != UNPOPULATED_ATTRIBUTE) {
            Uri thumbNailUri = Uri.fromFile(new File(mThumbNailLocation));
            if (DBG) {
                Log.d(TAG," updating thumbNail uri = " + thumbNailUri.toString());
            }
            metaDataBuilder.putString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
                    thumbNailUri.toString());

            Bitmap thumbNailBitmap = BitmapFactory.decodeFile(mThumbNailLocation);
            metaDataBuilder.putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, thumbNailBitmap);
        }

        return metaDataBuilder.build();
    }
}
