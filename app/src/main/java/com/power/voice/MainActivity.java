package com.power.voice;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements JsonRequestTask.TaskCompletedListener {
    private final boolean ifdef_log_enable = false;
    private final int MY_PERMISSIONS_RECORD_AUDIO = 1;
    private static final String LOG_TAG = "PVoSTT";
    private static final int SAMPLE_RATE = 16000;  // The sampling rate
    private static final int MAX_QUEUE_SIZE = 2500;  // 100 seconds audio, 1 / 0.04 * 100
    private static final List<String> resource = Arrays.asList(
            "encoder.onnx", "units.txt", "ctc.onnx", "decoder.onnx", "stt.mdl"
    );
    private static final List<String> targetList = Arrays.asList("사람 살려", "도와주세요", "도와 주세요", "살려주세요", "살려 주세요");

    private static final String TAG = "MainActivity";
    private boolean startRecord = false;
    private boolean emergencyDetected = false;
    private AudioRecord record = null;
    private int miniBufferSize = 0;  // 1280 bytes 648 byte 40ms, 0.04s
    private final BlockingQueue<short[]> bufferQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

    int count;
    int prev_count;

    private AudioManager audioManager;
    private BroadcastReceiver callEndReceiver;

    public static void assetsInit(Context context) throws IOException {
        AssetManager assetMgr = context.getAssets();
        for (String file : Objects.requireNonNull(assetMgr.list(""))) {
            if (resource.contains(file)) {
                File dst = new File(context.getFilesDir(), file);
                if (!dst.exists() || dst.length() == 0) {
                    InputStream is = assetMgr.open(file);
                    OutputStream os = new FileOutputStream(dst);
                    byte[] buffer = new byte[4 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
                    os.close();
                    is.close();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == MY_PERMISSIONS_RECORD_AUDIO) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(LOG_TAG, "record permission is granted");
                initRecorder();
            } else {
                Toast.makeText(this, "Permissions denied to record audio", Toast.LENGTH_LONG).show();
                Button button = findViewById(R.id.button);
                button.setEnabled(false);
            }
        }
    }


    @Override
    public void onTaskCompleted() {
    }

    public static String convertJapaneseRoom(String sentence) {
        String regex = "(일본\\s*방)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(sentence);
        return matcher.replaceAll("일번 방");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        requestAudioPermissions();

        Log.i(LOG_TAG, " *** PVoice onCreate()");

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        callEndReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getAction().equals("CALL_ENDED")) {
                    Log.i(LOG_TAG, "전화 종료됨 - 녹음 다시 시작");
                    startRecord = true;
                    emergencyDetected = false;  // 긴급 상황 해제
                    initRecorder();
                    startRecordThread();
                    startAsrThread();
                    Recognize.startDecode();
                }
            }
        };
        registerReceiver(callEndReceiver, new IntentFilter("CALL_ENDED"));

        Log.d(LOG_TAG, " *** onCreate()");

        prev_count = -1;

        Date date = new Date(System.currentTimeMillis());
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddhhmmss");
        String time = format.format(date);

        if (isExternalStorageWritable()) {
            File appDirectory = new File(Environment.getExternalStorageDirectory() + "/com.power.voice");
            File logDirectory = new File(appDirectory + "/logs");
            Log.d(LOG_TAG, "*** onCreate() - appDirectory :: " + appDirectory.getAbsolutePath());
            Log.d(LOG_TAG, "*** onCreate() - logDirectory :: " + logDirectory.getAbsolutePath());

            if (!appDirectory.exists()) {
                appDirectory.mkdirs();
            }

            if (!logDirectory.exists()) {
                logDirectory.mkdirs();
            }

            File logFile = new File(logDirectory, "logcat_" + time + ".txt");
            Log.d(LOG_TAG, "*** onCreate() - logFile :: " + logFile);

            try {
                if (ifdef_log_enable) {
                    java.lang.Process process = Runtime.getRuntime().exec("logcat -c");
                    process = Runtime.getRuntime().exec("logcat  -n 4 -r 1024 -f " + logFile);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        String ex1 = ",\n,\n@test1@,\n@test2@,\n,\n@test3@,\n,\n";
        String[] texts = extractText(ex1);
        int count2 = texts.length;

        System.out.println("Extracted Texts: ");
        for (String text : texts) {
            System.out.println(text);
        }
        System.out.println("Total elements count: " + count2);

        try {
            assetsInit(this);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error process asset files to file path");
        }

        ScrollView textScrollView = findViewById(R.id.scrollView);
        TextView textView = findViewById(R.id.textView);
        textView.setMovementMethod(new ScrollingMovementMethod());
        textView.setText("");
        Recognize.init(getFilesDir().getPath());

        Button button = findViewById(R.id.button);
        button.setText("Start Record");

        textScrollView.fullScroll(ScrollView.FOCUS_DOWN);
        button.setOnClickListener(view -> {
            if (!startRecord) {
                Log.i(LOG_TAG, " >>>>>>>>>>>>>>> New Record Starts...  ");
                startRecord = true;
                Recognize.reset();
                startRecordThread();
                startAsrThread();
                Recognize.startDecode();
                button.setText("Stop Record");
            } else {
                startRecord = false;
                Recognize.setInputFinished();
                button.setText("Start Record");
            }
            button.setEnabled(false);
        });
    }

    private void startCall() {
        try {
            Log.i(LOG_TAG, "긴급 상황 감지 - 전화 걸기 시작");
            if (startRecord) {
                stopRecording();  // Ensure recording is properly stopped
                Recognize.setInputFinished();
                Log.i(LOG_TAG, "Recognize.setInputFinished() 호출 성공");
            }
            emergencyDetected = true;  // 긴급 상황 감지됨
            OutgoingCallActivity.Companion.start(this, "sip:12567@192.168.10.112");
            Log.i(LOG_TAG, "OutgoingCallActivity 시작");
        } catch (Exception e) {
            Log.e(LOG_TAG, "전화 걸기 중 오류 발생", e);
        }
    }

    private void stopRecording() {
        startRecord = false;
        if (record != null) {
            if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
            record.release();
            record = null;
        }
        bufferQueue.clear();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (callEndReceiver != null) {
            unregisterReceiver(callEndReceiver);
        }
        if (record != null) {
            record.release();
            record = null;
        }
        sendBroadcast(new Intent("CALL_ENDED"));
    }

    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("앱 종료")
                .setMessage("정말로 앱을 종료하시겠습니까?")
                .setPositiveButton("예", (dialog, which) -> finish())
                .setNegativeButton("아니요", (dialog, which) -> dialog.dismiss())
                .show();
    }

    private void requestAudioPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    MY_PERMISSIONS_RECORD_AUDIO);
        } else {
            initRecorder();
        }
    }

    @SuppressWarnings("MissingPermission")
    private void initRecorder() {
        miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(LOG_TAG, "Audio buffer can't initialize!");
            return;
        }

        record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                miniBufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }
    }

    private void startRecordThread() {
        new Thread(() -> {
            VoiceRectView voiceView = findViewById(R.id.voiceRectView);
            if (record != null) {
                record.startRecording();
            }
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (startRecord) {
                short[] buffer = new short[miniBufferSize / 2];
                int read = record != null ? record.read(buffer, 0, buffer.length) : 0;
                voiceView.add(calculateDb(buffer));
                try {
                    if (AudioRecord.ERROR_INVALID_OPERATION != read) {
                        bufferQueue.put(buffer);
                    }
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, Objects.requireNonNull(e.getMessage()));
                }
                Button button = findViewById(R.id.button);
                if (!button.isEnabled() && startRecord) {
                    runOnUiThread(() -> button.setEnabled(true));
                }
            }
            if (record != null && record.getState() == AudioRecord.STATE_INITIALIZED && record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop();
            }
            voiceView.zero();
        }).start();
    }

    public static String[] extractText(String input) {
        Pattern pattern = Pattern.compile("\\@([^@]+)\\@");
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            result.append(matcher.group(1)).append("\n");
        }
        String[] texts = result.toString().split("\n");
        return texts;
    }

    private double calculateDb(short[] buffer) {
        double energy = 0.0;
        for (short value : buffer) {
            energy += value * value;
        }
        energy /= buffer.length;
        energy = (10 * Math.log10(1 + energy)) / 100;
        energy = Math.min(energy, 1.0);
        return energy;
    }

    private boolean containsTargetText(String sttResult) {
        for (String target : targetList) {
            if (sttResult.contains(target)) {
                return true;
            }
        }
        return false;
    }

    private void startAsrThread() {
        new Thread(() -> {
            count = 1;
            while (startRecord || bufferQueue.size() > 0) {
                try {
                    short[] data = bufferQueue.take();
                    Recognize.acceptWaveform(data);
                    runOnUiThread(() -> {
                        TextView textView = findViewById(R.id.textView);
                        String sttResult = convertJapaneseRoom(Recognize.getResult());
                        textView.setText(sttResult);
                        Log.i(LOG_TAG, " @@@@@@@@@    Recognize. Text  :  " + Recognize.getResult() + " -> " + sttResult);

                        if (!emergencyDetected && containsTargetText(sttResult)) {
                            Log.i(LOG_TAG, "긴급 상황 감지: " + sttResult);
                            startCall();
                        }

                        String[] texts;
                        if (!sttResult.isEmpty()) {
                            texts = extractText(sttResult);
                            for (String text : texts) {
                                System.out.println(text);
                            }
                        }

                        ScrollView textScrollView = findViewById(R.id.scrollView);
                        textScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    });
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, Objects.requireNonNull(e.getMessage()));
                }
            }

            while (true) {
                if (!Recognize.getFinished()) {
                    runOnUiThread(() -> {
                        TextView textView = findViewById(R.id.textView);
                        textView.setText(Recognize.getResult());
                        ScrollView textScrollView = findViewById(R.id.scrollView);
                        textScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                        Log.i(LOG_TAG, " >>>>>>>>>>>>>>> New Record Ends...  ");
                    });
                } else {
                    runOnUiThread(() -> {
                        Button button = findViewById(R.id.button);
                        button.setEnabled(true);
                    });
                    break;
                }
            }
        }).start();
    }
}
