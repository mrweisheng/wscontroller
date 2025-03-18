package com.example.wscontroller;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

public class DeviceNumberManager {
    private static final String DEVICE_NUMBER_FILE = "device_number.txt";
    private static final String DEVICE_NUMBER_PREF_KEY = "device_number";
    private static final String PREFS_NAME = "device_prefs";

    private Context context;
    private SharedPreferences prefs;
    private String deviceNumber;

    public DeviceNumberManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.deviceNumber = loadDeviceNumber();
    }

    // 加载设备编号
    private String loadDeviceNumber() {
        // 先尝试从文件读取
        String number = readDeviceNumberFromFile();

        // 如果文件中没有，从SharedPreferences中读取
        if (number == null || number.isEmpty()) {
            number = prefs.getString(DEVICE_NUMBER_PREF_KEY, "");

            // 如果SharedPreferences中有，写入文件保持一致
            if (!number.isEmpty()) {
                writeDeviceNumberToFile(number);
            }
        } else {
            // 如果文件中有，更新SharedPreferences保持一致
            prefs.edit().putString(DEVICE_NUMBER_PREF_KEY, number).apply();
        }

        return number;
    }

    // 从文件读取设备编号
    private String readDeviceNumberFromFile() {
        File file = new File(context.getExternalFilesDir(null), DEVICE_NUMBER_FILE);
        if (!file.exists()) {
            return "";
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            String number = reader.readLine();
            reader.close();
            return number != null ? number.trim() : "";
        } catch (IOException e) {
            Log.e("DeviceNumberManager", "读取设备编号文件失败", e);
            return "";
        }
    }

    // 写入设备编号到文件
    private void writeDeviceNumberToFile(String number) {
        File file = new File(context.getExternalFilesDir(null), DEVICE_NUMBER_FILE);
        try {
            FileWriter writer = new FileWriter(file);
            writer.write(number);
            writer.close();
        } catch (IOException e) {
            Log.e("DeviceNumberManager", "写入设备编号文件失败", e);
        }
    }

    // 保存新的设备编号
    public void saveDeviceNumber(String number) {
        // 验证编号格式 (三位数字)
        if (!number.matches("\\d{3}")) {
            throw new IllegalArgumentException("设备编号必须是三位数字");
        }

        // 保存到内存、文件和SharedPreferences
        this.deviceNumber = number;
        writeDeviceNumberToFile(number);
        prefs.edit().putString(DEVICE_NUMBER_PREF_KEY, number).apply();
    }

    // 获取当前设备编号
    public String getDeviceNumber() {
        return deviceNumber;
    }

    // 检查是否已设置设备编号
    public boolean hasDeviceNumber() {
        return deviceNumber != null && !deviceNumber.isEmpty();
    }
}