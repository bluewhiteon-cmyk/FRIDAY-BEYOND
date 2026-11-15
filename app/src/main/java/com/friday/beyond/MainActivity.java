
package com.friday.beyond;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.speech.SpeechRecognizer;
import android.speech.RecognizerIntent;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;
import android.text.InputType;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int PICK_ENV_FILE = 701;
    private static final int PICK_MEMORY_FILE = 702;
    private static final int REQUEST_MICROPHONE = 704;

    LinearLayout messages;
    EditText input;
    TextView status;
    Button wakeButton;
    ScrollView chatScroll;
    AvatarView avatar;

    PyObject friday;
    SpeechRecognizer recognizer;
    TextToSpeech tts;

    Handler handler = new Handler(Looper.getMainLooper());

    boolean wakeMode = false;
    boolean commandMode = false;
    boolean activityResumed = false;
    boolean ttsReady = false;

    private int recognitionGeneration = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        friday = Python.getInstance().getModule("friday_engine");
        friday.callAttr("configure_android",
                getFilesDir().getAbsolutePath());

        tts = new TextToSpeech(this, result -> {
            if (result == TextToSpeech.SUCCESS) {
                int language = tts.setLanguage(
                        Locale.forLanguageTag("en-IN"));
                ttsReady = language != TextToSpeech.LANG_MISSING_DATA
                        && language != TextToSpeech.LANG_NOT_SUPPORTED;
            }
        });

        tts.setOnUtteranceProgressListener(
                new UtteranceProgressListener() {
                    @Override
                    public void onStart(String id) {
                        runOnUiThread(() -> setMood("SPEAKING"));
                    }

                    @Override
                    public void onDone(String id) {
                        runOnUiThread(() -> afterSpeech());
                    }

                    @Override
                    public void onError(String id) {
                        runOnUiThread(() -> afterSpeech());
                    }
                });

        buildInterface();
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(14, 10, 14, 10);
        root.setBackgroundColor(Color.rgb(5, 30, 48));

        TextView title = new TextView(this);
        title.setText("FRIDAY BEYOND");
        title.setTextColor(Color.rgb(100, 225, 255));
        title.setTextSize(25);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("SYSTEM READY");
        status.setTextColor(Color.rgb(190, 245, 255));
        status.setGravity(Gravity.CENTER);
        status.setPadding(8, 8, 8, 8);
        root.addView(status);

        // Animated FRIDAY avatar
        avatar = new AvatarView(this);
        root.addView(avatar,
                new LinearLayout.LayoutParams(-1, 220));

        Button importConfig = new Button(this);
        importConfig.setText("IMPORT API CONFIG");
        root.addView(importConfig);
        importConfig.setOnClickListener(
                v -> openPicker(PICK_ENV_FILE));

        Button importMemory = new Button(this);
        importMemory.setText("IMPORT FRIDAY MEMORY");
        root.addView(importMemory);
        importMemory.setOnClickListener(
                v -> openPicker(PICK_MEMORY_FILE));

        chatScroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        chatScroll.addView(messages);

        root.addView(chatScroll,
                new LinearLayout.LayoutParams(-1, 0, 1));

        input = new EditText(this);
        input.setHint("Message FRIDAY...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        wakeButton = new Button(this);
        wakeButton.setText("WAKE WORD ON");
        wakeButton.setOnClickListener(v -> toggleWakeMode());

        Button send = new Button(this);
        send.setText("SEND");
        send.setOnClickListener(v ->
                sendMessage(false, input.getText().toString()));

        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(input,
                new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(wakeButton);
        bottom.addView(send);
        root.addView(bottom);

        input.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage(false, input.getText().toString());
            return true;
        });

        setContentView(root);

        addMessage("FRIDAY",
                "Welcome back, Jack. Systems online.");
        addMessage("FRIDAY",
                "Import your API config and existing FRIDAY memory.");
        addMessage("FRIDAY",
                "Tap WAKE WORD ON and say FRIDAY to activate me.");

        setMood("IDLE");
    }

    private void setMood(String mood) {
        if (avatar != null) {
            avatar.setMood(mood);
        }
    }

    private void toggleWakeMode() {
        if (wakeMode) {
            wakeMode = false;
            commandMode = false;
            stopRecognizer();
            wakeButton.setText("WAKE WORD ON");
            status.setText("MICROPHONE OFF");
            setMood("IDLE");
            addMessage("FRIDAY", "Wake-word listening disabled.");
            return;
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_MICROPHONE);
            return;
        }

        startWakeMode();
    }

    private void startWakeMode() {
        wakeMode = true;
        commandMode = false;
        wakeButton.setText("WAKE WORD OFF");
        status.setText("STANDBY — SAY FRIDAY");
        setMood("IDLE");
        addMessage("FRIDAY",
                "I'm standing by. Say FRIDAY to wake me.");
        startWakeListening();
    }

    private void startWakeListening() {
        if (!wakeMode || commandMode || !activityResumed) return;
        startRecognition(true);
    }

    private void startCommandListening() {
        if (!wakeMode || !activityResumed) return;
        commandMode = true;
        status.setText("LISTENING — SPEAK COMMAND");
        setMood("LISTENING");
        startRecognition(false);
    }

    private void startRecognition(boolean lookingForWakeWord) {
        if (!activityResumed || !wakeMode) return;

        stopRecognizer();
        final int session = ++recognitionGeneration;

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("SPEECH RECOGNITION UNAVAILABLE");
            wakeMode = false;
            commandMode = false;
            wakeButton.setText("WAKE WORD ON");
            setMood("IDLE");
            addMessage("FRIDAY",
                    "Speech recognition is unavailable.");
            return;
        }

        try {
            SpeechRecognizer newRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this);

            recognizer = newRecognizer;

            newRecognizer.setRecognitionListener(
                    new RecognitionListener() {

                @Override
                public void onReadyForSpeech(Bundle params) {}

                @Override
                public void onBeginningOfSpeech() {}

                @Override
                public void onRmsChanged(float rmsdB) {}

                @Override
                public void onBufferReceived(byte[] buffer) {}

                @Override
                public void onEndOfSpeech() {}

                @Override
                public void onPartialResults(Bundle results) {}

                @Override
                public void onEvent(int eventType, Bundle params) {}

                @Override
                public void onResults(Bundle results) {
                    if (session != recognitionGeneration) return;

                    ArrayList<String> matches =
                            results.getStringArrayList(
                                    SpeechRecognizer.RESULTS_RECOGNITION);

                    stopRecognizer();

                    if (matches == null || matches.isEmpty()) {
                        returnToStandby();
                        return;
                    }

                    String spoken = matches.get(0).trim();

                    if (lookingForWakeWord) {
                        if (spoken.toLowerCase(Locale.ROOT)
                                .contains("friday")) {

                            commandMode = true;
                            addMessage("YOU", spoken);
                            addMessage("FRIDAY",
                                    "I'm listening, Jack. Speak now.");

                            handler.postDelayed(
                                    () -> startCommandListening(), 400);
                        } else {
                            handler.postDelayed(
                                    () -> startWakeListening(), 500);
                        }
                    } else {
                        handleVoiceCommand(spoken);
                    }
                }

                @Override
                public void onError(int error) {
                    if (session != recognitionGeneration) return;

                    stopRecognizer();

                    if (!wakeMode || !activityResumed) return;

                    if (lookingForWakeWord) {
                        handler.postDelayed(
                                () -> startWakeListening(), 700);
                    } else {
                        returnToStandby();
                    }
                }
            });

            Intent intent = new Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

            intent.putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE, "en-IN");

            intent.putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS, 1);

            intent.putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);

            newRecognizer.startListening(intent);

        } catch (Exception e) {
            stopRecognizer();
            status.setText("VOICE ERROR");
            setMood("IDLE");
            handler.postDelayed(
                    () -> startWakeListening(), 1000);
        }
    }

    private void handleVoiceCommand(String spoken) {
        String lower = spoken.toLowerCase(Locale.ROOT).trim();

        if (lower.equals("stop listening")
                || lower.equals("stop")
                || lower.equals("cancel listening")
                || lower.equals("go to standby")) {

            addMessage("YOU", spoken);
            addMessage("FRIDAY", "Returning to standby, Jack.");
            returnToStandby();
            return;
        }

        sendMessage(true, spoken);
    }

    private void returnToStandby() {
        commandMode = false;
        setMood("IDLE");

        if (wakeMode && activityResumed) {
            status.setText("STANDBY — SAY FRIDAY");
            handler.postDelayed(
                    () -> startWakeListening(), 500);
        } else {
            status.setText("MICROPHONE OFF");
        }
    }

    private void sendMessage(boolean fromVoice, String rawMessage) {
        String message = rawMessage.trim();
        if (message.isEmpty()) return;

        if (!fromVoice) input.setText("");

        addMessage("YOU", message);
        status.setText("FRIDAY IS THINKING...");
        setMood("THINKING");

        new Thread(() -> {
            String answer;

            try {
                answer = friday.callAttr(
                        "android_chat", message).toString();
            } catch (Exception e) {
                answer = "Connection error: " + e.getMessage();
            }

            final String reply = answer;

            runOnUiThread(() -> {
                addMessage("FRIDAY", reply);

                if (fromVoice) {
                    speakReply(reply);
                } else {
                    setMood("IDLE");
                    status.setText(wakeMode
                            ? "STANDBY — SAY FRIDAY"
                            : "SYSTEM READY");
                }
            });
        }).start();
    }

    private void speakReply(String reply) {
        if (ttsReady) {
            setMood("SPEAKING");

            int result = tts.speak(
                    reply,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    "FRIDAY_REPLY");

            if (result == TextToSpeech.ERROR) {
                afterSpeech();
            }
        } else {
            addMessage("FRIDAY",
                    "Speech output is not ready on this device.");
            afterSpeech();
        }
    }

    private void afterSpeech() {
        setMood("IDLE");

        if (wakeMode && activityResumed) {
            commandMode = false;
            status.setText("STANDBY — SAY FRIDAY");
            handler.postDelayed(
                    () -> startWakeListening(), 500);
        } else {
            status.setText("SYSTEM READY");
        }
    }

    private void stopRecognizer() {
        recognitionGeneration++;

        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {}

            recognizer = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(
                requestCode, permissions, grantResults);

        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0
                    && grantResults[0]
                    == PackageManager.PERMISSION_GRANTED) {
                startWakeMode();
            } else {
                addMessage("FRIDAY",
                        "Microphone permission was denied.");
            }
        }
    }

    private void openPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        startActivityForResult(intent, requestCode);
    }

    private String readSelectedFile(Uri uri) throws Exception {
        StringBuilder contents = new StringBuilder();

        try (InputStream in =
                     getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(
                             in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                contents.append(line).append('\n');
            }
        }

        return contents.toString();
    }

    private void writePrivateFile(
            String filename, String contents) throws Exception {

        File target = new File(getFilesDir(), filename);

        try (OutputStream out =
                     new FileOutputStream(target, false)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        target.setReadable(false, false);
        target.setWritable(false, false);
        target.setReadable(true, true);
        target.setWritable(true, true);
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {

        super.onActivityResult(requestCode, resultCode, data);

        if ((requestCode != PICK_ENV_FILE
                && requestCode != PICK_MEMORY_FILE)
                || resultCode != RESULT_OK
                || data == null) {
            return;
        }

        Uri uri = data.getData();

        if (uri == null) {
            addMessage("FRIDAY", "No file was selected.");
            return;
        }

        try {
            String contents = readSelectedFile(uri);

            if (requestCode == PICK_ENV_FILE) {
                boolean hasGemini = false;
                boolean hasAccount = false;
                boolean hasToken = false;

                for (String line : contents.split("\\r?\\n")) {
                    String trimmed = line.trim();

                    if (trimmed.startsWith("GEMINI_API_KEY=")
                            && !trimmed.substring(15).trim().isEmpty()) {
                        hasGemini = true;
                    }

                    if (trimmed.startsWith("CLOUDFLARE_ACCOUNT_ID=")
                            && !trimmed.substring(22).trim().isEmpty()) {
                        hasAccount = true;
                    }

                    if (trimmed.startsWith("CLOUDFLARE_API_TOKEN=")
                            && !trimmed.substring(21).trim().isEmpty()) {
                        hasToken = true;
                    }
                }

                if (!hasGemini && !(hasAccount && hasToken)) {
                    addMessage("FRIDAY",
                            "Missing usable API credentials.");
                    return;
                }

                writePrivateFile(".env", contents);
                friday.callAttr("configure_android",
                        getFilesDir().getAbsolutePath());

                addMessage("FRIDAY",
                        "API configuration imported successfully.");

            } else {
                JSONObject memory = new JSONObject(contents);

                if (!memory.has("owner_name")
                        && !memory.has("saved_facts")
                        && !memory.has("preferences")) {
                    addMessage("FRIDAY",
                            "This does not look like FRIDAY memory.json.");
                    return;
                }

                writePrivateFile("memory.json", contents);
                friday.callAttr("configure_android",
                        getFilesDir().getAbsolutePath());

                addMessage("FRIDAY",
                        "FRIDAY memory imported successfully.");
            }

        } catch (Exception e) {
            addMessage("FRIDAY",
                    "Import failed: " + e.getMessage());
        }
    }

    void addMessage(String sender, String message) {
        TextView bubble = new TextView(this);
        bubble.setText(sender + "\n" + message);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(16);
        bubble.setPadding(16, 12, 16, 12);
        bubble.setBackgroundColor(Color.rgb(20, 65, 82));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(0, 8, 0, 8);
        messages.addView(bubble, params);

        if (chatScroll != null) {
            chatScroll.post(() ->
                    chatScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;

        if (wakeMode && !commandMode) {
            handler.postDelayed(
                    () -> startWakeListening(), 500);
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        stopRecognizer();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        wakeMode = false;
        stopRecognizer();

        if (avatar != null) {
            avatar.stopAnimation();
        }

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }

    // =====================================
    // FRIDAY ANIMATED AVATAR
    // =====================================

    public static class AvatarView extends View {

        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String mood = "IDLE";
        private boolean running = true;

        public AvatarView(Activity context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        public void setMood(String newMood) {
            mood = newMood;
            invalidate();
        }

        public void stopAnimation() {
            running = false;
        }

        private void color(Canvas c, int color) {
            p.setColor(color);
            p.setStyle(Paint.Style.FILL);
            p.setStrokeWidth(2);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            if (!running) return;

            float scale = Math.min(
                    getWidth() / 300f,
                    getHeight() / 220f);

            canvas.save();
            canvas.translate(
                    (getWidth() - 300 * scale) / 2,
                    (getHeight() - 220 * scale) / 2);
            canvas.scale(scale, scale);

            long time = System.currentTimeMillis();
            float breath = (float)Math.sin(time / 650.0) * 2f;
            float hairMove = (float)Math.sin(time / 900.0) * 3f;

            // Background aura
            color(canvas, Color.rgb(8, 52, 75));
            canvas.drawRoundRect(
                    new RectF(8, 4, 292, 216),
                    28, 28, p);

            p.setColor(Color.rgb(40, 190, 220));
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(1.5f);
            canvas.drawCircle(150, 108, 92 + breath, p);
            canvas.drawCircle(150, 108, 102 + breath, p);
            p.setStyle(Paint.Style.FILL);

            // Floating light particles
            for (int i = 0; i < 12; i++) {
                float x = 25 + (i * 47) % 250;
                float y = 25 + (float)Math.abs(
                        Math.sin(time / 700.0 + i)) * 165;
                color(canvas, Color.rgb(110, 240, 255));
                canvas.drawCircle(x, y, 1.8f, p);
            }

            // Hair behind body
            Path backHair = new Path();
            backHair.moveTo(104, 66);
            backHair.cubicTo(70, 50, 80, 130, 87, 194);
            backHair.cubicTo(90, 215, 108, 211, 111, 184);
            backHair.lineTo(190, 184);
            backHair.cubicTo(198, 214, 222, 213, 216, 180);
            backHair.cubicTo(226, 116, 224, 53, 180, 55);
            backHair.close();

            color(canvas, Color.rgb(80, 185, 235));
            canvas.drawPath(backHair, p);

            // Hair movement strands
            p.setColor(Color.rgb(155, 225, 255));
            p.setStrokeWidth(3);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(91 + hairMove, 83, 99, 183, p);
            canvas.drawLine(207 + hairMove, 82, 201, 185, p);
            p.setStyle(Paint.Style.FILL);

            // Neck
            color(canvas, Color.rgb(244, 194, 178));
            canvas.drawRoundRect(
                    new RectF(132, 148, 168, 184),
                    10, 10, p);

            // Shoulders and black outfit
            Path body = new Path();
            body.moveTo(118, 164 + breath);
            body.quadTo(150, 151 + breath, 182, 164 + breath);
            body.quadTo(213, 174, 222, 215);
            body.lineTo(78, 215);
            body.quadTo(87, 174, 118, 164 + breath);
            body.close();

            color(canvas, Color.rgb(18, 17, 29));
            canvas.drawPath(body, p);

            // Purple outfit details
            p.setColor(Color.rgb(155, 90, 225));
            p.setStrokeWidth(3);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(150, 178, 150, 211, p);
            p.setStyle(Paint.Style.FILL);

            // Face
            color(canvas, Color.rgb(255, 220, 202));
            canvas.drawOval(
                    new RectF(105, 39 + breath, 195, 161 + breath), p);

            // Ears
            canvas.drawCircle(104, 104 + breath, 10, p);
            canvas.drawCircle(196, 104 + breath, 10, p);

            // Front hair cap
            Path hairTop = new Path();
            hairTop.moveTo(104, 91);
            hairTop.cubicTo(90, 28, 113, 18, 151, 21);
            hairTop.cubicTo(204, 19, 217, 53, 194, 96);
            hairTop.cubicTo(180, 71, 166, 61, 150, 58);
            hairTop.cubicTo(135, 77, 120, 88, 104, 91);
            hairTop.close();

            color(canvas, Color.rgb(105, 195, 245));
            canvas.drawPath(hairTop, p);

            // Side locks
            Path leftLock = new Path();
            leftLock.moveTo(109, 67);
            leftLock.cubicTo(88, 95, 100, 147, 91, 185);
            leftLock.cubicTo(111, 171, 117, 128, 119, 87);
            leftLock.close();
            canvas.drawPath(leftLock, p);

            Path rightLock = new Path();
            rightLock.moveTo(190, 66);
            rightLock.cubicTo(213, 98, 201, 146, 211, 185);
            rightLock.cubicTo(190, 169, 184, 126, 181, 87);
            rightLock.close();
            canvas.drawPath(rightLock, p);

            // Hair highlights
            p.setColor(Color.rgb(190, 240, 255));
            p.setStrokeWidth(3);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(119, 34, 106, 75, p);
            canvas.drawLine(137, 28, 127, 60, p);
            canvas.drawLine(181, 31, 195, 70, p);
            p.setStyle(Paint.Style.FILL);

            // Bangs
            Path bangs = new Path();
            bangs.moveTo(105, 70);
            bangs.cubicTo(119, 62, 130, 53, 142, 45);
            bangs.cubicTo(141, 70, 137, 80, 129, 91);
            bangs.cubicTo(148, 78, 158, 58, 160, 43);
            bangs.cubicTo(177, 57, 185, 70, 196, 82);
            bangs.cubicTo(177, 79, 169, 73, 163, 67);
            bangs.cubicTo(151, 89, 128, 91, 105, 70);
            bangs.close();
            canvas.drawPath(bangs, p);

            // Eyes - blinking animation
            boolean blink = (time % 4300) < 150;

            if (blink) {
                p.setColor(Color.rgb(85, 45, 100));
                p.setStrokeWidth(3);
                p.setStyle(Paint.Style.STROKE);
                canvas.drawLine(119, 111, 139, 111, p);
                canvas.drawLine(161, 111, 181, 111, p);
                p.setStyle(Paint.Style.FILL);
            } else {
                color(canvas, Color.WHITE);
                canvas.drawOval(
                        new RectF(118, 101, 140, 121), p);
                canvas.drawOval(
                        new RectF(160, 101, 182, 121), p);

                color(canvas, Color.rgb(125, 75, 190));
                canvas.drawOval(
                        new RectF(124, 103, 137, 121), p);
                canvas.drawOval(
                        new RectF(163, 103, 176, 121), p);

                color(canvas, Color.rgb(35, 24, 49));
                canvas.drawCircle(132, 113, 5, p);
                canvas.drawCircle(169, 113, 5, p);

                color(canvas, Color.WHITE);
                canvas.drawCircle(130, 108, 2.5f, p);
                canvas.drawCircle(167, 108, 2.5f, p);
            }

            // Eyebrows
            p.setColor(Color.rgb(85, 65, 110));
            p.setStrokeWidth(2.5f);
            p.setStyle(Paint.Style.STROKE);
            canvas.drawLine(117, 96, 140, 94, p);
            canvas.drawLine(160, 94, 183, 96, p);
            p.setStyle(Paint.Style.FILL);

            // Nose
            p.setColor(Color.rgb(205, 145, 140));
            canvas.drawCircle(150, 128, 2, p);

            // Mouth animation
            p.setColor(Color.rgb(165, 65, 95));
            if ("SPEAKING".equals(mood)) {
                float mouth = 4 + Math.abs(
                        (float)Math.sin(time / 100.0)) * 7;
                canvas.drawOval(
                        new RectF(143, 137, 157, 137 + mouth), p);
            } else if ("LISTENING".equals(mood)) {
                canvas.drawOval(
                        new RectF(145, 137, 155, 143), p);
            } else {
                p.setStrokeWidth(2);
                p.setStyle(Paint.Style.STROKE);
                canvas.drawArc(
                        new RectF(140, 132, 160, 150),
                        15, 150, false, p);
                p.setStyle(Paint.Style.FILL);
            }

            // Blush
            color(canvas, Color.rgb(245, 155, 165));
            canvas.drawOval(
                    new RectF(111, 128, 127, 136), p);
            canvas.drawOval(
                    new RectF(173, 128, 189, 136), p);

            // Purple jewel
            Path jewel = new Path();
            jewel.moveTo(150, 178);
            jewel.lineTo(159, 188);
            jewel.lineTo(150, 201);
            jewel.lineTo(141, 188);
            jewel.close();

            color(canvas, Color.rgb(190, 115, 255));
            canvas.drawPath(jewel, p);

            p.setColor(Color.rgb(240, 205, 255));
            canvas.drawCircle(147, 185, 2, p);

            // Mood label
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(11);
            p.setColor(Color.rgb(145, 235, 255));
            String label = mood;
            float textWidth = p.measureText(label);
            canvas.drawText(label, 150 - textWidth / 2, 210, p);

            canvas.restore();

            postInvalidateDelayed(45);
        }
    }
}
