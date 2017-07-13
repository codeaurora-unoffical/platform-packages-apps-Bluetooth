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

package com.android.bluetooth.btservice;

/*
 * @hide
 */

final public class AbstractionLayer {
    // Do not modify without upating the HAL files.

    // TODO: Some of the constants are repeated from BluetoothAdapter.java.
    // Get rid of them and maintain just one.
    static final int BT_STATE_OFF = 0x00;
    static final int BT_STATE_ON = 0x01;

    static final int BT_SCAN_MODE_NONE = 0x00;
    static final int BT_SCAN_MODE_CONNECTABLE = 0x01;
    static final int BT_SCAN_MODE_CONNECTABLE_DISCOVERABLE = 0x02;

    static final int BT_PROPERTY_BDNAME = 0x01;
    static final int BT_PROPERTY_BDADDR = 0x02;
    static final int BT_PROPERTY_UUIDS = 0x03;
    static final int BT_PROPERTY_CLASS_OF_DEVICE = 0x04;
    static final int BT_PROPERTY_TYPE_OF_DEVICE = 0x05;
    static final int BT_PROPERTY_SERVICE_RECORD = 0x06;
    static final int BT_PROPERTY_ADAPTER_SCAN_MODE = 0x07;
    static final int BT_PROPERTY_ADAPTER_BONDED_DEVICES = 0x08;
    static final int BT_PROPERTY_ADAPTER_DISCOVERABLE_TIMEOUT = 0x09;

    static final int BT_PROPERTY_REMOTE_FRIENDLY_NAME = 0x0A;
    static final int BT_PROPERTY_REMOTE_RSSI = 0x0B;

    static final int BT_PROPERTY_REMOTE_VERSION_INFO = 0x0C;
    static final int BT_PROPERTY_LOCAL_LE_FEATURES = 0x0D;

    static final int BT_DEVICE_TYPE_BREDR = 0x01;
    static final int BT_DEVICE_TYPE_BLE = 0x02;
    static final int BT_DEVICE_TYPE_DUAL = 0x03;

    static final int BT_BOND_STATE_NONE = 0x00;
    static final int BT_BOND_STATE_BONDING = 0x01;
    static final int BT_BOND_STATE_BONDED = 0x02;

    static final int BT_SSP_VARIANT_PASSKEY_CONFIRMATION = 0x00;
    static final int BT_SSP_VARIANT_PASSKEY_ENTRY = 0x01;
    static final int BT_SSP_VARIANT_CONSENT = 0x02;
    static final int BT_SSP_VARIANT_PASSKEY_NOTIFICATION = 0x03;

    static final int BT_DISCOVERY_STOPPED = 0x00;
    static final int BT_DISCOVERY_STARTED = 0x01;

    static final int BT_ACL_STATE_CONNECTED = 0x00;
    static final int BT_ACL_STATE_DISCONNECTED = 0x01;

    static final int BT_UUID_SIZE = 16; // bytes

    public static final int BT_STATUS_SUCCESS = 0;
    public static final int BT_STATUS_FAIL = 1;
    public static final int BT_STATUS_NOT_READY = 2;
    public static final int BT_STATUS_NOMEM = 3;
    public static final int BT_STATUS_BUSY = 4;
    public static final int BT_STATUS_DONE = 5;
    public static final int BT_STATUS_UNSUPPORTED = 6;
    public static final int BT_STATUS_PARM_INVALID = 7;
    public static final int BT_STATUS_UNHANDLED = 8;
    public static final int BT_STATUS_AUTH_FAILURE = 9;
    public static final int BT_STATUS_RMT_DEV_DOWN = 10;
    public static final int BT_STATUS_AUTH_REJECTED =11;
    public static final int BT_STATUS_AUTH_TIMEOUT = 12;

    // Profile IDs to get profile features from profile_conf
    public static final int AVRCP = 1;
    public static final int PBAP = 2;
    public static final int MAP = 3;

    // Profile features supported in profile_conf
    public static final int PROFILE_VERSION =1;
    public static final int AVRCP_COVERART_SUPPORT = 2;
    public static final int AVRCP_0103_SUPPORT = 3;
    public static final int USE_SIM_SUPPORT = 4;
    public static final int MAP_EMAIL_SUPPORT = 5;
    public static final int PBAP_0102_SUPPORT = 6;

    // match up with interop_feature_t enum of interop.h
    public static final int INTEROP_DISABLE_LE_SECURE_CONNECTIONS = 0;
    public static final int INTEROP_AUTO_RETRY_PAIRING = 1;
    public static final int INTEROP_DISABLE_ABSOLUTE_VOLUME = 2;
    public static final int INTEROP_DISABLE_AUTO_PAIRING = 3;
    public static final int INTEROP_KEYBOARD_REQUIRES_FIXED_PIN = 4;
    public static final int INTEROP_2MBPS_LINK_ONLY = 5;
    public static final int INTEROP_DISABLE_SDP_AFTER_PAIRING = 6;
    public static final int INTEROP_REMOVE_HID_DIG_DESCRIPTOR = 7;
    public static final int INTEROP_DISABLE_SNIFF_DURING_SCO = 8;
    public static final int INTEROP_HID_PREF_CONN_SUP_TIMEOUT_3S = 9;
    public static final int INTEROP_GATTC_NO_SERVICE_CHANGED_IND = 10;
    public static final int INTEROP_INCREASE_AG_CONN_TIMEOUT = 11;
    public static final int INTEROP_DISABLE_LE_CONN_PREFERRED_PARAMS = 12;
    public static final int INTEROP_ADV_AVRCP_VER_1_3 = 13;
    public static final int INTEROP_DISABLE_AAC_CODEC = 14;
    public static final int INTEROP_DISABLE_ROLE_SWITCH_POLICY = 15;
    public static final int INTEROP_HFP_1_7_BLACKLIST = 16;
    public static final int INTEROP_STORE_REMOTE_AVRCP_VERSION_1_4 = 17;
    public static final int INTEROP_ADV_PBAP_VER_1_1 = 18;
    public static final int INTEROP_UPDATE_HID_SSR_MAX_LAT = 19;
    public static final int INTEROP_DISABLE_AUTH_FOR_HID_POINTING = 20;
    public static final int INTEROP_DISABLE_AVDTP_RECONFIGURE = 21;
    public static final int INTEROP_DYNAMIC_ROLE_SWITCH = 22;
    public static final int INTEROP_DISABLE_HF_INDICATOR = 23;
    public static final int INTEROP_DISABLE_ROLE_SWITCH = 24;
    public static final int INTEROP_INCREASE_COLL_DETECT_TIMEOUT = 25;
    public static final int INTEROP_DELAY_SCO_FOR_MT_CALL = 26;
    public static final int INTEROP_DISABLE_CODEC_NEGOTIATION = 27;
    public static final int INTEROP_DISABLE_PLAYER_APPLICATION_SETTING_CMDS = 28;
    public static final int INTEROP_DISABLE_CONNECTION_AFTER_COLLISION = 29;

    // match up with interop_bl_type enum of interop_config.h
    public static final int INTEROP_BL_TYPE_ADDR = 0;
    public static final int INTEROP_BL_TYPE_NAME = 1;
    public static final int INTEROP_BL_TYPE_MANUFACTURE = 2;
    public static final int INTEROP_BL_TYPE_VNDR_PRDT = 3;
    public static final int INTEROP_BL_TYPE_SSR_MAX_LAT = 4;
}
