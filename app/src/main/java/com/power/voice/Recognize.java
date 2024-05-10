package com.power.voice;

public class Recognize {

  static {
    System.loadLibrary("pvoice");
  }

  public static native void init(String modelDir);
  public static native void reset();
  public static native void acceptWaveform(short[] waveform);
  public static native void setInputFinished();
  public static native boolean getFinished();
  public static native void startDecode();
  public static native String getResult();
}
