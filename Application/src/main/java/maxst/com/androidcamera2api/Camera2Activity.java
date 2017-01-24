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
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
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

	private String cameraId;
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

		textureView = (TextureView)findViewById(R.id.texture);
		assert textureView != null;

		textureView.setSurfaceTextureListener(textureListener);
		takePictureButton = (Button)findViewById(R.id.btn_takepicture);
		assert takePictureButton != null;

		takePictureButton.setOnClickListener(new View.OnClickListener() {

			@Override
			public void onClick(View view) {
				takePicture();
			}
		});
	}

	private TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
		@Override
		public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
			openCamera();
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
			cameraDevice.close();
		}

		@Override
		public void onError(CameraDevice camera, int i) {
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

	protected  void startBackgroundThread() {
		backgroundThreadHandler = new HandlerThread("Camera Background");
		backgroundThreadHandler.start();
		backgroundHandler = new Handler(backgroundThreadHandler.getLooper());
	}

	protected  void stopBackgroundThread() {
		backgroundThreadHandler.quitSafely();
		try {
			backgroundThreadHandler.join();
			backgroundThreadHandler = null;
			backgroundHandler = null;
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
	}

	protected void takePicture() {
		if (cameraDevice == null) {
			Log.e(TAG, "Camera device is null");
			return;
		}

		CameraManager cameraManager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
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

				private void save(byte [] bytes) throws IOException {
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
			captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
			captureRequestBuilder.addTarget(surface);
			cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
				@Override
				public void onConfigured(CameraCaptureSession cameraCaptureSession) {
					if (cameraDevice == null) {
						return;
					}

					Camera2Activity.this.cameraCaptureSession = cameraCaptureSession;
					updatePreview();
				}

				@Override
				public void onConfigureFailed(CameraCaptureSession cameraCaptureSession) {
					Toast.makeText(Camera2Activity.this, "Configuration change", Toast.LENGTH_SHORT).show();
				}
			}, null);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void openCamera() {
		CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
		try {
			cameraId = manager.getCameraIdList()[0];
			CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
			StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
			assert map != null;

			imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
			if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
					ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
				ActivityCompat.requestPermissions(Camera2Activity.this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE},
						REQUEST_CAMERA_PERMISSION);
				return;
			}

			manager.openCamera(cameraId, stateCallback, null);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	protected void updatePreview() {
		assert cameraDevice != null;

		captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
		try {
			cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
		} catch (CameraAccessException e) {
			e.printStackTrace();
		}
	}

	private void closeCamera() {
		if (cameraDevice != null) {
			cameraDevice.close();
			cameraDevice = null;
		}

		if (imageReader != null) {
			imageReader.close();
			imageReader = null;
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
		startBackgroundThread();
		if (textureView.isAvailable()) {
			openCamera();
		} else {
			textureView.setSurfaceTextureListener(textureListener);
		}
	}

	@Override
	protected void onPause() {
		super.onPause();
		closeCamera();
		stopBackgroundThread();
	}
}
