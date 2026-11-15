
package com.friday.beyond;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.view.Gravity;
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
                    public void onStart(String id) {}

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
        root.setPadding(16, 16, 16, 16);
        root.setBackgroundColor(Color.rgb(15, 12, 25));

        TextView title = new TextView(this);
        title.setText("FRIDAY BEYOND");
        title.setTextColor(Color.rgb(190, 145, 255));
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("SYSTEM READY");
        status.setTextColor(Color.WHITE);
        status.setGravity(Gravity.CENTER);
        status.setPadding(8, 14, 8, 14);
        root.addView(status);

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

        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages);
        root.addView(scroll,
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
    }

    private void toggleWakeMode() {
        if (wakeMode) {
            wakeMode = false;
            commandMode = false;
            stopRecognizer();
            wakeButton.setText("WAKE WORD ON");
            status.setText("MICROPHONE OFF");
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
                    status.setText(wakeMode
                            ? "STANDBY — SAY FRIDAY"
                            : "SYSTEM READY");
                }
            });
        }).start();
    }

    private void speakReply(String reply) {
        if (ttsReady) {
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
        bubble.setBackgroundColor(Color.rgb(43, 32, 62));

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(-1, -2);

        params.setMargins(0, 10, 0, 10);
        messages.addView(bubble, params);
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

        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }

        super.onDestroy();
    }
}
