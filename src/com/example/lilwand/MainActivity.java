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

/**
 * This is the main Activity that displays the current session.
 */
public class MainActivity extends Activity implements PreviewCallback {
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
	public static final byte HEADER_CAMERA_IMAGE = 0;
	public static final byte HEADER_CAMERA_PARAM = 2;
	public static final byte HEADER_CONTROLLER_CMD = 1;

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
	private ActionBar ab;
	private FrameLayout mPreviewFrame;

	// Camera control variables
	private Camera mCamera = null;
	private SurfaceView mPreview = null;
	private Handler mHandler;

	// Image decoding variables
	private LinkedBlockingQueue<Bitmap> mQueue;
	Bitmap mBitmap;

	// Camera parameters
	private int imgFormat = ImageFormat.NV21;
	private MenuItem connectMenuItem;
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
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		setContentView(R.layout.main);
		ab = getActionBar();

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
		if (mBluetoothService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't
			// started already
			if (mBluetoothService.getState() == BluetoothService.STATE_NONE) {
				// Start the Bluetooth services
				mBluetoothService.start();
			}
		}

		if (mRole.get() == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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

		if (mRole.get() == ROLE_CAMERA && mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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
			Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	public void sendMessageWithHeader(byte headerType, byte[] data) {
		// add length to header
		byte[] header = concatByteArray(new byte[] { headerType }, intToByteArray(data.length));
		byte[] footer = { EOT }; // arbitrary stop bits

		byte[] message = concatByteArray(header, data);
		message = concatByteArray(message, footer);
		Log.d(TAG, "sent " + data.length + " bytes");
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
		Log.d(TAG, "parseMessage");
		if (mRole.get() == ROLE_CAMERA) {
		}

		else if (mRole.get() == ROLE_CONTROLLER) {
			if (messageType == HEADER_CAMERA_PARAM) {
				ByteBuffer b = ByteBuffer.wrap(message);
				int width = b.getInt();
				int height = b.getInt();
				setControllerImageSize(width, height);
			}
			if (messageType == HEADER_CAMERA_IMAGE) {
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

		connectMenuItem = menu.findItem(R.id.scan);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.scan:
			int btState = mBluetoothService.getState();
			if (btState == BluetoothService.STATE_CONNECTED || btState == BluetoothService.STATE_CONNECTING) {
				// initiate disconnect

				// if we have the camera, release it
				if (mRole.get() == ROLE_CAMERA) {
					stopPreviewAndReleaseCamera();
				} else {
					mRole.set(ROLE_CAMERA);
				}
				// restart bluetooth service to get it back into listening mode.
				// (For cases when connection fails or is lost, this happens
				// internally in BluetoothService).
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
			Log.d(TAG, "onPreviewFrame: compressed " + data.length + " to " + img.length);
			sendMessageWithHeader(HEADER_CAMERA_IMAGE, img);

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
						activity.ab.setTitle(R.string.title_connected_to);
						activity.ab.setSubtitle(activity.mConnectedDeviceName);
						break;
					case BluetoothService.STATE_CONNECTING:
						activity.ab.setTitle(R.string.title_connecting);
						break;
					case BluetoothService.STATE_LISTEN:
					case BluetoothService.STATE_NONE:
						// lost connection with paired device
						if (activity.mRole.get() == ROLE_CAMERA) {
							activity.stopPreviewAndReleaseCamera();
						}

						else if (activity.mRole.get() == ROLE_CONTROLLER) {

							activity.mRole.set(ROLE_CAMERA);
						}
						activity.mPreviewFrame.removeView(activity.mPreview);

						if (activity.mTimer != null) {
							// stop the timer
							activity.mTimerTask.cancel();
							activity.mTimer.cancel();
						}

						// change menu icon to connect
						if (activity.connectMenuItem != null)
							activity.connectMenuItem.setIcon(android.R.drawable.ic_menu_search);
						activity.ab.setTitle(R.string.title_not_connected);
						activity.ab.setSubtitle("");
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

					// change menu icon to disconnect
					activity.connectMenuItem.setIcon(android.R.drawable.ic_menu_close_clear_cancel);
					activity.mTimer = new Timer();
					// if we are a camera, start the camera preview
					if (activity.mRole.get() == MainActivity.ROLE_CAMERA) {
						activity.getCameraInstanceAndStartPreview();
						activity.mTimerTask = activity.new SendImageTimerTask();
						activity.mTimer.scheduleAtFixedRate(activity.mTimerTask, 0, SEND_IMAGE_INTERVAL);

					}
					// otherwise if we are a controller schedule to check queue for messages
					else if (activity.mRole.get() == ROLE_CONTROLLER) {
						// Reconfigure framelayout to be a controllerpreview
						FrameLayout fl = activity.mPreviewFrame;
						fl.removeAllViews();
						activity.mPreview = new ControllerPreview(activity.getApplicationContext());
						fl.addView(activity.mPreview);

						activity.mTimerTask = activity.new CheckQueueTimerTask();
						activity.mTimer.scheduleAtFixedRate(activity.mTimerTask, 0, CHECK_QUEUE_INTERVAL);

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
		int width;
		int height;

		@Override
		protected void onPreExecute() {
			// while we're in the ui thread, get the frame width and height
			super.onPreExecute();

			// get the image dimensions from the UI thread
			width = controllerImgWidth;
			height = controllerImgHeight;
		}

		protected Bitmap doInBackground(byte[]... imgList) {
			for (byte[] img : imgList) {
				try {
					Log.d(TAG, "decoding byte array of :" + img.length + "bytes");
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
				Log.d(TAG, "Decoding failed.");
			} else {
				Log.d(TAG, "Successfully decoded image.");

				// post it to the queue
				mQueue.add(bm);
			}
		}
	}

	private class CheckQueueTimerTask extends TimerTask {

		@Override
		public void run() {
			if (!mQueue.isEmpty()) {
				Bitmap bm = mQueue.remove();
				Log.d(TAG, "Pulling bitmap from queue");

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
		}
	}

	private class SendImageTimerTask extends TimerTask {

		@Override
		public void run() {
			raiseSendImgFlag();
		}
	}

	byte[] concatByteArray(byte[] A, byte[] B) {
		int aLen = A.length;
		int bLen = B.length;
		byte[] C = new byte[aLen + bLen];
		System.arraycopy(A, 0, C, 0, aLen);
		System.arraycopy(B, 0, C, aLen, bLen);
		return C;
	}

	byte[] intToByteArray(int input) {
		// TODO Auto-generated method stub
		return ByteBuffer.allocate(4).putInt(input).array();
	}

}