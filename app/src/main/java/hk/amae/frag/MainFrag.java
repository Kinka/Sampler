package hk.amae.frag;


import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.app.Fragment;
import android.os.Handler;
import android.os.Message;
import android.text.method.ScrollingMovementMethod;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import hk.amae.sampler.ChannelAct;
import hk.amae.sampler.MainAct;
import hk.amae.sampler.ModeSettingAct;
import hk.amae.sampler.MonitorAct;
import hk.amae.sampler.R;
import hk.amae.util.Comm;
import hk.amae.util.Comm.Channel;
import hk.amae.util.Command;
import hk.amae.widget.Battery;
import hk.amae.widget.NumberOperator;
import hk.amae.widget.TextProgressBar;

import hk.amae.util.Command.Once;

/**
 * A simple {@link Fragment} subclass.
 */
public class MainFrag extends Fragment implements View.OnClickListener, View.OnTouchListener, AdapterView.OnItemSelectedListener {
    OnMainFragListener mCallback;

    Spinner spinChannel;
    Spinner spinMode;
    TextProgressBar progSampling;
    Activity parent;
    ImageButton btnLock;
    ImageButton btnRun;
    LinearLayout wrapManual, wrapTiming;
    LinearLayout layoutCap, layoutTiming;
    TextView txtSpeed, txtVolume;

    NumberOperator npSpeed, npTiming, npVolume;

    TextView txtTimedSetting;
    TextView txtCountDown;

    TextView txtTips;

    private Channel currChannel;
    private int runningState = Comm.STOPPED; // -1 停止 0 暂停 1 运行
    private boolean isLocked = false;
    private int sampleMode;
    private boolean isSpinnerClick = false;

    private String fmtSpeed = "%d\nmL/min";
    private String fmtVolume = "%.2fL";

    private final String SP_MANUALMODE = "manual_mode"; // 手动情况下设定时长还是设定容量
    private final String SP_SAMPLEMODE = "sample_mode";

    private int __lastid = 0;

    private Timer __battery, __progress, __launch;
    private final int durationBattery = 60*1000, durationProgress = 10*1000;

    private final int UNITSPEED = 1000; // 单通道最高流量
    private int MaxSpeed = UNITSPEED;

    private String[] timedLaunchAt = new String[8];
    private int[] timedDuration = new int[8];
    private int[] timedVolume = new int[8];
    private int[] timedSpeed = new int[8];
    private int waitingGroup = -1;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.frag_main, container, false);

        spinChannel = (Spinner) v.findViewById(R.id.spin_channel);
        spinMode = (Spinner) v.findViewById(R.id.spin_model);
        // channel and mode
        int channels_res = getChannels();

        ArrayAdapter<CharSequence> spinAdapter =
                ArrayAdapter.createFromResource(parent, channels_res, android.R.layout.simple_spinner_item);

        ArrayAdapter<CharSequence> modelAdapter =
                ArrayAdapter.createFromResource(parent, R.array.models_array, android.R.layout.simple_spinner_item);

        spinAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modelAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinChannel.setAdapter(spinAdapter);
        spinMode.setAdapter(modelAdapter);

        // state
        txtSpeed = (TextView) v.findViewById(R.id.txt_speed);
        txtVolume = (TextView) v.findViewById(R.id.txt_volume);

        progSampling = (TextProgressBar) v.findViewById(R.id.prog_sampling);

        btnLock = (ImageButton) v.findViewById(R.id.toggle_lock);
        btnLock.setOnClickListener(this);

        btnRun = (ImageButton) v.findViewById(R.id.toggle_run);
        btnRun.setOnTouchListener(this);

        v.findViewById(R.id.btn_setting).setOnClickListener(this);
        v.findViewById(R.id.btn_connect).setOnClickListener(this);
        v.findViewById(R.id.btn_query).setOnClickListener(this);
        v.findViewById(R.id.btn_clean).setOnClickListener(this);

        v.findViewById(R.id.btn_monitor).setOnClickListener(this);

        txtTimedSetting = (TextView) v.findViewById(R.id.txt_timed_setting);
        txtTimedSetting.setMovementMethod(new ScrollingMovementMethod());

        txtCountDown = (TextView) v.findViewById(R.id.txt_countdown);

        txtTips = (TextView) v.findViewById(R.id.txt_tips);

        npSpeed = (NumberOperator) v.findViewById(R.id.np_speed);
        npTiming = (NumberOperator) v.findViewById(R.id.np_timing);
        npVolume = (NumberOperator) v.findViewById(R.id.np_cap);
        setMaxSpeed(0);

        wrapManual = (LinearLayout) v.findViewById(R.id.wrap_manual);
        wrapTiming = (LinearLayout) v.findViewById(R.id.wrap_timing);

        layoutCap = (LinearLayout) v.findViewById(R.id.layout_capacity);
        layoutTiming = (LinearLayout) v.findViewById(R.id.layout_timing);
        v.findViewById(R.id.label_cap).setOnClickListener(this);
        v.findViewById(R.id.label_timing).setOnClickListener(this);

        spinChannel.setOnItemSelectedListener(this);
        spinMode.setOnItemSelectedListener(this);

        isSpinnerClick = false;

        __lastid = MainAct.lastid;
        MainAct.lastid = 0;

        if (Comm.getIntSP("locked") == 1) {
            isLocked = true;
            setLock();
        }

        return v;
    }

    // 查询并初始化当前通道的运行状态
    void initChannelState() {
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
//                    cmd.ChannelState = Comm.PLAYING;
//                    cmd.Volume = 880;
//                    cmd.Speed = 300;
                progSampling.setProgress(cmd.Progress);
                txtSpeed.setText(String.format(fmtSpeed, cmd.Speed));
                txtVolume.setText(String.format(fmtVolume, cmd.Volume / 1000.0));
                runningState = cmd.ChannelState;
//                runningState = Comm.PLAYING;
                updateRunningState();
            }
        }).reqChannelState(currChannel);
    }

    void init() {
        initChannelState();

        // 主要是想知道是手动模式还是定时模式(定时长/定容量)
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
                sampleMode = cmd.Manual ? Comm.MANUAL_SET : cmd.SampleMode;
                sampleMode = Comm.getIntSP(SP_SAMPLEMODE); // todo 使用服务器的返回结果
                spinMode.setSelection(sampleMode);
                switchSampleMode(cmd);
            }
        }).reqSampleState();

        askBatteryState();
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        parent = activity;
        mCallback = (OnMainFragListener) parent;
    }

    @Override
    public void onStart() {
        super.onStart();
        init();
    }

    @Override
    public void onPause() {
        super.onPause();
        killTimer();
        stopCountDown();
    }
    private void killTimer() {
        try {
            if (__battery != null)
                __battery.cancel();
            if (__progress != null)
                __progress.cancel();
        } catch (Exception e) {

        }
    }
    private void stopCountDown() {
        try {
            if (__launch != null)
                __launch.cancel();
        } catch (Exception e) {

        }
    }
    private void startCountDown() {
        waitingGroup = -1;
        for (int i=0; i<ModeSettingAct.GROUPCOUNT; i++) {
            String diff = Comm.getDateDiff(timedLaunchAt[i]);
            if (diff.indexOf("-") == 0) // launch time passed
                continue;
            waitingGroup = i;
            break;
        }

        if (waitingGroup < 0) return;

        if (__launch != null)
            __launch.cancel();

        __launch = new Timer();
        __launch.schedule(new TimerTask() {
            @Override
            public void run() {
                Comm.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (waitingGroup == -1) return;

                        String diff = Comm.getDateDiff(timedLaunchAt[waitingGroup]);
                        txtCountDown.setText(String.format("第%d组 启动倒计时 %s", waitingGroup+1, diff));
                        if (diff.indexOf("-") == 0) { // 下一组
                            startCountDown();
                        }
                    }
                });
            }
        }, 0, 5*1000);
    }

    private int getMode() {
        String s_mode = Comm.getSP(ChannelAct.SP_CHANNELMODE);
        int mode = 0;
        if (s_mode.length() == 0)
            mode = ChannelAct.MODE_SINGLE;
        else
            mode = Integer.valueOf(s_mode);

        return mode;
    }
    private int getChannels() {
        int mode = getMode();

        switch (mode) {
            case ChannelAct.MODE_COUPLE:
                MaxSpeed = UNITSPEED * 2;
                currChannel = Channel.C1;
                return R.array.channels_C;
            case ChannelAct.MODE_4IN1:
                MaxSpeed = UNITSPEED * 4;
                currChannel = Channel.B1;
                return R.array.channels_B;
            case ChannelAct.MODE_8IN1:
                MaxSpeed = UNITSPEED * 8;
                currChannel = Channel.A1;
                return R.array.channels_A;
            default:
                MaxSpeed = UNITSPEED;
                currChannel = Channel.CH1;
                return R.array.channels_CH;
        }
    }

    private void askBatteryState() {
        __battery = new Timer();
        __battery.schedule(new TimerTask() {
            @Override
            public void run() {
                new Command(new Command.Once() {
                    @Override
                    public void done(boolean verify, Command cmd) {
                        Battery battery = (Battery) parent.findViewById(R.id.battery);
                        if (battery == null) return;

                        battery.setCharging(cmd.Charging);
                        battery.setCapacity(cmd.Power);
                    }
                }).reqBattery();
            }
        }, 0, durationBattery);
    }

    private void setLock() {
        if (isLocked)
            btnLock.setImageResource(R.drawable.lock);
        else
            btnLock.setImageResource(R.drawable.unlock);
        mCallback.onLockToggled(isLocked);
    }
    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.toggle_lock:
                isLocked = !isLocked;
                setLock();
                break;

            case R.id.btn_setting:
            case R.id.btn_connect:
            case R.id.btn_query:
            case R.id.btn_clean:
                mCallback.onButtonClick(view.getId());
                break;
            case R.id.btn_monitor:
                startActivity(new Intent(getActivity(), MonitorAct.class));
                break;
            case R.id.label_timing:
            case R.id.label_cap:
                if (view.getId() == R.id.label_timing) // toggle
                    Comm.setIntSP(SP_MANUALMODE, Comm.TIMED_SET_CAP);
                else
                    Comm.setIntSP(SP_MANUALMODE, Comm.TIMED_SET_TIME);
                initManualMode(null);
                break;
        }
    }

    TimerTask poweroffTask = null;
    TimerTask clickTask = null;
    int clickCnt = 0;
    long lastupTime = 0;
    long touchStartTime = 0;

    private Timer btnRunTimer = new Timer();
    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case 1:
                    Toast.makeText(parent, "关机提醒", Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    if (runningState == Comm.PLAYING) {
                        runningState = Comm.PAUSED;
                    } else {
                        runningState = Comm.PLAYING;
                    }
                    switchRunningState();
                    break;
            }
        }
    };

    /**
     * runningState 反映当前运行状态，但是图标展示的是下一步能进行的操作
     */
    private void __updateRunningState(boolean sendCmd) {
        int op = Comm.DO_PLAY;
        if (runningState == Comm.PAUSED || runningState == Comm.STOPPED) {
            btnRun.setImageResource(R.drawable.play);
            op = runningState == Comm.PAUSED ? Comm.DO_PAUSE : Comm.DO_STOP;
            killTimer();
        } else if (runningState == Comm.PLAYING) {
            int manualMode = Comm.getIntSP(SP_MANUALMODE);
            int cap = manualMode == Comm.TIMED_SET_TIME ? npTiming.getValue() : npVolume.getValue();
            boolean invalid = npSpeed.getValue() == 0 || cap == 0;
            if (sampleMode == Comm.MANUAL_SET && invalid) {
                runningState = Comm.STOPPED;
                Toast.makeText(getActivity(), "参数非法，请检查流量等参数是否为0", Toast.LENGTH_SHORT).show();
                return;
            }
            btnRun.setImageResource(R.drawable.pause);
            op = Comm.DO_PLAY;
        }
        if (!sendCmd) return;

        if (sampleMode == Comm.MANUAL_SET)
            setManual(op);
        else
            setTimed(op);
    }
    private void updateRunningState() {
        __updateRunningState(false);
    }
    // 改变机器的运行状态, 即同步当前的变化到机器侧
    private void switchRunningState() {
        __updateRunningState(true);
    }

    private void setManual(int op) {
        int speed = npSpeed.getValue();
        int manualMode = Comm.getIntSP(SP_MANUALMODE);
        int cap = manualMode == Comm.TIMED_SET_TIME ? npTiming.getValue() : npVolume.getValue();
        // 发送状态切换命令
        // 根据通道设置分配手动设置参数
//        String selected = spinChannel.getSelectedItem().toString();
//        Channel channel = selected.equals("全选") ? Channel.ALL : Channel.init(spinChannel.getSelectedItemPosition() + 1);
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
                progSampling.setProgress(cmd.Progress);
                txtSpeed.setText(String.format(fmtSpeed, cmd.Speed));
                txtVolume.setText(String.format(fmtVolume, cmd.Volume / 1000.0));
            }
        }).setManualChannel(op, manualMode, currChannel, speed, cap);
        // 开始定时查询
        pollManualState();
    }
    private void setTimed(int op) {
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
                progSampling.setProgress(cmd.Progress);
                txtSpeed.setText(String.format(fmtSpeed, cmd.Speed));
                txtVolume.setText(String.format(fmtVolume, cmd.Volume / 1000.0));
            }
        }).setManualChannel(op, 0, currChannel, 0, 0);
        pollTimedState();
    }

    private void reqChannelState() {
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
                progSampling.setProgress(cmd.Progress); // todo 也许定时模式也应该显示进度条的
                txtSpeed.setText(String.format(fmtSpeed, cmd.Speed));
                txtVolume.setText(String.format(fmtVolume, cmd.Volume / 1000.0));
                if (cmd.Progress >= 100) {
                    runningState = Comm.STOPPED;
                    updateRunningState();
                }
            }
        }).reqChannelState(currChannel);
    }
    // 定时模式下的采样状态轮询
    private void pollTimedState() {
        if (__progress != null)
            __progress.cancel();

        if (runningState != Comm.PLAYING)
            return;

        __progress = new Timer();
        __progress.schedule(new TimerTask() {
            @Override
            public void run() {
                reqChannelState();
            }
        }, 500, durationProgress);
    }
    // 查询手动设置下当前采样状态
    private void pollManualState() {
        if (__progress != null)
            __progress.cancel();

        if (runningState != Comm.PLAYING)
            return;

        __progress = new Timer();
        __progress.schedule(new TimerTask() {
            @Override
            public void run() {
//                String selected = spinChannel.getSelectedItem().toString();
//                Channel channel = selected.equals("全选") ? Channel.ALL : Channel.init(spinChannel.getSelectedItemPosition() + 1);
                reqChannelState();

                final String[] State = new String[]{"停止", "等待", "正在采样", "暂停", "完成", "延时等待"};
                new Command(new Once() {
                    @Override
                    public void done(boolean verify, Command cmd) {
                        if (!verify) return;
                        String states = "";
                        for (int i = 0; i < 8; i++) {
//                            cmd.MachineState[i] = (byte) (Math.round(Math.random() * 100) % 4);
                            if (cmd.MachineState[i] >= State.length || cmd.MachineState[i] < 0) continue;

                            states += String.format("通道%d%s ", i + 1, State[cmd.MachineState[i]]);
                            // 如果reqChannelState的进度条不可靠的话，则重新考虑如何更新对应通道的状态
//                            if (currChannel.getValue() == i + 1 && cmd.MachineState[i] == 4) {
//                                runningState = Comm.STOPPED;
//                                updateRunningState();
//                            }
                        }
                        txtTips.setText(states);
                    }
                }).reqMachineState();
            }
        }, 500, durationProgress);
    }

    @Override
    public boolean onTouch(View view, MotionEvent motionEvent) {
        switch (view.getId()) {
            case R.id.toggle_run:
                if (poweroffTask == null)
                    poweroffTask = new TimerTask() {
                        @Override
                        public void run() {
                            Message msg = new Message();
                            msg.what = 1;
                            handler.sendMessage(msg);
                            poweroffTask = null;
                        }
                    };
                if (clickTask == null)
                    clickTask = new TimerTask() {
                        @Override
                        public void run() {
                            Message msg = new Message();
                            msg.what = 2;
                            handler.sendMessage(msg);
                            clickTask = null;
                            clickCnt = 0;
                        }
                    };

                final int CLICKGAP = 150, DBLCLICKGAP = 150;
                if (motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
                    btnRunTimer.schedule(poweroffTask, 3000);

                    touchStartTime = System.currentTimeMillis();

//                    Comm.logI("gap " + (touchStartTime - lastupTime));
                    if (clickCnt==1 && touchStartTime - lastupTime < DBLCLICKGAP) { // 触发双击
                        clickCnt++;
                        clickTask.cancel();
                        clickTask = null;
                        runningState = Comm.STOPPED;
                        Toast.makeText(parent, "已发送停止命令", Toast.LENGTH_SHORT).show();
                        switchRunningState();
                    }
                } else if (motionEvent.getAction() == MotionEvent.ACTION_UP) {

                    long touchEndTime = System.currentTimeMillis();
//                    Comm.logI("passed time " + (touchEndTime - touchStartTime));
                    if (touchEndTime - touchStartTime < 3000) {
                        poweroffTask.cancel();
                        poweroffTask = null;
                    }

                    if (touchEndTime - touchStartTime <= CLICKGAP) { // click
                        clickCnt++;
                        if (clickCnt == 1)
                            btnRunTimer.schedule(clickTask, DBLCLICKGAP);
                        else
                            clickCnt = 0;
                    }

                    lastupTime = System.currentTimeMillis();
                }
                break;
        }
        return false;
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (!isSpinnerClick) {
            isSpinnerClick = true;
            return;
        }

        if (parent.equals(spinChannel)) {
            String selected = spinChannel.getSelectedItem().toString();
            if (selected.equals("全选")) {
                selected = "ALL";
                setMaxSpeed(8*UNITSPEED);
            } else {
                setMaxSpeed(0);
            }
            currChannel = Channel.valueOf(selected);
            initChannelState();

        } else if (parent.equals(spinMode)) {
            String selected = spinMode.getSelectedItem().toString();
            Intent intent = new Intent(getActivity(), ModeSettingAct.class);
            int lastMode = Comm.getIntSP(SP_SAMPLEMODE);
            // selected 选中文字对应spinMode的选项文字，在strings.xml中
            switch (selected) {
                case "手动":
                    sampleMode = Comm.MANUAL_SET;
                    runningState = Comm.STOPPED; // 强制切换状态为停止
                    updateRunningState();
                    intent = null;
                    break;
                case "定时模式":
                    sampleMode = Comm.TIMED_SET_TIME;
                    Comm.setIntSP(SP_MANUALMODE, sampleMode);
                    intent.putExtra("model", ModeSettingAct.TimingSet);
                    break;
                case "定容模式":
                    sampleMode = Comm.TIMED_SET_CAP;
                    Comm.setIntSP(SP_MANUALMODE, sampleMode);
                    intent.putExtra("model", ModeSettingAct.CapacitySet);
                    break;
            }
            Comm.setIntSP(SP_SAMPLEMODE, sampleMode);

            if (__lastid > 0 || lastMode == sampleMode) // 防止切换frag的时候意外触发,比如 设置
                isSpinnerClick = false;
            __lastid = 0; // 仅用于区分初次调用是否从其它frag切换回来

            if (intent != null && isSpinnerClick) {
                startActivityForResult(intent, 0);
            }

            switchSampleMode(null);
            isSpinnerClick = true;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {

    }

    private void switchSampleMode(Command cmd) {
        try {
            if (sampleMode == Comm.MANUAL_SET) {
                wrapManual.setVisibility(View.VISIBLE);
                wrapTiming.setVisibility(View.GONE);
                stopCountDown();
                pollManualState();
                initManualMode(cmd);
            } else {
                wrapManual.setVisibility(View.GONE);
                wrapTiming.setVisibility(View.VISIBLE);
                pollTimedState();
                initTimedMode();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            updateRunningState();
        }
    }

    private void setMaxSpeed(int maxSpeed) {
        if (maxSpeed == 0)
            npSpeed.setMax(MaxSpeed);
        else
            npSpeed.setMax(maxSpeed);
        if (npSpeed.getValue() > npSpeed.getMax())
            npSpeed.setValue(npSpeed.getValue());
    }

    /**
     * 手动模式下的相关设置，切换模式为"设定时长"或"设定容量"， 以及最大流量/时长/容量等限制
     * @param cmd
     */
    private void initManualMode(Command cmd) {
        int targetSpeed = cmd == null ? 0 : cmd.TargetSpeed;
        int targetDuration = cmd == null ? 0 : cmd.TargetDuration;

        if (targetSpeed == 0 || targetSpeed > npSpeed.getMax())
            targetSpeed = npSpeed.getValue();
        if (targetDuration == 0)
            targetDuration = npTiming.getDefault();

        npSpeed.setValue(targetSpeed);

        int manualMode = Comm.getIntSP(SP_MANUALMODE);
        if (manualMode == 0) {
            manualMode = Comm.TIMED_SET_CAP;
            Comm.setIntSP(SP_MANUALMODE, manualMode);
        }

        if (manualMode == Comm.TIMED_SET_CAP) {
            layoutCap.setVisibility(View.VISIBLE);
            layoutTiming.setVisibility(View.GONE);
            npVolume.setValue(targetDuration * targetSpeed);
            npVolume.setDelta(100);
        } else if (manualMode == Comm.TIMED_SET_TIME) {
            layoutCap.setVisibility(View.GONE);
            layoutTiming.setVisibility(View.VISIBLE);
            npTiming.setValue(targetDuration);
            npTiming.setDelta(1);
        }
    }

    private void initTimedMode() {
        // 查询定时设置，并开始倒计时
        new Command(new Once() {
            @Override
            public void done(boolean verify, Command cmd) {
                String str = "";
                boolean isSetCap = sampleMode == Comm.TIMED_SET_CAP;
                String strMode = isSetCap ? "容量" : "时长";
                String strUnit = isSetCap ? "mL" : "min";
                String strFmt = "第%d组 %s启动 " + strMode + "：%d" + strUnit + " 流量：%dmL/min\n";
                cmd.DateTime = "2015-05-31 22:35";
                cmd.TargetVolume = 1000;
                cmd.TargetSpeed = 100;
                cmd.TargetDuration = 1;
                for (int i=0; i<ModeSettingAct.GROUPCOUNT; i++) {
                    timedLaunchAt[i] = cmd.DateTime;
                    timedDuration[i] = cmd.TargetDuration;
                    timedVolume[i] = cmd.TargetVolume;
                    timedSpeed[i] = cmd.TargetSpeed;
                    str += String.format(strFmt, (i+1), cmd.DateTime, isSetCap ? cmd.TargetVolume : cmd.TargetDuration, cmd.TargetSpeed);
                }
                txtTimedSetting.setText(str);

                timedLaunchAt[0] = "2015-05-31 22:09";
                startCountDown();
            }
        }).reqTimedSetting(sampleMode, currChannel, 0);
    }

    public interface OnMainFragListener {
        void onLockToggled(boolean locked);
        void onButtonClick(int id);
    }
}
