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

import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import android.os.Handler;
import android.os.Looper;

public class DigitalInkRecognizer implements MethodChannel.MethodCallHandler {
    private static final String START = "vision#startDigitalInkRecognizer";
    private static final String CLOSE = "vision#closeDigitalInkRecognizer";
    private static final String MANAGE = "vision#manageInkModels";

    private final Map<String, com.google.mlkit.vision.digitalink.DigitalInkRecognizer> instances = new HashMap<>();
    private final GenericModelManager genericModelManager = new GenericModelManager();

    // Use a single background executor for all operations
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(4);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;
        switch (method) {
            case START:
                // Offload recognition to background immediately
                backgroundExecutor.execute(() -> handleDetection(call, result));
                break;
            case CLOSE:
                backgroundExecutor.execute(() -> {
                    closeDetector(call);
                    mainHandler.post(() -> result.success(null));
                });
                break;
            case MANAGE:
                // Offload model management to background
                backgroundExecutor.execute(() -> manageModel(call, result));
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleDetection(MethodCall call, MethodChannel.Result result) {
        String tag = call.argument("model");
        DigitalInkRecognitionModel model = getModel(tag, result);
        if (model == null) return;

        // Check model status in background
        if (!genericModelManager.isModelDownloaded(model)) {
            mainHandler.post(() ->
                    result.error("Model Error", "Model has not been downloaded yet", null)
            );
            return;
        }

        String id = call.argument("id");
        com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer;

        synchronized (instances) {
            recognizer = instances.get(id);
            if (recognizer == null) {
                recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                );
                instances.put(id, recognizer);
            }
        }

        Ink ink = buildInkFromMethodCall(call);
        if (ink == null) {
            mainHandler.post(() ->
                    result.error("Ink Error", "Failed to build ink object", null)
            );
            return;
        }

        RecognitionContext context = buildRecognitionContext(call);

        // Execute recognition in background
        if (context != null) {
            recognizer.recognize(ink, context)
                    .addOnSuccessListener(backgroundExecutor, recognitionResult -> {
                        List<Map<String, Object>> processedResults = processRecognitionResult(recognitionResult);
                        mainHandler.post(() -> result.success(processedResults));
                    })
                    .addOnFailureListener(backgroundExecutor, e -> {
                        mainHandler.post(() ->
                                result.error("Recognition Error", e.toString(), null)
                        );
                    });
        } else {
            recognizer.recognize(ink)
                    .addOnSuccessListener(backgroundExecutor, recognitionResult -> {
                        List<Map<String, Object>> processedResults = processRecognitionResult(recognitionResult);
                        mainHandler.post(() -> result.success(processedResults));
                    })
                    .addOnFailureListener(backgroundExecutor, e -> {
                        mainHandler.post(() ->
                                result.error("Recognition Error", e.toString(), null)
                        );
                    });
        }
    }

    private Ink buildInkFromMethodCall(MethodCall call) {
        try {
            Map<String, Object> inkMap = call.argument("ink");
            List<Map<String, Object>> strokeList = (List<Map<String, Object>>) inkMap.get("strokes");
            Ink.Builder inkBuilder = Ink.builder();

            for (final Map<String, Object> strokeMap : strokeList) {
                Ink.Stroke.Builder strokeBuilder = Ink.Stroke.builder();
                List<Map<String, Object>> pointsList = (List<Map<String, Object>>) strokeMap.get("points");

                for (final Map<String, Object> point : pointsList) {
                    float x = (float) (double) point.get("x");
                    float y = (float) (double) point.get("y");
                    Object t0 = point.get("t");
                    long t;
                    if (t0 instanceof Integer) {
                        t = (int) t0;
                    } else {
                        t = (long) t0;
                    }
                    Ink.Point strokePoint = Ink.Point.create(x, y, t);
                    strokeBuilder.addPoint(strokePoint);
                }
                inkBuilder.addStroke(strokeBuilder.build());
            }
            return inkBuilder.build();
        } catch (Exception e) {
            return null;
        }
    }

    private RecognitionContext buildRecognitionContext(MethodCall call) {
        try {
            Map<String, Object> contextMap = call.argument("context");
            if (contextMap == null) {
                return null;
            }

            RecognitionContext.Builder builder = RecognitionContext.builder();
            String preContext = (String) contextMap.get("preContext");
            if (preContext != null) {
                builder.setPreContext(preContext);
            } else {
                builder.setPreContext("");
            }

            Map<String, Object> writingAreaMap = (Map<String, Object>) contextMap.get("writingArea");
            if (writingAreaMap != null) {
                float width = (float) (double) writingAreaMap.get("width");
                float height = (float) (double) writingAreaMap.get("height");
                builder.setWritingArea(new WritingArea(width, height));
            }

            return builder.build();
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> processRecognitionResult(RecognitionResult recognitionResult) {
        List<Map<String, Object>> candidatesList = new ArrayList<>(recognitionResult.getCandidates().size());
        for (RecognitionCandidate candidate : recognitionResult.getCandidates()) {
            Map<String, Object> candidateData = new HashMap<>();
            double score = 0;
            if (candidate.getScore() != null) {
                score = candidate.getScore().doubleValue();
            }
            candidateData.put("text", candidate.getText());
            candidateData.put("score", score);
            candidatesList.add(candidateData);
        }
        return candidatesList;
    }

    private void closeDetector(MethodCall call) {
        String id = call.argument("id");
        com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer;

        synchronized (instances) {
            recognizer = instances.get(id);
            if (recognizer == null) return;
            instances.remove(id);
        }

        recognizer.close();
    }

    private void manageModel(MethodCall call, MethodChannel.Result result) {
        String tag = call.argument("model");
        DigitalInkRecognitionModel model = getModel(tag, result);
        if (model != null) {
            // Execute model management in background
            genericModelManager.manageModel(model, call, new MethodChannel.Result() {
                @Override
                public void success(Object o) {
                    mainHandler.post(() -> result.success(o));
                }

                @Override
                public void error(String errorCode, String errorMessage, Object errorDetails) {
                    mainHandler.post(() -> result.error(errorCode, errorMessage, errorDetails));
                }

                @Override
                public void notImplemented() {
                    mainHandler.post(result::notImplemented);
                }
            });
        }
    }

    private DigitalInkRecognitionModel getModel(String tag, MethodChannel.Result result) {
        try {
            DigitalInkRecognitionModelIdentifier modelIdentifier =
                    DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);

            if (modelIdentifier == null) {
                mainHandler.post(() ->
                        result.error("Model Error", "Invalid model identifier: " + tag, null)
                );
                return null;
            }
            return DigitalInkRecognitionModel.builder(modelIdentifier).build();
        } catch (MlKitException e) {
            mainHandler.post(() ->
                    result.error("Model Error", "Failed to create model: " + e.getMessage(), null)
            );
            return null;
        }
    }

    public void dispose() {
        backgroundExecutor.shutdown();
        synchronized (instances) {
            for (com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer : instances.values()) {
                recognizer.close();
            }
            instances.clear();
        }
    }
}