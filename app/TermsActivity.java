package com.datacollector;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class TermsActivity extends AppCompatActivity {

    public static final String PREFS_NAME  = "DataCollectorPrefs";
    public static final String KEY_AGREED  = "terms_agreed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // If user already agreed before, skip straight to MainActivity
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        if (prefs.getBoolean(KEY_AGREED, false)) {
            launchMain();
            return;
        }

        setContentView(R.layout.activity_terms);

        CheckBox chkAgree = findViewById(R.id.chkAgree);
        Button   btnAccept = findViewById(R.id.btnAccept);
        Button   btnDecline = findViewById(R.id.btnDecline);

        btnAccept.setEnabled(false);

        chkAgree.setOnCheckedChangeListener((buttonView, isChecked) ->
                btnAccept.setEnabled(isChecked));

        btnAccept.setOnClickListener(v -> {
            prefs.edit().putBoolean(KEY_AGREED, true).apply();
            launchMain();
        });

        btnDecline.setOnClickListener(v -> {
            Toast.makeText(this,
                    "You must agree to the terms to use this app.",
                    Toast.LENGTH_LONG).show();
            finish(); // close the app
        });
    }

    private void launchMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
