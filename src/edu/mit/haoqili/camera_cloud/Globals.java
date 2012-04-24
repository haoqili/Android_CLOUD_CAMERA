package edu.mit.haoqili.camera_cloud;

import java.io.File;

import android.os.Environment;


public class Globals {
		// frequently changed contants
		final static public int JPEG_SAMPLE_SIZE = 12; // if too low, will cause Nexus S out of memory!

		// cloud server
    	final static public String CLOUD_SERVER_NAME="18.111.14.105:8213";
    
    	// timeout stuff
    	final static public int TIMEOUTCONNECTION = 60000;
    	final static public int TIMEOUTSOCKET = 60000;
	
		// new region calculations
		// road parameters, used to calculate region width
        // sqrt(30^2+15^2), 21 meters is the max linearly on 77 Mass Ave, 
        // and we want PHONE_RANGE_METERS to span 2 regions, but 10.5 meters is too short
        final static public double PHONE_RANGE_METERS=33;
		final static public double ROAD_WIDTH_METERS = 30;
		// to calculate buffer zone
		final static public double REGION_WIDTH_BOUNDARY_METERS = 5;
		
		// to calculate start point & road angle:
		// Endpoints on (straight) Mass Ave to calculate theta
		/* Central Mass Ave
		final static public double NW_LONG = -71.104888;
		final static public double NW_LAT = 42.365944;
		final static public double SE_LONG = -71.100005;
		final static public double SE_LAT = 42.363492;
		*/
		
		// 77 Mass Ave
		final static public double NW_LONG = -71.093881;
		final static public double NW_LAT = 42.359644;
		final static public double SE_LONG = -71.092894;
		final static public double SE_LAT = 42.357741;

		
		final static public int NTHREADS=1;
        final static public boolean CACHE_ENABLED_ON_START = false;
        final static public double BENCHMARK_READ_DISTRIBUTION_ON_START = 0.9f;
        final static public long BENCHMARK_START_DELAY = 1000L; // milliseconds
        final static public String CSM_SERVER_NAME="128.30.87.130:5212"; //128.30.66.123:5212      

        final static public int SAMPLING_DURATION=1000;
        final static public int SAMPLING_DISTANCE=1;
        final static public String BROADCAST_ADDRESS="192.168.5.255"; //.255.255 also works

        
        // old region calculations
        final static public int REGION_WIDTH=17; // in meters, 35/2
        final static public int SOUTHEAST_LONG = -7110000;
        final static public int SOUTHEAST_LAT = 4236349;
        // Long is x
        final static public int MAX_X_REGIONS=6;
        final static public int MAX_Y_REGIONS=1;
        final static public int MINIMUM_LONGITUDE=SOUTHEAST_LONG - REGION_WIDTH*MAX_X_REGIONS;
        final static public int MINIMUM_LATITUDE=SOUTHEAST_LAT;
        
        // region constraints, for the UI
        final static public int MIN_REGION = 0;
        final static public int MAX_REGION = MAX_X_REGIONS-1;
        
        final static public int SPARSE_NUM_ITER=100000;

		// photo properties
		final static public int COMP_QUALITY = 10; // 0 - 100, 100 is max quality
		final static String PHOTO_PATH = Environment.getExternalStorageDirectory().getName() + File.separatorChar + "temp_photo.jpg";
		final static int TARGET_SHORT_SIDE = 200;
		final static int TARGET_LONG_SIDE = 240;
		final static String PHOTO_KEY = "diplomaPhotos";
} 
