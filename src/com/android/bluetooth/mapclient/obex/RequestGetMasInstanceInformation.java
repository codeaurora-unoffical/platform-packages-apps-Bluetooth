/*
 * Copyright (c) 2019, The Linux Foundation. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of The Linux Foundation nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.android.bluetooth.mapclient;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import javax.obex.ClientSession;
import javax.obex.HeaderSet;

/* This function allows the MCE to retrieve MAS instance information */
final class RequestGetMasInstanceInformation extends Request {
    private static final String TAG = "MceGetInstance";
    private static final String TYPE = "x-bt/MASInstanceInformation";
    private String mOwnerUci = null;
    private String mIntanceInformation = null;

    RequestGetMasInstanceInformation(byte instance) {

        mHeaderSet.setHeader(HeaderSet.TYPE, TYPE);

        ObexAppParameters oap = new ObexAppParameters();

        oap.add(OAP_TAGID_MAS_INSTANCE_ID, instance);

        oap.addToHeaderSet(mHeaderSet);
    }

    @Override
    protected void readResponseHeaders(HeaderSet headerset) {

        ObexAppParameters oap = ObexAppParameters.fromHeaderSet(headerset);

        mOwnerUci = oap.getString(OAP_TAGID_OWNER_UCI);
    }

    @Override
    protected void readResponse(InputStream stream) {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];

        try {
            int len;
            while ((len = stream.read(buf)) != -1) {
                baos.write(buf, 0, len);
            }
        } catch (IOException e) {
            Log.e(TAG, "I/O exception while reading response", e);
        }
        // Convert the input stream using UTF-8 since the attributes in the payload are all encoded
        // according to it. The actual message body may need to be transcoded depending on
        // charset/encoding defined for body-content.
        try {
            mIntanceInformation = baos.toString(StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            Log.e(TAG,
                    "Coudn't decode the bmessage with UTF-8. Something must be really messed up.");
            return;
        }
    }

    public String getOwnerUci() {
        return mOwnerUci;
    }

    public String getInstanceInformation() {
        return mIntanceInformation;
    }

    @Override
    public void execute(ClientSession session) throws IOException {
        executeGet(session);
    }
}
