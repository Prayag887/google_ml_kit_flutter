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
    // FIX 3: volatile so the background thread sees the flag immediately
    private volatile boolean isDisposed = false;

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        isDisposed = false;
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), channelName);

        // Capture channel locally — field may be nulled by onDetachedFromEngine
        // before the background thread posts back to the main thread.
        final MethodChannel capturedChannel = channel;

        executor.execute(() -> {
            final DigitalInkRecognizer recognizer = new DigitalInkRecognizer();

            mainThreadHandler.post(() -> {
                if (isDisposed) {
                    // Engine detached before we finished init — clean up quietly.
                    recognizer.dispose();
                } else {
                    digitalInkRecognizer = recognizer;
                    capturedChannel.setMethodCallHandler(digitalInkRecognizer);
                }
            });
        });
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        isDisposed = true;

        // Null-guard: channel could theoretically already be null if
        // onAttachedToEngine never completed successfully.
        if (channel != null) {
            channel.setMethodCallHandler(null);
            channel = null;
        }

        executor.shutdownNow();

        if (digitalInkRecognizer != null) {
            digitalInkRecognizer.dispose(); // now exists — FIX 2
            digitalInkRecognizer = null;
        }
    }
}