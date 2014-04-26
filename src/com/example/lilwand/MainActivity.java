/*
 * Copyright (C) 2009 The Android Open Source Project
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.lilwand;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.example.lilwand.R;
import android.util.FloatMath;

/**
 * This is the main Activity that displays the current session.
 */
public class MainActivity extends Activity implements PreviewCallback, SensorEventListener {
	// Debugging
	private static final String TAG = "MainActivity";
	private static final String PINPOINT_TAG = "Pinpoint";
	private static final boolean D = false;

	// Role of app
	private AtomicInteger mRole = new AtomicInteger();
	public static final int ROLE_CAMERA = 0;
	public static final int ROLE_CONTROLLER = 1;
	public static final int ROLE_UNASSIGNED = 2;

	// Bluetooth fields
	private String mConnectedDeviceName = null;
	private BluetoothAdapter mBluetoothAdapter = null;
	private BluetoothService mBluetoothService = null;

	// Constants that indicate bluetooth header types
	public static final byte HEADER_IMAGE = 0;
	public static final byte HEADER_CAMERA_PARAMETERS = 2;
	public static final byte HEADER_CONTROLLER_CMD = 1;
	private static final byte HEADER_IMAGE_RECEIVED = 3;

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

	// packet constants
	public static final byte EOT = 0x04; // END OF TRANSMISSION BYTE

	// Layout Views
	private ActionBar mActionBar;
	private FrameLayout mPreviewFrame;
	private MenuItem homeMenuItem;
	private MenuItem controlMenuItem;
	private MenuItem connectMenuItem;
	
	// Camera control variables
	private Camera mCamera = null;
	private SurfaceView mPreview = null;
	private Handler mHandler;

	// Image decoding variables
	private LinkedBlockingQueue<Bitmap> mQueue;
	Bitmap mBitmap;

	// Camera parameters
	private int imgFormat = ImageFormat.NV21;
	private int controllerImgWidth;
	private int controllerImgHeight;
	private int cameraImgHeight;
	private int cameraImgWidth;
	private boolean cameraConfigured = false;

	private boolean sendImgFlag = false;
	private Timer mTimer;
	private TimerTask mTimerTask;
	private static final long CHECK_QUEUE_INTERVAL = 16;
	private static final long SEND_IMAGE_INTERVAL = 32;

	// sensor variables
	private SensorManager mSensorManager;
	private Sensor mAccelSensor;
	private Sensor mMagSensor;
	private boolean hasSensors;
	private float[] mags;
	private float[] accels;
	private boolean grabInitAngles = true;
	private float initYaw;
	private float initRoll;
	private float initPitch;
	private float deltaYaw;
	private float deltaPitch;
	private float deltaRoll;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		if (D)
			Log.e(TAG, "+++ ON CREATE +++");

		// set app role (default as camera)
		mRole.set(ROLE_UNASSIGNED);

		// create message handler for bluetooth messages
		mHandler = new LilWandHandler(this);

		// Set up the window layout
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.main);
		mActionBar = getActionBar();

		// set up the camera preview widget
		mPreviewFrame = (FrameLayout) findViewById(R.id.camera_preview);
		// Get local Bluetooth adapter
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		// If the adapter is null, then Bluetooth is not supported
		if (mBluetoothAdapter == null) {
			Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		// initialize variables for bitmap decoding
		mQueue = new LinkedBlockingQueue<Bitmap>();

		// initialize sensors
		mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
		mAccelSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		mMagSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		hasSensors = mAccelSensor != null && mMagSensor != null; // boolean used to turn on or off orientation messages
	}

	@Override
	public void onStart() {
		super.onStart();
		if (D)
			Log.e(TAG, "++ ON START ++");

		// If BT is not on, request that it be enabled.
		// setupSession() will then be called during onActivityResult
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
			// Otherwise, setup the session
		} else if (mBluetoothService == null)
			setupSession();
	}

	@Override
	public synchronized void onResume() {
		super.onResume();
		if (D)
			Log.e(TAG, "+ ON RESUME +");

		// Performing this check in onResume() covers the case in which BT
		// was not enabled during onStart(), so we were paused to enable it...
		// onResume() will be called when ACTION_REQUEST_ENABLE activity
		// returns.
		if (mBluetoothService != null && mBluetoothService.getState() == BluetoothService.STATE_NONE) {
			// Start the Bluetooth services
			mBluetoothService.start();

		}
		if (mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
			if (mRole.get() == ROLE_CAMERA) {
				initializeCameraRole();
			} else if (mRole.get() == ROLE_CONTROLLER) {
				initializeControllerRole();
			}
		}
	
	}

	private void setupSession() {
		if(D) Log.d(TAG, "setupSession()");

		// Initialize the BluetoothService to perform bluetooth connections
		mBluetoothService = new BluetoothService(this, mHandler);

	}

	@Override
	public synchronized void onPause() {
		super.onPause();
		if (D)
			Log.e(TAG, "- ON PAUSE -");
		if (mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
			if (mRole.get() == ROLE_CAMERA) {
				releaseCameraRole();
			} else if (mRole.get() == ROLE_CONTROLLER) {
				releaseControllerRole();
			}
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
			Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	public void sendMessageWithHeader(byte headerType, byte[] data) {
		// add length to header
		byte[] header;
		
		if (data!=null){
			header = concatByteArray(new byte[] { headerType }, intToByteArray(data.length));
		}else{
			header = concatByteArray(new byte[] {headerType}, intToByteArray(0));
		}
		
		byte[] message;
		if(data !=null){
			message = concatByteArray(header, data);
		}else {
			message = header; 
		}
		
		byte[] footer = { EOT }; // arbitrary stop bits
		message = concatByteArray(message, footer);
		if(D) Log.d(TAG, "sent " + message.length + " bytes");
		// send message
		sendMessage(message);

	}

	/**
	 * Sends a message.
	 * 
	 * @param message
	 *            A byte array to send
	 */
	private void sendMessage(byte[] message) {
		// Check that we're actually connected before trying anything
		if (mBluetoothService.getState() != BluetoothService.STATE_CONNECTED) {
			Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
			return;
		}

		// Check that there's actually something to send and that we've received a response already
		if (message.length > 0) {
			// Get the message bytes and tell the BluetoothService to write
			mBluetoothService.write(message);
		}
	}

	private void parseMessage(int messageType, int messageLength, byte[] message) {
		// unpack the message
		if(D) Log.d(TAG, "parseMessage");
		if (mRole.get() == ROLE_CAMERA) {
			if(messageType == HEADER_IMAGE_RECEIVED) {
				sendImgFlag = true;
			}
		}

		else if (mRole.get() == ROLE_CONTROLLER) {
			if (messageType == HEADER_CAMERA_PARAMETERS) {
				ByteBuffer b = ByteBuffer.wrap(message);
				int width = b.getInt();
				int height = b.getInt();
				setControllerImageSize(width, height);
			}
			if (messageType == HEADER_IMAGE) {
				// if(D) Log.d(TAG,"parseMessage - image received");
				// execute worker task to decode image

				new DecodeBitmapTask().execute(message);
			}
		}

	}

	private void writeToFile(byte[] img, String fn) {
		// write img byte array to file named fn
		File file = new File(getExternalFilesDir(Environment.DIRECTORY_DCIM), fn);

		if (!file.exists()) {
			try {
				file.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(file);

			fos.write(img);
			fos.flush();
			fos.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	/* Checks if external storage is available for read and write */
	public boolean isExternalStorageWritable() {
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			return true;
		}
		return false;
	}

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (D)
			Log.d(TAG, "onActivityResult " + resultCode);
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				// Get the device MAC address
				String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
				// Get the BLuetoothDevice object
				BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
				// Attempt to connect to the device
				mBluetoothService.connect(device);
			} else if (resultCode == Activity.RESULT_CANCELED){
				mRole.set(ROLE_UNASSIGNED);
			}
			break;
		case REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up a session
				setupSession();
			} else {
				// User did not enable Bluetooth or an error occured
				if(D) Log.d(TAG, "BT not enabled");
				Toast.makeText(this, R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}

	/****************************************************** OPTIONS MENU ***********************************/
	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.option_menu, menu);

		connectMenuItem = menu.findItem(R.id.connect);
		homeMenuItem = menu.findItem(R.id.home);
		controlMenuItem = menu.findItem(R.id.control);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.connect:
			int btState = mBluetoothService.getState();
			if (btState == BluetoothService.STATE_CONNECTED || btState == BluetoothService.STATE_CONNECTING) {
				// initiate disconnect
				if (mRole.get() == ROLE_CAMERA) {
					releaseCameraRole();
				} else if (mRole.get() == ROLE_CONTROLLER) {
					releaseControllerRole();
				}
				releaseActiveRole();
				// stop the bluetooth service. This will cause an exception to be thrown in the connected thread of BluetoothService, which will
				// restart the service and put it into a listening mode
				// (For cases when connection fails or is lost, this happens internally in BluetoothService, but here we have to do it manually).
				if (mBluetoothService != null) {
					mBluetoothService.stop();
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
		case R.id.control:
			// enable sending control commands
			return true;
		case R.id.home:
			// command tripod to return home
			grabInitAngles = true;
			
			return true;

		}
		return false;
	}

	/**************************************** CAMERA METHODS ****************************************/

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

	public synchronized void raiseSendImgFlag() {
		sendImgFlag = true;
	}

	public void onPreviewFrame(byte[] data, Camera camera) {

		// Log.d(TAG, "onPreviewFrame - sending preview frame");

		// if the camera has been configured and response received, send another image
		if (cameraConfigured && sendImgFlag) {
			ByteArrayOutputStream outstr = new ByteArrayOutputStream();
			Rect rect = new Rect(0, 0, cameraImgWidth, cameraImgHeight);
			YuvImage yuvimage = new YuvImage(data, imgFormat, cameraImgWidth, cameraImgHeight, null);
			yuvimage.compressToJpeg(rect, 30, outstr);

			byte[] img = outstr.toByteArray();
			if(D) Log.d(TAG, "onPreviewFrame: compressed " + data.length + " to " + img.length);
			sendMessageWithHeader(HEADER_IMAGE, img);

			sendImgFlag = false;

		}
	}

	//
	public int getPreviewWidth() {
		return mPreviewFrame.getWidth();
	}

	public int getPreviewHeight() {
		return mPreviewFrame.getHeight();
	}

	public void setControllerImageSize(int width, int height) {
		// get the biggest size for the image that will fit the preview
		float widthScale = (float) getPreviewWidth() / width;
		float heightScale = (float) getPreviewHeight() / height;

		float scale = 0;
		// select the smaller of the two to be the scaling factor
		if (widthScale < heightScale) {
			scale = widthScale;
		} else {
			scale = heightScale;
		}

		controllerImgWidth = (int) (scale * width);
		controllerImgHeight = (int) (scale * height);
	}

	public void setCameraImageSize(int width, int height) {
		cameraImgWidth = width;
		cameraImgHeight = height;
		cameraConfigured = true;
	}

	/************************************** MESSAGE HANDLER ********************/
	// The Handler that gets information back from the BluetoothService
	private static class LilWandHandler extends Handler {
		// weak reference to activity so our handler can be static, otherwise
		// our activity couldn't be gc on destroy, creating memory leak
		private final WeakReference<MainActivity> mActivity;

		public LilWandHandler(MainActivity activity) {
			mActivity = new WeakReference<MainActivity>(activity);
		}

		@Override
		public void handleMessage(Message msg) {
			final MainActivity activity = mActivity.get();
			if (activity != null) {
				switch (msg.what) {
				case MESSAGE_STATE_CHANGE:
					switch (msg.arg1) {
					case BluetoothService.STATE_CONNECTED:
						activity.mActionBar.setTitle(R.string.title_connected_to);
						activity.mActionBar.setSubtitle(activity.mConnectedDeviceName);
						break;
					case BluetoothService.STATE_CONNECTING:
						activity.mActionBar.setTitle(R.string.title_connecting);
						break;
					case BluetoothService.STATE_LISTEN:
					case BluetoothService.STATE_NONE:
						// lost connection with paired device
						if (activity.mRole.get() == ROLE_CAMERA) {
							activity.releaseCameraRole();
						} else if (activity.mRole.get() == ROLE_CONTROLLER) {

							activity.releaseControllerRole();
						}
						activity.releaseActiveRole();
						break;
					}
					break;
				case MESSAGE_WRITE:
					// Log.d(TAG, "handleMessage - WRITE");
					break;
				case MESSAGE_READ:
					// Log.d(TAG, "handleMessage - READ");
					activity.parseMessage((int) msg.arg1, (int) msg.arg2, (byte[]) msg.obj);
					break;
				case MESSAGE_DEVICE_NAME:
					// save the connected device's name
					activity.mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
					Toast.makeText(activity.getApplicationContext(), "Connected to " + activity.mConnectedDeviceName, Toast.LENGTH_SHORT).show();
					
					activity.initializeActiveRole();
					if (activity.mRole.get() == ROLE_CONTROLLER) {
						activity.initializeControllerRole();
					} else {
						activity.initializeCameraRole();
					}
					break;
				case MESSAGE_TOAST:
					Toast.makeText(activity.getApplicationContext(), msg.getData().getString(TOAST), Toast.LENGTH_SHORT).show();
					break;
				}
			}
		}
	};

	// Async worker task to decode byte array into bitmap and add to queue
	// TODO: maybe this should be another thread with a Handler to post to the
	// queue? or lock orientation so activity doesn't get destroyed
	private class DecodeBitmapTask extends AsyncTask<byte[], Void, Bitmap> {
		

		@Override
		protected void onPreExecute() {
			// while we're in the ui thread, get the frame width and height
			super.onPreExecute();
		}

		protected Bitmap doInBackground(byte[]... imgList) {
			for (byte[] img : imgList) {
				try {
					if(D) Log.d(TAG, "decoding byte array of :" + img.length + "bytes");
					// Set bitmap factory options
					BitmapFactory.Options options = new BitmapFactory.Options();
					options.inPreferQualityOverSpeed = false;
					options.inDither = false;
					options.inJustDecodeBounds = false;

					// Decode bitmap
					mBitmap = BitmapFactory.decodeByteArray(img, 0, img.length, options);

				} catch (Exception e) {
					e.printStackTrace();
				}
				if (isCancelled())
					break;
			}
			return mBitmap;
		}

		protected void onPostExecute(Bitmap bm) {
			if (bm == null) {
				if(D) Log.d(TAG, "Decoding failed.");
			} else {
				if(D) Log.d(TAG, "Successfully decoded image.");
				
				sendMessageWithHeader(HEADER_IMAGE_RECEIVED, null);
				// post it to the queue
				mQueue.add(bm);
			}
		}
	}

	private class CheckQueueTimerTask extends TimerTask {

		@Override
		public void run() {
			try {
				if (!mQueue.isEmpty()) {
					Bitmap bm = mQueue.remove();
					if(D)Log.d(TAG, "Pulling bitmap from queue");

					// lock canvas
					Canvas canvas = mPreview.getHolder().lockCanvas();

					// draw to canvas
					Bitmap scaled = Bitmap.createScaledBitmap(bm, controllerImgWidth, controllerImgHeight, false);
					int centerX = (canvas.getWidth() - controllerImgWidth) / 2;
					int centerY = (canvas.getHeight() - controllerImgHeight) / 2;
					canvas.drawBitmap(scaled, centerX, centerY, new Paint());

					// unlock canvas and post
					mPreview.getHolder().unlockCanvasAndPost(canvas);
				}
			} catch (Exception e) {
				if(D) Log.d(TAG, "CheckQueueTimerTask failed in run", e);
			}
		}
	}

	/*private class SendImageTimerTask extends TimerTask {

		@Override
		public void run() {
			raiseSendImgFlag();
		}
	}*/

	byte[] concatByteArray(byte[] A, byte[] B) {
		int aLen = A.length;
		int bLen = B.length;
		byte[] C = new byte[aLen + bLen];
		System.arraycopy(A, 0, C, 0, aLen);
		System.arraycopy(B, 0, C, aLen, bLen);
		return C;
	}

	public void initializeActiveRole() {
		// change menu icon to disconnect
		connectMenuItem.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
		mTimer = new Timer();
	}
	
	public void initializeControllerRole() {
		// Reconfigure framelayout to be a controllerpreview		
		mPreview = new ControllerPreview(getApplicationContext());
		mPreviewFrame.addView(mPreview);

		mTimerTask = new CheckQueueTimerTask();
		mTimer.scheduleAtFixedRate(mTimerTask, 0, CHECK_QUEUE_INTERVAL);

		// show options menu buttons
		controlMenuItem.setVisible(true);
		homeMenuItem.setVisible(true);
		
		if (hasSensors) {
			mSensorManager.registerListener(this, mMagSensor, SensorManager.SENSOR_DELAY_NORMAL);
			mSensorManager.registerListener(this, mAccelSensor, SensorManager.SENSOR_DELAY_NORMAL);
		}
	}

	public void initializeCameraRole() {
		getCameraInstanceAndStartPreview();
//		mTimerTask = new SendImageTimerTask();
//		mTimer.scheduleAtFixedRate(mTimerTask, 0, SEND_IMAGE_INTERVAL);
		sendImgFlag = true;
		mRole.set(ROLE_CAMERA);
	}
	
	public void releaseActiveRole() {
		// any actions that both camera and controller must do to fully reset
		mPreviewFrame.removeView(mPreview);

		if (mTimer != null) {
			// stop the timer
			mTimerTask.cancel();
			mTimer.cancel();
		}

		// change menu icon to connect
		if (connectMenuItem != null)
			connectMenuItem.setIcon(android.R.drawable.ic_menu_search);
		mActionBar.setTitle(R.string.title_not_connected);
		mActionBar.setSubtitle("");

		mRole.set(ROLE_UNASSIGNED);
	}
	
	public void releaseControllerRole() {
		// everything we need to stop being the controller
		
		mSensorManager.unregisterListener(this);
		
		// turn off buttons in menu
		controlMenuItem.setVisible(false);
		homeMenuItem.setVisible(false);
	}

	public void releaseCameraRole() {
		// everything we need to stop being the camera
		stopPreviewAndReleaseCamera();
	}

	
	byte[] intToByteArray(int input) {
		// TODO Auto-generated method stub
		return ByteBuffer.allocate(4).putInt(input).array();
	}

	@Override
	public void onSensorChanged(SensorEvent event) {

		if (event.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)
			return;

		switch (event.sensor.getType()) {
		case Sensor.TYPE_MAGNETIC_FIELD:
			mags = event.values.clone();
			break;
		case Sensor.TYPE_ACCELEROMETER:
			accels = event.values.clone();
			break;
		}

		if (mags != null && accels != null) {
			final float[] R = new float[9];
			final float[] I = new float[9];
			final float[] outR = new float[9];
			final float[] values = new float[3];

			SensorManager.getRotationMatrix(R, I, accels, mags);

			// Correct if screen is in Landscape
			SensorManager.remapCoordinateSystem(R, SensorManager.AXIS_X, SensorManager.AXIS_Z, outR);

			SensorManager.getOrientation(outR, values);

			float yaw = (float) Math.toDegrees(values[0]);
			float pitch = (float) Math.toDegrees(values[1]);
			float roll = (float) Math.toDegrees(values[2]);

			if (grabInitAngles) {
				initYaw = yaw;
				initPitch = pitch;
				initRoll = roll;

				grabInitAngles = false;
			} else {
				deltaYaw = yaw - initYaw;
				deltaPitch = pitch - initPitch;
				deltaRoll = roll - initRoll;

			}
			// TODO: send values to camera
			Log.i(PINPOINT_TAG,
					"Roll = " + String.format("%.01f", deltaRoll) + ", Pitch = " + String.format("%.01f", deltaPitch) + ", Yaw = "
							+ String.format("%.01f", deltaYaw));

		}

	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
		// TODO Auto-generated method stub

	}

}