
package com.friday.beyond;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.Manifest;
import android.net.Uri;
import android.speech.RecognizerIntent;
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
import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int PICK_ENV_FILE = 701;
    private static final int PICK_MEMORY_FILE = 702;
    private static final int PICK_SPEECH = 703;
    private static final int REQUEST_MICROPHONE = 704;

    LinearLayout messages;
    EditText input;
    PyObject friday;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!Python.isStarted()) {
            Python.start(new AndroidPlatform(this));
        }

        friday = Python.getInstance().getModule("friday_engine");
        friday.callAttr("configure_android", getFilesDir().getAbsolutePath());

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(20, 20, 20, 20);
        root.setBackgroundColor(Color.rgb(15, 12, 25));

        TextView title = new TextView(this);
        title.setText("FRIDAY BEYOND");
        title.setTextColor(Color.rgb(190, 145, 255));
        title.setTextSize(25);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        Button importConfig = new Button(this);
        importConfig.setText("IMPORT API CONFIG");
        root.addView(importConfig);
        importConfig.setOnClickListener(v -> openPicker(PICK_ENV_FILE));

        Button importMemory = new Button(this);
        importMemory.setText("IMPORT FRIDAY MEMORY");
        root.addView(importMemory);
        importMemory.setOnClickListener(v -> openPicker(PICK_MEMORY_FILE));

        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        input = new EditText(this);
        input.setHint("Message FRIDAY...");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.LTGRAY);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        Button voice = new Button(this);
        voice.setText("VOICE");
        voice.setOnClickListener(v -> startVoiceInput());

        Button send = new Button(this);
        send.setText("SEND");

        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(voice);
        bottom.addView(send);
        root.addView(bottom);

        send.setOnClickListener(v -> sendMessage());

        input.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        setContentView(root);

        addMessage("FRIDAY", "Welcome back, Jack. Systems online.");
        addMessage("FRIDAY", "Import your API config and existing FRIDAY memory using the buttons above.");
        addMessage("FRIDAY", "Tap VOICE to speak to me.");
    }

    private void startVoiceInput() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQUEST_MICROPHONE
            );
            return;
        }

        launchSpeechRecognition();
    }

    private void launchSpeechRecognition() {
        try {
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            );

            intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to FRIDAY"
            );

            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "en-IN"
            );

            startActivityForResult(intent, PICK_SPEECH);

        } catch (Exception e) {
            addMessage("FRIDAY", "Voice recognition is unavailable on this device.");
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults) {

        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_MICROPHONE) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchSpeechRecognition();
            } else {
                addMessage("FRIDAY", "Microphone permission was denied.");
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

        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                 new InputStreamReader(in, StandardCharsets.UTF_8))) {

            String line;
            while ((line = reader.readLine()) != null) {
                contents.append(line).append('\n');
            }
        }

        return contents.toString();
    }

    private void writePrivateFile(String filename, String contents) throws Exception {
        File target = new File(getFilesDir(), filename);

        try (OutputStream out = new FileOutputStream(target, false)) {
            out.write(contents.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        target.setReadable(false, false);
        target.setWritable(false, false);
        target.setReadable(true, true);
        target.setWritable(true, true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_SPEECH) {
            if (resultCode == RESULT_OK && data != null) {
                ArrayList<String> results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                );

                if (results != null && !results.isEmpty()) {
                    input.setText(results.get(0));
                    sendMessage();
                }
            }
            return;
        }

        if ((requestCode != PICK_ENV_FILE && requestCode != PICK_MEMORY_FILE)
                || resultCode != RESULT_OK || data == null) {
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
                    addMessage("FRIDAY", "Missing usable API credentials.");
                    return;
                }

                writePrivateFile(".env", contents);
                friday.callAttr("configure_android", getFilesDir().getAbsolutePath());

                addMessage("FRIDAY", "API configuration imported successfully.");

            } else {
                JSONObject memory = new JSONObject(contents);

                if (!memory.has("owner_name")
                        && !memory.has("saved_facts")
                        && !memory.has("preferences")) {
                    addMessage("FRIDAY", "This does not look like FRIDAY memory.json.");
                    return;
                }

                writePrivateFile("memory.json", contents);
                friday.callAttr("configure_android", getFilesDir().getAbsolutePath());

                addMessage("FRIDAY", "FRIDAY memory imported. Ask about your favourite anime.");
            }

        } catch (Exception e) {
            addMessage("FRIDAY", "Import failed: " + e.getMessage());
        }
    }

    void sendMessage() {
        String message = input.getText().toString().trim();

        if (message.isEmpty()) return;

        input.setText("");
        addMessage("YOU", message);

        new Thread(() -> {
            String answer;

            try {
                answer = friday.callAttr("android_chat", message).toString();
            } catch (Exception e) {
                answer = "Connection error: " + e.getMessage();
            }

            final String reply = answer;
            runOnUiThread(() -> addMessage("FRIDAY", reply));
        }).start();
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
}
