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
    private val messageDelay = 300L  // 300毫秒内相同消息不重复显示

    companion object {
        private const val CAMERA_PERMISSION_CODE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        captureButton = findViewById(R.id.captureButton)
        flashButton = findViewById(R.id.flashButton)
        switchCameraButton = findViewById(R.id.switchCameraButton)

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

        // 解绑所有用例
        provider.unbindAll()

        // 创建预览用例
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }

        // 创建拍照用例 - 最高质量设置
        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setFlashMode(flashMode)
            .setJpegQuality(100)
            .build()

        // 选择摄像头
        val cameraSelector = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.DEFAULT_BACK_CAMERA
        } else {
            CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // 绑定用例并保存 camera 对象用于对焦
        camera = provider.bindToLifecycle(
            this,
            cameraSelector,
            preview,
            imageCapture
        )
    }

    // 触屏对焦
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

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

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
                    showMessage("照片已保存")
                }

                override fun onError(exception: ImageCaptureException) {
                    showMessage("拍照失败: ${exception.message}")
                }
            }
        )
    }

    // Snackbar + 防抖显示消息
    private fun showMessage(message: String) {
        val currentTime = System.currentTimeMillis()
        
        // 防抖：相同消息在短时间内不重复显示
        if (message == lastMessageText && currentTime - lastMessageTime < messageDelay) {
            return
        }
        
        lastMessageTime = currentTime
        lastMessageText = message
        
        // 在主线程显示 Snackbar
        Handler(Looper.getMainLooper()).post {
            val snackbar = Snackbar.make(
                findViewById(android.R.id.content),
                message,
                Snackbar.LENGTH_SHORT
            )
            snackbar.setAnchorView(R.id.captureButton)  // 将 Snackbar 显示在按钮上方
            snackbar.show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
