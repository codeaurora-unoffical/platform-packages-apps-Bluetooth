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

#define LOG_TAG "BluetoothAvrcpControllerJni"

#define LOG_NDEBUG 0

#include "android_runtime/AndroidRuntime.h"
#include "com_android_bluetooth.h"
#include "hardware/bt_rc_vendor.h"
#include "utils/Log.h"

#include <string.h>

namespace android {
static jmethodID method_handlePassthroughRsp;
static jmethodID method_onConnectionStateChanged;
static jmethodID method_getRcFeatures;
static jmethodID method_setplayerappsettingrsp;
static jmethodID method_handleplayerappsetting;
static jmethodID method_handleplayerappsettingchanged;
static jmethodID method_handleSetAbsVolume;
static jmethodID method_handleRegisterNotificationAbsVol;
static jmethodID method_handletrackchanged;
static jmethodID method_handleGetElementAttrResp;
static jmethodID method_onUidsChanged;
static jmethodID method_handleplaypositionchanged;
static jmethodID method_handleplaystatuschanged;
static jmethodID method_handleGetFolderItemsRsp;
static jmethodID method_handleGetPlayerItemsRsp;
static jmethodID method_handleGroupNavigationRsp;
static jmethodID method_createFromNativeMediaItem;
static jmethodID method_createFromNativeFolderItem;
static jmethodID method_createFromNativePlayerItem;
static jmethodID method_handleChangeFolderRsp;
static jmethodID method_handleSetBrowsedPlayerRsp;
static jmethodID method_handleSetAddressedPlayerRsp;
static jmethodID method_handleSearchRsp;
static jmethodID method_handleGetItemAttrResp;
static jmethodID method_handleNumOfItemsRsp;
static jmethodID method_onAddressedPlayerChanged;
static jmethodID method_onAvailablePlayerChanged;
static jmethodID method_onNowPlayingChanged;
static jmethodID method_handleAddToNowPlayingRsp;
static jmethodID method_handleErrorStatusCode;

static jclass class_MediaBrowser_MediaItem;
static jclass class_AvrcpPlayer;

static const btrc_ctrl_interface_t* sBluetoothAvrcpInterface = NULL;
static const btrc_vendor_ctrl_interface_t* sBluetoothAvrcpVendorInterface = NULL;
static jobject sCallbacksObj = NULL;

static void btavrcp_passthrough_response_callback(RawAddress* bd_addr, int id,
                                                  int pressed) {
  ALOGI("%s: id: %d, pressed: %d", __func__, id, pressed);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr for passthrough response");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handlePassthroughRsp,
                               (jint)id, (jint)pressed, addr.get());
}

static void btavrcp_groupnavigation_response_callback(int id, int pressed) {
  ALOGV("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleGroupNavigationRsp,
                               (jint)id, (jint)pressed);
}

static void btavrcp_connection_state_callback(bool rc_connect, bool br_connect,
                                              RawAddress* bd_addr) {
  ALOGI("%s: conn state: rc: %d br: %d", __func__, rc_connect, br_connect);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr for connection state");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_onConnectionStateChanged,
                               (jboolean)rc_connect, (jboolean)br_connect,
                               addr.get());
}

static void btavrcp_get_rcfeatures_callback(RawAddress* bd_addr, int features) {
  ALOGV("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr ");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_getRcFeatures, addr.get(),
                               (jint)features, (jint) 0);
}

static void btavrcp_setplayerapplicationsetting_rsp_callback(
    RawAddress* bd_addr, uint8_t accepted) {
  ALOGV("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr ");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_setplayerappsettingrsp,
                               addr.get(), (jint)accepted);
}

static void btavrcp_playerapplicationsetting_callback(
    RawAddress* bd_addr, uint8_t num_attr, btrc_player_app_attr_t* app_attrs,
    uint8_t num_ext_attr, btrc_player_app_ext_attr_t* ext_attrs) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr ");
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  /* TODO ext attrs
   * Flattening defined attributes: <id,num_values,values[]>
   */
  jint arraylen = 0;
  for (int i = 0; i < num_attr; i++) {
    /*2 bytes for id and num */
    arraylen += 2 + app_attrs[i].num_val;
  }
  ALOGV(" arraylen %d", arraylen);

  ScopedLocalRef<jbyteArray> playerattribs(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(arraylen));
  if (!playerattribs.get()) {
    ALOGE("Fail to new jbyteArray playerattribs ");
    return;
  }

  for (int i = 0, k = 0; (i < num_attr) && (k < arraylen); i++) {
    sCallbackEnv->SetByteArrayRegion(playerattribs.get(), k, 1,
                                     (jbyte*)&(app_attrs[i].attr_id));
    k++;
    sCallbackEnv->SetByteArrayRegion(playerattribs.get(), k, 1,
                                     (jbyte*)&(app_attrs[i].num_val));
    k++;
    sCallbackEnv->SetByteArrayRegion(playerattribs.get(), k,
                                     app_attrs[i].num_val,
                                     (jbyte*)(app_attrs[i].attr_val));
    k = k + app_attrs[i].num_val;
  }
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleplayerappsetting,
                               addr.get(), playerattribs.get(), (jint)arraylen);
}

static void btavrcp_playerapplicationsetting_changed_callback(
    RawAddress* bd_addr, btrc_player_settings_t* p_vals) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  int arraylen = p_vals->num_attr * 2;
  ScopedLocalRef<jbyteArray> playerattribs(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(arraylen));
  if (!playerattribs.get()) {
    ALOGE("Fail to new jbyteArray playerattribs ");
    return;
  }
  /*
   * Flatening format: <id,val>
   */
  for (int i = 0, k = 0; (i < p_vals->num_attr) && (k < arraylen); i++) {
    sCallbackEnv->SetByteArrayRegion(playerattribs.get(), k, 1,
                                     (jbyte*)&(p_vals->attr_ids[i]));
    k++;
    sCallbackEnv->SetByteArrayRegion(playerattribs.get(), k, 1,
                                     (jbyte*)&(p_vals->attr_values[i]));
    k++;
  }
  sCallbackEnv->CallVoidMethod(sCallbacksObj,
                               method_handleplayerappsettingchanged, addr.get(),
                               playerattribs.get(), (jint)arraylen);
}

static void btavrcp_set_abs_vol_cmd_callback(RawAddress* bd_addr,
                                             uint8_t abs_vol, uint8_t label) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleSetAbsVolume,
                               addr.get(), (jbyte)abs_vol, (jbyte)label);
}

static void btavrcp_register_notification_absvol_callback(RawAddress* bd_addr,
                                                          uint8_t label) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj,
                               method_handleRegisterNotificationAbsVol,
                               addr.get(), (jbyte)label);
}

static void btavrcp_attr_rsp_callback(RawAddress* bd_addr,
                                      uint8_t num_attr,
                                      btrc_element_attr_val_t* p_attrs,
                                      jmethodID method) {
  /*
   * byteArray will be formatted like this: id,len,string
   * Assuming text feild to be null terminated.
   */
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }

  ScopedLocalRef<jintArray> attribIds(sCallbackEnv.get(),
                                      sCallbackEnv->NewIntArray(num_attr));
  if (!attribIds.get()) {
    ALOGE(" failed to set new array for attribIds");
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  jclass strclazz = sCallbackEnv->FindClass("java/lang/String");
  ScopedLocalRef<jobjectArray> stringArray(
      sCallbackEnv.get(),
      sCallbackEnv->NewObjectArray((jint)num_attr, strclazz, 0));
  if (!stringArray.get()) {
    ALOGE(" failed to get String array");
    return;
  }

  for (jint i = 0; i < num_attr; i++) {
    ScopedLocalRef<jstring> str(
        sCallbackEnv.get(),
        sCallbackEnv->NewStringUTF((char*)(p_attrs[i].text)));
    if (!str.get()) {
      ALOGE("Unable to get str");
      return;
    }
    sCallbackEnv->SetIntArrayRegion(attribIds.get(), i, 1,
                                    (jint*)&(p_attrs[i].attr_id));
    sCallbackEnv->SetObjectArrayElement(stringArray.get(), i, str.get());
  }

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method,
                               addr.get(), (jbyte)(num_attr), attribIds.get(),
                               stringArray.get());
}

static void btavrcp_track_changed_callback(RawAddress* bd_addr,
                                           uint8_t num_attr,
                                           btrc_element_attr_val_t* p_attrs) {
  ALOGI("%s", __func__);
  btavrcp_attr_rsp_callback(bd_addr, num_attr, p_attrs, method_handletrackchanged);
}

static void btavrcp_play_position_changed_callback(RawAddress* bd_addr,
                                                   uint32_t song_len,
                                                   uint32_t song_pos) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleplaypositionchanged,
                               addr.get(), (jint)(song_len), (jint)song_pos);
}

static void btavrcp_play_status_changed_callback(
    RawAddress* bd_addr, btrc_play_status_t play_status) {
  ALOGI("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to get new array ");
    return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleplaystatuschanged,
                               addr.get(), (jbyte)play_status);
}

static void btavrcp_get_folder_items_callback(
    RawAddress* bd_addr, btrc_status_t status,
    const btrc_folder_items_t* folder_items, uint8_t count) {
  /* Folder items are list of items that can be either BTRC_ITEM_PLAYER
   * BTRC_ITEM_MEDIA, BTRC_ITEM_FOLDER. Here we translate them to their java
   * counterparts by calling the java constructor for each of the items.
   */
  ALOGV("%s count %d", __func__, count);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  // Inspect if the first element is a folder/item or player listing. They are
  // always exclusive.
  bool isPlayerListing =
      count > 0 && (folder_items[0].item_type == BTRC_ITEM_PLAYER);

  // Initialize arrays for Folder OR Player listing.
  ScopedLocalRef<jobjectArray> itemArray(sCallbackEnv.get(), NULL);
  if (isPlayerListing) {
    itemArray.reset(
        sCallbackEnv->NewObjectArray((jint)count, class_AvrcpPlayer, 0));
  } else {
    itemArray.reset(sCallbackEnv->NewObjectArray(
        (jint)count, class_MediaBrowser_MediaItem, 0));
  }
  if (!itemArray.get()) {
    ALOGE("%s itemArray allocation failed.", __func__);
    return;
  }
  for (int i = 0; i < count; i++) {
    const btrc_folder_items_t* item = &(folder_items[i]);
    ALOGV("%s item type %d", __func__, item->item_type);
    switch (item->item_type) {
      case BTRC_ITEM_MEDIA: {
        // Parse name
        ScopedLocalRef<jstring> mediaName(
            sCallbackEnv.get(),
            sCallbackEnv->NewStringUTF((const char*)item->media.name));
        if (!mediaName.get()) {
          ALOGE("%s can't allocate media name string!", __func__);
          return;
        }
        // Parse UID
        ScopedLocalRef<jbyteArray> uidByteArray(
            sCallbackEnv.get(),
            sCallbackEnv->NewByteArray(sizeof(uint8_t) * BTRC_UID_SIZE));
        if (!uidByteArray.get()) {
          ALOGE("%s can't allocate uid array!", __func__);
          return;
        }
        sCallbackEnv->SetByteArrayRegion(uidByteArray.get(), 0,
                                         BTRC_UID_SIZE * sizeof(uint8_t),
                                         (jbyte*)item->media.uid);

        // Parse Attrs
        ScopedLocalRef<jintArray> attrIdArray(
            sCallbackEnv.get(),
            sCallbackEnv->NewIntArray(item->media.num_attrs));
        if (!attrIdArray.get()) {
          ALOGE("%s can't allocate attr id array!", __func__);
          return;
        }
        ScopedLocalRef<jobjectArray> attrValArray(
            sCallbackEnv.get(),
            sCallbackEnv->NewObjectArray(
                item->media.num_attrs,
                sCallbackEnv->FindClass("java/lang/String"), 0));
        if (!attrValArray.get()) {
          ALOGE("%s can't allocate attr val array!", __func__);
          return;
        }

        for (int j = 0; j < item->media.num_attrs; j++) {
          sCallbackEnv->SetIntArrayRegion(
              attrIdArray.get(), j, 1,
              (jint*)&(item->media.p_attrs[j].attr_id));
          ScopedLocalRef<jstring> attrValStr(
              sCallbackEnv.get(),
              sCallbackEnv->NewStringUTF((char*)(item->media.p_attrs[j].text)));
          if (!uidByteArray.get()) {
            ALOGE("%s can't allocate uid array!", __func__);
            return;
          }
          sCallbackEnv->SetObjectArrayElement(attrValArray.get(), j,
                                              attrValStr.get());
        }

        ScopedLocalRef<jobject> mediaObj(
            sCallbackEnv.get(),
            (jobject)sCallbackEnv->CallObjectMethod(
                sCallbacksObj, method_createFromNativeMediaItem,
                uidByteArray.get(), (jint)item->media.type, mediaName.get(),
                attrIdArray.get(), attrValArray.get()));
        if (!mediaObj.get()) {
          ALOGE("%s failed to creae MediaItem for type ITEM_MEDIA", __func__);
          return;
        }
        sCallbackEnv->SetObjectArrayElement(itemArray.get(), i, mediaObj.get());
        break;
      }

      case BTRC_ITEM_FOLDER: {
        // Parse name
        ScopedLocalRef<jstring> folderName(
            sCallbackEnv.get(),
            sCallbackEnv->NewStringUTF((const char*)item->folder.name));
        if (!folderName.get()) {
          ALOGE("%s can't allocate folder name string!", __func__);
          return;
        }
        // Parse UID
        ScopedLocalRef<jbyteArray> uidByteArray(
            sCallbackEnv.get(),
            sCallbackEnv->NewByteArray(sizeof(uint8_t) * BTRC_UID_SIZE));
        if (!uidByteArray.get()) {
          ALOGE("%s can't allocate uid array!", __func__);
          return;
        }
        sCallbackEnv->SetByteArrayRegion(uidByteArray.get(), 0,
                                         BTRC_UID_SIZE * sizeof(uint8_t),
                                         (jbyte*)item->folder.uid);

        ScopedLocalRef<jobject> folderObj(
            sCallbackEnv.get(),
            (jobject)sCallbackEnv->CallObjectMethod(
                sCallbacksObj, method_createFromNativeFolderItem,
                uidByteArray.get(), (jint)item->folder.type, folderName.get(),
                (jint)item->folder.playable));
        if (!folderObj.get()) {
          ALOGE("%s failed to create MediaItem for type ITEM_FOLDER", __func__);
          return;
        }
        sCallbackEnv->SetObjectArrayElement(itemArray.get(), i,
                                            folderObj.get());
        break;
      }

      case BTRC_ITEM_PLAYER: {
        // Parse name
        isPlayerListing = true;
        jint id = (jint)item->player.player_id;
        jint playerType = (jint)item->player.major_type;
        jint playStatus = (jint)item->player.play_status;
        ScopedLocalRef<jbyteArray> featureBitArray(
            sCallbackEnv.get(),
            sCallbackEnv->NewByteArray(BTRC_FEATURE_BIT_MASK_SIZE *
                                       sizeof(uint8_t)));
        if (!featureBitArray.get()) {
          ALOGE("%s failed to allocate featureBitArray", __func__);
          return;
        }
        sCallbackEnv->SetByteArrayRegion(
            featureBitArray.get(), 0,
            sizeof(uint8_t) * BTRC_FEATURE_BIT_MASK_SIZE,
            (jbyte*)item->player.features);
        ScopedLocalRef<jstring> playerName(
            sCallbackEnv.get(),
            sCallbackEnv->NewStringUTF((const char*)item->player.name));
        if (!playerName.get()) {
          ALOGE("%s can't allocate player name string!", __func__);
          return;
        }
        ScopedLocalRef<jobject> playerObj(
            sCallbackEnv.get(),
            (jobject)sCallbackEnv->CallObjectMethod(
                sCallbacksObj, method_createFromNativePlayerItem, id,
                playerName.get(), featureBitArray.get(), playStatus,
                playerType));
        if (!playerObj.get()) {
          ALOGE("%s failed to create AvrcpPlayer from ITEM_PLAYER", __func__);
          return;
        }
        sCallbackEnv->SetObjectArrayElement(itemArray.get(), i,
                                            playerObj.get());
        break;
      }

      default:
        ALOGE("%s cannot understand type %d", __func__, item->item_type);
    }
  }

  if (isPlayerListing) {
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleGetPlayerItemsRsp,
                                 itemArray.get());
  } else {
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleGetFolderItemsRsp,
                                 status, itemArray.get());
  }
}

static void btavrcp_change_path_callback(RawAddress* bd_addr, uint32_t count) {
  ALOGI("%s count %d", __func__, count);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleChangeFolderRsp,
                               (jint)count);
}

static void btavrcp_set_browsed_player_callback(RawAddress* bd_addr,
                                                uint8_t num_items,
                                                uint8_t depth) {
  ALOGI("%s items %d depth %d", __func__, num_items, depth);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleSetBrowsedPlayerRsp,
                               (jint)num_items, (jint)depth);
}

static void btavrcp_set_addressed_player_callback(RawAddress* bd_addr,
                                                  uint8_t status) {
  ALOGI("%s status %d", __func__, status);

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj, method_handleSetAddressedPlayerRsp, (jint)status);
}

static void btavrcp_get_vendor_rcfeatures_callback(RawAddress* bd_addr, int features,
    uint16_t cover_art_psm) {
  ALOGV("%s", __func__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
      sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
    ALOGE("Fail to new jbyteArray bd addr ");
    return;
  }

  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);
  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_getRcFeatures, addr.get(),
                               (jint)features, (jint) cover_art_psm);
}

static void  btavrcp_get_elem_attr_rsp_callback(RawAddress *bd_addr,
        uint8_t num_attr, btrc_element_attr_val_t *p_attrs) {
  ALOGI("%s", __func__);
  btavrcp_attr_rsp_callback(bd_addr, num_attr, p_attrs, method_handleGetElementAttrResp);
}

static void btavrcp_uids_changed_callback (RawAddress *bd_addr, uint16_t uid_counter) {
  ALOGI("%s", __FUNCTION__);
  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  ScopedLocalRef<jbyteArray> addr(
    sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
  if (!addr.get()) {
      ALOGE("Fail to new jbyteArray bd addr ");
      return;
  }
  sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                   (jbyte*)bd_addr);

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_onUidsChanged,
                               addr.get(),(jint)uid_counter);
}

static void btavrcp_search_response_callback(RawAddress* bd_addr, uint8_t status,
                                             uint16_t uid_counter, uint32_t num_items) {
  ALOGI("%s: status: %d, uid_counter: %d, num_items: %d", __func__, status, uid_counter, num_items);

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleSearchRsp,
                               (jint)status, (jint)uid_counter, (jint)num_items);
}

static void  btavrcp_get_item_attr_rsp_callback(RawAddress *bd_addr,
        uint8_t num_attr, btrc_element_attr_val_t *p_attrs) {
  ALOGI("%s", __func__);
  btavrcp_attr_rsp_callback(bd_addr, num_attr, p_attrs, method_handleGetItemAttrResp);
}


static void btavrcp_num_of_items_rsp_callback(RawAddress* bd_addr, uint8_t status,
                                              uint16_t uid_counter, uint32_t num_items) {
  ALOGI("%s: status: %d, uid_counter: %d, num_items: %d", __func__, status, uid_counter, num_items);

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(sCallbacksObj, method_handleNumOfItemsRsp,
                               (jint)status, (jint)uid_counter, (jint)num_items);
}

static void btavrcp_addressed_player_update_callback (RawAddress* bd_addr,
                                             uint16_t player_id, uint16_t uid_counter)
{
    ALOGI("%s, player_id: %d, uid_counter: %d", __func__, player_id, uid_counter);

    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      ALOGE("Fail to get new array ");
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)bd_addr);
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_onAddressedPlayerChanged, addr.get(),
                                 (jint)(player_id), (jint)uid_counter);
}

static void btavrcp_available_player_changed_callback (RawAddress* bd_addr)
{
    ALOGI("%s", __func__);

    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      ALOGE("Fail to get new array ");
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)bd_addr);
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_onAvailablePlayerChanged, addr.get());
}

static void btavrcp_now_playing_changed_callback (RawAddress* bd_addr)
{
    ALOGI("%s", __func__);

    CallbackEnv sCallbackEnv(__func__);
    if (!sCallbackEnv.valid()) return;

    ScopedLocalRef<jbyteArray> addr(
        sCallbackEnv.get(), sCallbackEnv->NewByteArray(sizeof(RawAddress)));
    if (!addr.get()) {
      ALOGE("Fail to get new array ");
      return;
    }

    sCallbackEnv->SetByteArrayRegion(addr.get(), 0, sizeof(RawAddress),
                                     (jbyte*)bd_addr);
    sCallbackEnv->CallVoidMethod(sCallbacksObj, method_onNowPlayingChanged, addr.get());
}

static void btavrcp_add_to_now_playing_callback(RawAddress* bd_addr,
                                                uint8_t status) {
  ALOGI("%s status %d", __func__, status);

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj, method_handleAddToNowPlayingRsp, (jint)status);
}

static void btavrcp_error_status_code_callback(RawAddress* bd_addr,
                                             uint8_t opcode, uint8_t id, uint8_t status) {
  ALOGI("%s opcode: %d, id: %d, status: %d", __func__, opcode, id, status);

  CallbackEnv sCallbackEnv(__func__);
  if (!sCallbackEnv.valid()) return;

  sCallbackEnv->CallVoidMethod(
      sCallbacksObj, method_handleErrorStatusCode, (jint)opcode, (jint)id, (jint)status);
}

static btrc_ctrl_callbacks_t sBluetoothAvrcpCallbacks = {
    sizeof(sBluetoothAvrcpCallbacks),
    btavrcp_passthrough_response_callback,
    btavrcp_groupnavigation_response_callback,
    btavrcp_connection_state_callback,
    btavrcp_get_rcfeatures_callback,
    btavrcp_setplayerapplicationsetting_rsp_callback,
    btavrcp_playerapplicationsetting_callback,
    btavrcp_playerapplicationsetting_changed_callback,
    btavrcp_set_abs_vol_cmd_callback,
    btavrcp_register_notification_absvol_callback,
    btavrcp_track_changed_callback,
    btavrcp_play_position_changed_callback,
    btavrcp_play_status_changed_callback,
    btavrcp_get_folder_items_callback,
    btavrcp_change_path_callback,
    btavrcp_set_browsed_player_callback,
    btavrcp_set_addressed_player_callback};

static btrc_vendor_ctrl_callbacks_t  sBluetoothAvrcpVendorCallbacks = {
    sizeof(sBluetoothAvrcpVendorCallbacks),
    btavrcp_get_vendor_rcfeatures_callback,
    btavrcp_get_elem_attr_rsp_callback,
    btavrcp_uids_changed_callback,
    btavrcp_search_response_callback,
    btavrcp_get_item_attr_rsp_callback,
    btavrcp_num_of_items_rsp_callback,
    btavrcp_addressed_player_update_callback,
    btavrcp_available_player_changed_callback,
    btavrcp_now_playing_changed_callback,
    btavrcp_add_to_now_playing_callback,
    btavrcp_error_status_code_callback
};

static void classInitNative(JNIEnv* env, jclass clazz) {
  method_handlePassthroughRsp =
      env->GetMethodID(clazz, "handlePassthroughRsp", "(II[B)V");

  method_handleGroupNavigationRsp =
      env->GetMethodID(clazz, "handleGroupNavigationRsp", "(II)V");

  method_onConnectionStateChanged =
      env->GetMethodID(clazz, "onConnectionStateChanged", "(ZZ[B)V");

  method_getRcFeatures = env->GetMethodID(clazz, "getRcFeatures", "([BII)V");

  method_setplayerappsettingrsp =
      env->GetMethodID(clazz, "setPlayerAppSettingRsp", "([BB)V");

  method_handleplayerappsetting =
      env->GetMethodID(clazz, "handlePlayerAppSetting", "([B[BI)V");

  method_handleplayerappsettingchanged =
      env->GetMethodID(clazz, "onPlayerAppSettingChanged", "([B[BI)V");

  method_handleSetAbsVolume =
      env->GetMethodID(clazz, "handleSetAbsVolume", "([BBB)V");

  method_handleRegisterNotificationAbsVol =
      env->GetMethodID(clazz, "handleRegisterNotificationAbsVol", "([BB)V");

  method_handletrackchanged =
      env->GetMethodID(clazz, "onTrackChanged", "([BB[I[Ljava/lang/String;)V");

  method_handleGetElementAttrResp =
       env->GetMethodID(clazz, "onElementAttributeUpdate", "([BB[I[Ljava/lang/String;)V");

  method_handleGetItemAttrResp =
       env->GetMethodID(clazz, "onItemAttributeUpdate", "([BB[I[Ljava/lang/String;)V");

  method_onUidsChanged =
       env->GetMethodID(clazz, "onUidsChanged", "([BI)V");

  method_handleplaypositionchanged =
      env->GetMethodID(clazz, "onPlayPositionChanged", "([BII)V");

  method_handleplaystatuschanged =
      env->GetMethodID(clazz, "onPlayStatusChanged", "([BB)V");

  method_handleGetFolderItemsRsp =
      env->GetMethodID(clazz, "handleGetFolderItemsRsp",
                       "(I[Landroid/media/browse/MediaBrowser$MediaItem;)V");
  method_handleGetPlayerItemsRsp = env->GetMethodID(
      clazz, "handleGetPlayerItemsRsp",
      "([Lcom/android/bluetooth/avrcpcontroller/AvrcpPlayer;)V");

  method_createFromNativeMediaItem =
      env->GetMethodID(clazz, "createFromNativeMediaItem",
                       "([BILjava/lang/String;[I[Ljava/lang/String;)Landroid/"
                       "media/browse/MediaBrowser$MediaItem;");
  method_createFromNativeFolderItem = env->GetMethodID(
      clazz, "createFromNativeFolderItem",
      "([BILjava/lang/String;I)Landroid/media/browse/MediaBrowser$MediaItem;");
  method_createFromNativePlayerItem =
      env->GetMethodID(clazz, "createFromNativePlayerItem",
                       "(ILjava/lang/String;[BII)Lcom/android/bluetooth/"
                       "avrcpcontroller/AvrcpPlayer;");
  method_handleChangeFolderRsp =
      env->GetMethodID(clazz, "handleChangeFolderRsp", "(I)V");
  method_handleSetBrowsedPlayerRsp =
      env->GetMethodID(clazz, "handleSetBrowsedPlayerRsp", "(II)V");
  method_handleSetAddressedPlayerRsp =
      env->GetMethodID(clazz, "handleSetAddressedPlayerRsp", "(I)V");

  method_handleSearchRsp =
      env->GetMethodID(clazz, "handleSearchRsp", "(III)V");

  method_handleNumOfItemsRsp =
      env->GetMethodID(clazz, "handleNumOfItemsRsp", "(III)V");

  method_onAddressedPlayerChanged =
          env->GetMethodID(clazz, "onAddressedPlayerChanged", "([BII)V");
  
  method_onAvailablePlayerChanged =
          env->GetMethodID(clazz, "onAvailablePlayerChanged", "([B)V");

  method_onNowPlayingChanged =
          env->GetMethodID(clazz, "onNowPlayingChanged", "([B)V");

  method_handleAddToNowPlayingRsp =
      env->GetMethodID(clazz, "handleAddToNowPlayingRsp", "(I)V");

  method_handleErrorStatusCode =
      env->GetMethodID(clazz, "handleErrorStatusCode", "(III)V");

  ALOGI("%s: succeeds", __func__);
}

static void initNative(JNIEnv* env, jobject object) {
  jclass tmpMediaItem =
      env->FindClass("android/media/browse/MediaBrowser$MediaItem");
  class_MediaBrowser_MediaItem = (jclass)env->NewGlobalRef(tmpMediaItem);

  jclass tmpBtPlayer =
      env->FindClass("com/android/bluetooth/avrcpcontroller/AvrcpPlayer");
  class_AvrcpPlayer = (jclass)env->NewGlobalRef(tmpBtPlayer);

  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == NULL) {
    ALOGE("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothAvrcpVendorInterface != NULL) {
    ALOGW("Cleaning up Avrcp Vendor Interface before initializing...");
    sBluetoothAvrcpVendorInterface->cleanup_vendor();
    sBluetoothAvrcpVendorInterface = NULL;
  }

  if (sBluetoothAvrcpInterface != NULL) {
    ALOGW("Cleaning up Avrcp Interface before initializing...");
    sBluetoothAvrcpInterface->cleanup();
    sBluetoothAvrcpInterface = NULL;
  }

  if (sCallbacksObj != NULL) {
    ALOGW("Cleaning up Avrcp callback object");
    env->DeleteGlobalRef(sCallbacksObj);
    sCallbacksObj = NULL;
  }

  sBluetoothAvrcpInterface =
      (btrc_ctrl_interface_t*)btInf->get_profile_interface(
          BT_PROFILE_AV_RC_CTRL_ID);
  if (sBluetoothAvrcpInterface == NULL) {
    ALOGE("Failed to get Bluetooth Avrcp Controller Interface");
    return;
  }

  bt_status_t status =
      sBluetoothAvrcpInterface->init(&sBluetoothAvrcpCallbacks);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed to initialize Bluetooth Avrcp Controller, status: %d",
          status);
    sBluetoothAvrcpInterface = NULL;
    return;
  }

  sBluetoothAvrcpVendorInterface =
      (btrc_vendor_ctrl_interface_t*)btInf->get_profile_interface(
          BT_PROFILE_AV_RC_VENDOR_CTRL_ID);
  if (sBluetoothAvrcpVendorInterface == NULL) {
    ALOGE("Failed to get Bluetooth Avrcp Vendor Controller Interface");
    sBluetoothAvrcpInterface->cleanup();
    sBluetoothAvrcpInterface = NULL;
    return;
  }

  status =
      sBluetoothAvrcpVendorInterface->init_vendor(&sBluetoothAvrcpVendorCallbacks);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed to initialize Bluetooth Avrcp Vendor Controller, status: %d",
          status);
    sBluetoothAvrcpVendorInterface = NULL;
    sBluetoothAvrcpInterface->cleanup();
    sBluetoothAvrcpInterface = NULL;
    return;
  }


  sCallbacksObj = env->NewGlobalRef(object);
}

static void cleanupNative(JNIEnv* env, jobject object) {
  const bt_interface_t* btInf = getBluetoothInterface();
  if (btInf == NULL) {
    ALOGE("Bluetooth module is not loaded");
    return;
  }

  if (sBluetoothAvrcpVendorInterface != NULL) {
    sBluetoothAvrcpVendorInterface->cleanup_vendor();
    sBluetoothAvrcpVendorInterface = NULL;
  }

  if (sBluetoothAvrcpInterface != NULL) {
    sBluetoothAvrcpInterface->cleanup();
    sBluetoothAvrcpInterface = NULL;
  }

  if (sCallbacksObj != NULL) {
    env->DeleteGlobalRef(sCallbacksObj);
    sCallbacksObj = NULL;
  }
}

static jboolean sendPassThroughCommandNative(JNIEnv* env, jobject object,
                                             jbyteArray address, jint key_code,
                                             jint key_state) {
  if (!sBluetoothAvrcpInterface) return JNI_FALSE;

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);

  ALOGI("key_code: %d, key_state: %d", key_code, key_state);

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  bt_status_t status = sBluetoothAvrcpInterface->send_pass_through_cmd(
      (RawAddress*)addr, (uint8_t)key_code, (uint8_t)key_state);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending passthru command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static jboolean sendGroupNavigationCommandNative(JNIEnv* env, jobject object,
                                                 jbyteArray address,
                                                 jint key_code,
                                                 jint key_state) {
  if (!sBluetoothAvrcpInterface) return JNI_FALSE;

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);

  ALOGI("key_code: %d, key_state: %d", key_code, key_state);

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return JNI_FALSE;
  }

  bt_status_t status = sBluetoothAvrcpInterface->send_group_navigation_cmd(
      (RawAddress*)addr, (uint8_t)key_code, (uint8_t)key_state);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending Grp Navigation command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);

  return (status == BT_STATUS_SUCCESS) ? JNI_TRUE : JNI_FALSE;
}

static void setPlayerApplicationSettingValuesNative(JNIEnv* env, jobject object,
                                                    jbyteArray address,
                                                    jbyte num_attrib,
                                                    jbyteArray attrib_ids,
                                                    jbyteArray attrib_val) {
  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  if (!sBluetoothAvrcpInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  uint8_t* pAttrs = new uint8_t[num_attrib];
  uint8_t* pAttrsVal = new uint8_t[num_attrib];
  if ((!pAttrs) || (!pAttrsVal)) {
    delete[] pAttrs;
    ALOGE("setPlayerApplicationSettingValuesNative: not have enough memeory");
    return;
  }

  jbyte* attr = env->GetByteArrayElements(attrib_ids, NULL);
  jbyte* attr_val = env->GetByteArrayElements(attrib_val, NULL);
  if ((!attr) || (!attr_val)) {
    delete[] pAttrs;
    delete[] pAttrsVal;
    jniThrowIOException(env, EINVAL);
    return;
  }

  int i;
  for (i = 0; i < num_attrib; ++i) {
    pAttrs[i] = (uint8_t)attr[i];
    pAttrsVal[i] = (uint8_t)attr_val[i];
  }

  bt_status_t status = sBluetoothAvrcpInterface->set_player_app_setting_cmd(
      (RawAddress*)addr, (uint8_t)num_attrib, pAttrs, pAttrsVal);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending setPlAppSettValNative command, status: %d", status);
  }
  delete[] pAttrs;
  delete[] pAttrsVal;
  env->ReleaseByteArrayElements(attrib_ids, attr, 0);
  env->ReleaseByteArrayElements(attrib_val, attr_val, 0);
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void sendAbsVolRspNative(JNIEnv* env, jobject object, jbyteArray address,
                                jint abs_vol, jint label) {
  if (!sBluetoothAvrcpInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->set_volume_rsp(
      (RawAddress*)addr, (uint8_t)abs_vol, (uint8_t)label);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending sendAbsVolRspNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void sendRegisterAbsVolRspNative(JNIEnv* env, jobject object,
                                        jbyteArray address, jbyte rsp_type,
                                        jint abs_vol, jint label) {
  if (!sBluetoothAvrcpInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->register_abs_vol_rsp(
      (RawAddress*)addr, (btrc_notification_type_t)rsp_type, (uint8_t)abs_vol,
      (uint8_t)label);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending sendRegisterAbsVolRspNative command, status: %d",
          status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getPlaybackStateNative(JNIEnv* env, jobject object,
                                   jbyteArray address) {
  if (!sBluetoothAvrcpInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGV("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status =
      sBluetoothAvrcpInterface->get_playback_state_cmd((RawAddress*)addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getPlaybackStateNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getNowPlayingListNative(JNIEnv* env, jobject object,
                                    jbyteArray address, jint start, jint end) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGV("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->get_now_playing_list_cmd(
      (RawAddress*)addr, start, end);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getNowPlayingListNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getFolderListNative(JNIEnv* env, jobject object, jbyteArray address,
                                jint start, jint end) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGV("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->get_folder_list_cmd(
      (RawAddress*)addr, start, end);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getFolderListNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getPlayerListNative(JNIEnv* env, jobject object, jbyteArray address,
                                jint start, jint end) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);

  bt_status_t status = sBluetoothAvrcpInterface->get_player_list_cmd(
      (RawAddress*)addr, start, end);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getPlayerListNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void changeFolderPathNative(JNIEnv* env, jobject object,
                                   jbyteArray address, jint uidCounter,
                                   jbyte direction, jbyteArray uidarr) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  jbyte* uid = env->GetByteArrayElements(uidarr, NULL);
  if (!uid) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);

  bt_status_t status = sBluetoothAvrcpInterface->change_folder_path_cmd(
      (RawAddress*)addr, (uint16_t)uidCounter, (uint8_t)direction, (uint8_t*)uid);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending changeFolderPathNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void setBrowsedPlayerNative(JNIEnv* env, jobject object,
                                   jbyteArray address, jint id) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->set_browsed_player_cmd(
      (RawAddress*)addr, (uint16_t)id);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending setBrowsedPlayerNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void setAddressedPlayerNative(JNIEnv* env, jobject object,
                                     jbyteArray address, jint id) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->set_addressed_player_cmd(
      (RawAddress*)addr, (uint16_t)id);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending setAddressedPlayerNative command, status: %d",
          status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void playItemNative(JNIEnv* env, jobject object, jbyteArray address,
                           jbyte scope, jbyteArray uidArr, jint uidCounter) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  jbyte* uid = env->GetByteArrayElements(uidArr, NULL);
  if (!uid) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpInterface->play_item_cmd(
      (RawAddress*)addr, (uint8_t)scope, (uint8_t*)uid, (uint16_t)uidCounter);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending playItemNative command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
  env->ReleaseByteArrayElements(uidArr, uid, 0);
}

/* This api is used to fetch metadata for currently playing track
 * num_attribs: number of attributes to be fetched. 0 corresponds to fetch  all
 *              attributes
 * attrib_ids: list of attributes we want to fetch. NULL corresponds to fetch
 *             all attributes.
 */
static void getItemElementAttributesNative(JNIEnv *env, jobject object, jbyteArray address,
                                       jbyte numAttr, jintArray attrIds) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  if (numAttr > BTRC_MAX_ELEM_ATTR_SIZE) {
    ALOGE("getItemElementAttributesNative: number of attributes exceed maximum");
    return;
  }

  jint* attr = NULL;
  if ((numAttr > 0) && (attrIds != NULL)) {
    attr = env->GetIntArrayElements(attrIds, NULL);
    if (!attr) {
      jniThrowIOException(env, EINVAL);
      return;
    }
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->get_media_element_attributes_vendor(
      (RawAddress*)addr, numAttr, (uint32_t*)attr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getItemElementAttributesNative command, status: %d", status);
  }

  if (attr) env->ReleaseIntArrayElements(attrIds, attr, 0);
  env->ReleaseByteArrayElements(address, addr, 0);
}

/* This api is used to fetch metadata for currently playing track
 * num_attribs: number of attributes to be fetched. 0 corresponds to fetch  all
 *              attributes
 * attrib_ids: list of attributes we want to fetch. NULL corresponds to fetch
 *             all attributes.
 */
static void getElementAttributesNative(JNIEnv *env, jobject object, jbyteArray address,
                                       jbyte numAttr, jintArray attrIds) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  if (numAttr > BTRC_MAX_ELEM_ATTR_SIZE) {
    ALOGE("getElementAttributesNative: number of attributes exceed maximum");
    return;
  }

  jint* attr = NULL;
  if ((numAttr > 0) && (attrIds != NULL)) {
    attr = env->GetIntArrayElements(attrIds, NULL);
    if (!attr) {
      jniThrowIOException(env, EINVAL);
      return;
    }
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->get_element_attributes_cmd(
      (RawAddress*)addr, numAttr, (uint32_t*)attr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getElementAttributesNative command, status: %d", status);
  }

  if (attr) env->ReleaseIntArrayElements(attrIds, attr, 0);
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getFolderItemsNative(JNIEnv* env, jobject object, jbyteArray address,
                           jbyte scope, jint start, jint end, jbyte numAttr,
                           jintArray attrIds) {
    if (!sBluetoothAvrcpVendorInterface) return;

    jbyte* addr = env->GetByteArrayElements(address, NULL);
    if (!addr) {
      jniThrowIOException(env, EINVAL);
      return;
    }

    if (numAttr > BTRC_MAX_ELEM_ATTR_SIZE) {
      ALOGE("getFolderItemsNative: number of attributes exceed maximum");
      return;
    }

    jint* attr = NULL;
    if ((numAttr > 0) && (attrIds != NULL)) {
      attr = env->GetIntArrayElements(attrIds, NULL);
      if (!attr) {
        jniThrowIOException(env, EINVAL);
        return;
      }
    }

    ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
    bt_status_t status = sBluetoothAvrcpVendorInterface->get_folder_items_vendor_cmd(
        (RawAddress*)addr, scope, start, end, numAttr, (uint32_t*)attr);
    if (status != BT_STATUS_SUCCESS) {
      ALOGE("Failed sending getFolderItemsNative command, status: %d", status);
    }

    if (attr) env->ReleaseIntArrayElements(attrIds, attr, 0);
    env->ReleaseByteArrayElements(address, addr, 0);
}

static void searchNative(JNIEnv *env, jobject object, jbyteArray address, jint charset,
                           jint strLen, jstring pattern) {
  bt_status_t status;
  jbyte *addr;
  const char* search_pattern = NULL;

  if (!sBluetoothAvrcpVendorInterface) return;

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __FUNCTION__, sBluetoothAvrcpVendorInterface);

  addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  search_pattern = env->GetStringUTFChars(pattern, NULL);

  status = sBluetoothAvrcpVendorInterface->search_cmd((RawAddress*)addr, (uint16_t)charset,
      (uint16_t)strLen, (uint8_t*)search_pattern);

  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending searchNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
  env->ReleaseStringUTFChars(pattern, search_pattern);
}

static void getSearchListNative(JNIEnv* env, jobject object, jbyteArray address,
                                jint start, jint items) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGV("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);

  bt_status_t status = sBluetoothAvrcpVendorInterface->get_search_list_cmd(
      (RawAddress*)addr, start, items);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getSearchListNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
}

static void addToNowPlayingNative(JNIEnv* env, jobject object, jbyteArray address,
                                  jbyte scope, jbyteArray uidArr, jint uidCounter) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  jbyte* uid = env->GetByteArrayElements(uidArr, NULL);
  if (!uid) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->add_to_now_playing_cmd(
      (RawAddress*)addr, (uint8_t)scope, (uint8_t*)uid, (uint16_t)uidCounter);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending addToNowPlayingNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(uidArr, uid, 0);
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getItemAttributesNative(JNIEnv* env, jobject object, jbyteArray address,
                                    jbyte scope, jbyteArray uidArr, jint uidCounter,
                                    jbyte numAttr, jintArray attrIds) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  jbyte* uid = env->GetByteArrayElements(uidArr, NULL);
  if (!uid) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  if (numAttr > BTRC_MAX_ELEM_ATTR_SIZE) {
    ALOGE("getItemAttributesNative: number of attributes exceed maximum");
    return;
  }

  jint* attr = NULL;
  if ((numAttr > 0) && (attrIds != NULL)) {
    attr = env->GetIntArrayElements(attrIds, NULL);
    if (!attr) {
      jniThrowIOException(env, EINVAL);
      return;
    }
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->get_item_attr_cmd(
      (RawAddress*)addr, (uint8_t)scope, (uint8_t*)uid, (uint16_t)uidCounter,
      numAttr, (uint32_t*)attr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getItemAttributesNative command, status: %d", status);
  }

  if (attr) env->ReleaseIntArrayElements(attrIds, attr, 0);
  env->ReleaseByteArrayElements(uidArr, uid, 0);
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void getTotalNumOfItemsNative(JNIEnv* env, jobject object, jbyteArray address, jbyte scope) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->get_num_of_items_cmd(
      (RawAddress*)addr, (uint8_t)scope);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending getTotalNumOfItemsNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
}

static void fetchPlayerApplicationSettingNative(JNIEnv* env, jobject object, jbyteArray address) {
  if (!sBluetoothAvrcpInterface) return;
  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }
  ALOGV("%s: sBluetoothAvrcpInterface: %p", __func__, sBluetoothAvrcpInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->fetch_player_app_setting((RawAddress*)addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending fetch_player_app_setting command, status: %d", status);
  }
  env->ReleaseByteArrayElements(address, addr, 0);
}

static void requestContinuingResponseNative(JNIEnv* env, jobject object, jbyteArray address, jbyte pduId) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->request_continuing_response_cmd(
      (RawAddress*)addr, (uint8_t)pduId);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending requestContinuingResponseNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
}

static void abortContinuingResponseNative(JNIEnv* env, jobject object, jbyteArray address, jbyte pduId) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->abort_continuing_response_cmd(
      (RawAddress*)addr, (uint8_t)pduId);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed sending abortContinuingResponseNative command, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
}

static void disconnectNative(JNIEnv* env, jobject object, jbyteArray address) {
  if (!sBluetoothAvrcpVendorInterface) return;

  jbyte* addr = env->GetByteArrayElements(address, NULL);
  if (!addr) {
    jniThrowIOException(env, EINVAL);
    return;
  }

  ALOGI("%s: sBluetoothAvrcpVendorInterface: %p", __func__, sBluetoothAvrcpVendorInterface);
  bt_status_t status = sBluetoothAvrcpVendorInterface->disconnect((RawAddress*)addr);
  if (status != BT_STATUS_SUCCESS) {
    ALOGE("Failed to disconnect, status: %d", status);
  }

  env->ReleaseByteArrayElements(address, addr, 0);
}

static JNINativeMethod sMethods[] = {
    {"classInitNative", "()V", (void*)classInitNative},
    {"initNative", "()V", (void*)initNative},
    {"cleanupNative", "()V", (void*)cleanupNative},
    {"sendPassThroughCommandNative", "([BII)Z",
     (void*)sendPassThroughCommandNative},
    {"sendGroupNavigationCommandNative", "([BII)Z",
     (void*)sendGroupNavigationCommandNative},
    {"setPlayerApplicationSettingValuesNative", "([BB[B[B)V",
     (void*)setPlayerApplicationSettingValuesNative},
    {"sendAbsVolRspNative", "([BII)V", (void*)sendAbsVolRspNative},
    {"sendRegisterAbsVolRspNative", "([BBII)V",
     (void*)sendRegisterAbsVolRspNative},
    {"getPlaybackStateNative", "([B)V", (void*)getPlaybackStateNative},
    {"getNowPlayingListNative", "([BII)V", (void*)getNowPlayingListNative},
    {"getFolderListNative", "([BII)V", (void*)getFolderListNative},
    {"getPlayerListNative", "([BII)V", (void*)getPlayerListNative},
    {"changeFolderPathNative", "([BIB[B)V", (void*)changeFolderPathNative},
    {"playItemNative", "([BB[BI)V", (void*)playItemNative},
    {"setBrowsedPlayerNative", "([BI)V", (void*)setBrowsedPlayerNative},
    {"setAddressedPlayerNative", "([BI)V", (void*)setAddressedPlayerNative},
    {"getItemElementAttributesNative", "([BB[I)V",(void *) getItemElementAttributesNative},
    {"getElementAttributesNative", "([BB[I)V",(void *) getElementAttributesNative},
    {"getFolderItemsNative", "([BBIIB[I)V", (void *) getFolderItemsNative},
    {"searchNative", "([BIILjava/lang/String;)V",(void *) searchNative},
    {"getSearchListNative", "([BII)V", (void*)getSearchListNative},
    {"addToNowPlayingNative", "([BB[BI)V",(void *) addToNowPlayingNative},
    {"getItemAttributesNative", "([BB[BIB[I)V",(void *) getItemAttributesNative},
    {"getTotalNumOfItemsNative", "([BB)V",(void *) getTotalNumOfItemsNative},
    {"fetchPlayerApplicationSettingNative", "([B)V", (void*)fetchPlayerApplicationSettingNative},
    {"requestContinuingResponseNative", "([BB)V",(void *) requestContinuingResponseNative},
    {"abortContinuingResponseNative", "([BB)V",(void *) abortContinuingResponseNative},
    {"disconnectNative", "([B)V",(void *) disconnectNative},
};

int register_com_android_bluetooth_avrcp_controller(JNIEnv* env) {
  return jniRegisterNativeMethods(
      env, "com/android/bluetooth/avrcpcontroller/AvrcpControllerService",
      sMethods, NELEM(sMethods));
}
}
