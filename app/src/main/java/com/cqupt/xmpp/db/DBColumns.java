package com.cqupt.xmpp.db;

/**
 * Created by tiandawu on 2016/4/10.
 */
public class DBColumns {

    /**
     * 消息表
     */
    public static final String MSG_TABLE_NAME = "message";//表名
    public static final String MSG_FROM = "msg_from";//消息来自谁
    public static final String MSG_TO = "msg_to";//消息发给谁
    public static final String MSG_BODY = "msg_body";//消息体
    public static final String MSG_TIME = "msg_time";//消息收发时间
    public static final String MSG_OWNER = "msg_owner";//消息属于谁


    /**
     * 会话表
     */
    public static final String SESSION_TABLE_NAME = "session";//表名
    public static final String SESSION_FROM = "session_from";//会话来自谁
    public static final String SESSION_TO = "session_to";//会话发给谁
    public static final String SESSION_BODY = "session_body";//会话体
    public static final String SESSION_TIME = "session_time";//会话收发时间
    public static final String SESSION_OWNER = "session_owner";//会话属于谁


}
