package uk.co.rovcompanion;

import java.util.UUID;

/** Wire constants and encoding observed in ROV Motion 1.0.1; not hardware verified. */
public final class Protocol {
    private Protocol() {}
    public static UUID uuid(String prefix) {
        return UUID.fromString(prefix + "-A74C-4BE9-9571-8303E456054A");
    }
    public static final UUID CARRIAGE = uuid("BD08A000");
    public static final UUID POSITION = uuid("BD08A001");
    public static final UUID TARGET = uuid("BD08A002");
    public static final UUID SPEED = uuid("BD08A003");
    public static final UUID RAMP = uuid("BD08A004");
    public static final UUID STATUS = uuid("BD08A005");
    public static final UUID COMMAND = uuid("BD08A006");
    public static final UUID MAX_SPEED = uuid("BD08A007");
    public static final UUID MIN_SPEED = uuid("BD08A008");
    public static final UUID MAX_STEPS = uuid("BD08A009");
    public static final UUID SHUTTER_SERVICE = uuid("BD08B000");
    public static final UUID SHUTTER_COMMAND = uuid("BD08B003");
    public static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    public static final int START = 2, STOP = 4, LOOP_ON = 8, LOOP_OFF = 16;
    public static final int MOVING = 8, LOOP_ACTIVE = 32, OVERCURRENT = 16;
    public static final int DEFAULT_END_CLEARANCE = 5, MIN_END_CLEARANCE = 2, MAX_END_CLEARANCE = 15;
    public static final class TravelRange {
        public final int left, right, tolerance;
        private TravelRange(int left, int right, int tolerance) {
            this.left = left; this.right = right; this.tolerance = tolerance;
        }
    }
    /** App-side limits only: physical travel/calibration is never rewritten. */
    public static TravelRange quietRange(int travel, int clearancePercent) {
        if (travel < 3 || travel > 1000000 || clearancePercent < MIN_END_CLEARANCE || clearancePercent > MAX_END_CLEARANCE) {
            throw new IllegalArgumentException("Invalid quiet travel limits");
        }
        return workingRange(travel, clearancePercent, 100-clearancePercent);
    }
    public static TravelRange workingRange(int travel, int startPercent, int endPercent) {
        if (travel < 3 || travel > 1000000 || startPercent < 0 || startPercent > 100 || endPercent < 0 || endPercent > 100 || endPercent-startPercent < 10) {
            throw new IllegalArgumentException("Choose an ordered range inside 0–100%, at least 10% wide");
        }
        int left=(int)(((long)travel*startPercent+99)/100);
        int right=(int)((long)travel*endPercent/100);
        if (right<=left) throw new IllegalArgumentException("Not enough working travel");
        int clearance=Math.min(left,travel-right);
        return new TravelRange(left,right,clearance==0?Math.min(4,(right-left)/4):Math.min(20,clearance/4));
    }

    public static byte[] int32(int value) {
        return new byte[] {(byte)value, (byte)(value >>> 8), (byte)(value >>> 16), (byte)(value >>> 24)};
    }
    public static int uintValue(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > 4) {
            throw new IllegalArgumentException("Expected 1–4 value bytes");
        }
        int result = 0;
        for (int i = 0; i < bytes.length; i++) result |= (bytes[i] & 255) << (i * 8);
        return result;
    }
    public static byte[] command(int value) { return new byte[] {(byte)value}; }
    public static boolean idle(int status) { return (status & (MOVING | LOOP_ACTIVE)) == 0; }
    public static int speedForPercent(int percent, int min, int max) {
        if (percent < 0 || percent > 100 || min < 1 || max < min) {
            throw new IllegalArgumentException("Invalid speed limits");
        }
        return min + (int)((long)(max - min) * percent / 100);
    }
}
