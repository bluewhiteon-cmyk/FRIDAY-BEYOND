package com.friday.beyond;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.*;
import com.chaquo.python.Python;
import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.PyObject;
import android.text.InputType;

public class MainActivity extends Activity {
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

        ScrollView scroll = new ScrollView(this);
        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(
            -1, 0, 1
        ));

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
