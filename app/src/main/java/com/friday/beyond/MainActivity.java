package com.friday.beyond;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import android.content.Intent;
import android.net.Uri;
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

public class MainActivity extends Activity {
    private static final int PICK_ENV_FILE = 701;
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
        importConfig.setOnClickListener(v -> openConfigPicker());

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

        Button send = new Button(this);
        send.setText("SEND");

        LinearLayout bottom = new LinearLayout(this);
        bottom.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        bottom.addView(send);
        root.addView(bottom);

        send.setOnClickListener(v -> sendMessage());
        input.setOnEditorActionListener((v, actionId, event) -> {
            sendMessage();
            return true;
        });

        setContentView(root);
        addMessage("FRIDAY", "Welcome back, Jack. Systems online.");
        addMessage("FRIDAY", "Tap IMPORT API CONFIG to select your existing .env file.");
    }

    private void openConfigPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, ".env");
        startActivityForResult(intent, PICK_ENV_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ENV_FILE || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            addMessage("FRIDAY", "No file was selected.");
            return;
        }

        try {
            StringBuilder contents = new StringBuilder();
            boolean hasGemini = false;
            boolean hasAccount = false;
            boolean hasToken = false;

            try (InputStream in = getContentResolver().openInputStream(uri);
                 BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    contents.append(line).append('\n');
                    String trimmed = line.trim();
                    if (trimmed.startsWith("GEMINI_API_KEY=") &&
                        !trimmed.substring("GEMINI_API_KEY=".length()).trim().isEmpty()) {
                        hasGemini = true;
                    }
                    if (trimmed.startsWith("CLOUDFLARE_ACCOUNT_ID=") &&
                        !trimmed.substring("CLOUDFLARE_ACCOUNT_ID=".length()).trim().isEmpty()) {
                        hasAccount = true;
                    }
                    if (trimmed.startsWith("CLOUDFLARE_API_TOKEN=") &&
                        !trimmed.substring("CLOUDFLARE_API_TOKEN=".length()).trim().isEmpty()) {
                        hasToken = true;
                    }
                }
            }

            if (!hasGemini && !(hasAccount && hasToken)) {
                addMessage("FRIDAY", "The selected file is missing usable AI credentials. Nothing was imported.");
                return;
            }

            File target = new File(getFilesDir(), ".env");
            try (OutputStream out = new FileOutputStream(target, false)) {
                out.write(contents.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            target.setReadable(false, false);
            target.setWritable(false, false);
            target.setReadable(true, true);
            target.setWritable(true, true);

            friday.callAttr("configure_android", getFilesDir().getAbsolutePath());
            addMessage("FRIDAY", "API configuration imported into private app storage. Please send: Hello FRIDAY");
        } catch (Exception e) {
            addMessage("FRIDAY", "Could not import the configuration: " + e.getMessage());
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
