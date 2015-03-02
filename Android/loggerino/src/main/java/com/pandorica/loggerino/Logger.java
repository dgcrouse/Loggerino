package com.pandorica.loggerino;

import android.content.Context;
import android.util.Log;

/**
 * Created by dgcrouse on 2/20/15.
 */
public class Logger {

    private static Logger instance = null; // Singleton instance


    // Get singleton instance
    public static Logger getLogger(Context ctx){
        if (instance == null) instance = new Logger(ctx);
        return instance;
    }

    // Handles Arduino IO
    private LogKeeper keeper;

    private Logger(Context ctx){
        keeper = LogKeeper.getInstance(ctx);
    }

    // Destroy singleton instance
    public void destroy(){
        keeper.destroy();
        instance = null;
    }

    /* Log functions - tag is the log tag. shortMsg is the message (keep it 10 chars or less generally
     * that is displayed in scroll/page mode. longMsg is the long message to be displayed in the
     * expanded view.
     *
     * An Exception can be attached in place of a longMsg or both messages
     */

    // Debug logs

    public void d(String tag, String shortMsg, String longMsg){
        Log.d(tag, longMsg);
        keeper.sendLog(tag,shortMsg,longMsg, LogKeeper.LogType.D);
    }

    public void d(String tag, String longMsg){
        d(tag,longMsg,longMsg);
    }

    public void d(String tag, String shortMsg, Exception ex){
        Log.d(tag,shortMsg,ex);
        keeper.sendLog(tag,shortMsg,ex.getLocalizedMessage(),LogKeeper.LogType.D);
    }

    public void d(String tag, Exception ex){
        d(tag,ex.getCause().getClass().getName(),ex);
    }


    // Exception logs
    public void e(String tag, String shortMsg, String longMsg){
        Log.e(tag,longMsg);
        keeper.sendLog(tag, shortMsg, longMsg,LogKeeper.LogType.D);
    }

    public void e(String tag, String shortMsg, Exception ex){
        Log.e(tag,shortMsg,ex);
        keeper.sendLog(tag,shortMsg,ex.getLocalizedMessage(),LogKeeper.LogType.E);
    }

    public void e(String tag, Exception ex){
        e(tag,ex.getCause().getClass().getName(),ex);
    }

    public void e(String tag, String longMsg){
        e(tag,longMsg,longMsg);
    }


    // Info logs
    public void i(String tag, String shortMsg, String longMsg){
        Log.i(tag,longMsg);
        keeper.sendLog(tag,shortMsg,longMsg,LogKeeper.LogType.I);
    }
    public void i(String tag, String longMsg){
        i(tag,longMsg,longMsg);
    }

    public void i(String tag, String shortMsg, Exception ex){
        Log.i(tag,shortMsg,ex);
        keeper.sendLog(tag,shortMsg,ex.getLocalizedMessage(),LogKeeper.LogType.E);
    }

    public void i(String tag, Exception ex){
        i(tag,ex.getCause().getClass().getName(),ex);
    }


    // Warn logs
    public void w(String tag, String shortMsg, String longMsg){
        Log.w(tag,longMsg);
        keeper.sendLog(tag,shortMsg,longMsg,LogKeeper.LogType.W);
    }

    public void w(String tag, String longMsg){
        w(tag,longMsg,longMsg);
    }

    public void w(String tag, String shortMsg, Exception ex){
        Log.w(tag,shortMsg,ex);
        keeper.sendLog(tag,shortMsg,ex.getLocalizedMessage(),LogKeeper.LogType.W);
    }

    public void w(String tag, Exception ex){
        w(tag,ex.getCause().getClass().getName(),ex);
    }

}

