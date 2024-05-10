package com.power.voice;



import android.speech.tts.UtteranceProgressListener;

public class MyUtteranceProgressListener extends UtteranceProgressListener {
    private MainActivity mainActivity; // Reference to MainActivity

    public MyUtteranceProgressListener(MainActivity mainActivity) {
        this.mainActivity = mainActivity;
    }

    @Override
    public void onStart(String utteranceId) {
        // This method is called when the speech synthesis for the specified utterance ID starts.
    }

    @Override
    public void onDone(String utteranceId) {
        // This method is called when the speech synthesis for the specified utterance ID is completed.
        // This method is called when the speech synthesis for the specified utterance ID is completed.
        if (utteranceId.equals("MyUtteranceId")) {
            // Handle the completion of the speech synthesis request with the specified utterance ID
            // Call onSpeechCompleted() method of MainActivity
            mainActivity.onSpeechCompleted();
        }
    }

    @Override
    public void onError(String utteranceId) {
        // This method is called if there is an error during speech synthesis for the specified utterance ID.
    }
}

