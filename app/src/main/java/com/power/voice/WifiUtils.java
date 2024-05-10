package com.power.voice;


import android.content.Context;
        import android.net.wifi.WifiInfo;
        import android.net.wifi.WifiManager;

public class WifiUtils {

    // Wi-Fi MAC 주소 가져오기
    public static String getWifiMacAddress(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo != null) {
                return wifiInfo.getMacAddress();
            }
        }
        return null;
    }
}

