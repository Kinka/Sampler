package hk.amae.util;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.crypto.Mac;

import hk.amae.sampler.ModeSettingAct;
import hk.amae.util.Comm.Channel;

import hk.amae.widget.SettingItem;

/**
 * Created by kinka on 4/11/15.
 */
public class Command {
    public static int CHANNELCOUNT = 8;

    private final static Object __TOTAL = new Object();
    private final static Object __SUCC = new Object();
    private static int __total = 0;
    private static int __succ = 0;
    byte Version = 1;
    private int __cmd;

    // http://introcs.cs.princeton.edu/java/51data/CRC16CCITT.java.html
    public static short crc16(byte[] bytes) {
        return new Command(null).crc16_kermit(bytes, bytes.length);
    }
    private short crc16_ccitt(byte[] bytes, int end) {
        int crc = 0xFFFF;          // initial value
        int polynomial = 0x1021;   // 0001 0000 0010 0001  (0, 5, 12)

        for (int k=0; k<end; k++) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((bytes[k]   >> (7-i) & 1) == 1);
                boolean c15 = ((crc >> 15    & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit) crc ^= polynomial;
            }
        }

        crc &= 0xffff;

        return (short) crc;
    }

    private short P_KERMIT =  (short) 0x8408;
    private short crc_tabkermit[] = null;
    private void init_crckermit_tab() {
        crc_tabkermit = new short[256];
        short crc, c;

        for (int i=0; i<0x100; i++) {
            crc = 0;
            c = (short) (i & 0xff);

            for (int j=0; j<8; j++) {
                if (((crc ^ c) & 0x0001) > 0)
                    crc = (short) (((crc & 0xffff) >> 1) ^ P_KERMIT);
                else
                    crc = (short) ((crc & 0xffff) >> 1);
                c = (short) ((c & 0xffff) >> 1);
            }

            crc_tabkermit[i] = crc;
        }
    }
    private int crc16_update(int crc, byte c) {
        if (crc_tabkermit == null) init_crckermit_tab();

        short tmp, short_c;
        short_c = (short) (0xff & c);
        tmp = (short) (crc ^ short_c);
        crc = ((crc & 0xffff )>> 8) ^ crc_tabkermit[tmp & 0xff];
        return crc;
    }
    private short crc16_kermit(byte[] bytes, int end) {
        int crc = 0;
        for (int i=0; i<end; i++) {
            crc = crc16_update(crc, bytes[i]);
        }
        return (short) (crc & 0xffff);
    }

    private String getString(ByteBuffer reply, String encoding) {
        int len = reply.get();
        if (len <= 0)
            return "";
        byte[] data = new byte[len];
        reply.get(data);
        try {
            return encoding == null ? new String(data) : new String(data, encoding);
        } catch (Exception e) {
            return new String(data);
        }
    }
    private String getString(ByteBuffer reply) {
        return getString(reply, null);
    }
    private ByteBuffer build(final int cmd, byte[] data) {
        __cmd = cmd;
        if (data == null) data = new byte[0];
//if (cmd > 3) return null;
//        Comm.logI("req cmd 0x" + Integer.toHexString(cmd));
        final ByteBuffer buf = ByteBuffer.allocate(3 + 1 + 2 + 2 + data.length + 2);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0xaa);
        buf.put((byte) 0xaf);
        buf.put((byte) 0xfa);
        buf.put(Version);
        buf.putShort((short) cmd);
        buf.putShort((short) data.length);
        buf.put(data);

        short crc = crc16_kermit(buf.array(), buf.capacity() - 2); // crc16-CCITT
        buf.putShort(crc);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean verify = resolve(buf, cmd);
                if (Command.this.once == null)
                    return;
                Comm.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Command.this.once.done(verify, Command.this);
                        } catch (Exception e) {

                        }
                    }
                });
            }
        }).start();

        return buf;
    }
    public static void PacketLost() {
        System.out.println(String.format("succ/total: %d/%d, packet lost %.1f%%", __succ, __total, (__total - __succ)*100.0/__total));
    }
    private boolean resolve(ByteBuffer buf, int cmd) {
        synchronized (__TOTAL) {
            __total++;
        }

        int timeout = cmd == 0x101 ? 2000 : 0;
        ByteBuffer reply = Deliver.send(buf, timeout);
        if (reply.limit() == 0) return false;

        synchronized (__SUCC) {
            __succ++;
        }

        reply.getShort();
        reply.get(); // == 0xfa?
        reply.get(); // version
        int __cmd = reply.getShort(); // cmd
//        Comm.logI("reply cmd 0x" + Integer.toHexString(cmd));

        try {
            int len = reply.getShort();
            switch (cmd) {
                case 0x1:
                    resolveModel(reply);
                    break;
                case 0x2:
                    resolveATM_TEMP(reply);
                    break;
                case 0x3:
                case 0x104:
                    resolveDateTime(reply);
                    break;
                case 0x4:
                    resolveBattery(reply);
                    break;
                case 0x5:
                    resolveChannelState(reply);
                    break;
                case 0x6:
                    resolveMachineState(reply);
                    break;
                case 0x7:
                    resolveSampleState(reply);
                    break;
                case 0x8:
                    resolveSampleHistory(reply);
                    break;
                case 0x9:
                    resolveSampleData(reply);
                    break;
                case 0xa:
                    resolveSysInfo(reply);
                    break;
                case 0xb:
                    resolveTimedSetting(reply);
                    break;
                case 0x101:
                    resolveManualChannel(reply);
                    break;
                case 0x102:
                    resolveTimedChannel(reply);
                    break;
                case 0x103:
                    resolveBacklit(reply);
                    break;
                case 0x105:
                    resolveRestore(reply);
                    break;
                case 0x106:
                    resolveClearSample(reply);
                    break;
                case 0x107:
                    resolveAdjust(reply);
                    break;
                case 0xc:
                case 0x10c:
                    resolveChannelMode(reply);
                    break;
                case 0x10b:
                    resolveClean(reply);
                    break;
            }

            // 校验sum
            short recv_crc = reply.getShort();
            short crc = crc16_kermit(reply.array(), reply.arrayOffset());
        } catch (Exception e) {

        }

        return true;
//        return crc == sum;
    }

    public interface Once {
        void done(boolean verify, Command cmd);
    }
    private Once once;
    public Command(Once once) {
        this.once = once;
    }

    public String Model = ""; // 型号
    public String SN = ""; // 序列号
    public ByteBuffer reqModel() { // 查询型号
/*        String model = "EM-2008";
        String sn = "021547";
        ByteBuffer buffer = ByteBuffer.allocate(1 + model.length() + 1 + sn.length());
        buffer.put((byte) model.length());
        buffer.put(model.getBytes());
        buffer.put((byte) sn.length());
        buffer.put(sn.getBytes());
        return build(Version, 0x1, buffer.array());*/
        return build(0x1, null);
    }
    public void resolveModel(ByteBuffer reply) {
        Model = getString(reply);
        SN = getString(reply);
    }

    public float ATM, TEMP;
    public ByteBuffer reqATM_TEMP() { // 查询大气压和温度
        return build(0x2, null);
    }
    public void resolveATM_TEMP(ByteBuffer reply) {
        ATM = reply.getInt() % (1000*1000) / 1000f;
        TEMP = reply.getInt() % (1000*10) / 10f;
    }

    public String DateTime;
    public ByteBuffer reqDateTime() { // 查询日期和时间
        return build(0x3, null);
    }
    public void resolveDateTime(ByteBuffer reply) {

        DateTime = getString(reply);
    }

    public boolean Charging = false;
    public int Power = 0;
    public ByteBuffer reqBattery() { // 查询充电状态和电量
        return build(0x4, null);
    }
    public void resolveBattery(ByteBuffer reply) {
        Charging = (reply.get() == 1);
        Power = reply.get();
    }

    public int ChannelState;
    public Channel Channel;
    public int Speed;
    public int Volume;
    public ByteBuffer reqChannelState(Channel channel) { // 查询实时流量和已采样
        return build(0x5, new byte[] {channel == null ? 0 : channel.getValue()});
    }
    public void resolveChannelState(ByteBuffer reply) {
        ChannelState = reply.get();
        Channel = Comm.Channel.init(reply.get());
        Speed = reply.getShort();
        Volume = reply.getInt();
        Progress = reply.get();
    }

    public byte[] MachineState;
    public ByteBuffer reqMachineState(int channelCount) { // 查询机器工作状态
        MachineState = new byte[channelCount];
        return build(0x6, null);
    }
    public void resolveMachineState(ByteBuffer reply) {
        for (int i=0; i<MachineState.length; i++)
            MachineState[i] = reply.get();
    }

    public String SampleID; // 采样编号
    public int TargetSpeed; // 设定流量/流速
    public int SampleMode; // 采样模式(定时or定容)
    public int StandardVol; // 累计标体(临时命名)
    public byte Progress; // 采样进度
    public int Elapse; // 已采样时间
    public int TargetDuration; // 设定时间
    public boolean Manual; // 手动/定时
    public byte Group; // 定时模式 第几组 手动模式为0
    public ByteBuffer reqSampleState(Channel channel) { // 查询当前采样情况
        return build(0x7, new byte[] {channel == null ? 0 : channel.getValue()});
    }
    public void resolveSampleState(ByteBuffer reply) {
        SampleID = getString(reply);
        Speed = reply.getShort();
        TargetSpeed = reply.getShort();
        Volume = reply.getInt();
        StandardVol = reply.getInt();
        SampleMode = ManualMode = reply.get();
        DateTime = getString(reply);
        ATM = reply.getInt() / 1000f;
        TEMP = reply.getInt() / 10f;
        Progress = reply.get();
        Elapse = reply.getInt();
        TargetVolume = TargetDuration = reply.getInt();
        Channel = Comm.Channel.init(reply.get());
        Manual = reply.get() == 1;
        Group = reply.get();
        GPS = formatGPS(getString(reply));
    }

    public String[] History;
    public int HistorySize;
    public ByteBuffer reqSampleHistory(int start, int end) { // 查询历史采样数据编号
        ByteBuffer buffer = ByteBuffer.allocate(2 + 2);
        buffer.putShort((short) start);
        buffer.putShort((short) end);
        return build(0x8, buffer.array());
    }
    public void resolveSampleHistory(ByteBuffer reply) {
        HistorySize = reply.getShort();
        int len = reply.getShort();
//        HistorySize = 45;
//        int len = 20;
        History = new String[len];
        for (int i=0; i<History.length; i++) {
            History[i] = getString(reply);
//            History[i] = "20151017"+(i+1);
        }
    }

    public ByteBuffer reqSampleData(String item) { // 查询编号对应数据
        if (item == null || item.isEmpty())
            return build(0x9,null);
        ByteBuffer buffer = ByteBuffer.allocate(1 + item.length());
        buffer.put((byte) item.length());
        buffer.put(item.getBytes());
        return build(0x9, buffer.array());
    }
    public String GPS; // gps 信息
    public void resolveSampleData(ByteBuffer reply) {
        resolveSampleState(reply);
    }

    public String SoftwareVer; // 软件
    public String HardwareVer; // 硬件版本
    public String ChannelCount; // 8通道
    public String ChannelCap; // 通道容量
    public String Storage; // 存储容量
    public ByteBuffer reqSysInfo() { // 查询系统信息
        return build(0xa, null);
    }
    public void resolveSysInfo(ByteBuffer reply) {
        Model = getString(reply);
        SoftwareVer = getString(reply);
        ChannelCount = getString(reply, "gbk");

        ChannelCap = getString(reply);
        Storage = getString(reply);
        HardwareVer = getString(reply);
    }

//    public int SettingNum; // 第几条设置
//    public int TargetVolume;
    public SettingItem[] SettingItems;
    public void resolveTimedSetting(ByteBuffer reply) {
        SettingItems = new SettingItem[ModeSettingAct.GROUPCOUNT];

        for (int i=0; i<SettingItems.length; i++)
            SettingItems[i] = new SettingItem(i+1);

        try {
            SampleMode = reply.get();
            Channel = Comm.Channel.init(reply.get());
            for (SettingItem item:SettingItems) {
                item.isSet = reply.get() == 1;
                item.targetSpeed = reply.getShort();
                item.targetVol = item.targetDuration = reply.getInt(); // 根据SampleMode 再去作区分吧
                DateTime = getString(reply);
                int pos = DateTime.indexOf(" ");
                item.date = DateTime.substring(0, pos);
                item.time = DateTime.substring(pos+1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    public ByteBuffer reqTimedSetting(int mode, Channel channel) { // 查询定时(定容)设置
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1);
        buffer.put((byte) mode);
        buffer.put(channel == null ? 0 : channel.getValue());
        return build(0xb, buffer.array());
    }

    public int ChannelMode;
    // 查询通道合并模式
    public ByteBuffer reqChannelMode() {
        return build(0xc, null);
    }
    public void resolveChannelMode(ByteBuffer reply) {
        ChannelMode = reply.get();
    }

    public ByteBuffer setChannelMode(int channelMode) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) channelMode);
        return build(0x10d, buffer.array());
    }

    // 设置采样参数(手动模式)
    public int TargetVolume; // 设定容量
    public int ManualMode; // 手动模式下的定时长/定容量
    public ByteBuffer setManualChannel(int operation, int mode, Channel channel, int speed, int cap) {
        byte[] gps = formatGPS(null).getBytes();
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 1 + 2 + 4 + 1 + gps.length);
        buffer.put((byte) operation);
        buffer.put((byte) mode);
        buffer.put(channel == null ? 0 : channel.getValue());
        buffer.putShort((short) speed);
        buffer.putInt(cap); // 时长或者容量

        buffer.put((byte) gps.length);
        buffer.put(gps);

        return  build(0x101, buffer.array());
    }
    public void resolveManualChannel(ByteBuffer reply) {
        ChannelState = reply.get();
        ManualMode = reply.get();
        Channel = Comm.Channel.init(reply.get());
        TargetSpeed = reply.getShort();
        TargetDuration = TargetVolume = reply.getInt();
    }

    // 设置采样参数(定时定容)
    public ByteBuffer setTimedChannel(boolean doSet, int mode, Channel channel, SettingItem[] items) {
        byte[] gps = formatGPS(null).getBytes();
        int len = 1 + 1 + 1 + (1+2+4+1+16)*8 + 1 + gps.length;
        ByteBuffer buffer = ByteBuffer.allocate(len);
        buffer.put((byte) (doSet ? 1 : 2));
        buffer.put((byte) mode);
        buffer.put(channel == null ? 0 : channel.getValue());

        for (SettingItem item:items) {
            buffer.put((byte) (item.isSet ? 1 : 2));
            buffer.putShort((short) item.targetSpeed);
            buffer.putInt(mode == Comm.TIMED_SET_CAP ? item.targetVol : item.targetDuration);
            String datetime = item.date + " " + item.time;
            buffer.put((byte) datetime.length());
            buffer.put(datetime.getBytes());
        }

        buffer.put((byte) gps.length);
        buffer.put(gps);

        return build(0x102, buffer.array());
    }
    public boolean DoSet;
    public void resolveTimedChannel(ByteBuffer reply) {
        DoSet = reply.get() == 1;

        resolveTimedSetting(reply);
    }

    // 设置背光
    public ByteBuffer setBacklit(boolean doSet, int normal, int saving) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 2);
        buffer.put((byte) (doSet ? 1 : 0));
        buffer.put((byte) normal);
        buffer.put((byte) saving);
        return build(0x103, buffer.array());
    }
    public byte NormalBacklit;
    public byte SavingBacklit;
    public void resolveBacklit(ByteBuffer reply) {
        NormalBacklit = reply.get();
        SavingBacklit = reply.get();
    }

    // 设置时间
    public ByteBuffer setDateTime(String dateTime) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + dateTime.length());
        buffer.put((byte) dateTime.length());
        buffer.put(dateTime.getBytes());
        return build(0x104, buffer.array());
    }
    // see resolveDateTime

    // 恢复出厂设置
    public ByteBuffer setRestore(boolean doSet) {
        return build(0x105, new byte[] {(byte) (doSet ? 1 : 2)});
    }
    public void resolveRestore(ByteBuffer reply) {
        DoSet = reply.get() == 1; // 恢复命令 or 查询恢复进度
        Progress = reply.get();
    }

    // 清空采样数据
    public ByteBuffer setClearSample(boolean doSet) {
        return build(0x106, new byte[] {(byte) (doSet ? 1 : 2)});
    }
    public void resolveClearSample(ByteBuffer reply) {
        DoSet = reply.get() == 1; // 清空命令 or 查询清空进度
        Progress = reply.get();
    }

    // 流量校准
    public ByteBuffer setAdjust(int action, Channel channel, int adjustPressure, int adjustSpeed) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + 2 + 2 + 2 + 2 + 2 + 2);
        buffer.put((byte) action);
        buffer.put(channel.getValue());
        buffer.putShort((short) 0); // 动力输出
        buffer.putShort((short) 0); // 百分比
        buffer.putShort((short) 0); // 采集压力
        buffer.putShort((short) 0); // 电压
        buffer.putShort((short) adjustPressure);
        buffer.putShort((short) adjustSpeed);
        return build(0x107, buffer.array());
    }
    public int AdjustStatus; // 状态
    public int OutputPower; // 动力输出
    public int DutyCycle; // 占空比
    public int PickPower; // 采集压力
    public int PickVoltage; // 电压
    public int AdjustPressure; // 预估压力
    public int AdjustSpeed; // 被校流量
    public void resolveAdjust(ByteBuffer reply) {
        reply.get();
        Channel = Comm.Channel.init(reply.get());
        OutputPower = reply.getShort();
        DutyCycle = reply.getShort();
        PickPower = reply.getShort();
        PickVoltage = reply.getShort();
        AdjustPressure = reply.getShort();
        AdjustSpeed = reply.getShort();
        AdjustStatus = reply.get(); // 1  正在校准 2 停止校准
    }

    // 锁定界面
    public ByteBuffer setScreenLock(boolean doLock) {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) (doLock ? 1 : 0));
        return build(0x108, buffer.array());
    }

    // 打印某一条数据
    public ByteBuffer printSample(String sampleId) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + sampleId.length());
        buffer.put((byte) sampleId.length());
        buffer.put(sampleId.getBytes());
        return build(0x109, buffer.array());
    }

    // 定时关机
    public ByteBuffer setShutdown(boolean rightnow, String dateTime) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + 1 + dateTime.length());
        buffer.put((byte) (rightnow ? 1 : 0));
        buffer.put((byte) dateTime.length());
        buffer.put(dateTime.getBytes());
        return build(0x10a, buffer.array());
    }

    // 清洗
    public int CleanTime; // 清洗时长
    public ByteBuffer setClean(boolean doOrCancel) {
        byte[] data = new byte[] {(byte) (doOrCancel ? 1: 2)};
        return build(0x10b, data);
    }
    public void resolveClean(ByteBuffer reply) {
        CleanTime = reply.getShort();
    }

    public void setSN(String sn) {
        ByteBuffer buffer = ByteBuffer.allocate(1 + sn.length());
        buffer.put((byte) sn.length());
        buffer.put(sn.getBytes());
        build(0x10c, buffer.array());
    }

    private String formatGPS(String gps) {
        if (gps == null) {
            gps = Comm.getSP("gps");
            return gps.replace("\n", ","); // 发送
        } else {
            return gps.replace(",", "\n"); // 接收
        }
    }
}
