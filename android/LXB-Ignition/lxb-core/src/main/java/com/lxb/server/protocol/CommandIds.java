package com.lxb.server.protocol;

/**
 * Protocol command IDs shared by server modules.
 * Keep aligned with Python constants in src/lxb_link/constants.py.
 */
public final class CommandIds {

    private CommandIds() {}

    // Link layer
    public static final byte CMD_HANDSHAKE = 0x01;
    public static final byte CMD_ACK = 0x02;
    public static final byte CMD_HEARTBEAT = 0x03;

    // Input layer
    public static final byte CMD_TAP = 0x10;
    public static final byte CMD_SWIPE = 0x11;
    public static final byte CMD_LONG_PRESS = 0x12;
    public static final byte CMD_UNLOCK = 0x1B;
    public static final byte CMD_SET_TOUCH_MODE = 0x1C;
    public static final byte CMD_SET_SCREENSHOT_QUALITY = 0x1D;

    // Input extension
    public static final byte CMD_INPUT_TEXT = 0x20;
    public static final byte CMD_KEY_EVENT = 0x21;

    // Sense layer
    public static final byte CMD_GET_ACTIVITY = 0x30;
    public static final byte CMD_DUMP_HIERARCHY = 0x31;
    public static final byte CMD_FIND_NODE = 0x32;
    public static final byte CMD_DUMP_ACTIONS = 0x33;
    public static final byte CMD_GET_SCREEN_STATE = 0x36;
    public static final byte CMD_GET_SCREEN_SIZE = 0x37;
    public static final byte CMD_FIND_NODE_COMPOUND = 0x39;

    // Lifecycle layer
    public static final byte CMD_LAUNCH_APP = 0x43;
    public static final byte CMD_STOP_APP = 0x44;
    public static final byte CMD_LIST_APPS = 0x48;

    // Media layer
    public static final byte CMD_SCREENSHOT = 0x60;
    public static final byte CMD_IMG_REQ = 0x61;
}
