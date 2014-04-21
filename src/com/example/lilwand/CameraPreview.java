package com.example.lilwand;

import java.io.IOException;

import android.content.Context;
import android.hardware.Camera;
import android.hardware.Camera.PreviewCallback;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A basic Camera preview class */
public class CameraPreview extends SurfaceView implements
		SurfaceHolder.Callback {
	private static final String TAG = "CameraPreview";
	private SurfaceHolder mHolder;
	private Camera mCamera;
	private MainActivity mContext;
	private boolean cameraConfigured = false;

	public CameraPreview(MainActivity context, Camera camera) {
		super(context);
		mCamera = camera;

		mContext = context;
		// Install a SurfaceHolder.Callback so we get notified when the
		// underlying surface is created and destroyed.
		mHolder = getHolder();
		mHolder.addCallback(this);
		// deprecated setting, but required on Android versions prior to 3.0
		mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}

	public void surfaceCreated(SurfaceHolder holder) {
		// The Surface has been created, now tell the camera where to draw the
		// preview.
		try {
			mCamera.setPreviewDisplay(holder);			
		} catch (IOException e) {
			Log.d(TAG, "Error setting camera preview: " + e.getMessage());
		}
	}

	public void surfaceDestroyed(SurfaceHolder holder) {
		// empty. Take care of releasing the Camera preview in your activity.
	}

	public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
		// If your preview can change or rotate, take care of those events here.
		// Make sure to stop the preview before resizing or reformatting it.

		if (mHolder.getSurface() == null) {
			// preview surface does not exist
			return;
		}

		// stop preview before making changes
		try {
			mCamera.stopPreview();
		} catch (Exception e) {
			// ignore: tried to stop a non-existent preview
		}

		// set preview size and make any resize, rotate or reformatting changes here
		if (!cameraConfigured) {
			Camera.Parameters parameters = mCamera.getParameters();
			Camera.Size size = getBestPreviewSize(mContext.getFrameWidth(), mContext.getFrameHeight(), parameters);

			if (size != null) {
				parameters.setPreviewSize(size.width, size.height);
				mCamera.setParameters(parameters);
								
				// TODO: is this thread-safe?
				// send new image sizes
				mContext.setImgWidth(size.width);
				mContext.setImgHeight(size.height);
				//TODO: uncomment this
				//mContext.sendMessageWithHeader(MainActivity.HEADER_CAMERA_PARAM, new byte[] {(byte) size.width, (byte) size.height});		        
				cameraConfigured = true;
			}
		}

		// start preview with new settings
		try {
			mCamera.setPreviewDisplay(mHolder);
			// set preview callback to call onPreviewFrame in MainActivity
			mCamera.setPreviewCallback((PreviewCallback) mContext);
			mCamera.startPreview();

		} catch (Exception e) {
			Log.d(TAG, "Error starting camera preview: " + e.getMessage());
		}
	}

	private Camera.Size getBestPreviewSize(int width, int height, Camera.Parameters parameters) {
		Camera.Size result = null;

		for (Camera.Size size : parameters.getSupportedPreviewSizes()) {
			if (size.width <= width && size.height <= height) {
				if (result == null) {
					result = size;
				} else {
					int resultArea = result.width * result.height;
					int newArea = size.width * size.height;

					if (newArea > resultArea) {
						result = size;
					}
				}
			}
		}

		return (result);
	}

	public boolean isConfigured() {
		// TODO Auto-generated method stub
		return cameraConfigured;
	}

}