/*
 * Copyright (C) 2013 The Android Open Source Project
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

package net.craigiebabe.android.bluetoothlegatt;

import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class BluetoothLeService extends Service {
    private final static String TAG = BluetoothLeService.class.getSimpleName();

    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private String mBluetoothDeviceAddress;
    private BluetoothGatt mBluetoothGatt;
    private int mConnectionState = STATE_DISCONNECTED;

    public static final int STATE_DISCONNECTED = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;

    public final static String ACTION_GATT_CONNECTED = "com.example.bluetooth.le.ACTION_GATT_CONNECTED";
    public final static String ACTION_GATT_DISCONNECTED = "com.example.bluetooth.le.ACTION_GATT_DISCONNECTED";
    public final static String ACTION_GATT_SERVICES_DISCOVERED = "com.example.bluetooth.le.ACTION_GATT_SERVICES_DISCOVERED";
    public final static String ACTION_DATA_AVAILABLE = "com.example.bluetooth.le.ACTION_DATA_AVAILABLE";
    public final static String ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE =  "com.example.bluetooth.le.ACTION_CHARACTERISTIC_WRITE_OK";
    public final static String EXTRA_DATA = "com.example.bluetooth.le.EXTRA_DATA";
    public final static String EXTRA_CHARACTERISTIC_UUID = "com.example.bluetooth.le.EXTRA_CHARACTERISTIC_UUID";

    public int getConnectionState() {
    	return mConnectionState;
    }
    
    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            String intentAction;
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                intentAction = ACTION_GATT_CONNECTED;
                mConnectionState = STATE_CONNECTED;
                broadcastUpdate(intentAction);
                Log.i(TAG, "onConnectionStateChange(): Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "onConnectionStateChange(): Attempting to start service discovery:" + mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                intentAction = ACTION_GATT_DISCONNECTED;
                mConnectionState = STATE_DISCONNECTED;
                Log.i(TAG, "onConnectionStateChange(): Disconnected from GATT server.");
                broadcastUpdate(intentAction);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onServiceDiscovered(): broadcasting update: " + ACTION_GATT_SERVICES_DISCOVERED);
                broadcastUpdate(ACTION_GATT_SERVICES_DISCOVERED);
            } else {
                Log.w(TAG, "onServicesDiscovered received: " + status);
            }
        }

        /**
         * Callback reporting the result of a characteristic read operation. Parameters
         * gatt GATT client invoked readCharacteristic(BluetoothGattCharacteristic) characteristic 
         * Characteristic that was read from the associated remote device. status GATT_SUCCESS if the 
         * read operation was completed successfully.
         */
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onCharacteristicRead(): broadcasting update: " + ACTION_DATA_AVAILABLE);
                broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
            } else {
                Log.w(TAG, "onCharacteristicRead(): got status: " + status);
            }
        }

        /**
         * Callback triggered as a result of a remote characteristic notification. Parameters
         * gatt GATT client the characteristic is associated with characteristic Characteristic that has 
         * been updated as a result of a remote notification event.
         */
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            Log.i(TAG, "onCharacteristicChanged(): broadcasting update: " + ACTION_DATA_AVAILABLE);
            broadcastUpdate(ACTION_DATA_AVAILABLE, characteristic);
        }
        
        /**
         * Callback indicating the result of a characteristic write operation. 
		 * If this callback is invoked while a reliable write transaction is in progress, the value of the 
		 * characteristic represents the value reported by the remote device. An application should 
		 * compare this value to the desired value to be written. If the values don't match, the application 
		 * must abort the reliable write transaction. Parameters
		 * gatt GATT client invoked writeCharacteristic(BluetoothGattCharacteristic) characteristic 
 		 * Characteristic that was written to the associated remote device. status The result of the write 
 		 * operation GATT_SUCCESS if the operation succeeds.
         */
        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        	if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "onCharacteristicWrite(): successful");				
                Log.i(TAG, "onCharacteristicWrite(): broadcasting update: " + ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE);
				broadcastUpdate(ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE, characteristic);
			} else {
                Log.w(TAG, "onCharacteristicWrite(): unsuccessful - got status: " + status);				
                Log.i(TAG, "onCharacteristicWrite(): broadcasting update: " + ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE);
				broadcastUpdate(ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE, characteristic);
			}
        }
    };

    private void broadcastUpdate(final String action) {
        final Intent intent = new Intent(action);
        sendBroadcast(intent);
    }

    private void broadcastUpdate(final String action, final BluetoothGattCharacteristic characteristic) {
        final Intent intent = new Intent(action);

        final byte[] data = characteristic.getValue();
        if (data != null && data.length > 0) {
            final StringBuilder stringBuilder = new StringBuilder(data.length);
            for(byte byteChar : data)
                stringBuilder.append(String.format("%02X ", byteChar));
			int perms = characteristic.getPermissions();
            intent.putExtra(EXTRA_DATA, new String(data) + "\n" + stringBuilder.toString() + "::" + permissionsToString(perms));
            intent.putExtra(EXTRA_CHARACTERISTIC_UUID, characteristic.getUuid().toString());
        }
        sendBroadcast(intent);
    }

    private String permissionsToString(int perm) {
    	
		String permString = new String();

		permString.concat("<"); 
		if((perm & BluetoothGattCharacteristic.PERMISSION_READ) > 0)
			permString.concat("r"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED) > 0)
			permString.concat("e"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM) > 0)
			permString.concat("m"); 
		else 
			permString.concat(" ");
		
		if((perm & BluetoothGattCharacteristic.PERMISSION_WRITE) > 0)
			permString.concat("W"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED) > 0)
			permString.concat("E"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM) > 0)
			permString.concat("M"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED) > 0)
			permString.concat("S"); 
		else 
			permString.concat(" ");
		if((perm & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM) > 0)
			permString.concat("X"); 
		else 
			permString.concat(" ");
		permString.concat(">"); 
		return permString;
    }
    
    public class LocalBinder extends Binder {
        BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        // After using a given device, you should make sure that BluetoothGatt.close() is called
        // such that resources are cleaned up properly.  In this particular example, close() is
        // invoked when the UI is disconnected from the Service.
        close();
        return super.onUnbind(intent);
    }

    private final IBinder mBinder = new LocalBinder();

    /**
     * Initializes a reference to the local Bluetooth adapter.
     *
     * @return Return true if the initialization is successful.
     */
    public boolean initialize() {
        // For API level 18 and above, get a reference to BluetoothAdapter through
        // BluetoothManager.
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                Log.e(TAG, "Unable to initialize BluetoothManager.");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            Log.e(TAG, "Unable to obtain a BluetoothAdapter.");
            return false;
        }

        return true;
    }

    /**
     * Connects to the GATT server hosted on the Bluetooth LE device.
     *
     * @param address The device address of the destination device.
     *
     * @return Return true if the connection is initiated successfully. The connection result
     *         is reported asynchronously through the
     *         {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     *         callback.
     */
    public boolean connect(final String address) {
        if (mBluetoothAdapter == null || address == null) {
            Log.w(TAG, "BluetoothAdapter not initialized or unspecified address.");
            return false;
        }

        // Previously connected device.  Try to reconnect.
        if (mBluetoothDeviceAddress != null && address.equals(mBluetoothDeviceAddress)
                && mBluetoothGatt != null) {
            Log.d(TAG, "connect(): Trying to use an existing mBluetoothGatt for connection.");
            if (mBluetoothGatt.connect()) {
                mConnectionState = STATE_CONNECTING;
                Log.i(TAG, "connect(): Connecting.");
                return true;
            } else {
                Log.w(TAG, "connect(): Did not successfully initiate connection process.");
                return false;
            }
        }

        final BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            Log.w(TAG, "Device not found.  Unable to connect.");
            return false;
        }
        // We want to directly connect to the device, so we are setting the autoConnect parameter to false.
        mBluetoothGatt = device.connectGatt(this, false, mGattCallback);
        Log.d(TAG, "Trying to create a new connection.");
        mBluetoothDeviceAddress = address;
        mConnectionState = STATE_CONNECTING;
        return true;
    }

    /**
     * Disconnects an existing connection or cancel a pending connection. The disconnection result
     * is reported asynchronously through the
     * {@code BluetoothGattCallback#onConnectionStateChange(android.bluetooth.BluetoothGatt, int, int)}
     * callback.
     */
    public void disconnect() {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.disconnect();
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    /**
     * Request a read on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicRead(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to read from.
     */
    public void readCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "readCharacteristic(): BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.readCharacteristic(characteristic);
    }

    /**
     * Request a write on a given {@code BluetoothGattCharacteristic}. The read result is reported
     * asynchronously through the {@code BluetoothGattCallback#onCharacteristicWrite(android.bluetooth.BluetoothGatt, android.bluetooth.BluetoothGattCharacteristic, int)}
     * callback.
     *
     * @param characteristic The characteristic to write to.
     */
    public void writeCharacteristic(BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "writeCharacteristic(): BluetoothAdapter not initialized");
            return;
        }
        Log.i(TAG, "writeCharacteristic(): attempting to write characteristic!");
        boolean result = mBluetoothGatt.writeCharacteristic(characteristic);
        if(!result)
            Log.w(TAG, "writeCharacteristic(): write failed!");
    }

    /**
     * Enables or disables notification on a give characteristic.
     *
     * @param characteristic Characteristic to act on.
     * @param enabled If true, enable notification.  False otherwise.
     */
    public void setCharacteristicNotification(BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatt == null) {
            Log.w(TAG, "setCharacteristicNotification(): BluetoothAdapter not initialized");
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(characteristic, enabled);

        // This is specific to Heart Rate Measurement.
//        if (UUID_ROBOT_DIRECTION_CHARACTERISTIC.equals(characteristic.getUuid())) {
//            BluetoothGattDescriptor descriptor = characteristic.getDescriptor(
//                    UUID.fromString(BlunoGattAttributes.CLIENT_CHARACTERISTIC_CONFIG));
//            descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
//            mBluetoothGatt.writeDescriptor(descriptor);
//        }
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices() {
        if (mBluetoothGatt == null) {
            Log.w(TAG, "getSupportedGattServices(): BluetoothAdapter not initialized");
        	return null;
        }

        return mBluetoothGatt.getServices();
    }
}
