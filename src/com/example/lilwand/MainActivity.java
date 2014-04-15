/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.example.lilwand;

import java.lang.ref.WeakReference;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.hardware.Camera;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.example.lilwand.R;

/**
 * This is the main Activity that displays the current session.
 */
public class MainActivity extends Activity {
	// Debugging
	private static final String TAG = "MainActivity";
	private static final boolean D = true;

	// Message types sent from the BluetoothService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;

	private int mRole;

	// Constants that indicate the current role	
	public static final int ROLE_CAMERA = 0;
	public static final int ROLE_CONTROLLER = 1;
	

	// Key names received from the BluetoothService Handler
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";

	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;

	// Layout Views
	private TextView mTitle;

	// Name of the connected device
	private String mConnectedDeviceName = null;

	// Local Bluetooth adapter
	private BluetoothAdapter mBluetoothAdapter = null;
	// Member object for the bluetooth services
	private BluetoothService mBluetoothService = null;

	// Camera control variables
	private Camera mCamera = null;
	private CameraPreview mPreview = null;
	private Handler mHandler;
	private FrameLayout preview;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (D)
			Log.e(TAG, "+++ ON CREATE +++");

		// set app role (default as camera)
		mRole = ROLE_CAMERA;

		// create message handler for bluetooth messages
		mHandler = new LilWandHandler(this);

		// Set up the window layout
		requestWindowFeature(Window.FEATURE_CUSTOM_TITLE);
		setContentView(R.layout.main);
		getWindow().setFeatureInt(Window.FEATURE_CUSTOM_TITLE,
				R.layout.custom_title);

		// Set up the custom title
		mTitle = (TextView) findViewById(R.id.title_left_text);
		mTitle.setText(R.string.app_name);
		mTitle = (TextView) findViewById(R.id.title_right_text);

		// set up the camera preview widget
		preview = (FrameLayout) findViewById(R.id.camera_preview);
		
		// Get local Bluetooth adapter
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		// If the adapter is null, then Bluetooth is not supported
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available",
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		if (D)
			Log.e(TAG, "++ ON START ++");

	
		// If BT is not on, request that it be enabled.
		// setupSession() will then be called during onActivityResult
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
			// Otherwise, setup the session
		} else if (mBluetoothService == null) setupSession();
	}

	@Override
	public synchronized void onResume() {
		super.onResume();
		if (D)
			Log.e(TAG, "+ ON RESUME +");

		// Performing this check in onResume() covers the case in which BT
		// was not enabled during onStart(), so we were paused to enable it...
		// onResume() will be called when ACTION_REQUEST_ENABLE activity returns.
		if (mBluetoothService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't
			// started already
			if (mBluetoothService.getState() == BluetoothService.STATE_NONE) {
				// Start the Bluetooth services
				mBluetoothService.start();
			}
		}
		
		if(mRole == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
			// get camera again			
			getCameraInstanceAndStartPreview();	
		}

	}

	private void setupSession() {
		Log.d(TAG, "setupSession()");

		// Initialize the BluetoothService to perform bluetooth connections
		mBluetoothService = new BluetoothService(this, mHandler);

	}

	@Override
	public synchronized void onPause() {
		super.onPause();
		if (D)
			Log.e(TAG, "- ON PAUSE -");

		if(mRole == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
			// release camera
			stopPreviewAndReleaseCamera();		
		}
	}

	@Override
	public void onStop() {
		super.onStop();
		if (D)
			Log.e(TAG, "-- ON STOP --");
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		// Stop the Bluetooth services
		if (mBluetoothService != null)
			mBluetoothService.stop();
		if (D)
			Log.e(TAG, "--- ON DESTROY ---");
	}

	private void ensureDiscoverable() {
		if (D)
			Log.d(TAG, "ensure discoverable");
		if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent discoverableIntent = new Intent(
					BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(
					BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	/**
	 * Sends a message.
	 * 
	 * @param message
	 *            A string of text to send.
	 */
	private void sendMessage(String message) {
		// Check that we're actually connected before trying anything
		if (mBluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
			Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT)
					.show();
			return;
		}

		// Check that there's actually something to send
		if (message.length() > 0) {
			// Get the message bytes and tell the BluetoothService to write
			byte[] send = message.getBytes();
			mBluetoothService.write(send);
		}
	}

	// The Handler that gets information back from the BluetoothService
	private static class LilWandHandler extends Handler {
		private final WeakReference<MainActivity> mActivity;

		public LilWandHandler(MainActivity activity) {
			mActivity = new WeakReference<MainActivity>(activity);
		}

		@Override
		public void handleMessage(Message msg) {
			MainActivity activity = mActivity.get();
			if (activity != null) {
				switch (msg.what) {
				case MESSAGE_STATE_CHANGE:
					switch (msg.arg1) {
					case BluetoothService.STATE_CONNECTED:
						activity.mTitle.setText(R.string.title_connected_to);
						activity.mTitle.append(activity.mConnectedDeviceName);
						break;
					case BluetoothService.STATE_CONNECTING:
						activity.mTitle.setText(R.string.title_connecting);
						break;
					case BluetoothService.STATE_LISTEN:						
					case BluetoothService.STATE_NONE:
						// lost connection with paired device		
						if (activity.mRole == ROLE_CAMERA) {
							activity.stopPreviewAndReleaseCamera();			
						}else{
							activity.mRole = ROLE_CAMERA;
						}
						activity.mTitle.setText(R.string.title_not_connected);
						break;
					}
					break;
				case MESSAGE_WRITE:
					break;
				case MESSAGE_READ:
					byte[] readBuf = (byte[]) msg.obj;
					// construct a string from the valid bytes in the buffer
					break;
				case MESSAGE_DEVICE_NAME:
					// save the connected device's name
					activity.mConnectedDeviceName = msg.getData().getString(
							DEVICE_NAME);
					Toast.makeText(activity.getApplicationContext(),
							"Connected to " + activity.mConnectedDeviceName,
							Toast.LENGTH_SHORT).show();

					// if we are a camera, start the camera preview
					if (activity.mRole == MainActivity.ROLE_CAMERA) {
						activity.getCameraInstanceAndStartPreview();					

					}
					// otherwise if we are a controller, enable tap screen to
					// get a preview
					else if (activity.mRole == ROLE_CONTROLLER) {
						// TODO: fill this out
					
					}
					break;
				case MESSAGE_TOAST:
					Toast.makeText(activity.getApplicationContext(),
							msg.getData().getString(TOAST), Toast.LENGTH_SHORT)
							.show();
					break;
				}
			}
		}
	};

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (D)
			Log.d(TAG, "onActivityResult " + resultCode);
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				// Get the device MAC address
				String address = data.getExtras().getString(
						DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				// Get the BLuetoothDevice object
				BluetoothDevice device = mBluetoothAdapter
						.getRemoteDevice(address);
				// Attempt to connect to the device
				mBluetoothService.connect(device);
			}
			break;
		case REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up a session
				setupSession();
			} else {
				// User did not enable Bluetooth or an error occured
				Log.d(TAG, "BT not enabled");
				Toast.makeText(this, R.string.bt_not_enabled_leaving,
						Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.option_menu, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.scan:
			int btState = mBluetoothService.getState();
			if(btState == BluetoothService.STATE_CONNECTED || btState == BluetoothService.STATE_CONNECTING) {
				// initiate disconnect 
				
				// if we have the camera, release it
				if(mRole == ROLE_CAMERA){
					stopPreviewAndReleaseCamera();
				}else{
					mRole = ROLE_CAMERA;
				}
				// restart bluetooth service to get it back into listening mode.  (For cases when connection fails or is lost, this happens internally in BluetoothService).
				if (mBluetoothService !=null){
					mBluetoothService.stop();
					mBluetoothService.start();
				}
			} else {
				// Set the app role
				mRole = ROLE_CONTROLLER;

				// Launch the DeviceListActivity to see devices and do scan
				Intent serverIntent = new Intent(this, DeviceListActivity.class);
				startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE);
			}
			return true;	
		case R.id.discoverable:
			// Ensure this device is discoverable by others
			ensureDiscoverable();
			return true;
		}
		return false;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		int btState = mBluetoothService.getState();
		if(btState == BluetoothService.STATE_CONNECTED || btState == BluetoothService.STATE_CONNECTING) {
			menu.findItem(R.id.scan).setTitle(R.string.disconnect);
		} else {
			menu.findItem(R.id.scan).setTitle(R.string.connect);
		}
		return super.onPrepareOptionsMenu(menu);
	}

	/** A safe way to get an instance of the Camera object. */
	public void getCameraInstanceAndStartPreview() {
		try {
			mCamera = Camera.open(); // attempt to get a Camera instance
		} catch (Exception e) {
			Log.e(TAG, "Camera is not available (in use or does not exist)");
			return;
		}
		// Create preview view and set it as content of our activity				
		mPreview = new CameraPreview(this, mCamera);	
		preview.addView(mPreview);

	}

	private void stopPreviewAndReleaseCamera() {
		if (mCamera != null) {
			mCamera.stopPreview();
			
			// release preview from framelayout
			preview.removeView(mPreview);
			mCamera.release();
			mCamera = null;
		}
	}
}