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

package com.android.bluetooth;

import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.car.Car;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.SystemProperties;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.Utils;


public class CarBluetoothPowerManager{
    static final String TAG = "CarBluetoothPowerManager";
    static final boolean DBG = true;
    private final AdapterService mAdapterService;
    private Context mContext;
    private Car mCar;
    private CarPowerManager mCarPowerManager;
    private Thread mCarThread;

    static final String BLUETOOTH_BLE_OFFLOAD_PROPERTY = "persist.vendor.bluetooth.ble_offload";
    static final String BLUETOOTH_LPM_STATE_PROPERTY = "vendor.bluetooth.lpm_state";
    static final String PERMISSION_CAR_POWER = "android.car.permission.CAR_POWER";


    public CarBluetoothPowerManager(AdapterService service, Context context) {
        mAdapterService = service;
        mContext = context;
        setLpmState("init");
    }

    public void start(){
        debugLog("start");

        if (mCar != null && mCar.isConnected()) {
            mCar.disconnect();
            mCar = null;
        }
        if (isBleOffloadEnabled()) {
            debugLog("connect car service");
            mCar = Car.createCar(mContext, mCarCallbacks);
            if (mCar != null) mCar.connect();
        }
    }

    public void cleanup() {
        debugLog("cleanup");
        cleanPowerStatelistener();
        if (mCar != null && mCar.isConnected()) {
            mCar.disconnect();
            mCar = null;
        }
    }

    // Connect of Car Service callback
    private final ServiceConnection mCarCallbacks = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            debugLog("Connected to Car Service");
            setPowerStatelistener();

        }
        @Override
        public void onServiceDisconnected(ComponentName name) {
            debugLog("Disconnect from Car Service");
            cleanPowerStatelistener();
        }
    };

    // listen Car power state change
    private final CarPowerManager.CarPowerStateListener mCarPowerStateListener =
        (int state) -> {
             debugLog("onStateChanged() state = " + state);
            if (state == CarPowerManager.CarPowerStateListener.ON) {
                 debugLog("Car is powering on. ");
                // set property BLUETOOTH_LPM_STATE_PROPERTY wake here
                // get property in BT hidl to make chip leave lpm
                setWakeup();
                return;
            }
            if (state == CarPowerManager.CarPowerStateListener.SHUTDOWN_PREPARE) {
                 debugLog("Car is preparing for shutdown.");
                // set property BLUETOOTH_LPM_STATE_PROPERTY sleep here
                // get property in BT hidl to make chip enter lpm
                setSleep();
                return;
            }
        };

    private void setPowerStatelistener() {
        mContext.enforceCallingOrSelfPermission(PERMISSION_CAR_POWER, "Need CAR_POWER permission");
        mCarPowerManager = (CarPowerManager)mCar.getCarManager(Car.POWER_SERVICE);
        if (mCarPowerManager != null) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    try {
                        mCarPowerManager.setListener(mCarPowerStateListener);
                        debugLog("setListener for CarPowerManager");
                    } catch (IllegalStateException e) {
                        Log.e(TAG, "CarPowerManager listener was not cleared");
                    }
                }
            };
            mCarThread = new Thread(r);
            mCarThread.start();
        }
    }

    private void cleanPowerStatelistener() {
        if (mCarPowerManager != null) {
            debugLog("clear Listener for CarPowerManager");
            mCarPowerManager.clearListener();
        }

    }

    private void debugLog(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }

    private void setSleep() {
        setLpmState("sleep");
    }

    private void setWakeup() {
        setLpmState("wakeup");
    }

    private void setLpmState(String value) {
        debugLog("set Host State prop is " + value);
        SystemProperties.set(BLUETOOTH_LPM_STATE_PROPERTY, value);
    }

    private boolean isBleOffloadEnabled() {
        return SystemProperties.getBoolean(BLUETOOTH_BLE_OFFLOAD_PROPERTY, false);
    }
}

