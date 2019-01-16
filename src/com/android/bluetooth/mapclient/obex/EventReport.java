/*
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

package com.android.bluetooth.mapclient;

import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.HashMap;

/**
 * Object representation of event report received by MNS
 * <p>
 * This object will be received in {@link Client#EVENT_EVENT_REPORT}
 * callback message.
 */
public class EventReport {
    private static final String TAG = "EventReport";
    /* MAP-Event-Report Version 1.0 */
    private final Type mType;
    private final String mHandle;
    private final String mFolder;
    private final String mOldFolder;
    private final Bmessage.Type mMsgType;

    /* MAP-Event-Report Version 1.1 */
    private final String mDateTime;
    private final String mSubject;
    private final String mSenderName;
    private final boolean mPriority;

    /* MAP-Event-Report Version 1.2 */
    private final String mConversationName;
    // 32 character ASCII hexadecimal string
    private final String mConversationId;
    // A unsigned 8-bit value (0-255) from a defined list of possible availability
    // states on the MAP assigned numbers page
    private final Presence mPresenceAvailability;
    private final String mPresenceText;
    // This attribute contains the timestamp of the last activity of the participant
    private final String mLastActivity;

    private final ChatState mChatState;
    private final String mReadStatus;
    private final ExtendedData mExtendedData;
    private final String mParticipantUci;
    private final String mContactUid;

    private EventReport(HashMap<String, String> attrs) throws IllegalArgumentException {
        mType = parseType(attrs.get("type"));

        if (mType != Type.MEMORY_FULL && mType != Type.MEMORY_AVAILABLE) {
            String handle = attrs.get("handle");
            try {
                // just to validate
                new BigInteger(attrs.get("handle"), 16);

                mHandle = attrs.get("handle");
            } catch (NumberFormatException e) {
                Log.e(TAG, "Invalid value for handle:" + handle);
                throw new IllegalArgumentException("Invalid value for handle:" + handle);
            }
        } else {
            mHandle = null;
        }

        mFolder = attrs.get("folder");

        mOldFolder = attrs.get("old_folder");

        if (mType != Type.MEMORY_FULL && mType != Type.MEMORY_AVAILABLE) {
            String s = attrs.get("msg_type");

            if (s != null && s.isEmpty()) {
                // Some phones (e.g. SGS3 for MessageDeleted) send empty
                // msg_type, in such case leave it as null rather than throw
                // parse exception
                mMsgType = null;
            } else {
                mMsgType = parseMsgType(s);
            }
        } else {
            mMsgType = null;
        }

        if (mType == Type.NEW_MESSAGE || mType == Type.MESSAGE_EXTENDED_DATA_CHANGED) {
            String s = attrs.get("datetime");
            mDateTime = s;
        } else {
            mDateTime = null;
        }

        if (mType == Type.NEW_MESSAGE) {
            String s = attrs.get("subject");
            mSubject = s;

            s = attrs.get("sender_name");
            mSenderName = s;

            s = attrs.get("priority");
            mPriority = yesnoToBoolean(s);
        } else {
            mSubject = null;
            mSenderName = null;
            mPriority = false;
        }

        if (mType != Type.MEMORY_FULL && mType != Type.MEMORY_AVAILABLE) {
            String s = attrs.get("conversation_id");
            if (s != null) {
                try {
                    // just to validate
                    new BigInteger(s, 16);
                    mConversationId = s;
                } catch (NumberFormatException e) {
                    Log.e(TAG, "Invalid value for conversation_id:" + s);
                    throw new IllegalArgumentException("Invalid value for conversation_id:" + s);
                }
            } else {
                mConversationId = null;
            }
        } else {
            mConversationId = null;
        }

        if (mType != Type.MEMORY_FULL && mType != Type.MEMORY_AVAILABLE && mType != Type.SENDING_FAILURE) {
            mConversationName = attrs.get("conversation_name");
        } else {
            mConversationName = null;
        }

        if (mType == Type.PARTICIPANT_PRESENCE_CHANGED || mType == Type.CONVERSATION_CHANGED) {
            String s = attrs.get("presence_text");
            mPresenceText = s;

            s = attrs.get("presence_availability");
            if (s != null) {
                try {
                    mPresenceAvailability = parsePresence(s);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid value for presence_availability:" + s);
                    throw new IllegalArgumentException("Invalid value for presence_availability:" + s);
                }
            } else {
                mPresenceAvailability = Presence.Reserved;
            }
        } else {
            mPresenceAvailability = Presence.Reserved;
            mPresenceText = null;
        }

        if (mType == Type.PARTICIPANT_PRESENCE_CHANGED || mType == Type.CONVERSATION_CHANGED ||
                mType == Type.PARTICIPANT_CHAT_STATE_CHANGED) {
            mLastActivity = attrs.get("last_activity");
        } else {
            mLastActivity = null;
        }

        if (mType == Type.PARTICIPANT_CHAT_STATE_CHANGED || mType == Type.CONVERSATION_CHANGED) {
            String s = attrs.get("chat_state");
            if (s != null) {
                try {
                    mChatState = parseChatState(s);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid value for chat_state:" + s);
                    throw new IllegalArgumentException("Invalid value for chat_state:" + s);
                }
            } else {
                mChatState = ChatState.Reserved;
            }
        } else {
            mChatState = ChatState.Reserved;
        }

        if (mType == Type.NEW_MESSAGE || mType == Type.READ_STATUS_CHANGED) {
            mReadStatus = attrs.get("read_status");
        } else {
            mReadStatus = null;
        }

        if (mType == Type.MESSAGE_EXTENDED_DATA_CHANGED) {
            String s = attrs.get("extended_data");
            if (s != null) {
                try {
                    mExtendedData = parseExtendedData(s);
                } catch (IllegalArgumentException e) {
                    Log.e(TAG, "Invalid value for extended_data:" + s);
                    throw new IllegalArgumentException("Invalid value for extended_data:" + s);
                }
            } else {
                mExtendedData = ExtendedData.Reserved;
            }
        } else {
            mExtendedData = ExtendedData.Reserved;
        }

        if (mType == Type.MESSAGE_EXTENDED_DATA_CHANGED || mType == Type.PARTICIPANT_PRESENCE_CHANGED ||
                mType == Type.PARTICIPANT_CHAT_STATE_CHANGED || mType == Type.CONVERSATION_CHANGED) {
            mParticipantUci = attrs.get("participant_uci");
            mContactUid = attrs.get("contact_uid");

        } else {
            mParticipantUci = null;
            mContactUid = null;
        }
    }

    static EventReport fromStream(DataInputStream in) {
        EventReport ev = null;

        try {
            XmlPullParser xpp = XmlPullParserFactory.newInstance().newPullParser();
            xpp.setInput(in, "utf-8");

            int event = xpp.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                switch (event) {
                    case XmlPullParser.START_TAG:
                        if (xpp.getName().equals("event")) {
                            HashMap<String, String> attrs = new HashMap<String, String>();
                            for (int i = 0; i < xpp.getAttributeCount(); i++) {
                                attrs.put(xpp.getAttributeName(i), xpp.getAttributeValue(i));
                            }
                            ev = new EventReport(attrs);
                            // return immediately, only one event should be here
                            return ev;
                        }
                        break;
                }
                event = xpp.next();
            }

        } catch (XmlPullParserException e) {
            Log.e(TAG, "XML parser error when parsing XML", e);
        } catch (IOException e) {
            Log.e(TAG, "I/O error when parsing XML", e);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Invalid event received", e);
        }
        return ev;
    }

    private boolean yesnoToBoolean(String yesno) {
        return "yes".equals(yesno);
    }

    private Type parseType(String type) throws IllegalArgumentException {
        for (Type t : Type.values()) {
            if (t.toString().equals(type)) {
                return t;
            }
        }

        throw new IllegalArgumentException("Invalid value for type: " + type);
    }

    private Bmessage.Type parseMsgType(String msgType) throws IllegalArgumentException {
        for (Bmessage.Type t : Bmessage.Type.values()) {
            if (t.name().equals(msgType)) {
                return t;
            }
        }

        throw new IllegalArgumentException("Invalid value for msg_type: " + msgType);
    }

    private Presence parsePresence(String presence) throws IllegalArgumentException {
        for (Presence t : Presence.values()) {
            if (t.toString().equals(presence)) {
                return t;
            }
        }

        throw new IllegalArgumentException("Invalid value for Presence: " + presence);
    }

    private ChatState parseChatState(String state) throws IllegalArgumentException {
        for (ChatState t : ChatState.values()) {
            if (t.toString().equals(state)) {
                return t;
            }
        }

        throw new IllegalArgumentException("Invalid value for ChatState: " + state);
    }

    private ExtendedData parseExtendedData(String data) throws IllegalArgumentException {
        for (ExtendedData t : ExtendedData.values()) {
            if (t.toString().equals(data)) {
                return t;
            }
        }

        throw new IllegalArgumentException("Invalid value for ExtendedData: " + data);
    }

    /**
     * @return {@link EventReport.Type} object corresponding to
     * <code>type</code> application parameter in MAP specification
     */
    public Type getType() {
        return mType;
    }

    /**
     * @return value corresponding to <code>handle</code> parameter in MAP
     * specification
     */
    public String getHandle() {
        return mHandle;
    }

    /**
     * @return value corresponding to <code>folder</code> parameter in MAP
     * specification
     */
    public String getFolder() {
        return mFolder;
    }

    /**
     * @return value corresponding to <code>old_folder</code> parameter in MAP
     * specification
     */
    public String getOldFolder() {
        return mOldFolder;
    }

    /**
     * @return {@link Bmessage.Type} object corresponding to
     * <code>msg_type</code> application parameter in MAP specification
     */
    public Bmessage.Type getMsgType() {
        return mMsgType;
    }

    /**
     * @return value corresponding to <code>datetime</code> parameter in MAP specification
     */
    public String getDateTime() {
        return mDateTime;
    }

    /**
     * @return value corresponding to <code>subject</code> parameter in MAP specification
     */
    public String getSubject() {
        return mSubject;
    }

    /**
     * @return value corresponding to <code>sender_name</code> parameter in MAP specification
     */
    public String getSenderName() {
        return mSenderName;
    }

    /**
     * @return value corresponding to <code>priority</code> parameter in MAP specification
     */
    public boolean getPriority() {
        return mPriority;
    }

    /**
     * @return value corresponding to <code>conversation_name</code> parameter in MAP specification
     */
    public String getConversationName() {
        return mConversationName;
    }

    /**
     * @return value corresponding to <code>conversation_id</code> parameter in MAP specification
     */
    public String getConversationId() {
        return mConversationId;
    }

    /**
     * @return value corresponding to <code>presence_availability</code> parameter in MAP specification
     */

    public Presence getPresenceAvailability() {
        return mPresenceAvailability;
    }

    /**
     * @return value corresponding to <code>presence_text</code> parameter in MAP specification
     */
    public String getPresenceText() {
        return mPresenceText;
    }

    /**
     * @return value corresponding to <code>last_activity</code> parameter in MAP specification
     */
    public String getLastActivity() {
        return mLastActivity;
    }

    /**
     * @return value corresponding to <code>chat_state</code> parameter in MAP specification
     */
    public ChatState getChatState() {
        return mChatState;
    }

    /**
     * @return value corresponding to <code>read_status</code> parameter in MAP specification
     */
    public String getReadStatus() {
        return mReadStatus;
    }

    /**
     * @return value corresponding to <code>extended_data</code> parameter in MAP specification
     */
    public ExtendedData getExtendedData() {
        return mExtendedData;
    }

    /**
     * @return value corresponding to <code>participant_uci</code> parameter in MAP specification
     */
    public String getParticipantUci() {
        return mParticipantUci;
    }

    /**
     * @return value corresponding to <code>contact_uid</code> parameter in MAP specification
     */
    public String getContactUid() {
        return mContactUid;
    }

    @Override
    public String toString() {
        JSONObject json = new JSONObject();

        try {
            json.put("type", mType);
            json.put("handle", mHandle);
            json.put("folder", mFolder);
            json.put("old_folder", mOldFolder);
            json.put("msg_type", mMsgType);
            json.put("datetime", mDateTime);
            json.put("subject", mSubject);
            json.put("sender_name", mSenderName);
            json.put("priority", mPriority);
            json.put("conversation_name", mConversationName);
            json.put("conversation_id", mConversationId);
            json.put("presence_availability", mPresenceAvailability);
            json.put("presence_text", mPresenceText);
            json.put("last_activity", mLastActivity);
            json.put("chat_state", mChatState);
            json.put("read_status", mReadStatus);
            json.put("extended_data", mExtendedData);
            json.put("participant_uci", mParticipantUci);
            json.put("contact_uid", mContactUid);
        } catch (JSONException e) {
            // do nothing
        }

        return json.toString();
    }

    public enum Type {
        /* MAP-Event-Report Version 1.0 */
        NEW_MESSAGE("NewMessage"),
        DELIVERY_SUCCESS("DeliverySuccess"),
        SENDING_SUCCESS("SendingSuccess"),
        DELIVERY_FAILURE("DeliveryFailure"),
        SENDING_FAILURE("SendingFailure"),
        MEMORY_FULL("MemoryFull"),
        MEMORY_AVAILABLE("MemoryAvailable"),
        MESSAGE_DELETED("MessageDeleted"),
        MESSAGE_SHIFT("MessageShift"),

        /* MAP-Event-Report Version 1.1 */
        READ_STATUS_CHANGED("ReadStatusChanged"),

        /* MAP-Event-Report Version 1.2 */
        MESSAGE_REMOVED("MessageRemoved"),
        MESSAGE_EXTENDED_DATA_CHANGED("MessageExtendedDataChanged"),
        PARTICIPANT_PRESENCE_CHANGED("ParticipantPresence-Changed"),
        PARTICIPANT_CHAT_STATE_CHANGED("ParticipantChatState-Changed"),
        CONVERSATION_CHANGED("ConversationChanged");

        private final String mSpecName;

        Type(String specName) {
            mSpecName = specName;
        }

        @Override
        public String toString() {
            return mSpecName;
        }
    }

    public enum Presence {
        Unknown, Offline , Online, Away, DoNotDisturb, Busy, InAMeeting, Reserved;
    }

    public enum ChatState {
        Unknown, Inactive, Active, Composing, PausedComposing, Gone, Reserved;
    }

    public enum ExtendedData {
        NumberOfFacebookLikes, NumberOfTwitterFollowers, NumberOfTwitterRetweets, NumberOfGooglePlus1s, Reserved;
    }
}
