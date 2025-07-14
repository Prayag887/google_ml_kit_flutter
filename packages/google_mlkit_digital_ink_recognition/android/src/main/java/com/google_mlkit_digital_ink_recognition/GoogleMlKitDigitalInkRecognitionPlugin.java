package com.google_mlkit_digital_ink_recognition;

import androidx.annotation.NonNull;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class GoogleMlKitDigitalInkRecognitionPlugin implements FlutterPlugin {
    private MethodChannel channel;
    private DigitalInkRecognizer digitalInkRecognizer;
    private static final String channelName = "google_mlkit_digital_ink_recognizer";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());
    private boolean isDisposed = false;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), channelName);

        // Initialize in background thread
        executor.execute(() -> {
            DigitalInkRecognizer recognizer = new DigitalInkRecognizer();

            mainThreadHandler.post(() -> {
                if (isDisposed) {
                    recognizer.dispose();
                } else {
                    digitalInkRecognizer = recognizer;
                    channel.setMethodCallHandler(digitalInkRecognizer);
                }
            });
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        isDisposed = true;
        channel.setMethodCallHandler(null);
        channel = null;

        // Shutdown executor and clean up
        executor.shutdownNow();
        if (digitalInkRecognizer != null) {
            digitalInkRecognizer.dispose();
            digitalInkRecognizer = null;
        }
    }
}