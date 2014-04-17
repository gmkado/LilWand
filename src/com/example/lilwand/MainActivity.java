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

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.PreviewCallback;
import android.os.AsyncTask;
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
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.example.lilwand.R;

/**
 * This is the main Activity that displays the current session.
 */
public class MainActivity extends Activity implements PreviewCallback{
	// Debugging
	private static final String TAG = "MainActivity";
	private static final boolean D = true;

	// Role of app
	private AtomicInteger mRole = new AtomicInteger();
	public static final int ROLE_CAMERA = 0;
	public static final int ROLE_CONTROLLER = 1;
	
	// Bluetooth fields
	private String mConnectedDeviceName = null;
	private BluetoothAdapter mBluetoothAdapter = null;
	private BluetoothService mBluetoothService = null;
	
	// Constants that indicate bluetooth header types
	public static final byte HEADER_IMAGE = 0;
	public static final byte HEADER_CAMERA_PARAM = 1;

	// Key names received from the BluetoothService Handler
	public static final String DEVICE_NAME = "device_name";
	public static final String TOAST = "toast";

	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE = 1;
	private static final int REQUEST_ENABLE_BT = 2;
	
	// Message types sent from the BluetoothService Handler
	public static final int MESSAGE_STATE_CHANGE = 1;
	public static final int MESSAGE_READ = 2;
	public static final int MESSAGE_WRITE = 3;
	public static final int MESSAGE_DEVICE_NAME = 4;
	public static final int MESSAGE_TOAST = 5;
	
	// Layout Views
	private TextView mTitle;
	private FrameLayout mPreviewFrame;
	
	// Camera control variables
	private Camera mCamera = null;
	private CameraPreview mPreview = null;
	private Handler mHandler;
	
	// Camera parameters
	private int imgWidth;		// set in cameraPreview for cameras, parseMessage for controller
	private int imgHeight;		// set in cameraPreview for cameras, parseMessage for controller
	private int imgFormat = ImageFormat.NV21;
	
	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (D)
			Log.e(TAG, "+++ ON CREATE +++");

		// set app role (default as camera)
		mRole.set(ROLE_CAMERA);
		
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
		mPreviewFrame = (FrameLayout) findViewById(R.id.camera_preview);
		
		
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
		
		if(mRole.get() == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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

		if(mRole.get() == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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
	private void sendMessage(byte[] message) {
		// Check that we're actually connected before trying anything
		if (mBluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
			Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT)
					.show();
			return;
		}

		// Check that there's actually something to send
		if (message.length > 0) {
			// Get the message bytes and tell the BluetoothService to write
			mBluetoothService.write(message);
		}
	}
	
	public void sendMessageWithHeader(byte header, byte[] data) {
		// add the message type
		byte[] newdata = new byte[data.length+1];
		newdata[0] = header;		
		
		//concatenate array
		System.arraycopy(data, 0, newdata, 1, data.length);
		
		// send message
		sendMessage(newdata);
		
	}
	
	private void parseMessage(byte[] readBuf) {
		// unpack the message 
		byte messageType = readBuf[0];		
		if(mRole.get() == ROLE_CAMERA){
			
		}else if (mRole.get() == ROLE_CONTROLLER) {
			if(messageType == HEADER_IMAGE)	{
				//if(D) Log.d(TAG,"parseMessage - image received");
			}
		}
		
	}


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

	
	/******************************************************OPTIONS MENU ***********************************/
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
				if(mRole.get() == ROLE_CAMERA){
					stopPreviewAndReleaseCamera();
				}else{
					mRole.set(ROLE_CAMERA);
				}
				// restart bluetooth service to get it back into listening mode.  (For cases when connection fails or is lost, this happens internally in BluetoothService).
				if (mBluetoothService !=null){
					mBluetoothService.stop();
					mBluetoothService.start();
				}
			} else {
				// Set the app role
				mRole.set(ROLE_CONTROLLER);

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

	
	
	/****************************************CAMERA METHODS****************************************/
	
	/** A safe way to get an instance of the Camera object. */
	private void getCameraInstanceAndStartPreview() {
		try {
			mCamera = Camera.open(); // attempt to get a Camera instance
					
		} catch (Exception e) {
			Log.e(TAG, "Camera is not available (in use or does not exist)");
			return;
		}
		
		// Create preview view and set it as content of our activity				
		mPreview = new CameraPreview(this, mCamera);	
		mPreviewFrame.addView(mPreview);						

	}

	private void stopPreviewAndReleaseCamera() {
		if (mCamera != null) {
			mCamera.stopPreview();
			
			// remove preview callback
			mCamera.setPreviewCallback(null);
			
			// release preview from framelayout
			mPreviewFrame.removeView(mPreview);
			mCamera.release();
			mCamera = null;						  
		}
	}
	
	public void onPreviewFrame(byte[] data, Camera camera) {
		Log.d(TAG, "onPreviewFrame - sending preview frame");

		if (mPreview.isConfigured())	{
        ByteArrayOutputStream outstr = new ByteArrayOutputStream();
        Rect rect = new Rect(0, 0, imgWidth, imgHeight);
        YuvImage yuvimage = new YuvImage(data, imgFormat , imgWidth,imgHeight, null);
        yuvimage.compressToJpeg(rect, 80, outstr); // outstr contains image in jpeg        		
		
		sendMessageWithHeader(HEADER_IMAGE, outstr.toByteArray());
		}
		else{
			Log.w(TAG, "onPreviewFrame - mPreview not configured");
		}
	}
	
	public int getFrameWidth() {
		return mPreviewFrame.getWidth();
	}

	public int getFrameHeight() {
		return mPreviewFrame.getHeight();
	}
	
	public void setImgWidth(int imgWidth) {
		this.imgWidth = imgWidth;
	}

	public void setImgHeight(int imgHeight) {
		this.imgHeight = imgHeight;
	}

	/************************************ BITMAP WORKER TASK ************************/
	private class BitmapWorkerTask extends AsyncTask<Integer, Void, Bitmap> {
		private final WeakReference<ImageView> imageViewReference;
		private int data = 0;

		public BitmapWorkerTask(ImageView imageView) {
			// Use a WeakReference to ensure the ImageView can be garbage collected
			imageViewReference = new WeakReference<ImageView>(imageView);
		}

		// Decode image in background.
		@Override
		    protected Bitmap doInBackground(Integer... params) {
		        data = params[0];
		        return decodeSampledBitmapFromResource(getResources(), data, 100, 100);
		    }

		// Once complete, see if ImageView is still around and set bitmap.
		@Override
		protected void onPostExecute(Bitmap bitmap) {
			if (imageViewReference != null && bitmap != null) {
				final ImageView imageView = imageViewReference.get();
				if (imageView != null) {
					imageView.setImageBitmap(bitmap);
				}
			}
		}
		
		public Bitmap decodeSampledBitmapFromResource(Resources res, int resId, int reqWidth, int reqHeight) {

		    // First decode with inJustDecodeBounds=true to check dimensions
		    final BitmapFactory.Options options = new BitmapFactory.Options();
		    options.inJustDecodeBounds = true;
		    BitmapFactory.decodeResource(res, resId, options);

		    // Calculate inSampleSize
		    options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

		    // Decode bitmap with inSampleSize set
		    options.inJustDecodeBounds = false;
		    return BitmapFactory.decodeResource(res, resId, options);
		}
		
		public int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
	    // Raw height and width of image
	    final int height = options.outHeight;
	    final int width = options.outWidth;
	    int inSampleSize = 1;

	    if (height > reqHeight || width > reqWidth) {

	        final int halfHeight = height / 2;
	        final int halfWidth = width / 2;

	        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
	        // height and width larger than the requested height and width.
	        while ((halfHeight / inSampleSize) > reqHeight
	                && (halfWidth / inSampleSize) > reqWidth) {
	            inSampleSize *= 2;
	        }
	    }

	    return inSampleSize;
	}

	}
	
	
	/************************************** MESSAGE HANDLER ********************/
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
							if (activity.mRole.get() == ROLE_CAMERA) {
								activity.stopPreviewAndReleaseCamera();			
							}else{
								activity.mRole.set(ROLE_CAMERA);
							}
							activity.mTitle.setText(R.string.title_not_connected);
							break;
						}
						break;
					case MESSAGE_WRITE:
						//Log.d(TAG, "handleMessage - WRITE");
						break;
					case MESSAGE_READ:
						//Log.d(TAG, "handleMessage - READ");
						byte[] readBuf = (byte[]) msg.obj;
						activity.parseMessage(readBuf);
						break;
					case MESSAGE_DEVICE_NAME:
						// save the connected device's name
						activity.mConnectedDeviceName = msg.getData().getString(
								DEVICE_NAME);
						Toast.makeText(activity.getApplicationContext(),
								"Connected to " + activity.mConnectedDeviceName,
								Toast.LENGTH_SHORT).show();

						// if we are a camera, start the camera preview
						if (activity.mRole.get() == MainActivity.ROLE_CAMERA) {
							activity.getCameraInstanceAndStartPreview();					

						}
						// otherwise if we are a controller, enable tap screen to
						// get a preview
						else if (activity.mRole.get() == ROLE_CONTROLLER) {
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

}