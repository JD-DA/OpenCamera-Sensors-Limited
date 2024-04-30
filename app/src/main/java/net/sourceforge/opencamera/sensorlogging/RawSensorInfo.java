package net.sourceforge.opencamera.sensorlogging;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.StorageUtils;
import net.sourceforge.opencamera.StorageUtilsWrapper;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * Handles gyroscope and accelerometer raw info recording
 * Assumes all the used sensor types are motion or position sensors
 * and output [x, y, z] values -- the class should be updated if that changes
 */
public class RawSensorInfo implements SensorEventListener {
    private static final String TAG = "RawSensorInfo";
    private static final String CSV_SEPARATOR = ",";
    private static final List<Integer> SENSOR_TYPES = Collections.unmodifiableList(
            Arrays.asList(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_MAGNETIC_FIELD, Sensor.TYPE_AMBIENT_TEMPERATURE,Sensor.TYPE_GRAVITY,Sensor.TYPE_PRESSURE,Sensor.TYPE_RELATIVE_HUMIDITY)
    );
    private static final Map<Integer, String> SENSOR_TYPE_NAMES;
    static {
        SENSOR_TYPE_NAMES = new HashMap<>();
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_ACCELEROMETER, "accel");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_GYROSCOPE, "gyro");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_MAGNETIC_FIELD, "magnetic");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_AMBIENT_TEMPERATURE, "thermo");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_GRAVITY, "gravity");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_PRESSURE, "baro");
        SENSOR_TYPE_NAMES.put(Sensor.TYPE_RELATIVE_HUMIDITY, "hygro");
    }

    final private SensorManager mSensorManager;
    private boolean mIsRecording;
    private final Map<Integer, Sensor> mUsedSensorMap;
    private final Map<Integer, PrintWriter> mSensorWriterMap;
    private final Map<Integer, File> mLastSensorFilesMap;

    public Map<Integer, File> getLastSensorFilesMap() {
        return mLastSensorFilesMap;
    }

    public boolean isSensorAvailable(int sensorType) {
        return mUsedSensorMap.get(sensorType) != null;
    }

    public RawSensorInfo(MainActivity context) {
        mSensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        mUsedSensorMap = new HashMap<>();
        mSensorWriterMap = new HashMap<>();
        mLastSensorFilesMap = new HashMap<>();

        for (Integer sensorType : SENSOR_TYPES) {
            mUsedSensorMap.put(sensorType, mSensorManager.getDefaultSensor(sensorType));
        }

    }

    public int getSensorMinDelay(int sensorType) {
        Sensor sensor = mUsedSensorMap.get(sensorType);
        if (sensor != null) {
            return sensor.getMinDelay();
        } else {
            // Unsupported sensorType
            if (MyDebug.LOG) {
                Log.d(TAG, "Unsupported sensor type was provided");
            }
            return 0;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (mIsRecording) {
            StringBuilder sensorData = new StringBuilder();
            for (int j = 0; j < event.values.length; j++) {
                sensorData.append(event.values[j]).append(CSV_SEPARATOR);
            }
            sensorData.append(event.timestamp).append("\n");

            Sensor sensor = mUsedSensorMap.get(event.sensor.getType());
            if (sensor != null) {
                PrintWriter sensorWriter = mSensorWriterMap.get(event.sensor.getType());
                if (sensorWriter != null) {
                    sensorWriter.write(sensorData.toString());
                } else {
                    if (MyDebug.LOG) {
                        Log.d(TAG, "Sensor writer for the requested type wasn't initialized");
                    }
                }
            }
            /*if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER && mAccelBufferedWriter != null) {
                mAccelBufferedWriter.write(sensorData.toString());
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE && mGyroBufferedWriter != null) {
                mGyroBufferedWriter.write(sensorData.toString());
            }*/
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // TODO: Add logs for when sensor accuracy decreased
    }

    /**
     * Handles sensor info file creation, uses StorageUtils to work both with SAF and standard file
     * access.
     */
    private FileWriter getRawSensorInfoFileWriter(MainActivity mainActivity, Integer sensorType, String sensorName,
                                                  Date lastVideoDate) throws IOException {
        StorageUtilsWrapper storageUtils = mainActivity.getStorageUtils();
        FileWriter fileWriter;
        try {
            if (storageUtils.isUsingSAF()) {
                Uri saveUri = storageUtils.createOutputCaptureInfoFileSAF(
                        StorageUtils.MEDIA_TYPE_RAW_SENSOR_INFO, sensorName, "csv", lastVideoDate
                );
                ParcelFileDescriptor rawSensorInfoPfd = mainActivity
                        .getContentResolver()
                        .openFileDescriptor(saveUri, "w");
                if (rawSensorInfoPfd != null) {
                    fileWriter = new FileWriter(rawSensorInfoPfd.getFileDescriptor());
                    File saveFile = storageUtils.getFileFromDocumentUriSAF(saveUri, false);
                    storageUtils.broadcastFile(saveFile, true, false, true);
                    mLastSensorFilesMap.put(sensorType, saveFile);
                } else {
                    throw new IOException("File descriptor was null");
                }
            } else {
                File saveFile = storageUtils.createOutputCaptureInfoFile(
                        StorageUtils.MEDIA_TYPE_RAW_SENSOR_INFO, sensorName, "csv", lastVideoDate
                );
                fileWriter = new FileWriter(saveFile);
                if (MyDebug.LOG) {
                    Log.d(TAG, "save to: " + saveFile.getAbsolutePath());
                }
                mLastSensorFilesMap.put(sensorType, saveFile);
                storageUtils.broadcastFile(saveFile, false, false, false);
            }
            return fileWriter;
        } catch (IOException e) {
            e.printStackTrace();
            if (MyDebug.LOG) {
                Log.e(TAG, "failed to open raw sensor info files");
            }
            throw new IOException(e);
        }
    }

    private PrintWriter setupRawSensorInfoWriter(MainActivity mainActivity, Integer sensorType, String sensorName,
            Date currentVideoDate) throws IOException {
        FileWriter rawSensorInfoFileWriter = getRawSensorInfoFileWriter(
                mainActivity, sensorType, sensorName, currentVideoDate
        );
        PrintWriter rawSensorInfoWriter = new PrintWriter(
                new BufferedWriter(rawSensorInfoFileWriter)
        );
        return rawSensorInfoWriter;
    }

    public void startRecording(MainActivity mainActivity, Date currentVideoDate, Map<Integer, Boolean> wantSensorRecordingMap) {
        mLastSensorFilesMap.clear();
        try {
/*            if (wantGyroRecording && mSensorGyro != null) {
                mGyroBufferedWriter = setupRawSensorInfoWriter(
                        mainActivity, SENSOR_TYPE_GYRO, currentVideoDate
                );
            }
            if (wantAccelRecording && mSensorAccel != null) {
                mAccelBufferedWriter = setupRawSensorInfoWriter(
                        mainActivity, SENSOR_TYPE_ACCEL, currentVideoDate
                );
            }*/
            for (Integer sensorType : wantSensorRecordingMap.keySet()) {
                Boolean wantRecording = wantSensorRecordingMap.get(sensorType);
                if (sensorType != null &&
                        wantRecording != null &&
                        wantRecording == true
                ) {
                    mSensorWriterMap.put(
                            sensorType,
                            setupRawSensorInfoWriter(mainActivity, sensorType, SENSOR_TYPE_NAMES.get(sensorType), currentVideoDate)
                    );
                }
            }
            mIsRecording = true;
        } catch (IOException e) {
            e.printStackTrace();
            if (MyDebug.LOG) {
                Log.e(TAG, "Unable to setup sensor info writer");
            }
        }
    }

    public void stopRecording() {
        if (MyDebug.LOG) {
            Log.d(TAG, "Close all files");
        }
        for (PrintWriter sensorWriter : mSensorWriterMap.values()) {
            if (sensorWriter != null) {
                sensorWriter.close();
            }
        }
        /*if (mGyroBufferedWriter != null) {
            mGyroBufferedWriter.flush();
            mGyroBufferedWriter.close();
        }
        if (mAccelBufferedWriter != null) {
            mAccelBufferedWriter.flush();
            mAccelBufferedWriter.close();
        }*/
        mIsRecording = false;
    }

    public boolean isRecording() {
        return mIsRecording;
    }

    public void enableSensors(Map<Integer, Integer> sampleRateMap) {
        if (MyDebug.LOG) {
            Log.d(TAG, "enableSensors");
        }
        for (Integer sensorType : mUsedSensorMap.keySet()) {
            Integer sampleRate = sampleRateMap.get(sensorType);
            if (sampleRate == null) {
                // Assign default value if not provided
                sampleRate = 0;
            }

            if (sensorType != null) {
                enableSensor(sensorType, sampleRate);
            }

        }
        /*enableSensor(Sensor.TYPE_GYROSCOPE, gyroSampleRate);
        enableSensor(Sensor.TYPE_ACCELEROMETER, accelSampleRate);*/
    }


    /**
     * Enables sensor with specified frequency
     * @return Returns false if sensor isn't available
     */
    public boolean enableSensor(int sensorType, int sampleRate) {
        if (MyDebug.LOG) {
            Log.d(TAG, "enableSensor");
        }

        Sensor sensor = mUsedSensorMap.get(sensorType);
        if (sensor != null) {
            mSensorManager.registerListener(this, sensor, sampleRate);
            return true;
        } else {
            return false;
        }
        /*if (sensorType == Sensor.TYPE_ACCELEROMETER) {
            if (mSensorAccel == null) return false;
            mSensorManager.registerListener(this, mSensorAccel, sampleRate);
            return true;
        } else if (sensorType == Sensor.TYPE_GYROSCOPE) {
            if (mSensorGyro == null) return false;
            mSensorManager.registerListener(this, mSensorGyro, sampleRate);
            return true;
        } else {
            return false;
        }*/
    }

    public void disableSensors() {
        if (MyDebug.LOG) {
            Log.d(TAG, "disableSensors");
        }
        mSensorManager.unregisterListener(this);
    }
}
