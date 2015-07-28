package com.example.eleven.lock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.media.AudioFormat;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.example.eleven.speechlibrary.DTWrecognize;
import com.example.eleven.speechlibrary.MFCC;
import com.example.eleven.speechlibrary.Preprocess;
import com.example.eleven.speechlibrary.Recorder;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Created by Eleven on 2015/7/28.
 */
public class UnlockActivity extends Activity {

    private TextView hints;
    private Button btn_speak;
    private Button btn_cancel;

    private AlertDialog alertDialog;

    private static double[][] firstMfcc; //保存的mfcc参数
    private static double[][] secondMfcc;

    private double[][] inputMfcc; //输入的mfcc参数

    private Recorder recorder;

    static final int frequency = 22050;
    static final int channelConfiguration = AudioFormat.CHANNEL_IN_MONO;
    static final int audioEncoding = AudioFormat.ENCODING_PCM_16BIT;

    private final int NUM_OF_MFCC = 12;
    private final int NUM_OF_FILTER = 26;

    Future<double[][]> future1;
    Future<double[][]> future2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.recordview);

        recorder = new Recorder(frequency, channelConfiguration, audioEncoding);

        hints = (TextView) findViewById(R.id.hints);
        hints.setText(R.string.unlock_hints);

        btn_speak = (Button) findViewById(R.id.btn_speak);
        btn_cancel = (Button) findViewById(R.id.btn_cancel);

        ExecutorService executorService = Executors.newCachedThreadPool();
        future1 = executorService.submit(new readDataTask("firstMfcc.dat", this));
        future2 = executorService.submit(new readDataTask("secondMfcc.dat", this));

        btn_cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btn_speak.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    AlertDialog.Builder adBuilder = new AlertDialog.Builder(UnlockActivity.this);
                    adBuilder.setMessage(R.string.recording)
                            .setCancelable(false)
                            .setPositiveButton(R.string.confirm, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.cancel();
                                }
                            });
                    alertDialog = adBuilder.create();
                    alertDialog.show();//显示对话框
                    alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(false);

                    recorder.start();//开始录音
                } else if (event.getAction() == MotionEvent.ACTION_UP) {
                    recorder.stop();
                    String strProcessing = getString(R.string.processing);
                    alertDialog.setMessage(strProcessing);

                    ExecutorService executor = Executors.newCachedThreadPool();
                    Future<double[][]> future = executor.submit(new DataProcess());//处理数据，获得第一个语音的mfcc参数

                    try {
                        inputMfcc = future.get();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (ExecutionException e) {
                        e.printStackTrace();
                    }

                    if (inputMfcc == null) {
                        String strNoSpeech = getString(R.string.no_speech);
                        alertDialog.setMessage(strNoSpeech);
                        alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                    } else {
                        try {
                            firstMfcc = future1.get();
                            secondMfcc = future2.get();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        } catch (ExecutionException e) {
                            e.printStackTrace();
                        }

                        if (firstMfcc == null || secondMfcc == null) {
                            alertDialog.setMessage("未能找到声音锁，请重新设置");
                            alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                        } else {
                            double dis1 = DTWrecognize.dtw(firstMfcc, inputMfcc);
                            double meanErr1 = dis1 * 2 / (firstMfcc.length + inputMfcc.length);
                            double dis2 = DTWrecognize.dtw(secondMfcc, inputMfcc);
                            double meanErr2 = dis2 * 2 / (secondMfcc.length + inputMfcc.length);

                            if ((meanErr1 + meanErr2) / 2 > 40) {
                                String strFail = getString(R.string.unlock_fail);
                                alertDialog.setMessage(strFail);
                                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                            } else {
                                String strSucess = getString(R.string.unlock_sucess);
                                alertDialog.setMessage(strSucess);
                                alertDialog.getButton(DialogInterface.BUTTON_POSITIVE).setEnabled(true);
                            }
                        }
                    }
                }
                return false;
            }
        });
    }

    /**
     * 获得mfcc参数
     */
    private class DataProcess implements Callable<double[][]> {

        @Override
        public double[][] call() throws Exception {
            double[] nomalizeData = recorder.getNormalizedData();
            //预加重信号
            double[] preEmphasis = Preprocess.highpass(nomalizeData);
            //短时能量和短时过零率
            double[] stApm = Preprocess.shortTernEnergy(preEmphasis, frequency);
            double[] stCZ = Preprocess.shortTernCZ(preEmphasis, frequency);
            //端点检测
            ArrayList<Integer> endPoints = null;
            endPoints = Preprocess.divide(stApm, stCZ);

            if (endPoints.size() < 2) {
                return null;
            }

            ArrayList<double[]> speechFrames = new ArrayList<>();

            for (int i = 0; i < endPoints.size(); i = i + 2){
                for (int j = endPoints.get(i); j < endPoints.get(i + 1); ++j) {
                    double[] frame = new double[512];
                    System.arraycopy(preEmphasis, 256 * j, frame, 0, 512);
                    Preprocess.hamming(frame);
                    speechFrames.add(frame);
                }
            }

            double[][] mfcc = new double[speechFrames.size()][NUM_OF_MFCC];
            for (int i = 0; i < speechFrames.size(); ++i) {
                double[] fftData = MFCC.rFFT(speechFrames.get(i), 512);
                double[] mfccData = MFCC.mfcc(fftData, 512, NUM_OF_FILTER, NUM_OF_MFCC, frequency);
                mfcc[i] = mfccData;
            }
            double[][] dtMfcc = MFCC.diff(mfcc, 2);
            double[][] result = new double[speechFrames.size()][2 * NUM_OF_MFCC];
            for (int i = 0; i < result.length; i++){
                System.arraycopy(mfcc[i], 0, result[i], 0, NUM_OF_MFCC);
                System.arraycopy(dtMfcc[i], 0, result[i], NUM_OF_MFCC, NUM_OF_MFCC);
            }
            return result;
        }
    }

    /**
     * 读入mfcc数据
     */
    private static class readDataTask implements Callable<double[][]> {
        String filename;
        WeakReference<UnlockActivity> weakReference;

        public readDataTask(String filename, UnlockActivity unlockActivity) {
            this.filename = filename;
            this.weakReference = new WeakReference<>(unlockActivity);
        }

        @Override
        public double[][] call() throws Exception {
            UnlockActivity thisActivity = weakReference.get();
            if (thisActivity != null) {
                FileInputStream fis = null;
                try {
                    fis = thisActivity.openFileInput(filename);
                    DataInputStream dis = new DataInputStream(new BufferedInputStream(fis));
                    List<double[]> datas = new ArrayList<>();
                    while (dis.available() > 0) {
                        double[] mfccData = new double[2 * thisActivity.NUM_OF_MFCC];
                        for (int i = 0; i < mfccData.length; i++) {
                            mfccData[i] = dis.readDouble();
                        }
                        datas.add(mfccData);
                    }
                    double[][] result = new double[datas.size()][2 * thisActivity.NUM_OF_MFCC];
                    for (int i = 0; i < datas.size(); i++){
                        for (int j = 0; j < 2 * thisActivity.NUM_OF_MFCC; j++){
                            result[i][j] = datas.get(i)[j];
                        }
                    }
                    return result;
                } catch (FileNotFoundException e) {
                    e.printStackTrace();
                } finally {
                    fis.close();
                }
            }
            return null;
        }
    }
}
