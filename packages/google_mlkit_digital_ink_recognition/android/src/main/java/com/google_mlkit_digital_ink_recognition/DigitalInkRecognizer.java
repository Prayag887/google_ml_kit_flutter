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

    // Background thread executor for heavy ML Kit operations
    private final ExecutorService backgroundExecutor = Executors.newFixedThreadPool(2);
    // Handler to post results back to main thread
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        String method = call.method;
        switch (method) {
            case START:
                handleDetection(call, result);
                break;
            case CLOSE:
                closeDetector(call);
                break;
            case MANAGE:
                manageModel(call, result);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private void handleDetection(MethodCall call, final MethodChannel.Result result) {
        // Move the entire heavy operation to background thread
        backgroundExecutor.execute(() -> {
            try {
                performRecognitionInBackground(call, result);
            } catch (Exception e) {
                // Post error back to main thread
                mainHandler.post(() ->
                        result.error("Recognition Error", e.getMessage(), null)
                );
            }
        });
    }

    private void performRecognitionInBackground(MethodCall call, final MethodChannel.Result result) {
        String tag = call.argument("model");
        DigitalInkRecognitionModel model = getModel(tag, result);
        if (model == null) return;

        if (!genericModelManager.isModelDownloaded(model)) {
            mainHandler.post(() ->
                    result.error("Model Error", "Model has not been downloaded yet", null)
            );
            return;
        }

        String id = call.argument("id");
        com.google.mlkit.vision.digitalink.DigitalInkRecognizer recognizer;

        // Synchronize access to the instances map
        synchronized (instances) {
            recognizer = instances.get(id);
            if (recognizer == null) {
                recognizer = DigitalInkRecognition.getClient(
                        DigitalInkRecognizerOptions.builder(model).build()
                );
                instances.put(id, recognizer);
            }
        }

        // Build Ink object in background thread
        Ink ink = buildInkFromMethodCall(call);
        if (ink == null) {
            mainHandler.post(() ->
                    result.error("Ink Error", "Failed to build ink object", null)
            );
            return;
        }

        // Build recognition context in background thread
        RecognitionContext context = buildRecognitionContext(call);

        // Perform recognition
        if (context != null) {
            recognizer.recognize(ink, context)
                    .addOnSuccessListener(recognitionResult -> {
                        // Process results in background thread
                        backgroundExecutor.execute(() -> {
                            List<Map<String, Object>> processedResults = processRecognitionResult(recognitionResult);
                            // Post results back to main thread
                            mainHandler.post(() -> result.success(processedResults));
                        });
                    })
                    .addOnFailureListener(e -> {
                        mainHandler.post(() ->
                                result.error("Recognition Error", e.toString(), null)
                        );
                    });
        } else {
            recognizer.recognize(ink)
                    .addOnSuccessListener(recognitionResult -> {
                        // Process results in background thread
                        backgroundExecutor.execute(() -> {
                            List<Map<String, Object>> processedResults = processRecognitionResult(recognitionResult);
                            // Post results back to main thread
                            mainHandler.post(() -> result.success(processedResults));
                        });
                    })
                    .addOnFailureListener(e -> {
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

        // Close recognizer on background thread to avoid blocking UI
        backgroundExecutor.execute(() -> {
            recognizer.close();
        });
    }

    private void manageModel(MethodCall call, final MethodChannel.Result result) {
        String tag = call.argument("model");
        DigitalInkRecognitionModel model = getModel(tag, result);
        if (model != null) {
            genericModelManager.manageModel(model, call, result);
        }
    }

    private DigitalInkRecognitionModel getModel(String tag, final MethodChannel.Result result) {
        DigitalInkRecognitionModelIdentifier modelIdentifier;
        try {
            modelIdentifier = DigitalInkRecognitionModelIdentifier.fromLanguageTag(tag);
        } catch (MlKitException e) {
            mainHandler.post(() ->
                    result.error("Failed to create model identifier", e.toString(), null)
            );
            return null;
        }
        if (modelIdentifier == null) {
            mainHandler.post(() ->
                    result.error("Model Identifier error", "No model was found", null)
            );
            return null;
        }
        return DigitalInkRecognitionModel.builder(modelIdentifier).build();
    }

    // Clean up resources when plugin is destroyed
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