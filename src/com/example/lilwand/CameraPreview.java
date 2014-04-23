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

		// set preview size based on frames width/height
		configureCameraPreview(mContext.getPreviewWidth(), mContext.getPreviewHeight());
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

	public void configureCameraPreview(int width, int height) {
		Camera.Parameters parameters = mCamera.getParameters();
		Camera.Size size = getBestPreviewSize(width, height, parameters);

		if (size != null) {
			parameters.setPreviewSize(size.width, size.height);
			mCamera.setParameters(parameters);
			
			byte[] message = mContext.concatByteArray(mContext.intToByteArray(size.width),mContext.intToByteArray(size.height));
			mContext.sendMessageWithHeader(MainActivity.HEADER_CAMERA_PARAM, message);
			mContext.setCameraImageSize(size.width, size.height);
			

		}
	}

	private Camera.Size getBestPreviewSize(int width, int height,
			Camera.Parameters parameters) {
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


}