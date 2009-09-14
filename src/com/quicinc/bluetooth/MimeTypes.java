/*
 * Copyright (C) 2008 OpenIntents.org
 * Copyright (c) 2009, Code Aurora Forum. All rights reserved.
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

package com.quicinc.bluetooth;

import java.util.HashMap;
import java.util.Map;

public class MimeTypes {

    private Map<String, String> mMimeTypes;

    public MimeTypes() {
        mMimeTypes = new HashMap<String, String>();
    }

    public void put(String type, String extension) {
        mMimeTypes.put(type, extension);
    }

    public String getMimeType(String filename) {

        String extension = FileUtils.getExtension(filename);

        String mimetype = mMimeTypes.get(extension);

        return mimetype;
    }

}
