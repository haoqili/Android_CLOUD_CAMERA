package edu.mit.haoqili.camera_cloud;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OptionalDataException;
import java.io.PrintWriter;
import java.io.Reader;
import java.net.URISyntaxException;

import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONObject;

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

	private static final int CAMERA_PIC_REQUEST = 111;

	// UI elements
	Button camera_button, region_button, my_camera_button;
	Button get1_button, get2_button, get3_button, get4_button, get5_button,
			get6_button;
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

	// timeout stuff
	final static private long uploadTimeoutPeriod = 6000L;
	final static private long downloadTimoutPeriod = 10000L;
	// areButtonsEnabled is the first line of defense against multi-clicking
	// set to false as soon as a take/get picture button is pressed
	// none of the other buttons can be pressed until it's set true again
	// set to true when progressDialog is dismissed
	private boolean areButtonsEnabled = false;
	// progressDialog is the second line of defense against multi-clicking
	// when shown, disables the rest of the ui, including buttons
	private ProgressDialog progressDialog;

	// VCore Daemon Location Constants
	private long nodeId;
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

	// Runnables // TODO
	/**
	 * Disable buttons at press of any button (take new pic for upload / region
	 * x get for download)
	 */
	private Runnable disableButtonsProgressStartR = new Runnable() {
		public void run() {
			Log.i(TAG,
					"Inside disableButtonsR #############################################");
			areButtonsEnabled = false;
			Log.i(TAG, "areButtonsEnabled --> false");
			progressDialog = ProgressDialog.show(CameraCloud.this, "",
					"Processing photo get or save to cloud server ... :)");
		}
	};

	private void _enableButtons() {
		Log.i(TAG, "Inside _enableButtons");
		progressDialog.dismiss();
		areButtonsEnabled = true;
		Log.i(TAG, "areButtonsEnabled --> true");
		CharSequence text = "Can press buttons again";
		Toast toast = Toast.makeText(getApplicationContext(), text,
				Toast.LENGTH_SHORT);
		toast.show();
	}

	/** Enable buttons again, either when getting reply or timed out */
	/*
	 * private Runnable buttonsEnableProgressTimeoutR = new Runnable() { public
	 * void run() { Log.i(TAG,
	 * "inside buttonsEnableProgressTimeoutR. OH NO! Your photo (save/get) request TIMED OUT. Try again later!"
	 * ); CharSequence text =
	 * "OH NO! Your photo (take/get) request TIMED OUT. Try again later!"; Toast
	 * toast = Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG);
	 * toast.show(); _enableButtons();
	 * 
	 * } };
	 */

	// check that we can press buttons by
	// 1. areButtonsEnabled is true AND region is inside valid range
	private boolean canPressButton() {
		if (areButtonsEnabled == false) {
			logMsg("canPressButton = FALSE because areButtonsEnabled = false");
			CharSequence text = "Can't press button during processing";
			Toast toast = Toast.makeText(getApplicationContext(), text,
					Toast.LENGTH_SHORT);
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
		nodeId = -1;
		// Start outside active region
		long initRx = -1;
		long initRy = -1;
		myRegion = new RegionKey(initRx, initRy);

		// Buttons
		region_button = (Button) findViewById(R.id.region_button);
		region_button.setOnClickListener(region_button_listener);
		camera_button = (Button) findViewById(R.id.camera_button);
		// camera_button.setOnClickListener(camera_button_listener);
		get1_button = (Button) findViewById(R.id.get1_button);
		get1_button.setOnClickListener(get1_button_listener);
		get2_button = (Button) findViewById(R.id.get2_button);
		get2_button.setOnClickListener(get2_button_listener);
		get3_button = (Button) findViewById(R.id.get3_button);
		get3_button.setOnClickListener(get3_button_listener);
		get4_button = (Button) findViewById(R.id.get4_button);
		get4_button.setOnClickListener(get4_button_listener);
		get5_button = (Button) findViewById(R.id.get5_button);
		get5_button.setOnClickListener(get5_button_listener);
		get6_button = (Button) findViewById(R.id.get6_button);
		get6_button.setOnClickListener(get6_button_listener);

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
					Log.i(TAG,
							"Inside disableButtonsR #############################################");
					areButtonsEnabled = false;
					Log.i(TAG, "areButtonsEnabled --> false");
					progressDialog = ProgressDialog
							.show(CameraCloud.this, "",
									"Processing photo get or save to cloud server ... :)");

					// myHandler.postDelayed(buttonsEnableProgressTimeoutR,
					// uploadTimeoutPeriod);

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
	// TODO
	private class HandlePictureStorage implements PictureCallback {
		@Override
		public void onPictureTaken(byte[] picture, Camera camera) {
			logMsg("inside HandlePictureStorage onPictureTaken()");

			// let the preview work again
			cameraSurfaceView.camera.startPreview();

			logMsg("Picture successfully taken, ORIG BYTE LENGTH = "
					+ picture.length);
			try {
				Bitmap orig_bitmap = _bytesToBitmap(picture);
				Bitmap new_bitmap = _bytesResizeBitmap(picture, orig_bitmap);
				ImageView image = (ImageView) findViewById(R.id.photoResultView);

				logMsg("Show photo from handle my camera take");
				image.setImageBitmap(new_bitmap);
				sendClientNewpic(new_bitmap);
			} catch (OptionalDataException e) {
				logMsg("HandlePictureStorage _bytesToBitmap failed OptionalDataExeption");
				e.printStackTrace();
			} catch (ClassNotFoundException e) {
				logMsg("HandlePictureStorage _bytesToBitmap failed ClassNotFoundException");
				e.printStackTrace();
			} catch (IOException e) {
				logMsg("HandlePictureStorage _bytesToBitmap failed IOException");
				e.printStackTrace();
			}
		}
	}

	protected void sendClientNewpic(Bitmap bitmap) {
		logMsg("client making photo packet to send to leader");
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

			// TODO: I'm a location marker. Delete this line when useless
			// Processing the return from the cloud
			// Analogous to Camera DIPLOMA's
			// "case Packet.CLIENT_UPLOAD_PHOTO_ACK"

			// latency stuff
			long upload_end = System.currentTimeMillis();
			long latency = upload_end - upload_start;
			logMsg("CameraCloud upload new photo latency = " + latency);
			logMsg("CameraCloud upload start " + upload_start + " ~ stop "
					+ upload_end);

			// see if it was unsuccessful
			if (co_return.status == CloudObject.CR_ERROR) {
				logMsg("FAIL! Client now knows saving photo on cloud server failed");
				CharSequence text = "FAIL! Saving photo on cloud server failed, try again.";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.show();
			} else { // CloudObject.CR_OKAY:
				logMsg("SUCCESS! Client now knows saving photo on cloud server succeeded");
				CharSequence text = "SUCCESS! Saving photo on cloud server succeeded";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_SHORT);
				toast.show();
			}

			// enable buttons right now, not until progressdialog timeout
			// myHandler.removeCallbacks(buttonsEnableProgressTimeoutR);
			_enableButtons();

			logMsg("RETURN STATUS: " + co_return.status);
		} catch (URISyntaxException e) {
			logMsg("sendClientNewpic failed URISyntaxException");
			e.printStackTrace();
		} catch (IOException e) {
			logMsg("sendClientNewpic failed IOException");
			e.printStackTrace();
		}
		logMsg("end of client send picture method");
	}

	// resize photo
	protected Bitmap _getAndResizeBitmap() {
		BitmapFactory.Options options = new BitmapFactory.Options();
		// first we don't produce an actual bitmap, but just probe its
		// dimensions
		options.inJustDecodeBounds = true;
		Bitmap bitmap = BitmapFactory.decodeFile(Globals.PHOTO_PATH, options);
		int h, w;
		if (options.outHeight > options.outWidth) {
			h = (int) Math.ceil(options.outHeight
					/ (float) Globals.TARGET_SHORT_SIDE);
			w = (int) Math.ceil(options.outWidth
					/ (float) Globals.TARGET_LONG_SIDE);
		} else {
			w = (int) Math.ceil(options.outHeight
					/ (float) Globals.TARGET_SHORT_SIDE);
			h = (int) Math.ceil(options.outWidth
					/ (float) Globals.TARGET_LONG_SIDE);
		}
		if (h > 1 || w > 1) {
			options.inSampleSize = (h > w) ? h : w;
		}
		// now we actually produce the bitmap, resized
		options.inJustDecodeBounds = false;
		bitmap = BitmapFactory.decodeFile(Globals.PHOTO_PATH, options);
		logMsg("Our new height x width: " + bitmap.getHeight() + " x "
				+ bitmap.getWidth());

		return bitmap;
	}

	protected Bitmap _bytesResizeBitmap(byte[] orig_bytes, Bitmap orig_bitmap) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		int h, w;
		if (orig_bitmap.getHeight() > orig_bitmap.getWidth()) {
			h = (int) Math.ceil(orig_bitmap.getHeight()
					/ (float) Globals.TARGET_SHORT_SIDE);
			w = (int) Math.ceil(orig_bitmap.getWidth()
					/ (float) Globals.TARGET_LONG_SIDE);
		} else {
			w = (int) Math.ceil(orig_bitmap.getHeight()
					/ (float) Globals.TARGET_SHORT_SIDE);
			h = (int) Math.ceil(orig_bitmap.getWidth()
					/ (float) Globals.TARGET_LONG_SIDE);
		}
		if (h > 1 || w > 1) {
			options.inSampleSize = (h > w) ? h : w;
		}
		// now we actually produce the bitmap, resized
		options.inJustDecodeBounds = false;
		Bitmap new_bitmap = BitmapFactory.decodeByteArray(orig_bytes, 0,
				orig_bytes.length, options);
		logMsg("Our new height x width: " + new_bitmap.getHeight() + " x "
				+ new_bitmap.getWidth());

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

	public Bitmap _bytesToBitmap(byte[] photo_bytes)
			throws OptionalDataException, ClassNotFoundException, IOException {
		return BitmapFactory
				.decodeByteArray(photo_bytes, 0, photo_bytes.length);
	}

	/* ############################################### */
	/* ############################################### */
	/* ############################################### */
	/* dumb button listeners */
	private OnClickListener get1_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 1 **");
				long targetRegion = 1;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 1 yet");
			}
		}
	};
	private OnClickListener get2_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 2 **");
				long targetRegion = 2;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 2 yet");
			}
		}
	};
	private OnClickListener get3_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 3 **");
				long targetRegion = 3;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 3 yet");
			}
		}
	};
	private OnClickListener get4_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 4 **");
				long targetRegion = 4;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 4 yet");
			}
		}
	};
	private OnClickListener get5_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 5 **");
				long targetRegion = 5;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 5 yet");
			}
		}
	};
	private OnClickListener get6_button_listener = new OnClickListener() {
		public void onClick(View v) {
			if (canPressButton()) {
				// disable button clicks ASAP
				areButtonsEnabled = false;
				logMsg("areButtonsEnabled --> false ");

				logMsg("** Clicked getphotos Button from region 6 **");
				long targetRegion = 6;
				_button_listener_helper(targetRegion);
			} else {
				logMsg("can't press region 6 yet");
			}
		}
	};

	private void _button_listener_helper(long targetRegion) { // TODO
		// Disable buttons until timeout is over, or received reply
		// myHandler.post(disableButtonsProgressStartR);
		Log.i(TAG,
				"Inside disableButtonsR @@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@@");
		areButtonsEnabled = false;
		Log.i(TAG, "areButtonsEnabled --> false");
		progressDialog = ProgressDialog.show(CameraCloud.this, "",
				"Processing photo get or save to cloud server ... :)");

		// myHandler.postDelayed(buttonsEnableProgressTimeoutR,
		// downloadTimoutPeriod);

		// Create a Packet to send through Mux to Leader's UserApp
		CloudObject co_send = new CloudObject(null);
		CloudObject co_return;
		logMsg("Trying to get photo from server %%%%%%@%@%@%@%@%@%@%@%@%@%@%@%@%@%@%%@%@%@@%%@");
		try {
			// Send to the Cloud
			long download_start = System.currentTimeMillis();
			co_return = serverRequest(CLIENT_DOWNLOAD_PHOTO,
					(int) targetRegion, 0, co_send);

			// Processing the return from the cloud
			// Analogous to Camera DIPLOMA's
			// "case Packet.CLIENT_SHOW_REMOTEPHOTO"

			// latency stuff
			long download_end = System.currentTimeMillis();
			long latency = download_end - download_start;
			logMsg("CameraCloud download photo latency = " + latency);
			logMsg("CameraCloud download start " + download_start + " ~ stop "
					+ download_end);

			// see if it was unsuccessful
			if (co_return.status == CloudObject.CR_ERROR) {
				logMsg("FAIL! Client failed to get photo from cloud server");
				CharSequence text = "FAIL! Failed to get photo from cloud server, try again";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_LONG);
				toast.show();
			} else if (co_return.status == CloudObject.CR_NO_PHOTO) { // no
																		// photo
				logMsg("PHOTO DATA is NULL, perhaps region doesn't have a photo yet");
				CharSequence text = "PHOTO DATA is NULL, perhaps region doesn't have a photo yet";
				Toast toast = Toast.makeText(getApplicationContext(), text,
						Toast.LENGTH_LONG);
				toast.show();
			} else { // success and has photo data!

				// process photo
				byte[] photo_bytes = co_return.photo_bytes;
				if (photo_bytes == null) {
					// in case photo is null but server didn't ditect
					logMsg("PHOTO DATA is NULL, perhaps region doesn't have a photo yet and server doesn't know");
					CharSequence text = "PHOTO DATA is NULL, perhaps region doesn't have a photo yet";
					Toast toast = Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_LONG);
					toast.show();
				} else {
					Bitmap photo_one = null;
					try {
						photo_one = _bytesToBitmap(photo_bytes);
					} catch (OptionalDataException e) {
						logMsg("get photo _bytesToBitmap OptionalDataException");
						e.printStackTrace();
					} catch (ClassNotFoundException e) {
						logMsg("get photo _bytesToBitmap ClassNotFoundException");
						e.printStackTrace();
					} catch (IOException e) {
						logMsg("get photo _bytesToBitmap IOException");
						e.printStackTrace();
					}
					ImageView image = (ImageView) findViewById(R.id.photoResultView);

					// print success!
					logMsg("Success! Client getting photo from cloud server, showing photo...");
					CharSequence text = "SUCCESS! Getting photo from cloud server succeeded, showing photo ...";
					Toast toast = Toast.makeText(getApplicationContext(), text,
							Toast.LENGTH_SHORT);
					toast.show();

					// show photo
					image.setImageBitmap(photo_one);
				}
			}

			// enable buttons right now, not untill progressdialog timeout
			// myHandler.removeCallbacks(buttonsEnableProgressTimeoutR);
			_enableButtons();
		} catch (ClientProtocolException e1) {
			logMsg("get photo ClientProtocolException");
			e1.printStackTrace();
		} catch (URISyntaxException e1) {
			logMsg("get photo URISyntaxException");
			e1.printStackTrace();
		} catch (IOException e1) {
			logMsg("get photo IOException");
			e1.printStackTrace();
		}

		logMsg("Done with Get photos button for region " + targetRegion);
	}

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
		// TODO: make this work with Y as well
		// currently determining region only depends on X

		logMsg("INSIDE DETERMINELOCATION");
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

		// Endpoints on (straight) Mass Ave to calculate theta
		/*
		 * final double north_west_loc_long = -71.104888; final double
		 * north_west_loc_lat = 42.365944; final double south_east_loc_long =
		 * -71.100005; final double south_east_loc_lat = 42.363492;
		 */
		final double north_west_loc_long = -71.093881;
		final double north_west_loc_lat = 42.359644;
		final double south_east_loc_long = -71.092894;
		final double south_east_loc_lat = 42.357741;

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
		logMsg("unrotated x, y: " + loc_x + ", " + loc_y);

		// rotational matrix
		double loc_x_rotated = -1 * loc_x * Math.cos(theta) + loc_y
				* Math.sin(theta);
		double loc_y_rotated = loc_x * Math.sin(theta) + loc_y
				* Math.cos(theta);
		logMsg("rotated x, y: " + loc_x_rotated + ", " + loc_y_rotated);

		// find the current region
		// Note: only depending on loc_x_rotated for this experiment
		// TODO: for experiments involving a matrix of regions, add y
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
			CloudObject cloudObj) throws URISyntaxException,
			ClientProtocolException, IOException {
		// ref
		// http://localtone.blogspot.com/2009/07/post-json-using-android-and-httpclient.html
		InputStream data = null;
		String url = String.format("http://" + Globals.CLOUD_SERVER_NAME
				+ "/%d/%d/%d/", client_req_int, x, y);
		logMsg("Server request to url: " + url);
		DefaultHttpClient httpclient = new DefaultHttpClient();
		HttpPost httpost = new HttpPost(url);

		JSONObject holder = new JSONObject();
		Gson gson_send = new Gson();

		String cloudObj_gsonstring = gson_send.toJson(cloudObj);
		logMsg("Cloud server request length: "
				+ cloudObj_gsonstring.getBytes().length);

		StringEntity se = new StringEntity(cloudObj_gsonstring);

		httpost.setEntity(se);
		httpost.setHeader("Accept", "application/json");
		httpost.setHeader("Content-type", "application/json");

		logMsg("about to execute HTTP POST");
		long startTime = System.currentTimeMillis();
		HttpResponse response = httpclient.execute(httpost);
		long stopTime = System.currentTimeMillis();
		logMsg(String.format("Execute HTTP latency: %dms", stopTime - startTime));

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
	}
}
