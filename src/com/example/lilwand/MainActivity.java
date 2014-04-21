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

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
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
import android.view.Choreographer;
import android.view.Choreographer.FrameCallback;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
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
	private ActionBar ab;
	private FrameLayout mPreviewFrame;

	// Camera control variables
	private Camera mCamera = null;
	private SurfaceView mPreview = null;
	private Handler mHandler;

	// Image decoding variables
	private LinkedBlockingQueue<Bitmap> mQueue;
	Bitmap mBitmap;
	Choreographer mChor;

	// Camera parameters
	private int imgWidth; // set in cameraPreview for cameras, parseMessage for
							// controller
	private int imgHeight; // set in cameraPreview for cameras, parseMessage for
							// controller
	private int imgFormat = ImageFormat.NV21;
	private Timer mTimer;
	private TimerTask mTimerTask;

	// To debug transmitted bits
	private boolean debugBT = false;
	boolean first = true;

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
			Toast.makeText(this, "Bluetooth is not available",
					Toast.LENGTH_LONG).show();
			finish();
			return;
		}

		// initialize variables for bitmap decoding
		mQueue = new LinkedBlockingQueue<Bitmap>();

		mTimer = new Timer();
		mTimerTask = new TimerTask() {

			@Override
			public void run() {
				if (!mQueue.isEmpty()) {
					Bitmap bm = mQueue.remove();
					Log.d(TAG, "Pulling bitmap from queue");

					// lock canvas
					Canvas canvas = mPreview.getHolder().lockCanvas();

					// draw to canvas
					canvas.drawBitmap(bm, 0, 0, new Paint());

					// unlock canvas and post
					mPreview.getHolder().unlockCanvasAndPost(canvas);
				}
			}
		};

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

		if (mRole.get() == ROLE_CAMERA
				&& mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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

		if (mRole.get() == ROLE_CAMERA
				&& mBluetoothService.getState() == BluetoothService.STATE_CONNECTED) {
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
		// create a new message array
		byte[] newdata = new byte[data.length + 3];
		// add the message type
		newdata[0] = header;
		// add the length of the message
		short msgLength = (short) data.length;
		newdata[1] = (byte) (msgLength & 0xFF);
		newdata[2] = (byte) ((msgLength >>> 8) & 0xFF);
		
		// concatenate array
		System.arraycopy(data, 0, newdata, 3, data.length);

		// send message
		sendMessage(newdata);

	}

	private void parseMessage(int messageType, int messageLength, byte[] message) {
		// unpack the message
		if (mRole.get() == ROLE_CAMERA) {

		} else if (mRole.get() == ROLE_CONTROLLER) {
			if (messageType == HEADER_IMAGE) {
				// if(D) Log.d(TAG,"parseMessage - image received");
				// execute worker task to decode image

				if (!debugBT || debugBT && first) {
					writeToFile(message, "received image");					
					new DecodeBitmapTask().execute(message);
					first = false;
				}

				
			}
		}

	}

	private void writeToFile(byte[] img, String fn) {
		// write img byte array to file named fn
		File file = new File(getExternalFilesDir(Environment.DIRECTORY_DCIM),
				fn);

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

	/****************************************************** OPTIONS MENU ***********************************/
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
			if (btState == BluetoothService.STATE_CONNECTED
					|| btState == BluetoothService.STATE_CONNECTING) {
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
		if (btState == BluetoothService.STATE_CONNECTED
				|| btState == BluetoothService.STATE_CONNECTING) {
			menu.findItem(R.id.scan).setTitle(R.string.disconnect);
		} else {
			menu.findItem(R.id.scan).setTitle(R.string.connect);
		}
		return super.onPrepareOptionsMenu(menu);
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

	public void onPreviewFrame(byte[] data, Camera camera) {
		
			Log.d(TAG, "onPreviewFrame - sending preview frame");

			if (((CameraPreview) mPreview).isConfigured()) {
				ByteArrayOutputStream outstr = new ByteArrayOutputStream();
				Rect rect = new Rect(0, 0, imgWidth, imgHeight);
				YuvImage yuvimage = new YuvImage(data, imgFormat, imgWidth,
						imgHeight, null);
				yuvimage.compressToJpeg(rect, 80, outstr); // outstr contains
															// image
															// in jpeg
				Log.d(TAG, "sending byte array of :" + data.length + "bytes");

				byte[] img = outstr.toByteArray();
				if (!debugBT || debugBT && first) {
					writeToFile(img, "transmitted image");
					sendMessageWithHeader(HEADER_IMAGE, img);
					first = false;
				}
				
			} else {
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
						} else if (activity.mRole.get() == ROLE_CONTROLLER) {
							activity.mPreviewFrame
									.removeView(activity.mPreview);

							// stop the timer from trying to decode images
							activity.mTimerTask.cancel();
							activity.mTimer.cancel();

							activity.mRole.set(ROLE_CAMERA);
						}
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
					activity.parseMessage((int)msg.arg1, (int)msg.arg2, (byte[])msg.obj);
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
						// Reconfigure framelayout to be a controllerpreview
						activity.mPreviewFrame.removeAllViews();
						activity.mPreview = new ControllerPreview(
								activity.getApplicationContext());
						activity.mPreviewFrame.addView(activity.mPreview);

						activity.mTimer.scheduleAtFixedRate(
								activity.mTimerTask, 0, 16);

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

	// Async worker task to decode byte array into bitmap and add to queue
	// TODO: maybe this should be another thread with a Handler to post to the
	// queue? or lock orientation so activity doesn't get destroyed
	private class DecodeBitmapTask extends AsyncTask<byte[], Void, Bitmap> {
		int fwidth;
		int fheight;

		@Override
		protected void onPreExecute() {
			// while we're in the ui thread, get the frame width and height
			super.onPreExecute();
			fwidth = getFrameWidth();
			fheight = getFrameHeight();
		}

		protected Bitmap doInBackground(byte[]... imgList) {
			for (byte[] img : imgList) {
				try {
					Log.d(TAG, "decoding byte array of :" + img.length
							+ "bytes");
					// Set bitmap factory options
					BitmapFactory.Options options = new BitmapFactory.Options();
					options.inPreferQualityOverSpeed = false;
					options.inDither = false;
					
					if (mBitmap != null) {
						options.inSampleSize = 1;
						options.inBitmap = mBitmap;

					} else {
						// Calculate inSampleSize
						options.inSampleSize = calculateInSampleSize(options,
								fwidth, fheight);

						// First decode with inJustDecodeBounds=true to check
						// dimensions
						options.inJustDecodeBounds = true;
						BitmapFactory.decodeByteArray(img, 0, img.length,
								options);
					}

					// Decode bitmap
					options.inJustDecodeBounds = false;
					mBitmap = BitmapFactory.decodeByteArray(img, 0, img.length,
							options);

				} catch (Exception e) {
					e.printStackTrace();
				}
				if (isCancelled())
					break;
			}
			return mBitmap;
		}

		private int calculateInSampleSize(BitmapFactory.Options options,
				int reqWidth, int reqHeight) {
			// Raw height and width of image
			final int height = options.outHeight;
			final int width = options.outWidth;
			int inSampleSize = 1;

			if (height > reqHeight || width > reqWidth) {

				final int halfHeight = height / 2;
				final int halfWidth = width / 2;

				// Calculate the largest inSampleSize value that is a power of 2
				// and keeps both
				// height and width larger than the requested height and width.
				while ((halfHeight / inSampleSize) > reqHeight
						&& (halfWidth / inSampleSize) > reqWidth) {
					inSampleSize *= 2;
				}
			}

			return inSampleSize;
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

}