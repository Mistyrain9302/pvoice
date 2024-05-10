package com.power.voice;


import java.util.concurrent.TimeUnit;



import android.widget.TextView;

import java.lang.ref.WeakReference;


import android.os.AsyncTask;
import android.util.Log;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class JsonRequestTask extends AsyncTask<String, Void, String> {

    private static final String TAG = "JsonRequestTask";
    private WeakReference<TextView> textViewWeakReference;
    private TaskCompletedListener listener;

    public interface TaskCompletedListener {
        void onTaskCompleted();
    }

    public JsonRequestTask(TextView textView ,  TaskCompletedListener listener) {
        textViewWeakReference = new WeakReference<>(textView);
        this.listener = listener;
    }

    @Override
    protected String doInBackground(String...  reqJson) {

//        String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";


//        OkHttpClient client = new OkHttpClient();
        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS) // Set connection timeout
                .readTimeout(30, TimeUnit.SECONDS)    // Set read timeout
                .writeTimeout(30, TimeUnit.SECONDS)   // Set write timeout
                .build();

        // JSON body for the POST request
        // String jsonBody = "{\"sender\":\"test_user\", \"message\":\"Turn on the living room lights\"}";
        Log.i(TAG, "doInBackground ### chat Request  JSON: " + reqJson[0].toString());
        String jsonBody = reqJson[0];
        //String jsonBody = "{\"sender\":\"test_user\", \"message\":\"거실 조명 켜\"}";
        // Request body
        RequestBody requestBody = RequestBody.create(MediaType.parse("application/json"), jsonBody);
        Log.i(TAG, "chat Request  JSON: " + "https://demo.voise.co.kr:38231/webhooks/rest/webhook");
        // HTTP request
        //
        // .url("http://192.168.0.88:5005/webhooks/rest/webhook")
        Request request = new Request.Builder()
                .url("https://demo.voise.co.kr:38231/webhooks/rest/webhook")
                .post(requestBody)
                .build();

        try {

            // Sending the request synchronously
            Response response = client.newCall(request).execute();
            if (response.isSuccessful()) {
                //   publishProgress(response.body().string());
                return response.body().string();
            } else {
                Log.e(TAG, "Unexpected response code: " + response);
                //publishProgress("ChatBot FAIL");
                return null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error making request: " + e.getMessage());
            return null;
        }


    }

    //UI작업 관련 작업 (백그라운드 실행중 이 메소드를 통해 UI작업을 할 수 있다)
//publishProgress(value)의 value를 값으로 받는다.values는 배열이라 여러개 받기가능
  /****
    protected void onProgressUpdate(String ... values) {
        TextView textView = textViewWeakReference.get();
        if (textView != null) {

            textView.setText("현재  값 : " + values[0]);
        }

    }

   ***/
    @Override
    protected void onPostExecute(String response) {
        // Handle response here
        if (response != null) {
            try {
                JSONArray jsonArray = new JSONArray(response);
                for (int i = 0; i < jsonArray.length(); i++) {
                    JSONObject jsonObject = jsonArray.getJSONObject(i);
                    String text = jsonObject.getString("text");

                    TextView textView = textViewWeakReference.get();
                    if (textView != null) {
                        textView.setText(text);
                    }

                    // Notify MainActivity that task is completed
                    if (listener != null) {
                        listener.onTaskCompleted();
                    }

                    Log.d(TAG, "Text: " + text);
                    // Do something with the text
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing JSON: " + e.getMessage());
            }
        } else {
            Log.e(TAG, "Failed to get response");
            // Handle error
        }
    }
}

