package com.google_mlkit_digital_ink_recognition;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodChannel;

public class GoogleMlKitDigitalInkRecognitionPlugin implements FlutterPlugin {
    private MethodChannel channel;
    private DigitalInkRecognizer digitalInkRecognizer;
    private static final String channelName = "google_mlkit_digital_ink_recognizer";

    @Override
    public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
        channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), channelName);
        digitalInkRecognizer = new DigitalInkRecognizer();
        channel.setMethodCallHandler(digitalInkRecognizer);
    }

    @Override
    public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
        channel.setMethodCallHandler(null);
        // Clean up background threads and resources
        if (digitalInkRecognizer != null) {
            digitalInkRecognizer.dispose();
            digitalInkRecognizer = null;
        }
        channel = null;
    }
}