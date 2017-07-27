package com.android.bluetooth.btservice;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothAssignedNumbers;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.content.Intent;
import android.os.Looper;
import android.support.test.runner.AndroidJUnit4;

import com.android.bluetooth.Utils;
import com.android.bluetooth.hfp.HeadsetHalConstants;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

@RunWith(AndroidJUnit4.class)
public class RemoteDevicesTest {
    private static final String TEST_BT_ADDR_1 = "00:11:22:33:44:55";

    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);
    private ArgumentCaptor<String> mStringArgument = ArgumentCaptor.forClass(String.class);
    private BluetoothDevice mDevice1;
    private RemoteDevices mRemoteDevices;

    @Mock private AdapterService mAdapterService;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        if (Looper.myLooper() == null) Looper.prepare();
        mDevice1 = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TEST_BT_ADDR_1);
        mRemoteDevices = new RemoteDevices(mAdapterService);
    }

    @Test
    public void testSendUuidIntent() {
        mRemoteDevices.updateUuids(mDevice1);
        Looper.myLooper().quitSafely();
        Looper.loop();

        verify(mAdapterService).sendBroadcast(any(), anyString());
        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_normalSequence() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(), batteryLevel);

        // Verify that update same battery level for the same device does not trigger intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(any(), anyString());

        // Verify that updating battery level to different value triggers the intent again
        batteryLevel = 15;
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(2))
                .sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);

        // Verify that user can get battery level after the update
        Assert.assertEquals(
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(), batteryLevel);

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorNegativeValue() {
        int batteryLevel = BluetoothDevice.BATTERY_LEVEL_UNKNOWN;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating with invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, never()).sendBroadcast(any(), anyString());

        // Verify that device property stays null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testUpdateBatteryLevel_errorTooLargeValue() {
        int batteryLevel = 101;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating invalid battery level does not trigger the intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, never()).sendBroadcast(any(), anyString());

        // Verify that device property stays null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetBeforeUpdate() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that resetting battery level keeps device property null
        mRemoteDevices.resetBatteryLevel(mDevice1);
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testResetAfterUpdate() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(), batteryLevel);

        // Verify that resetting battery level changes it back to BluetoothDevice
        // .BATTERY_LEVEL_UNKNOWN
        mRemoteDevices.resetBatteryLevel(mDevice1);
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel(),
                BluetoothDevice.BATTERY_LEVEL_UNKNOWN);

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(2))
                .sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testResetBatteryLevel_testAclStateChangeCallback() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());

        // Verify that user can get battery level after the update
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(
                batteryLevel, mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        // Verify that when device is completely disconnected, RemoteDevices reset battery level to
        // BluetoothDevice.BATTERY_LEVEL_UNKNOWN
        when(mAdapterService.getState()).thenReturn(BluetoothAdapter.STATE_ON);
        mRemoteDevices.aclStateChangeCallback(
                0, Utils.getByteAddress(mDevice1), AbstractionLayer.BT_ACL_STATE_DISCONNECTED);
        verify(mAdapterService).getState();
        verify(mAdapterService).getConnectionState(mDevice1);
        verify(mAdapterService, times(2)).sendBroadcast(any(), anyString());
        Assert.assertNotNull(mRemoteDevices.getDeviceProperties(mDevice1));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                mRemoteDevices.getDeviceProperties(mDevice1).getBatteryLevel());

        // Verify that updating battery level triggers ACTION_BATTERY_LEVEL_CHANGED intent again
        mRemoteDevices.updateBatteryLevel(mDevice1, batteryLevel);
        verify(mAdapterService, times(3))
                .sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());

        verifyNoMoreInteractions(mAdapterService);
    }

    @Test
    public void testHfIndicatorParser_testCorrectValue() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_HF_INDICATORS_VALUE_CHANGED intent updates battery level
        mRemoteDevices.onHfIndicatorValueChanged(getHfIndicatorIntent(
                mDevice1, batteryLevel, HeadsetHalConstants.HF_INDICATOR_BATTERY_LEVEL_STATUS));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, batteryLevel, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());
    }

    @Test
    public void testHfIndicatorParser_testWrongIndicatorId() {
        int batteryLevel = 10;

        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that ACTION_HF_INDICATORS_VALUE_CHANGED intent updates battery level
        mRemoteDevices.onHfIndicatorValueChanged(getHfIndicatorIntent(mDevice1, batteryLevel, 3));
        verify(mAdapterService, never()).sendBroadcast(any(), anyString());
        // Verify that device property is still null after invalid update
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectPlantronicsXEvent() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(getVendorSpecificHeadsetEventIntent(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT,
                BluetoothAssignedNumbers.PLANTRONICS, BluetoothHeadset.AT_CMD_TYPE_SET,
                getXEventArray(3, 8), mDevice1));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, 37, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());
    }

    @Test
    public void testOnVendorSpecificHeadsetEvent_testCorrectAppleBatteryVsc() {
        // Verify that device property is null initially
        Assert.assertNull(mRemoteDevices.getDeviceProperties(mDevice1));

        // Verify that correct ACTION_VENDOR_SPECIFIC_HEADSET_EVENT updates battery level
        mRemoteDevices.onVendorSpecificHeadsetEvent(getVendorSpecificHeadsetEventIntent(
                BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV,
                BluetoothAssignedNumbers.APPLE, BluetoothHeadset.AT_CMD_TYPE_SET,
                new Object[] {3,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, 5,
                        2, 1, 3, 10},
                mDevice1));
        verify(mAdapterService).sendBroadcast(mIntentArgument.capture(), mStringArgument.capture());
        verfyBatteryLevelChangedIntent(mDevice1, 60, mIntentArgument);
        Assert.assertEquals(AdapterService.BLUETOOTH_PERM, mStringArgument.getValue());
    }

    @Test
    public void testGetBatteryLevelFromXEventVsc() {
        Assert.assertEquals(37, RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 8)));
        Assert.assertEquals(100, RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(1, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(3, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, 1)));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromXEventVsc(getXEventArray(-1, -1)));
    }

    @Test
    public void testGetBatteryLevelFromAppleBatteryVsc() {
        Assert.assertEquals(10,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        0}));
        Assert.assertEquals(100,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        9}));
        Assert.assertEquals(60,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {3,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, 5,
                        2, 1, 3, 10}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {3,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, 5,
                        2, 1, 3}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        10}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        -1}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1,
                        BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL,
                        "5"}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(new Object[] {1, 35, 37}));
        Assert.assertEquals(BluetoothDevice.BATTERY_LEVEL_UNKNOWN,
                RemoteDevices.getBatteryLevelFromAppleBatteryVsc(
                        new Object[] {1, "WRONG", "WRONG"}));
    }

    private static void verfyBatteryLevelChangedIntent(
            BluetoothDevice device, int batteryLevel, ArgumentCaptor<Intent> intentArgument) {
        Assert.assertEquals(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED,
                intentArgument.getValue().getAction());
        Assert.assertEquals(
                device, intentArgument.getValue().getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(batteryLevel,
                intentArgument.getValue().getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -15));
        Assert.assertEquals(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT,
                intentArgument.getValue().getFlags());
    }

    private static Intent getHfIndicatorIntent(
            BluetoothDevice device, int batteryLevel, int indicatorId) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_HF_INDICATORS_VALUE_CHANGED);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_ID, indicatorId);
        intent.putExtra(BluetoothHeadset.EXTRA_HF_INDICATORS_IND_VALUE, batteryLevel);
        return intent;
    }

    private static Intent getVendorSpecificHeadsetEventIntent(String command, int companyId,
            int commandType, Object[] arguments, BluetoothDevice device) {
        Intent intent = new Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD, command);
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE, commandType);
        // assert: all elements of args are Serializable
        intent.putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments);
        intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
        intent.addCategory(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY + "."
                + Integer.toString(companyId));
        return intent;
    }

    private static Object[] getXEventArray(int batteryLevel, int numLevels) {
        ArrayList<Object> list = new ArrayList<>();
        list.add(BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY_LEVEL);
        list.add(batteryLevel);
        list.add(numLevels);
        list.add(0);
        list.add(0);
        return list.toArray();
    }
}
