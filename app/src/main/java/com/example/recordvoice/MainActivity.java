package com.example.recordvoice;

import android.app.Activity;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MainActivity extends Activity implements OnClickListener {
    AudioRecord record = null;
    AudioTrack track = null;
    boolean isRecording;
    int sampleRate = 8000;
    Button startRecord, playRecord;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setVolumeControlStream(AudioManager.MODE_IN_COMMUNICATION);
        startRecord = (Button) findViewById(R.id.rec);
        playRecord = (Button) findViewById(R.id.play);
        playRecord.setEnabled(true);
    }

    public class Producer implements Runnable {

        private final BlockingQueue sharedQueue;

        public Producer(BlockingQueue sharedQueue) {
            this.sharedQueue = sharedQueue;
        }

        @Override
        public void run() {
            try {
                File recordFile = new File(Environment.getExternalStorageDirectory(), "Record.pcm");
                recordFile.createNewFile();
                OutputStream outputStream = new FileOutputStream(recordFile);
                BufferedOutputStream bufferedOutputStream = new BufferedOutputStream(outputStream);
                DataOutputStream dataOutputStream = new DataOutputStream(bufferedOutputStream);
                int minBufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);

                short[] audioData = new short[minBufferSize];
                synchronized (audioData) {
                    record = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            minBufferSize);
                    record.startRecording();
                    record.setNotificationMarkerPosition(76000);
                    record.setPositionNotificationPeriod(2000);
                    record.setRecordPositionUpdateListener(new AudioRecord.OnRecordPositionUpdateListener() {
                        public void onPeriodicNotification(AudioRecord recorder) {
                        }
                        public void onMarkerReached(AudioRecord recorder) {
                            Toast.makeText(MainActivity.this, "Record stop", Toast.LENGTH_LONG).show();
                            isRecording = false;
                            playRecord.setEnabled(true);
                            record.stop();
                        }
                    });
                    while (isRecording) {
                        int numberOfShort = record.read(audioData, 0, minBufferSize);
                        for (int i = 0; i < numberOfShort; i++) {
                            dataOutputStream.writeShort(audioData[i]);
                        }
                    }
                    record.stop();
                    sharedQueue.put(audioData);
                    dataOutputStream.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
                isRecording = false;
            } catch (InterruptedException e) {
                isRecording = false;
            }
        }
    }

    public class Consumer implements Runnable {

        private final BlockingQueue sharedQueue;

        public Consumer(BlockingQueue sharedQueue) {
            this.sharedQueue = sharedQueue;
        }

        @Override
        public void run() {
            try {
                File recordFile = new File(Environment.getExternalStorageDirectory(), "Record.pcm");
                int sizeInBytes = Byte.SIZE / Byte.SIZE;
                int bufferSizeInBytes = (int) (recordFile.length() / sizeInBytes);
                byte[] audioData = new byte[bufferSizeInBytes];

                InputStream inputStream = new FileInputStream(recordFile);
                BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
                DataInputStream dataInputStream = new DataInputStream(bufferedInputStream);
                synchronized (sharedQueue) {
                    try {
                        int i = 0;
                        while (dataInputStream.available() > 0) {
                            audioData[i] = dataInputStream.readByte();
                            i++;
                        }
                    } catch (IllegalThreadStateException e) {
                        track.release();
                        dataInputStream.close();
                    } catch (ArrayIndexOutOfBoundsException e) {
                        track.release();
                        dataInputStream.close();
                    }
                    if(bufferSizeInBytes>0) {
                        track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                                bufferSizeInBytes, AudioTrack.MODE_STREAM);
                        track.write(reverse(audioData), 0, bufferSizeInBytes);
                        track.play();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private byte[] reverse(byte[] array) {
        for (int i = 0; i < array.length / 2; i++) {
            byte temp = array[i];
            array[i] = array[array.length - i - 1];
            array[array.length - i - 1] = temp;
        }
        return array;
    }

    @Override
    public void onClick(View v) {
        final BlockingQueue sharedQueue = new LinkedBlockingQueue();
        Thread producerThread = new Thread(new Producer(sharedQueue));
        Thread consumerThread = new Thread(new Consumer(sharedQueue));
        switch (v.getId()) {
            case R.id.rec:
                producerThread.start();
                isRecording = true;
                break;
            case R.id.play:
                consumerThread.start();
                break;
        }
    }
}