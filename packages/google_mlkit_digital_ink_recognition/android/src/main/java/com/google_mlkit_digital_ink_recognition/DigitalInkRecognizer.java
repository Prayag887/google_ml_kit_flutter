package com.google_mlkit_digital_ink_recognition;

import androidx.annotation.NonNull;

import com.google.mlkit.common.MlKitException;
import com.google.mlkit.vision.digitalink.DigitalInkRecognition;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel;
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier;
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions;
import com.google.mlkit.vision.digitalink.Ink;
import com.google.mlkit.vision.digitalink.RecognitionCandidate;
import com.google.mlkit.vision.digitalink.RecognitionContext;
import com.google.mlkit.vision.digitalink.RecognitionResult;
import com.google.mlkit.vision.digitalink.WritingArea;
import com.google_mlkit_commons.GenericModelManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.os.Handler;
import android.os.Looper;

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class DigitalInkRecognizer implements MethodChannel.MethodCallHandler {
    private static final String START  = "vision#startDigitalInkRecognizer";
    private static final String CLOSE  = "vision#closeDigitalInkRecognizer";
    private static final String MANAGE = "vision#manageInkModels";

    private final Map<String, com.google.mlkit.vision.digitalink.DigitalInkRecognizer> instances = new HashMap<>();
    private final GenericModelManager genericModelManager = new GenericModelManager();

    // Background executor for blocking isModelDownloaded() call
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler  = new Handler(Looper.getMainLooper());

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        switch (call.method) {
            case START:  handleDetection(call, result); break;
            case CLOSE:  closeDetector(call);           break;
            case MANAGE: manageModel(call, result);     break;
            default:     result.notImplemented();       break;
        }
    }

   private void handleDetection(MethodCall call, final MethodChannel.Result result) {
    String tag = call.argument("model");
    DigitalInkRecognitionModel model = getModel(tag, result);
    if (model == null) return;

    genericModelManager.isModelDownloaded(model, new GenericModelManager.CheckModelIsDownloadedCallback() {
        @Override
        public void onCheckResult(Boolean isDownloaded) {
            if (!isDownloaded) {
                result.error("Model Error", "Model has not been downloaded yet", null);
                return;
            }

            String id = call.argument("id");
            com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer;
            synchronized (instances) {
                recognizer = instances.get(id);
                if (recognizer == null) {
                    recognizer = DigitalInkRecognition.getClient(
                            DigitalInkRecognizerOptions.builder(model).build());
                    instances.put(id, recognizer);
                }
            }

            Map<String, Object> inkMap = call.argument("ink");
            List<Map<String, Object>> strokeList =
                    (List<Map<String, Object>>) inkMap.get("strokes");
            Ink.Builder inkBuilder = Ink.builder();
            for (Map<String, Object> strokeMap : strokeList) {
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                List<Map<String, Object>> pointsList =
                        (List<Map<String, Object>>) strokeMap.get("points");
                for (Map<String, Object> point : pointsList) {
                    float  x  = (float) (double) point.get("x");
                    float  y  = (float) (double) point.get("y");
                    Object t0 = point.get("t");
                    long   t  = (t0 instanceof Integer) ? (int) t0 : (long) t0;
                    strokeBuilder.addPoint(Ink.Point.create(x, y, t));
                }
                inkBuilder.addStroke(strokeBuilder.build());
            }
            Ink ink = inkBuilder.build();

            RecognitionContext context = null;
            Map<String, Object> contextMap = call.argument("context");
            if (contextMap != null) {
                RecognitionContext.Builder builder = RecognitionContext.builder();
                String preContext = (String) contextMap.get("preContext");
                builder.setPreContext(preContext != null ? preContext : "");
                Map<String, Object> writingAreaMap =
                        (Map<String, Object>) contextMap.get("writingArea");
                if (writingAreaMap != null) {
                    float w = (float) (double) writingAreaMap.get("width");
                    float h = (float) (double) writingAreaMap.get("height");
                    builder.setWritingArea(new WritingArea(w, h));
                }
                context = builder.build();
            }

            final RecognitionContext finalContext = context;
            final com.google.mlkit.vision.digitalink.DigitalInkRecognizer finalRecognizer = recognizer;
            if (finalContext != null) {
                finalRecognizer.recognize(ink, finalContext)
                        .addOnSuccessListener(r -> process(r, result))
                        .addOnFailureListener(e -> result.error("Recognition Error", e.toString(), null));
            } else {
                finalRecognizer.recognize(ink)
                        .addOnSuccessListener(r -> process(r, result))
                        .addOnFailureListener(e -> result.error("Recognition Error", e.toString(), null));
            }
        }

        @Override
        public void onError(Exception e) {
            result.error("Model Error", "Failed to check model: " + e.toString(), null);
        }
    });
}

    private void process(RecognitionResult recognitionResult, MethodChannel.Result result) {
        List<Map<String, Object>> candidatesList =
                new ArrayList<>(recognitionResult.getCandidates().size());
        for (RecognitionCandidate candidate : recognitionResult.getCandidates()) {
            Map<String, Object> data = new HashMap<>();
            data.put("text", candidate.getText());
            data.put("score", candidate.getScore() != null
                    ? candidate.getScore().doubleValue() : 0.0);
            candidatesList.add(data);
        }
        result.success(candidatesList);
    }

    private void closeDetector(MethodCall call) {
        String id = call.argument("id");
        synchronized (instances) {
            com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer = instances.get(id);
            if (recognizer == null) return;
            recognizer.close();
            instances.remove(id);
        }
    }

    private void manageModel(MethodCall call, MethodChannel.Result result) {
        String tag = call.argument("model");
        DigitalInkRecognitionModel model = getModel(tag, result);
        if (model == null) return;
        genericModelManager.manageModel(model, call, result);
    }

    // Called by GoogleMlKitDigitalInkRecognitionPlugin.onDetachedFromEngine()
    public void dispose() {
        executor.shutdownNow();
        synchronized (instances) {
            for (com.google.mlkit.vision.digitalink.DigitalInkRecognizer r : instances.values()) {
                try { r.close(); } catch (Exception ignored) {}
            }
            instances.clear();
        }
    }

    private DigitalInkRecognitionModel getModel(String tag, MethodChannel.Result result) {
        DigitalInkRecognitionModelIdentifier modelIdentifier;
        try {
            modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
        } catch (MlKitException e) {
            result.error("Failed to create model identifier", e.toString(), null);
            return null;
        }
        if (modelIdentifier == null) {
            result.error("Model Identifier error", "No model was found", null);
            return null;
        }
        return DigitalInkRecognitionModel.builder(modelIdentifier).build();
    }
}