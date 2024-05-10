package com.power.voice;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.speech.tts.TextToSpeech;
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
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements JsonRequestTask.TaskCompletedListener, TextToSpeech.OnInitListener {
    private final boolean ifdef_log_enable = false;
    private final int MY_PERMISSIONS_RECORD_AUDIO = 1;
    private static final String LOG_TAG = "PVoSTT";
    private static final int SAMPLE_RATE = 16000;  // The sampling rate
    private static final int MAX_QUEUE_SIZE = 2500;  // 100 seconds audio, 1 / 0.04 * 100
    // private static final int MAX_QUEUE_SIZE = 75;  //  2 seconds audio, 1 / 0.04 * 3
    private static final List<String> resource = Arrays.asList(
//          "final.zip", "units.txt", "ctc.ort", "decoder.ort", "encoder.ort"
//          "encoder.ort", "units.txt", "ctc.ort", "decoder.ort"
          "encoder.onnx", "units.txt", "ctc.onnx", "decoder.onnx", "stt.mdl"
    );

    private static final String TAG = "MainActivity";
    private boolean startRecord = false;
    private AudioRecord record = null;
    private int miniBufferSize = 0;  // 1280 bytes 648 byte 40ms, 0.04s
    private final BlockingQueue<short[]> bufferQueue = new ArrayBlockingQueue<>(MAX_QUEUE_SIZE);

    JsonRequestTask chatTask;
    String ethernetMacAddress;
    String WifiMacAddress;
    int count;
    int prev_count;

    private TextToSpeech textToSpeech;

    private AudioManager audioManager;


    public static void assetsInit(Context context) throws IOException {
        AssetManager assetMgr = context.getAssets();
        // Unzip all files in resource from assets to context.
        // Note: Uninstall the APP will remove the resource files in the context.
        for (String file : Objects.requireNonNull(assetMgr.list(""))) {
            if (resource.contains(file)) {
                File dst = new File(context.getFilesDir(), file);
                if (!dst.exists() || dst.length() == 0) {
                    //  Log.i(LOG_TAG, "Unzipping " + file + " to " + dst.getAbsolutePath());
                    InputStream is = assetMgr.open(file);
                    OutputStream os = new FileOutputStream(dst);
                    byte[] buffer = new byte[4 * 1024];
                    int read;
                    while ((read = is.read(buffer)) != -1) {
                        os.write(buffer, 0, read);
                    }
                    os.flush();
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
    public void onInit(int status) {
        if (status == TextToSpeech.SUCCESS) {
            // Set language for TTS
            int result = textToSpeech.setLanguage(Locale.KOREA);

            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "Language not supported");
                Toast.makeText(this, "Language not supported", Toast.LENGTH_SHORT).show();
            }

            textToSpeech.setOnUtteranceProgressListener(new MyUtteranceProgressListener(this));


        } else {
            Log.e(TAG, "Initialization failed");
            Toast.makeText(this, "TTS Initialization failed", Toast.LENGTH_SHORT).show();
        }
    }

    // Method to be called when speech synthesis is completed
    public void onSpeechCompleted() {
        // Perform any action you want after speech synthesis is completed
        Log.i("MainActivity", "Speech synthesis completed");
        // Unmute microphone
        audioManager.setMicrophoneMute(false);
    }

    @Override
    public void onTaskCompleted() {
        Log.d(TAG, "Text to speech task completed");
        // You can perform any action you want here after the task is completed

        speakOut();
    }

    public static String convertJapaneseRoom(String sentence) {
        // 정규 표현식을 사용하여 '일본 방' 또는 '일본방'을 '일번 방'으로 변환
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
        String textToSpeak = "안녕하세요? 반갑습니다.";


        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        // Initialize TextToSpeech
        textToSpeech = new TextToSpeech(this, this);

        Log.d(LOG_TAG, " *** onCreate()");

        prev_count = -1;
        ethernetMacAddress = getEthernetMacAddress();
        Log.i("Ethernet MAC Address", ethernetMacAddress);
        WifiMacAddress = WifiUtils.getWifiMacAddress(this);
        Log.i("Wifi MAC Address", WifiMacAddress);


        Date date = new Date(System.currentTimeMillis());
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMddhhmmss");
        String time = format.format(date);

        if (isExternalStorageWritable()) {
            //read, write 둘다 가능

            File appDirectory = new File(Environment.getExternalStorageDirectory() + "/com.power.voice");
            File logDirectory = new File(appDirectory + "/logs");
            Log.d(LOG_TAG, "*** onCreate() - appDirectory :: " + appDirectory.getAbsolutePath());
            Log.d(LOG_TAG, "*** onCreate() - logDirectory :: " + logDirectory.getAbsolutePath());

            //appDirectory 폴더 없을 시 생성
            if (!appDirectory.exists()) {
                appDirectory.mkdirs();
            }

            //logDirectory 폴더 없을 시 생성
            if (!logDirectory.exists()) {
                logDirectory.mkdirs();
            }

            File logFile = new File(logDirectory, "logcat_" + time + ".txt");
            // File logFile = new File( appDirectory, "logcat_" + time + ".txt" );
            Log.d(LOG_TAG, "*** onCreate() - logFile :: " + logFile);


            //이전 logcat 을 지우고 파일에 새 로그을 씀

            try {
                if (ifdef_log_enable) {
                    java.lang.Process process = Runtime.getRuntime().exec("logcat -c");
                    process = Runtime.getRuntime().exec("logcat  -n 4 -r 1024 -f " + logFile);   // 1024 kilo bytes,  4 count 개수 만큼 로그파일 rotat
                    //  process = Runtime.getRuntime().exec("logcat -f " + logFile);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }


        } else if (isExternalStorageReadable()) {
            //read 만 가능
        } else {
            //접근 불가능
        }

        String ex1 = ",\n,\n@test1@,\n@test2@,\n,\n@test3@,\n,\n";
        String[] texts = extractText(ex1);
        int count2 = texts.length;

        System.out.println("Extracted Texts: ");
        for (String text : texts) {
            System.out.println(text);
        }
        System.out.println("Total elements count: " + count2);

/****
 TextView chatTextView = findViewById(R.id.textChatResponse);
 chatTask  = new  JsonRequestTask(chatTextView );
 //excute를 통해 백그라운드 task를 실행시킨다
 //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
 // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
 String jsonBody = "{\"sender\":\"test_user\", \"message\":\"안방 조명 켜 \"}";
 chatTask.execute(jsonBody );
 ****/


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


    /**
     * 외부저장소 read/write 가능 여부 확인
     *
     * @return
     */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }


    /**
     * 외부저장소 read 가능 여부 확인
     *
     * @return
     */
    public boolean isExternalStorageReadable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state) || Environment.MEDIA_MOUNTED_READ_ONLY.equals(state);
    }

    @Override
    public void onBackPressed() {
        // 앱 종료를 확인하는 다이얼로그를 표시합니다.
        new AlertDialog.Builder(this)
                .setTitle("앱 종료")
                .setMessage("정말로 앱을 종료하시겠습니까?")
                .setPositiveButton("예", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 예 버튼을 클릭하면 앱을 종료합니다.
                        finish();
                    }
                })
                .setNegativeButton("아니요", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // 아니요 버튼을 클릭하면 다이얼로그를 닫습니다.
                        dialog.dismiss();
                    }
                })
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

    //@SuppressLint("MissingPermission")

    @SuppressWarnings("MissingPermission")
    private void initRecorder() {
        // buffer size in bytes 1280
        miniBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (miniBufferSize == AudioRecord.ERROR || miniBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            Log.e(LOG_TAG, "Audio buffer can't initialize!");
            return;
        }

        /***
         miniBufferSize = 4096 ;
         miniBufferSize = 3072 ;
         miniBufferSize = 2048 ;
         ***/
        record = new AudioRecord(MediaRecorder.AudioSource.DEFAULT,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                miniBufferSize);
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(LOG_TAG, "Audio Record can't initialize!");
            return;
        }
        //  Log.i(LOG_TAG, "Record init okay  : miniBufferSize -> " +  miniBufferSize );
    }

    private void startRecordThread() {

        new Thread(() -> {
            VoiceRectView voiceView = findViewById(R.id.voiceRectView);
            record.startRecording();
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            while (startRecord) {
                short[] buffer = new short[miniBufferSize / 2];
                int read = record.read(buffer, 0, buffer.length);
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
            record.stop();
            voiceView.zero();
        }).start();
    }

    public String getEthernetMacAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
            for (NetworkInterface intf : interfaces) {
                if (intf.getName().equalsIgnoreCase("eth0")) {
                    byte[] mac = intf.getHardwareAddress();
                    if (mac == null) {
                        return "02:00:00:00:00:00";
                    }
                    StringBuilder buf = new StringBuilder();
                    for (byte aMac : mac) {
                        buf.append(String.format("%02X:", aMac));
                    }
                    if (buf.length() > 0) {
                        buf.deleteCharAt(buf.length() - 1);
                    }
                    return buf.toString();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "02:00:00:00:00:00";
    }


    private void speakOut() {

        TextView chatTextView = findViewById(R.id.textChatResponse);
        String text = chatTextView.getText().toString();
        // mute microphone
        audioManager.setMicrophoneMute(true);

        // Check if TextToSpeech is initialized
        if (textToSpeech != null) {
            String utteranceId = "MyUtteranceId"; // Unique identifier for this speech synthesis request
            textToSpeech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        }
    }


    public static String[] extractText(String input) {
        Pattern pattern = Pattern.compile("\\@([^@]+)\\@"); // 정규 표현식으로 *로 둘러싸인 문자열 추출
        Matcher matcher = pattern.matcher(input);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            result.append(matcher.group(1)).append("\n"); // 매칭된 부분을 결과에 추가
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

    private void startAsrThread() {
        new Thread(() -> {
            //Thread.currentThread().setPriority( Thread.MAX_PRIORITY );
            count = 1;
            // Send all data
            while (startRecord || bufferQueue.size() > 0) {
                try {
//                    if (bufferQueue.size() > 0)
//                        Log.i(LOG_TAG, " @@@@@@@@@    bufferQueue Count  :  " + bufferQueue.size());
                    short[] data = bufferQueue.take();

                    // 1. add data to C++ interface
                    Recognize.acceptWaveform(data);
                    // 2. get partial result
                    runOnUiThread(() -> {
                        TextView textView = findViewById(R.id.textView);
                        String sttResult = convertJapaneseRoom(Recognize.getResult());
                        textView.setText(sttResult);
                        Log.i(LOG_TAG, " @@@@@@@@@    Recognize. Text  :  " + Recognize.getResult() + " -> " + sttResult);

                        if (sttResult.contains("사람 살려")) {
                            // "사람 살려"가 감지되면 수행할 작업
                            Log.i(LOG_TAG, "긴급 상황 감지: " + sttResult);
                            // 추가적으로 긴급 상황 처리를 위한 메소드 호출 가능
                        }

                        String[] texts;
                        int ncount = 0;
                        if (sttResult != "") {
                            texts = extractText(sttResult);
                            ncount = texts.length;
                            System.out.println("startAsrThread # Extracted Texts: ");
                            for (String text : texts) {
                                System.out.println(text);
                                if (text == "사람 살려"){
                                    Log.i(LOG_TAG,"Text Detected :" + text);
//                                    linphoneManager.makeCall("sip:destination@sipserver.com")
                                }
                            }
                            System.out.println("startAsrThread # Total elements count: " + ncount);


                            //excute를 통해 백그라운드 task를 실행시킨다
                            //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                            // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
//                            String jsonBody2 = "{\"sender\":\"test_user-" + getEthernetMacAddress() + "-" + WifiMacAddress + "\", \"message\":\"" + texts[ncount - 1] + "\"}";
//                            System.out.println(jsonBody2);
//
//                            if (ncount >= 1 && ncount != prev_count && texts[ncount - 1].length() > 1) {
//                                prev_count = ncount;
//                                String jsonBody = "{\"sender\":\"test_user-" + getEthernetMacAddress() + "-" + WifiMacAddress + "\", \"message\":\"" + texts[ncount - 1] + "\"}";
//                                System.out.println(jsonBody);
//                                TextView chatTextView = findViewById(R.id.textChatResponse);
//                                chatTask = new JsonRequestTask(chatTextView, this);
//                                chatTask.execute(jsonBody);
//                            }
                        }

                        /****
                         if ( count%4 == 0) {
                         Log.i(LOG_TAG, " startAsrThread @@@@@ 0 @@@@   Count  :  " + count );
                         TextView chatTextView = findViewById(R.id.textChatResponse);
                         chatTask = new JsonRequestTask(chatTextView);
                         //excute를 통해 백그라운드 task를 실행시킨다
                         //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                         String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
                         //String jsonBody = "{\"sender\":\"test_user\", \"message\":\"안방 조명 켜\"}";
                         chatTask.execute(jsonBody);
                         count++ ;
                         }
                         else if ( count%4 == 1 ) {
                         Log.i(LOG_TAG, " startAsrThread @@@@ 1 @@@@@   Count  :  " + count );
                         TextView chatTextView = findViewById(R.id.textChatResponse);
                         chatTask = new JsonRequestTask(chatTextView);
                         //excute를 통해 백그라운드 task를 실행시킨다
                         //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                         // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
                         String jsonBody = "{\"sender\":\"test_user\", \"message\":\"안방 조명 켜\"}";
                         chatTask.execute(jsonBody);
                         count++ ;
                         }
                         else if ( count%4 == 2 ) {
                         Log.i(LOG_TAG, " startAsrThread @@@@ 2 @@@@@   Count  :  " + count );
                         TextView chatTextView = findViewById(R.id.textChatResponse);
                         chatTask = new JsonRequestTask(chatTextView);
                         //excute를 통해 백그라운드 task를 실행시킨다
                         //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                         // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
                         String jsonBody = "{\"sender\":\"test_user\", \"message\":\"전체 조명 켜\"}";
                         chatTask.execute(jsonBody);
                         count++ ;
                         }
                         else if ( count%4 == 3 ) {
                         Log.i(LOG_TAG, " startAsrThread @@@@ 3 @@@@@   Count  :  " + count );
                         TextView chatTextView = findViewById(R.id.textChatResponse);
                         chatTask = new JsonRequestTask(chatTextView);
                         //excute를 통해 백그라운드 task를 실행시킨다
                         //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                         // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
                         String jsonBody = "{\"sender\":\"test_user\", \"message\":\"1번방 조명 켜\"}";
                         chatTask.execute(jsonBody);
                         count++ ;
                         }
                         **
                         TextView chatTextView = findViewById(R.id.textChatResponse);
                         JsonRequestTask  chatTask;
                         chatTask  = new  JsonRequestTask(chatTextView );
                         //excute를 통해 백그라운드 task를 실행시킨다
                         //jsonBody 매개변수 보내는데  매개변수를 doInBackGround에서 사용했다.
                         String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
                         chatTask.execute(jsonBody );
                         ****/

                        ScrollView textScrollView = findViewById(R.id.scrollView);
                        textScrollView.fullScroll(ScrollView.FOCUS_DOWN);
                    });
                } catch (InterruptedException e) {
                    Log.e(LOG_TAG, Objects.requireNonNull(e.getMessage()));
                }
            }

            // Wait for final result
            while (true) {
                // get result
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
