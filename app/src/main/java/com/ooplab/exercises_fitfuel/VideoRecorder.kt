package com.ooplab.exercises_fitfuel

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.camera.video.FileOutputOptions
import java.io.File
import androidx.camera.core.MirrorMode


class VideoRecorder (
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
    ) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var currentRecording: Recording? = null
    var onVideoSaved: ((Uri) -> Unit)? = null


    //카메라 초기화
    fun setupCamera(imageAnalyzer: ImageAnalysis? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = androidx.camera.core.Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HD))
                .build()


            videoCapture = VideoCapture.Builder(recorder)
                .setMirrorMode(MirrorMode.MIRROR_MODE_ON)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                val useCases = mutableListOf<UseCase>(preview, videoCapture!!)

                // 분석기도 항상 함께 바인딩
                if (imageAnalyzer != null) {
                    useCases.add(imageAnalyzer)
                    Log.d("VideoRecorder", "ImageAnalysis 바인딩 포함")
                }

                cameraProvider.bindToLifecycle(
                    lifecycleOwner, cameraSelector, *useCases.toTypedArray()
                )
                Log.d("VideoRecorder", "카메라 바인딩 완료. useCases=${useCases.size}")
            } catch (e: Exception) {
                Log.e("VideoRecorder", "카메라 바인딩 실패", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    //녹화 시작
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(onSaved: (Uri) -> Unit) {
        onVideoSaved = onSaved

        val videosDir = File(context.getExternalFilesDir(null), "user_videos_tmp").apply { mkdirs() }
        val fileName = "challenge-${System.currentTimeMillis()}.mp4"
        val outFile = File(videosDir, fileName)

        val outputOptions = FileOutputOptions
            .Builder(outFile)
            .build()

        currentRecording = videoCapture?.output
            ?.prepareRecording(context, outputOptions)
            ?.withAudioEnabled()
            ?.start(ContextCompat.getMainExecutor(context)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        Log.d("VideoRecorder", "녹화 시작됨")
                    }
                    is VideoRecordEvent.Status -> {
                        Log.d("VideoRecorder",
                            "녹화 진행 중: ${event.recordingStats.numBytesRecorded} bytes")
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (event.hasError()) {
                            Log.e("VideoRecorder", "녹화 실패: ${event.error}", event.cause)
                        } else {
                            val savedUri = Uri.fromFile(outFile)
                            Log.d("VideoRecorder", "녹화 완료: $savedUri")
                            onVideoSaved?.invoke(savedUri)
                        }
                    }
                }
            }
    }

    //녹화 중지
    fun stopRecording() {
        currentRecording?.stop()
        currentRecording = null
    }
}