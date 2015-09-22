package com.example.eleven.speechlibrary;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

/**
 * 录音类(singleton)
 * Created by Eleven on 2015/7/24.
 */
public class Recorder {

    public static final int frequency = 22050;
    public static final int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    public static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    private int recBufSize;
    private AudioRecord mAudioRecord;

    private LinkedList<short[]> mData =  new LinkedList<>();//保存录音数据

    private volatile boolean isRecording;

    Thread recThread;

    private Recorder() {

        recBufSize = AudioRecord.getMinBufferSize(frequency,
                channelConfiguration, audioEncoding);  //每次缓冲区能读入最多的字节数
        mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, frequency,
                channelConfiguration, audioEncoding, recBufSize);

    }

    private static Recorder instance = new Recorder();

    public static Recorder getInstance() {
        return instance;
    }
    /**
     * 开始录音
     */
    public void start() {
        mData.clear();
        isRecording = true;
        recThread = new Thread(new recTask());
        recThread.start();
    }

    /**
     * 停止录音
     */
    public void stop() {
        // TODO 自动生成的方法存根
        isRecording = false;
        try {
            recThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        try {
            TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     *
     * @return 获得归一化信号
     */
    public synchronized double[] getNormalizedData() {
        if(mData.size() == 0) {
            return null;
        }
        int dataLength = mData.getFirst().length * (mData.size() - 1) + mData.getLast().length;
        double[] data = new double[dataLength];

        Iterator<short[]> it = mData.listIterator();

        int count = 0;
        while (it.hasNext()) {
            short[] temp = it.next();
            for (int i = 0; i < temp.length; i++){
                data[count + i] = ((double) temp[i]) / Short.MAX_VALUE;
            }
            count += temp.length;
        }

        return data;
    }

    /**
     * 录音线程
     */
    private class recTask implements Runnable {

        @Override
        public void run() {
            try {
                mAudioRecord.startRecording();

            } catch (IllegalStateException e) {
                e.printStackTrace();
            }

            while (isRecording) {
                short[] buffer = new short[recBufSize/2];

                int readLength = mAudioRecord.read(buffer, 0, recBufSize/2);

                synchronized (this) {
                    mData.add(buffer);
                }
            }

            mAudioRecord.stop();
        }
    }

}
