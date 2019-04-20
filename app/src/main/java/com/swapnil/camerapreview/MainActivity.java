package com.swapnil.camerapreview;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Camera;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private CameraDevice mCamera;

    private final int CAMERA_REQ_CODE = 0;
    private CameraManager mCameraManager;
    private SurfaceView mSurfaceView;
    private DrawView overlayView;
    /**
     * Used to retrieve the captured image when the user takes a snapshot.
     */
    ImageReader mCaptureBuffer;

    /**
     * Handler for running tasks in the background.
     */
    Handler mBackgroundHandler;

    /** Our image capture session. */
    CameraCaptureSession mCaptureSession;

    /** An additional thread for running tasks that shouldn't block the UI. */
    HandlerThread mBackgroundThread;

    /** Handler for running tasks on the UI thread. */
    Handler mForegroundHandler;


    /** Output files will be saved as /sdcard/Pictures/sample*.jpg */
    static final String CAPTURE_FILENAME_PREFIX = "sample";

    private static final String TAG = "MAIN";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Start a background thread to manage camera requests
        mBackgroundThread = new HandlerThread("background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
        mForegroundHandler = new Handler(getMainLooper());

        //1. checking if have camera on the device
        if (checkCameraHardware(this)) {
            //2. Requesting to access the camera
            getCameraPermission();
        } else {
            showToast("Sorry no camera on this device");
        }
    }

    /**
     * Called when our {@code Activity} loses focus. <p>Tears everything back down.</p>
     */
    @Override
    protected void onPause() {
        super.onPause();
        if(mSurfaceView != null){
            try {
                // Ensure SurfaceHolderCallback#surfaceChanged() will run again if the user returns
                mSurfaceView.getHolder().setFixedSize(/*width*/0, /*height*/0);
                // Cancel any stale preview jobs
                if (mCaptureSession != null) {
                    mCaptureSession.close();
                    mCaptureSession = null;
                }
            } finally {
                if (mCamera != null) {
                    mCamera.close();
                    mCamera = null;
                }
            }
            // Finish processing posted messages, then join on the handling thread
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
            } catch (InterruptedException ex) {
                showLog("Background worker thread was interrupted while joined "+ ex.getLocalizedMessage());
            }
            // Close the ImageReader now that the background thread has stopped
            if (mCaptureBuffer != null) mCaptureBuffer.close();
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == CAMERA_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                //3. Since permission is granted we will start the preview
                showLog("permission granted");
                startCameraOps();
            } else {
                showToast("camera permission denied cannot move ahead");
                finish();
            }
        }
    }

    final SurfaceHolder.Callback mSurfaceHolderCallback = new SurfaceHolder.Callback() {

        /** The camera device to use, or null if we haven't yet set a fixed surface size. */
        private String mCameraId;
        /** Whether we received a change callback after setting our fixed surface size. */
        private boolean mGotSecondCallback;

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            showLog("surface created");
            // This is called every time the surface returns to the foreground
            mCameraId = null;
            mGotSecondCallback = false;
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            showLog("surface changed");
            // On the first invocation, width and height were automatically set to the view's size
            if (mCameraId == null) {
                // Find the device's back-facing camera and set the destination buffer sizes
                try {
                    for (String cameraId : mCameraManager.getCameraIdList()) {
                        CameraCharacteristics cameraCharacteristics =
                                mCameraManager.getCameraCharacteristics(cameraId);
                        if (cameraCharacteristics.get(cameraCharacteristics.LENS_FACING) ==
                                CameraCharacteristics.LENS_FACING_BACK) {
                            showLog("Found a back-facing camera");
                            StreamConfigurationMap info = cameraCharacteristics
                                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                            // Bigger is better when it comes to saving our image
                            Size largestSize = Collections.max(
                                    Arrays.asList(info.getOutputSizes(ImageFormat.JPEG)),
                                    new CompareSizesByArea());
                            // Prepare an ImageReader in case the user wants to capture images
                            showLog("Capture size: " + largestSize);
                            mCaptureBuffer = ImageReader.newInstance(largestSize.getWidth(),
                                    largestSize.getHeight(), ImageFormat.JPEG, /*maxImages*/2);
                            mCaptureBuffer.setOnImageAvailableListener(
                                    mImageCaptureListener, mBackgroundHandler);
                            // Danger, W.R.! Attempting to use too large a preview size could
                            // exceed the camera bus' bandwidth limitation, resulting in
                            // gorgeous previews but the storage of garbage capture data.
                            showLog("SurfaceView size: " +
                                    mSurfaceView.getWidth() + 'x' + mSurfaceView.getHeight());
                            Size optimalSize = chooseBigEnoughSize(
                                    info.getOutputSizes(SurfaceHolder.class), width, height);
                            // Set the SurfaceHolder to use the camera's largest supported size
                            showLog("Preview size: " + optimalSize);
                            SurfaceHolder surfaceHolder = mSurfaceView.getHolder();
                            surfaceHolder.setFixedSize(optimalSize.getWidth(),
                                    optimalSize.getHeight());
                            mCameraId = cameraId;
                            return;
                            // Control flow continues with this method one more time
                            // (since we just changed our own size)
                        }
                    }
                } catch (CameraAccessException ex) {
                    showLog("Unable to list cameras " + ex.getLocalizedMessage());
                }
                showLog("Didn't find any back-facing cameras");
                // This is the second time the method is being invoked: our size change is complete
            } else if (!mGotSecondCallback) {
                if (mCamera != null) {
                    showLog("Aborting camera open because it hadn't been closed");
                    return;
                }
                // Open the camera device
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE}, CAMERA_REQ_CODE);
                } else {
                    //permission granted already
                    try {
                        mCameraManager.openCamera(mCameraId, mCameraStateCallback,
                                mBackgroundHandler);
                    } catch (CameraAccessException ex) {
                        showLog("Failed to configure output surface");
                    }
                    mGotSecondCallback = true;
                    // Control flow continues in mCameraStateCallback.onOpened()
                }

            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            showLog("surface destroyed");
            holder.removeCallback(this);
            // We don't stop receiving callbacks forever because onResume() will reattach us
        }
    };

    /**
     * Callback invoked when we've received a JPEG image from the camera.
     */
    final ImageReader.OnImageAvailableListener mImageCaptureListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    // Save the image once we get a chance
                    //mBackgroundHandler.post(new CapturedImageSaver(reader.acquireNextImage()));
                    // Control flow continues in CapturedImageSaver#run()
                }};

    private void startCameraOps() {
        initCamera();
        initSurfaceView();
    }

    private void initSurfaceView() {
//        View layout = getLayoutInflater().inflate(R.layout.activity_main, null);
        mSurfaceView = findViewById(R.id.mainSurfaceView);
//        overlayView = findViewById(R.id.drawView);
        mSurfaceView.getHolder().addCallback(mSurfaceHolderCallback);
//        setContentView(mSurfaceView);

    }

    private void getCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE}, CAMERA_REQ_CODE);
        } else {
            //permission granted already
            showLog("permission granted already");
            startCameraOps();
        }
    }

    private void initCamera() {
        mCameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
    }

    private boolean checkCameraHardware(Context context) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            // this device has a camera
            return true;
        } else {
            // no camera on this device
            return false;
        }
    }

    /**
     * Calledbacks invoked upon state changes in our {@code CameraDevice}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraDevice.StateCallback mCameraStateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    showLog("Successfully opened camera");
                    mCamera = camera;
                    try {
                        List<Surface> outputs = Arrays.asList(
                                mSurfaceView.getHolder().getSurface(), mCaptureBuffer.getSurface());
                        camera.createCaptureSession(outputs, mCaptureSessionListener,
                                mBackgroundHandler);
                    } catch (CameraAccessException ex) {
                        showLog("Failed to create a capture session"+ ex.getLocalizedMessage());
                    }
                    // Control flow continues in mCaptureSessionListener.onConfigured()
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    showLog("Camera was disconnected");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    showLog("State error on device '" + camera.getId() + "': code " + error);
                }
            };


    /**
     * Callbacks invoked upon state changes in our {@code CameraCaptureSession}. <p>These are run on
     * {@code mBackgroundThread}.</p>
     */
    final CameraCaptureSession.StateCallback mCaptureSessionListener =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    showLog("Finished configuring camera outputs");
                    mCaptureSession = session;
                    SurfaceHolder holder = mSurfaceView.getHolder();
                    if (holder != null) {
                        try {
                            // Build a request for preview footage
                            CaptureRequest.Builder requestBuilder =
                                    mCamera.createCaptureRequest(mCamera.TEMPLATE_PREVIEW);
                            requestBuilder.addTarget(holder.getSurface());
                            CaptureRequest previewRequest = requestBuilder.build();
                            // Start displaying preview images
                            try {
                                session.setRepeatingRequest(previewRequest, /*listener*/null,
                                        /*handler*/null);
                            } catch (CameraAccessException ex) {
                                showLog("Failed to make repeating preview request"+ ex.getLocalizedMessage());
                            }
                        } catch (CameraAccessException ex) {
                            showLog("Failed to build preview request "+ex.getLocalizedMessage());
                        }
                    }
                    else {
                        showLog("Holder didn't exist when trying to formulate preview request");
                    }
                }
                @Override
                public void onClosed(CameraCaptureSession session) {
                    mCaptureSession = null;
                }
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    showLog("Configuration error on device '" + mCamera.getId());
                }};


    /**
     * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
     * width and height are at least as large as the respective requested values.
     * @param choices The list of sizes that the camera supports for the intended output class
     * @param width The minimum desired width
     * @param height The minimum desired height
     * @return The optimal {@code Size}, or an arbitrary one if none were big enough
     */
    static Size chooseBigEnoughSize(Size[] choices, int width, int height) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<Size>();
        for (Size option : choices) {
            if (option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            Log.e("MAIN", "Couldn't find any suitable preview size");

            return choices[0];
        }
    }
    /**
     * Compares two {@code Size}s based on their areas.
     */
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() -
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    private void showToast(String message) {
        Toast.makeText(this,message,Toast.LENGTH_SHORT).show();
    }

    private void showLog(String log){
        Log.e("MAIN",""+log);
    }

    //click listener written in xml file
    public void onClickOnSurfaceView(View view) {

    }

    /**
     * Deferred processor responsible for saving snapshots to disk. <p>This is run on
     * {@code mBackgroundThread}.</p>
     */
    static class CapturedImageSaver implements Runnable {
        /** The image to save. */
        private Image mCapture;
        public CapturedImageSaver(Image capture) {
            mCapture = capture;
        }
        @Override
        public void run() {
            try {
                // Choose an unused filename under the Pictures/ directory
                File file = File.createTempFile(CAPTURE_FILENAME_PREFIX, ".jpg",
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_PICTURES));
                try (FileOutputStream ostream = new FileOutputStream(file)) {
                    Log.i(TAG, "Retrieved image is" +
                            (mCapture.getFormat() == ImageFormat.JPEG ? "" : "n't") + " a JPEG");
                    ByteBuffer buffer = mCapture.getPlanes()[0].getBuffer();
                    Log.i(TAG, "Captured image size: " +
                            mCapture.getWidth() + 'x' + mCapture.getHeight());
                    // Write the image out to the chosen file
                    byte[] jpeg = new byte[buffer.remaining()];
                    buffer.get(jpeg);
                    ostream.write(jpeg);
                } catch (FileNotFoundException ex) {
                    Log.e(TAG, "Unable to open output file for writing", ex);
                } catch (IOException ex) {
                    Log.e(TAG, "Failed to write the image to the output file", ex);
                }
            } catch (IOException ex) {
                Log.e(TAG, "Unable to create a new output file", ex);
            } finally {
                mCapture.close();
            }
        }
    }
}
