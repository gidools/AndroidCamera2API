package maxst.com.androidcamera2api;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class Camera2Activity extends AppCompatActivity {

	private static final String TAG = Camera2Activity.class.getSimpleName();

	private Button takePictureButton;
	private TextureView textureView;
	private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

	static {
		ORIENTATIONS.append(Surface.ROTATION_0, 90);
		ORIENTATIONS.append(Surface.ROTATION_90, 0);
		ORIENTATIONS.append(Surface.ROTATION_180, 270);
		ORIENTATIONS.append(Surface.ROTATION_270, 180);
	}

	protected CameraDevice cameraDevice;
	protected CameraCaptureSession cameraCaptureSession;
	protected CaptureRequest captureRequest;
	protected CaptureRequest.Builder captureRequestBuilder;
	private Size imageDimension;
	private ImageReader imageReader;
	private File file;
	private static final int REQUEST_CAMERA_PERMISSION = 200;
	private boolean flashSupported;
	private Handler backgroundHandler;
	private HandlerThread backgroundThreadHandler;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_camera2);

		textureView = (TextureView) findViewById(R.id.texture);
		assert textureView != null;

		textureView.setSurfaceTextureListener(textureListener);
		takePictureButton = (Button) findViewById(R.id.btn_takepicture);
		assert takePictureButton != null;

		takePictureButton.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View view) {
				takePicture();
			}
		});
		findViewById(R.id.start_camera).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				startBackgroundThread();
				openCamera();
			}
		});

		findViewById(R.id.stop_camera).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View view) {
				closeCamera();
				stopBackgroundThread();
			}
		});
	}

	private ImageReader.OnImageAvailableListener imageAvailListener = new ImageReader.OnImageAvailableListener() {

		private int  frames      = 0;
		private long initialTime = SystemClock.elapsedRealtimeNanos();

		@Override
		public void onImageAvailable(ImageReader imageReader) {

			Image currentCameraImage = imageReaderForProcessing.acquireLatestImage();

			// Return if no new camera image is available.
			if (currentCameraImage == null) {
				return;
			}

			frames++;
			if ((frames % 30) == 0) {
				long currentTime = SystemClock.elapsedRealtimeNanos();
				long fps = Math.round(frames * 1e9 / (currentTime - initialTime));
				Log.i("ImageReader", "onImageAvailable. width " + currentCameraImage.getWidth() + ", height : " + currentCameraImage.getHeight());
				Log.d("Image", "frame# : " + frames + ", approximately " + fps + " fps");
				frames = 0;
				initialTime = SystemClock.elapsedRealtimeNanos();
			}
			currentCameraImage.close();
		}
	};

	private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {

		}

		@Override
		public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

		}

		@Override
		public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
			return false;
		}

		@Override
		public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

		}
	};

	private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
		@Override
		public void onOpened(CameraDevice camera) {
			cameraDevice = camera;
			createCameraPreview();
		}

		@Override
		public void onDisconnected(CameraDevice camera) {
			cameraOpenCloseLock.release();

			cameraDevice.close();
		}

		@Override
		public void onError(CameraDevice camera, int i) {
			cameraOpenCloseLock.release();
			cameraDevice.close();
			cameraDevice = null;
		}
	};

	private CameraCaptureSession.CaptureCallback captureCallbackListener = new CameraCaptureSession.CaptureCallback() {

		@Override
		public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
			super.onCaptureCompleted(session, request, result);
			Toast.makeText(Camera2Activity.this, "Saved:" + file, Toast.LENGTH_SHORT).show();
			createCameraPreview();
		}
	};

	protected void startBackgroundThread() {
		backgroundThreadHandler = new HandlerThread("Camera Background");
		backgroundThreadHandler.start();
		backgroundHandler = new Handler(backgroundThreadHandler.getLooper());
	}

	protected void stopBackgroundThread() {
		if (backgroundThreadHandler != null) {
			backgroundThreadHandler.quitSafely();
			try {
				backgroundThreadHandler.join();
				backgroundThreadHandler = null;
				backgroundHandler = null;
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}

	private ImageReader imageReaderForProcessing;


	protected void takePicture() {
		if (cameraDevice == null) {
			Log.e(TAG, "Camera device is null");
			return;
		}

		CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
		try {
			CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
			Size[] jpegSizes = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP).getOutputSizes(ImageFormat.JPEG);
			int width = 640;
			int height = 480;
			if (jpegSizes != null && jpegSizes.length > 0) {
				width = jpegSizes[0].getWidth();
				height = jpegSizes[0].getHeight();
			}

			ImageReader reader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1);
			List<Surface> outputSurfaces = new ArrayList<>(2);
			outputSurfaces.add(reader.getSurface());
			outputSurfaces.add(new Surface(textureView.getSurfaceTexture()));

			final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
			captureBuilder.addTarget(reader.getSurface());
			captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

			// Orientation
			int rotation = getWindowManager().getDefaultDisplay().getRotation();
			captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
			final File file = new File(Environment.getExternalStorageDirectory() + "/pic.jpg");
			ImageReader.OnImageAvailableListener readerListener = new ImageReader.OnImageAvailableListener() {
				@Override
				public void onImageAvailable(ImageReader imageReader) {
					Image image = null;
					try {
						image = imageReader.acquireLatestImage();
						ByteBuffer buffer = image.getPlanes()[0].getBuffer();
						byte[] bytes = new byte[buffer.capacity()];
						buffer.get(bytes);
						save(bytes);
					} catch (FileNotFoundException e) {
						e.printStackTrace();
					} catch (IOException e) {
						e.printStackTrace();
					} finally {
						if (image != null) {
							image.close();
						}
					}
				}

				private void save(byte[] bytes) throws IOException {
					OutputStream outputStream = null;
					try {
						outputStream = new FileOutputStream(file);
						outputStream.write(bytes);
					} finally {
						if (outputStream != null) {
							outputStream.close();
						}
					}
				}
			};

			reader.setOnImageAvailableListener(readerListener, backgroundHandler);
			final CameraCaptureSession.CaptureCallback captureListener = new CameraCaptureSession.CaptureCallback() {
				@Override
				public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
					super.onCaptureCompleted(session, request, result);
					Toast.makeText(Camera2Activity.this, "Saved : " + file, Toast.LENGTH_SHORT).show();
					createCameraPreview();
				}
			};

			cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
				@Override
				public void onConfigured(CameraCaptureSession cameraCaptureSession) {
					try {
						cameraCaptureSession.capture(captureBuilder.build(), captureListener, backgroundHandler);
					} catch (CameraAccessException e) {
						e.printStackTrace();
					}
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {

				}
			}, backgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void createCameraPreview() {
		SurfaceTexture texture = textureView.getSurfaceTexture();
		assert texture != null;
		texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
		Surface surface = new Surface(texture);
		try {
			imageReaderForProcessing = ImageReader.newInstance(imageDimension.getWidth(), imageDimension.getHeight(), ImageFormat.YUV_420_888, 2);

			// Handle all new camera frames received on the background thread.
			imageReaderForProcessing.setOnImageAvailableListener(imageAvailListener, backgroundHandler);

			CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

			CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics("0");
			Range [] fpsRanges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

			for (Range range : fpsRanges) {
				Log.i("Range", range.toString());
			}

			captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRanges[fpsRanges.length - 1]);
			captureRequestBuilder.addTarget(surface);
			captureRequestBuilder.addTarget(imageReaderForProcessing.getSurface());
			cameraDevice.createCaptureSession(Arrays.asList(surface, imageReaderForProcessing.getSurface()), new CameraCaptureSession.StateCallback() {
				@Override
				public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
					if (cameraDevice == null) {
						return;
					}

					Camera2Activity.this.cameraCaptureSession = cameraCaptureSession;
					updatePreview();
				}

				@Override
				public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
					Toast.makeText(Camera2Activity.this, "Configuration change", Toast.LENGTH_SHORT).show();
				}
			}, backgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	/**
	 * A Semaphore to prevent the camera simultaneously opening and closing.
	 */
	private Semaphore cameraOpenCloseLock = new Semaphore(1);

	private void openCamera() {
		if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
				ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			throw new RuntimeException("Camera permissions must be granted to function.");
		}

		CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

		String[] cameras = new String[0];
		try {
			cameras = manager.getCameraIdList();
			for (String cameraId : cameras) {
				CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(cameraId);
				// Reject all cameras but the back-facing camera.
				if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) != CameraMetadata.LENS_FACING_BACK) {
					continue;
				}

				try {
					if (!cameraOpenCloseLock.tryAcquire(3000, TimeUnit.MILLISECONDS)) {
						throw new RuntimeException(("Camera lock cannot be acquired during opening."));
					}

					CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
					StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
					assert map != null;

					imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];

					manager.openCamera(cameraId, stateCallback, backgroundHandler);
				} catch (InterruptedException e) {
					throw new RuntimeException("Camera open/close semaphore cannot be acquired");
				}
			}
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	protected void updatePreview() {
		assert cameraDevice != null;

		captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
		try {
			cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
			cameraOpenCloseLock.release();
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void closeCamera() {
		try {
			cameraOpenCloseLock.acquire();

			if (cameraDevice != null) {
				cameraDevice.close();
				cameraDevice = null;
			}

			if (imageReader != null) {
				imageReader.close();
				imageReader = null;
			}

			if (imageReaderForProcessing != null) {
				imageReaderForProcessing.close();
				imageReaderForProcessing = null;
			}
		} catch (InterruptedException e) {
			e.printStackTrace();
		} finally {
			cameraOpenCloseLock.release();
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
				Toast.makeText(this, "Sorry, camera permission denied", Toast.LENGTH_SHORT).show();
				finish();
			}
		}
	}

	@Override
	protected void onResume() {
		super.onResume();

		if (textureView.isAvailable()) {
			openCamera();
		} else {
			textureView.setSurfaceTextureListener(textureListener);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
	}
}
