/*
 * Copyright (C) 2013,2016-2017 The Linux Foundation. All rights reserved
 * Not a Contribution.
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

#define LOG_TAG "BluetoothVendorJni"

#include "com_android_bluetooth.h"
#include <hardware/vendor.h>
#include "utils/Log.h"
#include "android_runtime/AndroidRuntime.h"


namespace android {

static jmethodID method_onBredrCleanup;
static jmethodID method_iotDeviceBroadcast;
static jmethodID method_getLinkKeyCallback;

static btvendor_interface_t *sBluetoothVendorInterface = NULL;
static jobject mCallbacksObj = NULL;

static jstring create_link_key_string(JNIEnv* env, LINK_KEY link_key) {
  char c_linkkey[128];
  snprintf(c_linkkey, sizeof(c_linkkey), "%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X%02X",
           link_key[0], link_key[1], link_key[2], link_key[3],
           link_key[4], link_key[5], link_key[6], link_key[7],
           link_key[8], link_key[9], link_key[10], link_key[11],
           link_key[12], link_key[13], link_key[14], link_key[15]);

  return env->NewStringUTF(c_linkkey);
}

static void bredr_cleanup_callback(bool status){

    ALOGI("%s", __FUNCTION__);
    CallbackEnv sCallbackEnv(__func__);

    if (!sCallbackEnv.valid()) return;

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_onBredrCleanup, (jboolean)status);
}

static void iot_device_broadcast_callback(RawAddress* bd_addr, uint16_t error,
        uint16_t error_info, uint32_t event_mask, uint8_t lmp_ver, uint16_t lmp_subver,
        uint16_t manufacturer_id, uint8_t power_level, uint8_t rssi, uint8_t link_quality){

    ALOGI("%s", __FUNCTION__);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) return;

    ScopedLocalRef<jbyteArray> addr(
    sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
        ALOGE("Error while allocation byte array in %s", __func__);
        return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                               (jbyte*)bd_addr);

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_iotDeviceBroadcast, addr.get(), (jint)error,
                    (jint)error_info, (jint)event_mask, (jint)lmp_ver, (jint)lmp_subver,
                    (jint)manufacturer_id, (jint)power_level, (jint)rssi, (jint)link_quality);

}

static void get_link_key_callback(RawAddress* bd_addr, bool key_found, LINK_KEY link_key, int key_type) {

    ALOGI("%s", __FUNCTION__);
    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) return;

    if (!bd_addr) {
        ALOGE("Address is null in %s", __func__);
        return;
    }

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
        ALOGE("Address allocation failed in %s", __func__);
        return;
    }
    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

    ScopedLocalRef<jstring> linkkey(sCallbackEnv.get(),
                                   create_link_key_string(sCallbackEnv.get(), link_key));

    sCallbackEnv->CallVoidMethod(mCallbacksObj, method_getLinkKeyCallback, linkkey.get(), addr.get(),
                                key_found, key_type);
}


static btvendor_callbacks_t sBluetoothVendorCallbacks = {
    sizeof(sBluetoothVendorCallbacks),
    bredr_cleanup_callback,
    iot_device_broadcast_callback,
    get_link_key_callback
};

static void classInitNative(JNIEnv* env, jclass clazz) {

    method_onBredrCleanup = env->GetMethodID(clazz, "onBredrCleanup", "(Z)V");
    method_iotDeviceBroadcast = env->GetMethodID(clazz, "iotDeviceBroadcast", "([BIIIIIIIII)V");
    method_getLinkKeyCallback = env->GetMethodID(clazz, "onGetLinkKey", "(Ljava/lang/String;[BZI)V");
    ALOGI("%s: succeeds", __FUNCTION__);
}

static void initNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;
    bt_status_t status;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (mCallbacksObj != NULL) {
        ALOGW("Cleaning up Bluetooth Vendor callback object");
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }

    if ( (sBluetoothVendorInterface = (btvendor_interface_t *)
          btInf->get_profile_interface(BT_PROFILE_VENDOR_ID)) == NULL) {
        ALOGE("Failed to get Bluetooth Vendor Interface");
        return;
    }

    if ( (status = sBluetoothVendorInterface->init(&sBluetoothVendorCallbacks))
                 != BT_STATUS_SUCCESS) {
        ALOGE("Failed to initialize Bluetooth Vendor, status: %d", status);
        sBluetoothVendorInterface = NULL;
        return;
    }
    mCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv *env, jobject object) {
    const bt_interface_t* btInf;
    bt_status_t status;

    if ( (btInf = getBluetoothInterface()) == NULL) {
        ALOGE("Bluetooth module is not loaded");
        return;
    }

    if (sBluetoothVendorInterface !=NULL) {
        ALOGW("Cleaning up Bluetooth Vendor Interface...");
        sBluetoothVendorInterface->cleanup();
        sBluetoothVendorInterface = NULL;
    }

    if (mCallbacksObj != NULL) {
        ALOGW("Cleaning up Bluetooth Vendor callback object");
        env->DeleteGlobalRef(mCallbacksObj);
        mCallbacksObj = NULL;
    }

}

static bool bredrcleanupNative(JNIEnv *env, jobject obj) {

    ALOGI("%s", __FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothVendorInterface) return result;

    sBluetoothVendorInterface->bredrcleanup();
    return JNI_TRUE;
}

static bool setWifiStateNative(JNIEnv *env, jobject obj, jboolean status) {

    ALOGI("%s", __FUNCTION__);

    jboolean result = JNI_FALSE;
    if (!sBluetoothVendorInterface) return result;

    sBluetoothVendorInterface->set_wifi_state(status);
    return JNI_TRUE;
}

static bool getProfileInfoNative(JNIEnv *env, jobject obj, jint profile_id , jint profile_info) {

    ALOGI("%s", __FUNCTION__);

    jboolean result = JNI_FALSE;

    if (!sBluetoothVendorInterface) return result;

    result = sBluetoothVendorInterface->get_profile_info((profile_t)profile_id, (profile_info_t)profile_info);

    return result;
}

static void getLinkKeyNative(JNIEnv* env, jobject obj, jbyteArray address) {

    ALOGI("%s", __FUNCTION__);

    jboolean result = JNI_FALSE;

    if (!sBluetoothVendorInterface) return;

    jbyte* addr = env->GetByteArrayElements(address, NULL);
    if (addr == NULL) {
        jniThrowIOException(env, EINVAL);
        return;
    }

    sBluetoothVendorInterface->get_link_key((RawAddress*)addr);
    env->ReleaseByteArrayElements(address, addr, 0);
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void *) classInitNative},
    {"initNative", "()V", (void *) initNative},
    {"cleanupNative", "()V", (void *) cleanupNative},
    {"bredrcleanupNative", "()V", (void*) bredrcleanupNative},
    {"setWifiStateNative", "(Z)V", (void*) setWifiStateNative},
    {"getProfileInfoNative", "(II)Z", (void*) getProfileInfoNative},
    {"getLinkKeyNative", "([B)V", (void*) getLinkKeyNative},

};

int register_com_android_bluetooth_btservice_vendor(JNIEnv* env)
{
    ALOGE("%s:",__FUNCTION__);
    return jniRegisterNativeMethods(env, "com/android/bluetooth/btservice/Vendor",
                                    sMethods, NELEM(sMethods));
}

} /* namespace android */
