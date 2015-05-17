/*
 * GATT Profile: A spec for sending / receiving attributes over a BLE link. Each attribute is uniquely identified by a UUID.
 * 
 * Role: central|peripheral
 * 	central -> perform scanning, looks for advertisements
 *  peripheral -> advertises itself
 * 
 * Service 1:M Characteristic (like a class or datatype, single value) 1:M Descriptor (attributes describing a characteristic value)
 * 
 * Service -> Tennis Coach
 * 	Characteristic -> Command
 * 		Descriptor -> 
 * 	Characteristic -> Status
 * 		Descriptor -> 
 * 
 * Service -> Robot 4WD
 *  Characteristic -> Direction
 *  	Descriptor -> left|right|forward|back
 *  Characteristic -> Speed
 *  	Descriptor -> metres/second
 *  NOTE: If you want to scan for only specific types of peripherals, you can call startLeScan(UUID[], BluetoothAdapter.LeScanCallback), providing an array of UUID objects that specify the GATT services your app supports.
 */

package net.craigiebabe.android.bluetoothlegatt;

import android.app.Activity;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * For a given BLE device, this Activity provides the user interface to connect, display data,
 * and display GATT services and characteristics supported by the device. The Activity
 * communicates with {@code BluetoothLeService}, which in turn interacts with the
 * Bluetooth LE API.
 */
public class DeviceControlActivity extends Activity {
    private final static String TAG = DeviceControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    private TextView mConnectionState;
    private TextView mAttrDataText;
    private EditText mSerialTextToSendField;
    private TextView mReceivedSerialText;
    private Button mSendButton;
    private String mDeviceName;
    private String mDeviceAddress;
    private ExpandableListView mGattServicesList;
    private BluetoothLeService mBluetoothLeService;
    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();
    
    private boolean mConnected = false;
    
    private BluetoothGattCharacteristic mCommandCharacteristic; // for sending AT commands - e.g. password + setting baudrate (setvalue + writecharacteristic)
    private BluetoothGattCharacteristic mSerialPortCharacteristic; // for sending (serialchar.setvalue+writecharacteristic) and 
    																// receiving data (serialcharacteristic.setnotification(true) then onReceive(intent) intent.getStringExtra(BluetoothLeService.EXTRA_DATA)))
    private BluetoothGattCharacteristic mModelNumberCharacteristic; // Curiously, it seems you can set characteristic notification on model number - why? Because it is the device "name", which is writable!
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    
    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

	private int mBaudrate=115200;	//set the default baud rate to 115200
	private String mPasswordCommand="AT+PASSWORD=DFRobot\r\n";
	private String mSetBaudrateCommand = "AT+CURRUART="+mBaudrate+"\r\n";
	private String mBlunoDebugCommand = "AT+BLUNODEBUG=ON\r\n"; // When Bluetooth is connected and Bluno receives UART messages from Arduino device, send the data to both the Android device over BLE and the USB port.
	private String mUsbDebugCommand = "AT+USBDEBUG=ON\r\n"; // When Bluetooth is connected and Bluno receives BLE messages from Android device, send the data to both the UART and the USB port.

	// byte[] mBaudrateBuffer={0x32,0x00,(byte) (mBaudrate & 0xFF),(byte) ((mBaudrate>>8) & 0xFF),(byte) ((mBaudrate>>16) & 0xFF),0x00};
	
	public void serialBegin(int baudrate){
		mBaudrate=baudrate;
		mSetBaudrateCommand = "AT+CURRUART=" + mBaudrate + "\r\n";
	}
    
	public void serialSend(String text){
		if(! isConnected()) {
            Log.e(TAG, "serialSend(): Not connected!");
			return;
		}
		
		if(! isCompatibleDfrobotBleDevice()) {
            Log.e(TAG, "serialSend(): Not a compatible DFRobot bluno device!");
			return;
		}

		if(mSerialPortCharacteristic.setValue(text + "\r\n"))
			mBluetoothLeService.writeCharacteristic(mSerialPortCharacteristic);
		else
			Log.e(TAG, "serialSend(): Error setting value on characteristic");
	}
	
	public void commandSend(String text){
		if(! isConnected()) {
            Log.e(TAG, "commandSend(): Not connected!");
			return;
		}
		
		if(! isCompatibleDfrobotBleDevice()) {
            Log.e(TAG, "commandSend(): Not a compatible DFRobot bluno device!");
			return;
		}

		if(mCommandCharacteristic.setValue(text + "\r\n"))
			mBluetoothLeService.writeCharacteristic(mCommandCharacteristic);
		else
			Log.e(TAG, "commandSend(): Error setting value on characteristic");
	}
	
    public boolean isConnected() {
    	return mConnected;
    }
    
    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "onServiceConnected(): Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialisation.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device. This can be a result of read or notification operations.
    // ACTION_GATT_CHARACTERISTIC_WRITE_OK: wrote data to the device successfully.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): connected!");
                updateConnectionState(R.string.connected);
                invalidateOptionsMenu();
                disableSend();
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): disconnected!");
                updateConnectionState(R.string.disconnected);
                invalidateOptionsMenu();
                clearUI();
                disableSend();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): discovered!");
                enableSend();
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): data available!");
                displayCharacteristicData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                enableSend();
                
                String intentUuid = intent.getStringExtra(BluetoothLeService.EXTRA_CHARACTERISTIC_UUID);

                if(isBlunoModelNumberCharacteristic(intentUuid)) {
            		if (intent.getStringExtra(BluetoothLeService.EXTRA_DATA).toUpperCase().startsWith("DF BLUNO")) {
                        Log.i(TAG, "mGattUpdateReceiver.onReceive(): got Bluno - so we need to initialise it with some AT commands");
//						commandSend(mPasswordCommand);
						commandSend(mSetBaudrateCommand);
//						commandSend(mBlunoDebugCommand);
						commandSend(mUsbDebugCommand);
	                    Log.i(TAG, "mGattUpdateReceiver.onReceive(): finished sending password and baud rate commands!");						
					}
            		else {
	                    Log.w(TAG, "mGattUpdateReceiver.onReceive(): Not a compatible DFRobot device!");
            			Toast.makeText(context, "Please select a compatible DFRobot device",Toast.LENGTH_SHORT).show();
					}
            	} else if (isBlunoCommandCharacteristic(intentUuid)) {
            		Log.i(TAG, "mGattUpdateReceiver.onReceive(): mBlunoCommandCharacteristic.EXTRA_DATA = " + intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                    displayReceivedCommandText(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
            	} else if (isBlunoSerialPortCharacteristic(intentUuid)) {
            		Log.i(TAG, "mGattUpdateReceiver.onReceive(): mBlunoSerialPortCharacteristic.EXTRA_DATA = " + intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                    displayReceivedSerialText(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
				} else {
	                Log.w(TAG, "mGattUpdateReceiver.onReceive(): got unexpected intent characteristic: " + intentUuid);
				}
            } else if (BluetoothLeService.ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE.equals(action)) {
                String intentUuid = intent.getStringExtra(BluetoothLeService.EXTRA_CHARACTERISTIC_UUID);
                String data = intent.getStringExtra(BluetoothLeService.EXTRA_DATA);
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): write complete for <" + BlunoGattAttributes.lookup(intentUuid, intentUuid) + "> complete");
                Log.i(TAG, "mGattUpdateReceiver.onReceive(): extra data: " + data);
                Toast.makeText(context, R.string.ble_command_sent_ok, Toast.LENGTH_SHORT).show();
                enableSend();
            } else {
                Log.e(TAG, "mGattUpdateReceiver.onReceive(): got unexpected action: " + action);
            }
        }
    };

	private void enableSend() {
        mSendButton.setEnabled(true);
        mSerialTextToSendField.setEnabled(true);
	}

	private void disableSend() {
        mSendButton.setEnabled(false);
        mSerialTextToSendField.setEnabled(false);
	}

    // If a given GATT characteristic is selected, check for supported features.  This sample demonstrates 'Read' and 'Notify' features.  
	// See http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListener =
        new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
                if (mGattCharacteristics != null) {
                    BluetoothGattCharacteristic characteristic = mGattCharacteristics.get(groupPosition).get(childPosition);
                    
                    // Characteristic properties from BluetoothGattCharacteristic:
                    // PROPERTY_BROADCAST			Characteristic is broadcastable.
                    // PROPERTY_EXTENDED_PROPS		Characteristic has extended properties
	                // PROPERTY_INDICATE			Characteristic supports indication
	                // PROPERTY_NOTIFY				Characteristic supports notification
	                // PROPERTY_READ				Characteristic is readable.
	                // PROPERTY_SIGNED_WRITE		Characteristic supports write with signature
	                // PROPERTY_WRITE				Characteristic can be written.
	                // PROPERTY_WRITE_NO_RESPONSE	Characteristic can be written without response
                    
                    final int charaProp = characteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                		Log.i(TAG, "OnChildClickListener(): got a readable characteristic !");
                        // If there is an active notification on a characteristic, clear
                        // it first so it doesn't update the data field on the user interface.
                        if (mNotifyCharacteristic != null) {
                        	// Once notifications are enabled for a characteristic, an onCharacteristicChanged() callback 
                        	// is triggered if the characteristic changes on the remote device
                            mBluetoothLeService.setCharacteristicNotification(mNotifyCharacteristic, false);
                            mNotifyCharacteristic = null;
                        }
                        mBluetoothLeService.readCharacteristic(characteristic);
                    }
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                		Log.i(TAG, "OnChildClickListener(): got a characteristic with notification support: " + characteristic.getUuid());
                    	mNotifyCharacteristic = characteristic;
                        mBluetoothLeService.setCharacteristicNotification(characteristic, true);
                    }
                    return true;
                }
                return false;
            }
    	};

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
        mAttrDataText.setText(R.string.no_data);
        mReceivedSerialText.setText(R.string.no_data);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gatt_services_characteristics);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        // Sets up UI references.
        ((TextView) findViewById(R.id.device_address)).setText(mDeviceAddress);
        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListener);
        mConnectionState = (TextView) findViewById(R.id.connection_state);
        mAttrDataText = (TextView) findViewById(R.id.attr_data);
        mSerialTextToSendField = (EditText) findViewById(R.id.serial_text_to_send);
        mSendButton = (Button) findViewById(R.id.serial_send_button);
        mReceivedSerialText = (TextView) findViewById(R.id.received_serial_text);

        mSendButton.setEnabled(false);
        mSendButton.setOnClickListener(new OnClickListener() {

			@Override
			public void onClick(View view) {
				if(mSerialPortCharacteristic == null) {
	                Log.w(TAG, "onClick(): mSerialPortCharacteristic is null!!!");		
	                return;
				}
                Log.i(TAG, "onClick(): send button clicked: mSerialTextToSendField length: " + mSerialTextToSendField.getText().length());
				if(mSerialTextToSendField.getText().length() > 0) {
					boolean result = mSerialPortCharacteristic.setValue(mSerialTextToSendField.getText().toString());
					if(!result)
		                Log.w(TAG, "onClick(): mSerialPortCharacteristic.setValue() failed!");					
					mSerialPortCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
					mBluetoothLeService.writeCharacteristic(mSerialPortCharacteristic);
					disableSend();
				}
			}

		});
        
        serialBegin(115200);
        
        getActionBar().setTitle(mDeviceName);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.gatt_services, menu);
        if (mConnected) {
            menu.findItem(R.id.menu_connect).setVisible(false);
            menu.findItem(R.id.menu_disconnect).setVisible(true);
        } else {
            menu.findItem(R.id.menu_connect).setVisible(true);
            menu.findItem(R.id.menu_disconnect).setVisible(false);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case R.id.menu_connect:
                mBluetoothLeService.connect(mDeviceAddress);
                return true;
            case R.id.menu_disconnect:
                mBluetoothLeService.disconnect();
                return true;
            case android.R.id.home:
                onBackPressed();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mConnectionState.setText(resourceId);
            }
        });
    }

    private void displayCharacteristicData(String data) {
        if (data != null) {
            mAttrDataText.setText(data);
        }
    }

    private void displayReceivedSerialText(String data) {
        if (data != null) {
        	mReceivedSerialText.setText("ser:" + data);
        }
    }

    private void displayReceivedCommandText(String data) {
        if (data != null) {
        	mReceivedSerialText.setText("cmd:" + data);
        }
    }

    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {
        if (gattServices == null)
        	return;
        
        String unknownServiceString = getResources().getString(R.string.unknown_service);
        String unknownCharacteristicString = getResources().getString(R.string.unknown_characteristic);
        
        ArrayList<HashMap<String, String>> displayedGattServiceData = new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> displayedGattCharacteristicData = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService currentService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            String currentServiceUuid = currentService.getUuid().toString();
            currentServiceData.put(LIST_NAME, BlunoGattAttributes.lookup(currentServiceUuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, currentServiceUuid);
            displayedGattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> currentGattCharacteristicGroupData = new ArrayList<HashMap<String, String>>();
            ArrayList<BluetoothGattCharacteristic> gattCharacteristicsList = new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic currentCharacteristic : currentService.getCharacteristics()) {
                gattCharacteristicsList.add(currentCharacteristic);
                HashMap<String, String> currentCharacteristicData = new HashMap<String, String>();
                String currentCharacteristicUuid = currentCharacteristic.getUuid().toString();
                currentCharacteristicData.put(LIST_NAME, BlunoGattAttributes.lookup(currentCharacteristicUuid, unknownCharacteristicString));
                currentCharacteristicData.put(LIST_UUID, currentCharacteristicUuid);
                currentGattCharacteristicGroupData.add(currentCharacteristicData);
                
                if(isBlunoModelNumberCharacteristic(currentCharacteristicUuid)){
                	mModelNumberCharacteristic = currentCharacteristic;
                }
                else if(isBlunoSerialPortCharacteristic(currentCharacteristicUuid)) {
                	mSerialPortCharacteristic = currentCharacteristic;
                }
                else if(isBlunoCommandCharacteristic(currentCharacteristicUuid)){
                	mCommandCharacteristic = currentCharacteristic;
                }
            }
            mGattCharacteristics.add(gattCharacteristicsList);
            displayedGattCharacteristicData.add(currentGattCharacteristicGroupData);
        }

        if (isCompatibleDfrobotBleDevice()) {
        	mBluetoothLeService.setCharacteristicNotification(mModelNumberCharacteristic, true);
        	mBluetoothLeService.readCharacteristic(mModelNumberCharacteristic);

        	mBluetoothLeService.setCharacteristicNotification(mCommandCharacteristic, true);
        	mBluetoothLeService.readCharacteristic(mSerialPortCharacteristic);

        	mBluetoothLeService.setCharacteristicNotification(mSerialPortCharacteristic, true);
        	mBluetoothLeService.readCharacteristic(mSerialPortCharacteristic);
		} else {
			Toast.makeText(this.getApplicationContext(), "This is not a compatible DFRobot BLE device",Toast.LENGTH_SHORT).show();
		}
        
        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                
                displayedGattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 },
                
                displayedGattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 }
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    private boolean isBlunoModelNumberCharacteristic(String uuid) {
    	if(uuid != null && uuid.equals(BlunoGattAttributes.BLUNO_MODEL_NUMBER_CHARACTERISTIC))
    		return true;
		return false;
	}

    private boolean isBlunoSerialPortCharacteristic(String uuid) {
    	if(uuid != null && uuid.equals(BlunoGattAttributes.BLUNO_SERIAL_PORT_CHARACTERISTIC))
    		return true;
		return false;
	}

    private boolean isBlunoCommandCharacteristic(String uuid) {
    	if(uuid != null && uuid.equals(BlunoGattAttributes.BLUNO_COMMAND_CHARACTERISTIC))
    		return true;
		return false;
	}

	private boolean isCompatibleDfrobotBleDevice() {
		return mModelNumberCharacteristic != null && mSerialPortCharacteristic != null && mCommandCharacteristic != null;
	}

	private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CHARACTERISTIC_WRITE_COMPLETE);
        return intentFilter;
    }
}
