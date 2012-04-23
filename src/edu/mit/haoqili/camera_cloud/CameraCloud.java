package edu.mit.haoqili.camera_cloud;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.UnsupportedEncodingException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.gson.Gson;

public class CameraCloud extends Activity implements LocationListener {
	final static private String TAG = "CameraCloud";

	// Everything in CloudObject will be converted into a gson object
	// to be sent to the cloud. (gson is convert java objects into json)
	public class CloudObject {
		// Status codes
		final static int CR_ERROR = 13;
		final static int CR_OKAY = 12;
		// could be either region doesn't exist or
		// region hasn't uploaded a photo yet
		final static int CR_NO_PHOTO = 20;

		public int status;

		// Upload photo:
		// client --- photo_bytes filled --> server
		// client <-- photo_bytes null ----- server
		// Download photo:
		// client --- photo_bytes null ----> server
		// client <-- photo_bytes filled --- server
		public byte[] photo_bytes = null;

		CloudObject(byte[] d) {
			photo_bytes = d;
		}
	}

	// UI elements
	Button camera_button, region_button, my_camera_button;
	Button get0_button, get1_button, get2_button, get3_button, get4_button, get5_button;
	TextView opCountTv, successCountTv, failureCountTv;
	TextView regionTv;
	EditText regionText, threadsText;
	ListView msgList;
	ArrayAdapter<String> receivedMessages;
	CameraSurfaceView cameraSurfaceView;

	PowerManager.WakeLock wl = null;
	LocationManager lm;

	// Logging to file
	File myLogFile;
	PrintWriter myLogWriter;
	
	// counts: success/failures
	private int takeNum = 0; // # of times pressed "Take Picture"
	private int takeCamGood = 0; // # times got into the Camera callback
	private int takeGoodSave = 0; // # "Take Picture" successes
	private int takeBad = 0; // # "Take Picture" failures
	private int takeException = 0;
	private int getNum = 0; // # of times pressed "Get x Region"
	private int getGood = 0; // # get success
	private int getBad = 0; // # get failure
	private int getException = 0;


	// areButtonsEnabled is the first line of defense against multi-clicking
	// set to false as soon as a take/get picture button is pressed
	// none of the other buttons can be pressed until it's set true again
	// set to true when progressDialog is dismissed
	private boolean areButtonsEnabled = false;
	// progressDialog is the second line of defense against multi-clicking
	// when shown, disables the rest of the ui, including buttons
	private ProgressDialog progressDialog;

	// VCore Daemon Location Constants
	private RegionKey myRegion;
	private static final int regionWidth = Globals.REGION_WIDTH; // ~meters
	// lat / long * 10^5, e.g. 103.77900 becomes 10377900
	private static final int minLatitude = Globals.MINIMUM_LATITUDE;
	private static final int minLongitude = Globals.MINIMUM_LONGITUDE;

	// Message types
	protected final static int LOG_NODISPLAY = 27;
	protected final static int LOG = 3;
	protected final static int VNC_STATUS_CHANGE = 6;
	protected final static int REGION_CHANGE = 7;
	protected final static int CLIENT_STATUS_CHANGE = 8;
	// for camera client
	final static int CLIENT_UPLOAD_PHOTO = 101;
	final static int CLIENT_DOWNLOAD_PHOTO = 102;

	/** Handle messages from various components */
	private final Handler myHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case LOG:
				receivedMessages.add((String) msg.obj);
				// Write to file
				if (myLogWriter != null) {
					myLogWriter.println((String) msg.obj);
				}
				break;
			case LOG_NODISPLAY:
				// receivedMessages.add((String) msg.obj);
				// Write to file
				if (myLogWriter != null) {
					myLogWriter.println((String) msg.obj);
				}
				break;
			}
		}
	};
	
	/** Log message and also display on screen */
	public void logMsg(String msg) {
		msg = String.format("%d: %s", System.currentTimeMillis(), msg);
		receivedMessages.add(msg);
		Log.i(TAG, msg);
		if (myLogWriter != null) {
			myLogWriter.println(msg);
			myLogWriter.flush();
		}
	}

	private void logCounts(){
		logMsg("takeNum="+takeNum+ " takeCamGood="+takeCamGood+ " takeGoodSave="+takeGoodSave
				+ " takeBad="+takeBad+ " takeException="+takeException+ " getNum="+getNum
				+ " getGood="+getGood+ " getBad="+getBad+ " getException="+getException);
	}
	
	/**
	 * Disable buttons at press of any button (take new pic for upload / region
	 * x get for download)
	 */
	private Runnable disableButtonsProgressStartR = new Runnable() {
		public void run() {
			Log.i(TAG,
					"Inside disableButtonsR XXX");
			areButtonsEnabled = false;
			Log.i(TAG, "areButtonsEnabled --> false");
			progressDialog = ProgressDialog.show(CameraCloud.this, "",
					"Processing photo get or save to cloud server ... :)");
		}
	};
	
	private void _enableButtons() {
		logMsg("Inside _enableButtons");
		if (progressDialog != null) {
			progressDialog.dismiss();
		} else {
			logMsg("No progress dialog to dismiss");
		}
		areButtonsEnabled = true;
		logMsg("areButtonsEnabled --> true");
	}

	// check that we can press buttons by
	// 1. areButtonsEnabled is true AND region is inside valid range
	private boolean canPressButton() {
		if (areButtonsEnabled == false) {
			logMsg("canPressButton = FALSE because areButtonsEnabled = false");
			CharSequence text = "Can't press button during processing";
			Toast toast = Toast.makeText(getApplicationContext(), text,
					Toast.LENGTH_SHORT);
			toast.setGravity(Gravity.CENTER, 0,0);
			toast.show();
			return false;
		}
		if (myRegion.x < Globals.MIN_REGION || myRegion.x > Globals.MAX_REGION) {
			logMsg("canPressButton = false. Can't press button because you're not at a valid region: "
					+ Globals.MIN_REGION
					+ " ~ "
					+ Globals.MAX_REGION
					+ ". You're at " + myRegion.x);
			CharSequence text = "Can't press button because you're not at a valid region: "
					+ Globals.MIN_REGION
					+ " ~ "
					+ Globals.MAX_REGION
					+ ". You're at " + myRegion.x;
			Toast toast = Toast.makeText(getApplicationContext(), text,
					Toast.LENGTH_LONG);
			toast.setGravity(Gravity.CENTER, 0,0);
			toast.show();
			return false;
		}
		logMsg("canPressButton = TRUE");
		return true;
	}

	/**
	 * Android application lifecycle management
	 **/

	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		// Mux initializations
		// Start outside active region
		long initRx = -1;
		long initRy = -1;
		myRegion = new RegionKey(initRx, initRy);

		// Buttons
		region_button = (Button) findViewById(R.id.region_button);
		region_button.setOnClickListener(region_button_listener);
		camera_button = (Button) findViewById(R.id.camera_button);
		get0_button = (Button) findViewById(R.id.get0_button);
		get0_button.setOnClickListener(get_button_listener);
		get1_button = (Button) findViewById(R.id.get1_button);
		get1_button.setOnClickListener(get_button_listener);
		get2_button = (Button) findViewById(R.id.get2_button);
		get2_button.setOnClickListener(get_button_listener);
		get3_button = (Button) findViewById(R.id.get3_button);
		get3_button.setOnClickListener(get_button_listener);
		get4_button = (Button) findViewById(R.id.get4_button);
		get4_button.setOnClickListener(get_button_listener);
		get5_button = (Button) findViewById(R.id.get5_button);
		get5_button.setOnClickListener(get_button_listener);

		// Setup the FrameLayout with the Camera Preview Screen
		cameraSurfaceView = new CameraSurfaceView(this);
		FrameLayout camerapreview = (FrameLayout) findViewById(R.id.CameraPreview);
		camerapreview.addView(cameraSurfaceView);
		// Setup the 'Take Picture' button to take a picture
		my_camera_button = (Button) findViewById(R.id.cameraPrev_button);
		my_camera_button.setOnClickListener(new OnClickListener() {
			public void onClick(View v) {
				if (canPressButton()) {
					// disable button clicks ASAP
					areButtonsEnabled = false;
					logMsg("areButtonsEnabled --> false");
					logMsg("disabling buttons ...");
					// Disable buttons until timeout is over or received reply
					// myHandler.post(disableButtonsProgressStartR);
					logMsg("new pic disableButtons XXX");
					areButtonsEnabled = false;
					logMsg("areButtonsEnabled --> false");
					progressDialog = ProgressDialog.show(CameraCloud.this, "",
									"Processing photo get or save to cloud server ... :)");

					// myHandler.postDelayed(buttonsEnableProgressTimeoutR,
					// uploadTimeoutPeriod);
					
					takeNum += 1;
					logCounts();

					logMsg("** Clicked take picture button **");

					Camera camera = cameraSurfaceView.getCamera();
					camera.takePicture(null, null, new HandlePictureStorage());

				} else {
					logMsg("can't press camera button yet");
				}
			}
		});

		// Text views
		opCountTv = (TextView) findViewById(R.id.opcount_tv);
		successCountTv = (TextView) findViewById(R.id.successcount_tv);
		failureCountTv = (TextView) findViewById(R.id.failurecount_tv);

		regionText = (EditText) findViewById(R.id.region_text);

		// Text views
		regionTv = (TextView) findViewById(R.id.region_tv);

		msgList = (ListView) findViewById(R.id.msgList);
		receivedMessages = new ArrayAdapter<String>(this, R.layout.message);
		msgList.setAdapter(receivedMessages);

		// Get a wakelock to keep everything running
		PowerManager pm = (PowerManager) getApplicationContext()
				.getSystemService(Context.POWER_SERVICE);
		wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK
				| PowerManager.ON_AFTER_RELEASE, TAG);
		wl.acquire();

		lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);

		// Setup writing to log file on sd card
		boolean mExternalStorageAvailable = false;
		boolean mExternalStorageWriteable = false;
		String state = Environment.getExternalStorageState();
		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			mExternalStorageAvailable = mExternalStorageWriteable = true;
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
			mExternalStorageAvailable = true;
			mExternalStorageWriteable = false;
		} else {
			// Something else is wrong. It may be one of many other states, but
			// all we need to know is we can neither read nor write
			mExternalStorageAvailable = mExternalStorageWriteable = false;
		}

		if (mExternalStorageAvailable && mExternalStorageWriteable) {
			myLogFile = new File(Environment.getExternalStorageDirectory(),
					String.format("csm-%d.txt", System.currentTimeMillis()));
			try {
				myLogWriter = new PrintWriter(myLogFile);
				logMsg("*** Opened log file for writing ***");
			} catch (Exception e) {
				myLogWriter = null;
				logMsg("*** Couldn't open log file for writing ***");
			}
		}

		// Start the mux, which will start the entire VNC, CSM, etc stack
		long id = -1;
		Bundle extras = getIntent().getExtras();
		if (extras != null && extras.containsKey("id")) {
			// we're running from within the simulator, so use given id and
			// start benchmark after a delay
			id = Long.valueOf(extras.getString("id"));
			Log.i("Status Activity, getting id = ", String.valueOf(id));
		}

		// enable button pressing
		areButtonsEnabled = true;
		logMsg("areButtonsEnabled --> true");

		logMsg("*** Application started ***");

	} // end OnCreate()

	/**
	 * onResume is is always called after onStart, even if userApp's not paused
	 */
	@Override
	protected void onResume() {
		logMsg("HI I'm in ONRESUME()");
		super.onResume();
		// update if phone moves 5m ( once GPS fix is acquired )
		// or if 5s has passed since last update
		lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,
				Globals.SAMPLING_DURATION, Globals.SAMPLING_DISTANCE, this);
		String logLocationUpdateParameters = String.format(
				"SAMPLING_DISTANCE : %d, SAMPLING_DURATION : %d",
				Globals.SAMPLING_DISTANCE, Globals.SAMPLING_DURATION);
		logMsg(logLocationUpdateParameters);
	}

	@Override
	protected void onPause() {
		logMsg("HI I'm in ONPAUSE()");
		super.onPause();
	}

	@Override
	public void onDestroy() {
		logMsg("inside onDestroy()");

		myLogWriter.flush();
		myLogWriter.close();

		lm.removeUpdates(this);
		if (wl != null)
			wl.release();
		super.onDestroy();

		// close camera
		if (cameraSurfaceView.camera != null) {
			logMsg("closing camera in Status Activity");
			cameraSurfaceView.camera.stopPreview();
			cameraSurfaceView.camera.setPreviewCallback(null);
			cameraSurfaceView.camera.release();
		} else {
			logMsg("no camera to close");
		}

		// from: http://stackoverflow.com/a/5036668
		// kill completely for a fresh start every time
		logMsg("close everything else");
		System.runFinalizersOnExit(true);
		System.exit(0);

		android.os.Process.killProcess(android.os.Process.myPid());
	}

	/*** UI Callbacks for Buttons, etc. ***/
	// UI callback for "Set Region" button.
	private OnClickListener region_button_listener = new OnClickListener() {
		public void onClick(View v) {
			String strX = regionText.getText().toString();
			if (strX.equals("")) {
				logMsg("please input a region");
				CharSequence text = "please input a region";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.CENTER, 0,0);
				toast.show();
			} else {
				int rX = Integer.parseInt(strX);
				int rY = 0;
				if (rX < Globals.MIN_REGION || rX > Globals.MAX_REGION) {
					logMsg("please input a region between "
							+ Globals.MIN_REGION + " ~ " + Globals.MAX_REGION);
					CharSequence text = "please input a region between "
							+ Globals.MIN_REGION + " ~ " + Globals.MAX_REGION;
					Toast toast = Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT);
					toast.setGravity(Gravity.CENTER, 0,0);
					toast.show();
				} else {
					changeRegion(new RegionKey(rX, rY));
				}
			}
		}
	};

	/***
	 * Location / GPS Stuff adapted from
	 * http://hejp.co.uk/android/android-gps-example/
	 */

	/** Called when a location update is received */
	@Override
	public void onLocationChanged(Location loc) {
		logMsg(".......... GPS onLocationChanged ...... ");
		if (loc != null) {
			// checkLocation(loc);
			determineLocation(loc, myRegion);
		} else {
			logMsg("Null Location");
		}
	}

	@Override
	public void onProviderDisabled(String arg0) { // GPS off
		logMsg("************ GPS turned OFF *************");
	}

	@Override
	public void onProviderEnabled(String arg0) {
		logMsg("************ GPS turned ON *************");
	}

	/** Called upon change in GPS status */
	@Override
	public void onStatusChanged(String provider, int status, Bundle extras) {
		logMsg("....... GPS status changed ....... ");
		switch (status) {
		case LocationProvider.OUT_OF_SERVICE:
			logMsg("GPS out of service");
			break;
		case LocationProvider.TEMPORARILY_UNAVAILABLE:
			logMsg("GPS temporarily unavailable");
			break;
		case LocationProvider.AVAILABLE:
			logMsg("GPS available");
			break;
		}
	}

	/**
	 * Camera from CameraSurface Works on both Nexus S and Galaxy Note phones,
	 * because StatusActivity is never paused
	 * 
	 * The photo is not saved on the sdcard.
	 * */
	private class HandlePictureStorage implements PictureCallback {
		@Override
		public void onPictureTaken(byte[] picture, Camera camera) {
			logMsg("inside HandlePictureStorage onPictureTaken()");

			takeCamGood += 1;
			logCounts();
			
			// let the preview work again
			cameraSurfaceView.camera.startPreview();

			logMsg("Picture successfully taken, ORIG BYTE LENGTH = "
					+ picture.length);

			// must garbage collect here or VM Heap might run out of memory!!
			System.gc();
			Bitmap new_bitmap = _bytesResizeBitmap(picture);
			ImageView image = (ImageView) findViewById(R.id.photoResultView);

			logMsg("Show photo from handle my camera take");

			image.setImageBitmap(new_bitmap);
			sendClientNewpic(new_bitmap);
		}
	}

	protected void sendClientNewpic(Bitmap bitmap) {
		logMsg("client making photo packet to send to leader");
		
		boolean isSaveSuccess = false;
		
		// Create a Packet to send to the Cloud
		try {
			// jpeg compression in bitmapToBytes
			byte[] photo_bytes = _bitmapToBytes(bitmap);
			logMsg("BYTE SIZE AFTER COMPRESSION: " + photo_bytes.length);

			// Send to the cloud
			CloudObject co_send = new CloudObject(photo_bytes);

			logMsg("sending new pic ....");
			long upload_start = System.currentTimeMillis();
			CloudObject co_return = serverRequest(CLIENT_UPLOAD_PHOTO,
					(int) myRegion.x, (int) myRegion.y, co_send);

			if (co_return != null) { // success!

				// Processing the return from the cloud
				// Analogous to Camera DIPLOMA's
				// "case Packet.CLIENT_UPLOAD_PHOTO_ACK"

				// see if it was unsuccessful
				if (co_return.status == CloudObject.CR_ERROR) {
					logMsg("FAIL! Client now knows saving photo on cloud server failed");
					CharSequence text = "FAIL! Saving photo on cloud server failed, try again.";
					Toast toast = Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT);
					toast.setGravity(Gravity.CENTER, 0,0);
					toast.show();
				} else { // CloudObject.CR_OKAY:
					
					// count it
					isSaveSuccess = true;
					takeGoodSave += 1; // add here in case things screw up later
					logCounts();
					
					logMsg("SUCCESS! Client now knows saving photo on cloud server succeeded");
					//CharSequence text = "SUCCESS! Saving photo on cloud server succeeded";
					//Toast toast = Toast.makeText(getApplicationContext(), text,
					//		Toast.LENGTH_SHORT);
					//toast.setGravity(Gravity.CENTER, 0,0);
					//toast.show();
				}

				logMsg("RETURN STATUS: " + co_return.status);
				
			} else { // something went wrong in the server request
				logMsg("Failed to complete the server request");
				CharSequence text = "Failed to complete the server request";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.CENTER, 0,0);
				toast.show();
			}
			
		} catch (IOException e) {
			logMsg("sendClientNewpic failed IOException");
			e.printStackTrace();
		}
		
		// enable buttons regardless of success or fail
		// myHandler.removeCallbacks(buttonsEnableProgressTimeoutR);
		_enableButtons();
		
		if (!isSaveSuccess){
			takeBad += 1;
			logCounts();
			logMsg("takeBad++");
		}
		
		logMsg("end of client send picture method");
	}

	protected Bitmap _bytesResizeBitmap(byte [] orig_bytes){
		BitmapFactory.Options options =new BitmapFactory.Options();

		// now we actually produce the bitmap, resized
		options.inJustDecodeBounds=false;
		// hard-code it to a big number
		options.inSampleSize=Globals.JPEG_SAMPLE_SIZE;
		// This decodeByteArray might crash the phone due to VM Heap OutOfMemoryError
		// if the options.inSampleSize is not set to a big number
		// that's why we garbage collect before calling this function and gc other decodeByteArrays to be safe
		// http://stackoverflow.com/questions/6402858/android-outofmemoryerror-bitmap-size-exceeds-vm-budget
		// http://stackoverflow.com/questions/477572/android-strange-out-of-memory-issue-while-loading-an-image-to-a-bitmap-object
		Bitmap new_bitmap =BitmapFactory.decodeByteArray(orig_bytes, 0, orig_bytes.length, options);
		logMsg("Our new height x width: " + new_bitmap.getHeight() + " x " + new_bitmap.getWidth());
		
		return new_bitmap;
	}

	protected int _bitmapBytes(Bitmap bitmap) {
		return bitmap.getRowBytes() * bitmap.getHeight();
	}

	public byte[] _bitmapToBytes(Bitmap bmp) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		bmp.compress(Bitmap.CompressFormat.JPEG, Globals.COMP_QUALITY, bos);
		// should still be under 65000 bytes
		byte[] bytes = bos.toByteArray();
		return bytes;
	}

	/* ############################################### */
	private OnClickListener get_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");
				logMsg("Inside get photo disableButtons XXX");
				// TODO: marker
				// progressDialog not showing due to the massive amount of stuff that follows
				// http://stackoverflow.com/questions/2798443/android-progressdialog-doesnt-show
				// but users can't press buttons either, so hopefully it's going to be okay
				progressDialog = ProgressDialog.show(CameraCloud.this, "",
						"Processing photo get or save to cloud server... :)");

        		getNum +=1;
        		logCounts();
        		
        		boolean isGetSuccess = false;
        		
				long targetRegion = 666;
        		switch (v.getId()){
        		case R.id.get0_button:
        			targetRegion = 0;
        			break;
        		case R.id.get1_button:
        			targetRegion = 1;
        			break;
        		case R.id.get2_button:
        			targetRegion = 2;
        			break;
        		case R.id.get3_button:
        			targetRegion = 3;
        			break;
        		case R.id.get4_button:
        			targetRegion = 4;
        			break;
        		case R.id.get5_button:
        			targetRegion = 5;
        			break;
        		}
        		logMsg("** Clicked getphotos Button from region " + targetRegion + " **");

        		// Create a Packet to send through Mux to Leader's UserApp
				CloudObject co_send = new CloudObject(null);
				CloudObject co_return;
				logMsg("Trying to get photo from server, about to call serverRequest()");

				// Send to the Cloud
				long download_start = System.currentTimeMillis();
				co_return = serverRequest(CLIENT_DOWNLOAD_PHOTO,
						(int) targetRegion, 0, co_send);

				if (co_return != null) { // success
					// Processing the return from the cloud
					// Analogous to Camera DIPLOMA's
					// "case Packet.CLIENT_SHOW_REMOTEPHOTO"

					// see if it was unsuccessful
					if (co_return.status == CloudObject.CR_ERROR) {
						logMsg("FAIL! Client failed to get photo from cloud server");
						CharSequence text = "FAIL! Failed to get photo from cloud server, try again";
						Toast toast = Toast.makeText(getApplicationContext(), text,
								Toast.LENGTH_LONG);
						toast.setGravity(Gravity.CENTER, 0,0);
						toast.show();
					} else if (co_return.status == CloudObject.CR_NO_PHOTO) { // no
						// photo
						logMsg("PHOTO DATA is NULL, perhaps region doesn't have a photo yet");
						CharSequence text = "PHOTO DATA is NULL, perhaps region doesn't have a photo yet";
						Toast toast = Toast.makeText(getApplicationContext(), text,
								Toast.LENGTH_LONG);
						toast.setGravity(Gravity.CENTER, 0,0);
						toast.show();
					} else { // success and might have photo data!

						// process photo
						byte[] photo_bytes = co_return.photo_bytes;
						if (photo_bytes == null) {
							// in case photo is null but server didn't ditect
							logMsg("PHOTO DATA is NULL, perhaps region doesn't have a photo yet and server doesn't know");
							CharSequence text = "PHOTO DATA is NULL, perhaps region doesn't have a photo yet";
							Toast toast = Toast.makeText(getApplicationContext(), text,
									Toast.LENGTH_LONG);
							toast.setGravity(Gravity.CENTER, 0,0);
							toast.show();

						} else {
							
							// success!
							
							isGetSuccess = true;
							getGood += 1;
							logCounts();
							
							ImageView image = (ImageView) findViewById(R.id.photoResultView);

							logMsg("Success! Client getting photo from cloud server, showing photo...");
							//CharSequence text = "SUCCESS! Getting photo from cloud server succeeded, showing photo ...";
							//Toast toast = Toast.makeText(getApplicationContext(), text,
							//		Toast.LENGTH_SHORT);
							//toast.setGravity(Gravity.CENTER, 0,0);
							//toast.show();

							logMsg("Remote photo's length: " + photo_bytes.length);

							// show photo
							// Garbage collect in case VM Heap runs out of memory with decodeByteArray
							System.gc();
							image.setImageBitmap(BitmapFactory.decodeByteArray(photo_bytes, 0, photo_bytes.length));
						}
					}

					logMsg("Done with Get photos button for region " + targetRegion);

				} else { // something went wrong in the server request
					logMsg("Failed to complete the server request (to get photo)");
					CharSequence text = "Failed to complete the server request";
					Toast toast = Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT);
					toast.setGravity(Gravity.CENTER, 0,0);
					toast.show();
				}
				// TODO marker
				// enable buttons regardless of success or fail
				// myHandler.removeCallbacks(buttonsEnableProgressTimeoutR);
				_enableButtons();
				
				if (!isGetSuccess){
					getBad += 1;
					logCounts();
					logMsg("getBad++");
				}
				
			} else {
				logMsg("can't press any buttons yet (in cameracloud)");
			}
		}
	};


	/**
	 * VCOREDAEMON STUFF
	 * 
	 */
	/** Called when location has changed, or periodically */
	/* Called upon moving from one current region to a new one, rx, ry */
	public void changeRegion(RegionKey newRegion) {
		if (newRegion.equals(myRegion)) // hasn't changed?
			return;

		RegionKey oldRegion = new RegionKey(myRegion);
		myRegion = new RegionKey(newRegion);

		// update screen
		regionTv.setText(String.format("(%d,%d)", myRegion.x, myRegion.y));

		logMsg(String.format("moving from region %s, to %s", oldRegion,
				newRegion));
	}

	public void checkLocation(Location loc) {
		if (loc == null)
			return;

		// round to 5th decimal place (approx 1 meter at equator)
		long mX = Math.round((loc.getLongitude() * 100000) - minLongitude);
		long mY = Math.round((loc.getLatitude() * 100000) - minLatitude);
		logMsg(String.format(
				"GPS lat,long: %f,%f mapping to cartesian x,y: %d,%d",
				loc.getLongitude(), loc.getLatitude(), mX, mY));

		// Determine what region we're in now
		// and if we've entered a new region since last check, take action
		/*
		 * long rx = mX / regionWidth; long ry = mY / regionWidth; RegionKey
		 * newRegion = new RegionKey(rx, ry); if (!newRegion.equals(myRegion))
		 * changeRegion(newRegion);
		 */
		long rx = Math.round(mX / regionWidth);
		// long ry = mY / regionWidth;
		RegionKey newRegion = new RegionKey(rx, 0);
		if (!newRegion.equals(myRegion))
			changeRegion(newRegion);

	}

	/*
	 * Region 0 starts at south-east point and increments one by one
	 * north-west-wards along Mass Ave.
	 */
	public void determineLocation(Location loc, RegionKey prevRegion) {
		// currently determining region only depends on X

		logMsg("Loc = " + loc + " Previous Region = " + prevRegion);

		double locx = loc.getLongitude();
		double locy = loc.getLatitude();
		double power = 100000;

		// x-width of a rectangular region
		double region_width = Math.sqrt(Math.pow(Globals.PHONE_RANGE_METERS, 2)
				- Math.pow(Globals.ROAD_WIDTH_METERS, 2));
		logMsg("GPS x/long:" + locx + ", GPS y/lat: " + locy
				+ ". Region width in x: " + region_width);

		// X = Longitude, Y = Latitude

		// Converting Latitude and Longitude into meters
		// Latitude: each is 10^-5 degree of lat Y
		final int earth_radius_meters = 6378140; // at equator
		final double location_latitude = 42.365; // angle from location to
													// equator
		double one_lat_to_meters = earth_radius_meters * 2 * Math.PI
				/ (360 * power); // 1.113 meters
		// logMsg("one_lat_to_meters = " + one_lat_to_meters);
		double one_long_to_meters = Math.cos(Math.toRadians(location_latitude))
				* one_lat_to_meters; // 0.822 meters
		// logMsg("one_long_to_meters = " + one_long_to_meters);

		// Endpoints of straight road to calculate theta
		final double north_west_loc_long = Globals.NW_LONG;
		final double north_west_loc_lat = Globals.NW_LAT;
		final double south_east_loc_long = Globals.SE_LONG;
		final double south_east_loc_lat = Globals.SE_LAT;

		double x_diff = Math.abs(south_east_loc_long - north_west_loc_long)
				* one_long_to_meters * power; // 401.6m
		// logMsg("x_diff = " + x_diff);
		double y_diff = Math.abs(north_west_loc_lat - south_east_loc_lat)
				* one_lat_to_meters * power; // 272.9m
		// logMsg("y_diff = " + y_diff);
		double theta = Math.atan(y_diff / x_diff); // 0.597 radians or 34.21
													// degrees
		// logMsg("theta = " + theta);

		// location in respect to south_east point
		double loc_x = (locx - south_east_loc_long) * one_long_to_meters
				* power;
		double loc_y = (locy - south_east_loc_lat) * one_lat_to_meters * power;
		//logMsg("unrotated x, y: " + loc_x + ", " + loc_y);

		// rotational matrix
		double loc_x_rotated = -1 * loc_x * Math.cos(theta) + loc_y
				* Math.sin(theta);
		double loc_y_rotated = loc_x * Math.sin(theta) + loc_y
				* Math.cos(theta);
		//logMsg("rotated x, y: " + loc_x_rotated + ", " + loc_y_rotated);

		// find the current region
		// Note: only depending on loc_x_rotated for this experiment
		double current_region = (int) Math.floor(loc_x_rotated / region_width);
		logMsg("location PINPOINTS to region = " + current_region
				+ ", previous " + prevRegion.x);

		double region_width_boundary = Globals.REGION_WIDTH_BOUNDARY_METERS;
		// check if it's inside boundary of region
		// region_width_boundary is defined as the boundary from the edge of
		// region to edge of boundary
		// i.e. the total boundary length surrounding an edge is 2*this value
		if ((fractionMod(loc_x_rotated, region_width) < region_width_boundary)
				|| (fractionMod(region_width - loc_x_rotated, region_width) < region_width_boundary)) {
			logMsg("location is INSIDE BOUNDARY, stay at prev region = "
					+ prevRegion);
		} else {
			// outside boundary

			// check that prev region and new region are different
			RegionKey new_region = new RegionKey((int) current_region, 0);
			if (Math.abs(new_region.x - prevRegion.x) == 0) {
				logMsg("stay at region " + prevRegion.x);
			} else {
				logMsg("location CHANGED TO NEW region = " + new_region
						+ " from region = " + prevRegion);
				changeRegion(new_region);
			}
		}
	}

	private double fractionMod(double a, double b) {
		double quotient = Math.floor(a / b);
		return a - quotient * b;
	}

	/**
	 * Make an HTTP GET request to the cloud
	 */

	private CloudObject serverRequest(int client_req_int, int x, int y,
			CloudObject cloudObj) {
		// ref
		// http://localtone.blogspot.com/2009/07/post-json-using-android-and-httpclient.html
		InputStream data = null;
		String url = String.format("http://" + Globals.CLOUD_SERVER_NAME
				+ "/%d/%d/%d/", client_req_int, x, y);
		logMsg("Server request to url: " + url);
		
		DefaultHttpClient httpclient = new DefaultHttpClient();

		/* we do NOT want timeouts because we want to show cloud is slow
		// (http://stackoverflow.com/a/1565243 timeout stuff)
		HttpParams httpParameters = new BasicHttpParams();
		
		// Set the timeout in milliseconds until a connection is established.
		// The default value is zero, that means the timeout is not used. 
		int timeoutConnection = 10000;
		HttpConnectionParams.setConnectionTimeout(httpParameters, timeoutConnection);
		// Set the default socket timeout (SO_TIMEOUT) 
		// in milliseconds which is the timeout for waiting for data.
		int timeoutSocket = 20000;
		HttpConnectionParams.setSoTimeout(httpParameters, timeoutSocket);

		DefaultHttpClient httpclient = new DefaultHttpClient(httpParameters);
		//DefaultHttpClient httpclient = new DefaultHttpClient();
		 */
		HttpPost httpost = new HttpPost(url);

		//JSONObject holder = new JSONObject();
		Gson gson_send = new Gson();

		String cloudObj_gsonstring = gson_send.toJson(cloudObj);
		logMsg("Cloud server request length: "
				+ cloudObj_gsonstring.getBytes().length);

		StringEntity se;
		try {
			se = new StringEntity(cloudObj_gsonstring);

			httpost.setEntity(se);
			httpost.setHeader("Accept", "application/json");
			httpost.setHeader("Content-type", "application/json");
			
			logMsg("about to execute HTTP POST");
			long startTime = System.currentTimeMillis();

			HttpResponse response;
			try {
				logMsg("in serverRequest() about to httpclient.execute()");
				response = httpclient.execute(httpost);

				long stopTime = System.currentTimeMillis();
				if (client_req_int == CLIENT_UPLOAD_PHOTO) {
					logMsg(String.format("CameraCloud Execute HTTP Upload latency: %dms", stopTime - startTime));
				} else { // CLIENT_DOWNLOAD_PHOTO
					logMsg(String.format("CameraCloud Execute HTTP Download latency: %dms", stopTime - startTime));
				}
					
				logMsg("finished executing HTTP POST, get data");
				data = response.getEntity().getContent();

				logMsg("make input stream reader for data");
				Reader r = new InputStreamReader(data);

				// TODO: ADD TIME and add to DIPLOMA
				logMsg("Cloud response length: "
						+ response.getEntity().getContentLength());

				Gson gson_ret = new Gson();

				logMsg("Returning cloud object");
				CloudObject returnedObject = gson_ret.fromJson(r, CloudObject.class);

				return returnedObject;
			
				// Note: Exceptions do not produce latencies!
			} catch (ClientProtocolException cpe) {
				logMsg("error excuting HTTP POST, ClientProtocolException");
				cpe.printStackTrace();
				_enableButtons();
				CharSequence text = "Cloud Failed due to ClientProtocolException";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.CENTER, 0,0);
				toast.show();
				if (client_req_int == CLIENT_UPLOAD_PHOTO) {
					takeException += 1;
				} else { // CLIENT_DOWNLOAD_PHOTO
					getException += 1;
				}
				logCounts();
			} catch (IOException ioe) {
				logMsg("error excuting HTTP POST, IOException");
				ioe.printStackTrace();
				_enableButtons();
				CharSequence text = "Cloud Failed due to IOException";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.setGravity(Gravity.CENTER, 0,0);
				toast.show();
				if (client_req_int == CLIENT_UPLOAD_PHOTO) {
					takeException += 1;
				} else { // CLIENT_DOWNLOAD_PHOTO
					getException += 1;
				}
				logCounts();
			} 
		} catch (UnsupportedEncodingException e) {
			logMsg("Error making String Entity");
			e.printStackTrace();
			_enableButtons();
			CharSequence text = "Cloud Failed due to UnsupportedEncodingException";
			Toast toast = Toast.makeText(getApplicationContext(), text,
					Toast.LENGTH_SHORT);
			toast.setGravity(Gravity.CENTER, 0,0);
			toast.show();
			if (client_req_int == CLIENT_UPLOAD_PHOTO) {
				takeException += 1;
			} else { // CLIENT_DOWNLOAD_PHOTO
				getException += 1;
			}
			logCounts();
		}

		return null;
	}
}
