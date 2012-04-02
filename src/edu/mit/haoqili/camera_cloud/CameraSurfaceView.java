package edu.mit.haoqili.camera_cloud;

import java.io.IOException;

import android.content.Context;
import android.util.Log;
import android.view.SurfaceView;
import android.view.SurfaceHolder;
import android.hardware.Camera;

/**
 * http://code.google.com/p/openmobster/wiki/CameraTutorial 
 * @author openmobster@gmail.com
 */
public class CameraSurfaceView extends SurfaceView implements SurfaceHolder.Callback
{
	final static private String TAG = "CameraSurfaceView";
	private SurfaceHolder holder;
	public Camera camera;

	public CameraSurfaceView(Context context) 
	{
		super(context);

		//Initiate the Surface Holder properly
		this.holder = this.getHolder();
		this.holder.addCallback(this);
		this.holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
	}

	@Override
	public void surfaceCreated(SurfaceHolder holder) 
	{
		Log.i(TAG, "inside surfaceCreated()");
		//http://stackoverflow.com/questions/2563973/android-fail-to-connect-to-camera
		if (this.camera == null){ 
			try
			{
				//Open the Camera in preview mode
				Log.i(TAG, "opening Camera.open()");
				this.camera = Camera.open();
				Log.i(TAG, "opened camera");
				this.camera.setPreviewDisplay(this.holder);
				
			}
			catch(IOException ioe)
			{
				Log.i(TAG, "in ioexception of surfaceCreated() :(");
				this.camera.release();
				this.camera = null;
				ioe.printStackTrace(System.out);
			}
		}
	}
	
	@Override
	public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) 
	{
		// Now that the size is known, set up the camera parameters and begin
		// the preview.
		//Camera.Parameters parameters = camera.getParameters();
		//parameters.setPreviewSize(width, height);
		//camera.setParameters(parameters);
		Log.i(TAG, "inside on surfaceChanged()");
		camera.startPreview();
	}


	@Override
	public void surfaceDestroyed(SurfaceHolder holder) 
	{
		Log.i(TAG, "inside surfaceDestroyed()");
		if (camera != null){ //http://stackoverflow.com/questions/3371692/fail-to-connect-to-camera-service
			// Surface will be destroyed when replaced with a new screen
			//Always make sure to release the Camera instance
			camera.stopPreview();

			//http://stackoverflow.com/questions/2563973/android-fail-to-connect-to-camera
			// camera fatal exception fix?
			Log.i(TAG, "inside surfaceDestroyed()");
			camera.setPreviewCallback(null); // http://code.google.com/p/android/issues/detail?id=6201

			camera.release();
			camera = null; 
		}
	}

	public Camera getCamera()
	{
		Log.i(TAG, "inside getCamera()");
		return this.camera;
	}
}
