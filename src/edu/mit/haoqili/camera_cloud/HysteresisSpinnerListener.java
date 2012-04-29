package edu.mit.haoqili.camera_cloud;

import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.TextView;

// Hysteresis Spinner stuff
public class HysteresisSpinnerListener implements OnItemSelectedListener {

	public void onItemSelected(AdapterView<?> parent,
			View view, int pos, long id) {
		CameraCloud.changeHysteresis(parent.getItemAtPosition(pos).toString());
	}

	public void onNothingSelected(AdapterView parent) {
		// Do nothing.
	}
}