package com.example.simplecamera

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var flashView: View
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null

    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private var flashMode = ImageCapture.FLASH_MODE_OFF

    private lateinit var captureButton: Button
    private lateinit var flashButton: Button
    private lateinit var switchCameraButton: Button

    // 防抖相关
    private var lastMessageTime = 0L
    private var lastMessageText = ""
    private val messageDelay = 300L

    // 闪烁动画 Handler
    private val flashHandler = Handler(Looper.getMainLooper())
    private var flashRunnable: Runnable? = null

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
        private const val FLASH_DURATION = 80L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        flashView = findViewById(R.id.flashView)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)

        // 设置 PreviewView 高度为屏幕宽度的 3/4（4:3 比例），并顶部对齐
        adjustPreviewViewSize()

        cameraExecutor = Executors.newSingleThreadExecutor()

        // 点击屏幕对焦
        previewView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                focusOnTouch(event.x, event.y)
            }
            true
        }

        captureButton.setOnClickListener {
            takePhoto()
        }

        flashButton.setOnClickListener {
            cycleFlashMode()
        }

        switchCameraButton.setOnClickListener {
            switchCamera()
        }

        if (checkCameraPermission()) {
            startCamera()
        } else {
            requestCameraPermission()
        }
    }

    private fun adjustPreviewViewSize() {
        // 获取屏幕宽度
        val screenWidth = resources.displayMetrics.widthPixels
        // 4:3 比例，高度 = 宽度 * 3 / 4
        val previewHeight = screenWidth * 3 / 4

        val layoutParams = previewView.layoutParams
        layoutParams.height = previewHeight
        previewView.layoutParams = layoutParams
    }

    private fun checkCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            CAMERA_PERMISSION_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera()
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return

        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(100)
            .build()

        val cameraSelector = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        camera = provider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageCapture
        )
    }

    private fun focusOnTouch(x: Float, y: Float) {
        val camera = this.camera ?: return

        try {
            val meteringPointFactory = previewView.meteringPointFactory
            val point = meteringPointFactory.createPoint(x, y)

            val action = FocusMeteringAction.Builder(point)
                .addPoint(point, FocusMeteringAction.FLAG_AF)
                .addPoint(point, FocusMeteringAction.FLAG_AE)
                .build()

            camera.cameraControl.startFocusAndMetering(action)
            showMessage("对焦中...")
        } catch (e: Exception) {
            showMessage("不支持触屏对焦")
        }
    }

    private fun cycleFlashMode() {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_OFF -> {
                flashButton.text = "🔆"
                showMessage("闪光灯: 开启")
                ImageCapture.FLASH_MODE_ON
            }
            ImageCapture.FLASH_MODE_ON -> {
                flashButton.text = "✨"
                showMessage("闪光灯: 自动")
                ImageCapture.FLASH_MODE_AUTO
            }
            else -> {
                flashButton.text = "⚡"
                showMessage("闪光灯: 关闭")
                ImageCapture.FLASH_MODE_OFF
            }
        }
        imageCapture?.flashMode = flashMode
    }

    private fun switchCamera() {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            showMessage("切换到前置摄像头")
            CameraSelector.LENS_FACING_FRONT
        } else {
            showMessage("切换到后置摄像头")
            CameraSelector.LENS_FACING_BACK
        }
        bindCameraUseCases()
    }

    private fun flashPreview() {
        flashView.visibility = View.VISIBLE
        flashRunnable?.let { flashHandler.removeCallbacks(it) }
        flashRunnable = Runnable {
            flashView.visibility = View.GONE
        }
        flashHandler.postDelayed(flashRunnable!!, FLASH_DURATION)
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        flashPreview()

        val name = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/SimpleCamera")
            }
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    // 不显示弹窗
                }

                override fun onError(exception: ImageCaptureException) {
                    showMessage("拍照失败: ${exception.message}")
                }
            }
        )
    }

    private fun showMessage(message: String) {
        val currentTime = System.currentTimeMillis()

        if (message == lastMessageText && currentTime - lastMessageTime < messageDelay) {
            return
        }

        lastMessageTime = currentTime
        lastMessageText = message

        Handler(Looper.getMainLooper()).post {
            val snackbar = Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_SHORT
            )
            snackbar.setAnchorView(R.id.captureButton)
            snackbar.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        flashRunnable?.let { flashHandler.removeCallbacks(it) }
        cameraExecutor.shutdown()
    }
}
