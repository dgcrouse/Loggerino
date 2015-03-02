package com.pandorica.loggerino;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LogKeeper {

    // TODO: Make comments Javadoc compliant, exhaustive testing, rate limiting, handle id overflow

    private static LogKeeper instance = null; // The singleton instance of the class

    // Retrieve the singleton instance, given a Context
    // param ctx    The context to use
    // returns      The singleton instance of LogKeeper
    public static LogKeeper getInstance(Context ctx){
        if (instance == null) instance = new LogKeeper(ctx);
        return instance;
    }

// The different types of logs that are available
    public enum LogType{
        E,D,I,W
    }

    // The different states the logger can be in
    // Scroll sends the latest entries as they come in
    public enum LogState{
        SCROLL,PAGE,EXPANDED
    }

    // Name of intent to fire when USB permission given
    private static final String ACTION_USB_PERMISSION =
            "com.pandorica.loggerino.USB_PERMISSION";

    private final LinkedList<LogEntry> mEntries; // All log entries

    private final Context mContext;   // The context of the application

    private LogState mState = LogState.SCROLL;  // The current state of the logger

    private boolean isReady = false;    // Are we ready to send logs to the device?

    private int lineLen = 19;           // Maximum line length on device

    private IOProcessor mIO;            // Handles communication with the device

    //*********** from serial library *********************************************
    private final UsbManager mManager;

    private UsbSerialDriver mDriver;

    //****************************************************************************

    // Class encapsulating an entry in the log
    private class LogEntry{
        private Date logTime;
        private String tag;
        private String shortMsg;
        private String longMsg;
        private LogType t;
        private UINT16 id;

        public LogEntry(String tag, String shortMsg, String longMsg, LogType t) {
            logTime = new Date();
            this.tag = tag;
            this.shortMsg = shortMsg;
            this.longMsg = longMsg;
            this.t = t;
        }

        public Date getLogTime() {
            return logTime;
        }

        public String getTag() {
            return tag;
        }

        public String getShortMsg() {
            return shortMsg;
        }

        public String getLongMsg() {
            return longMsg;
        }

        public LogType getType() {
            return t;
        }

        public UINT16 getId() {
            return id;
        }

        public void setID(int id){
            this.id = new UINT16(id);
        }

        public void setID(UINT16 id){
            this.id = id;
        }
    }

    private class UINT8{
        private short data;

        public UINT8(byte val){
            setValue(val);
        }

        public UINT8(short val){
            setValue(val);
        }

        public void setValue(byte b){
            data = (short)(b & 0xff);
        }

        public void setValue(short s){
            data = (short)(s & 0xff);
        }

        public short getValue(){
            return (short)(data&0xff);
        }

        public byte getByteValue(){
            return (byte)(data&0xff);
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || !o.getClass().equals(this.getClass())) return false;
            return ((UINT8)o).getByteValue() == getByteValue();
        }
    }

    private class UINT16{
        private int data;

        public UINT16(byte b){
            setValue(b);
        }

        public UINT16(byte[]b){
            setValue(b);
        }

        public UINT16(byte[] b, int offset){
            setValue(b,offset);
        }

        public UINT16(short s){
            setValue(s);
        }

        public UINT16(int i){
            setValue(i);
        }

        public void setValue(byte b){
            data = (b&0xff);
        }

        public void setValue(byte[] b, int offset){
            if (b.length < 2){
                setValue(b[0]);
                return;
            }
            data = (b[offset+1] & 0xff)<<8 | b[offset] & 0xff;
        }

        public void setValue(byte[] b){
            setValue(b,0);
        }

        public void setValue(short s){
            data = (s&0xffff);
        }

        public void setValue(int i){
            data = (i&0xffff);
        }

        public int getValue(){
            return data&0xffff;
        }

        public byte[] getBytes(){
            byte b[] = new byte[2];
            addToArray(b,0);
            return b;
        }

        public void addToArray(byte[] array, int position){
            array[position] = (byte)(data&0xff);
            array[position+1] = (byte)((data>>>8)&0xff);
        }

        public boolean equals(Object o) {
            if (o == null || !o.getClass().equals(this.getClass())) return false;
            return Arrays.equals(((UINT16) o).getBytes(), getBytes());
        }
    }

    // Handles all serial communication
    private class IOProcessor extends Thread{

        // Protocol defines
        private final byte ENQ = 0x05;
        private final byte SYN = 0x16;
        private final byte ACK = 0x06;
        private final byte NAK = 0x15;
        private final byte CAN = 0x18;

        private final byte SOH = 0x01;
        private final byte ETB = 0x17;
        private final byte STX = 0x02;
        private final byte ETX = 0x03;
        private final byte EOT = 0x04;

        private final byte VERSION = 0x01;



        private final Queue<LogEntry> mSendBuffer;  // Holds all LogEntry objects waiting to be sent
        private final InputListener mListener;      // Listens for input over serial

        // From the serial class
        private final SerialInputOutputManager mSerialIoManager;
        private final UsbSerialPort mSerialPort;
        private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

        // Acts as a buffer on serial read
        private class InputListener implements SerialInputOutputManager.Listener{

            private final Queue<Byte> mReceiveBuffer; // Buffer of received bytes
            private int numBytesNeeded = 0;           // Number of bytes waiting for
            private boolean exceptState = false;      // True if we're in an exception

            // Constructor
            public InputListener(){
                mReceiveBuffer = new ArrayDeque<>();
            }

            // Fires when new data received
            @Override
            public void onNewData(byte[] data){

                // Synchronized with getBytes
                synchronized (mReceiveBuffer) {

                    // Add bytes to buffer
                    for (byte b : data) {
                        mReceiveBuffer.add(b);
                    }
                }

                // If we're waiting for bytes, stop
                synchronized (this) {
                if (numBytesNeeded > 0 && mReceiveBuffer.size() >= numBytesNeeded) {

                        numBytesNeeded = 0;
                        notify(); // Interrupt mid-read sleep
                    }
                }
                exceptState = false; // We're no longer having trouble reading
            }

            // Returns number of bytes available in buffer
            public int getNumAvailableBytes(){
                return mReceiveBuffer.size();
            }


            /* Retrieve a number of bytes from buffer
             * param numBytes   The number of bytes to try to read
             * returns          An array of bytes from the buffer, always numBytes long
             * throws ProtocolException when not enough bytes can be read
             * throws IOException       when ExceptState is true
             */
            private byte[] getBytes(int numBytes) throws ProtocolException, IOException{
                if (numBytes > mReceiveBuffer.size()) throw new ProtocolException();
                if (exceptState) throw new IOException();

                byte[] result = new byte[numBytes];
                synchronized (mReceiveBuffer){
                    for (int i = 0; i < numBytes; i++){
                        result[i] = mReceiveBuffer.remove();
                    }
                }
                return result;
            }


            /* Retrieve a number of bytes from the buffer, waiting for them to arrive. Called from IOProcessor
             * param numBytes   The number of bytes to try to read
             * param timeout    The maximum amount of time to wait for the bytes to arrive
             * returns          An array of bytes from the buffer, always numBytes long
             * throws ProtocolException     When not enough bytes can be read
             * throws IOException           When ExceptState is true
             * throws InterruptedException  When interrupted
             */
            public byte[] readBytes(int numBytes, int timeout) throws ProtocolException, IOException,InterruptedException{

                if (numBytes <= mReceiveBuffer.size()) return getBytes(numBytes); // Will not throw ProtocolException

                synchronized (this) {
                    numBytesNeeded = numBytes;
                    wait(timeout);
                    numBytesNeeded = 0;
                }

                return getBytes(numBytes); // throws ProtocolException when not enough can be read
            }


            // Convenience function, equivalent to readBytes(1,timeout)[0];
            public byte readByte(int timeout) throws ProtocolException, IOException,InterruptedException{
               return readBytes(1,timeout)[0];
            }

            // Flush the buffers
            public void flushBuffer() throws IOException{
                synchronized (mReceiveBuffer){
                    mReceiveBuffer.clear(); // Clear this buffer
                    mSerialPort.purgeHwBuffers(true, false); // Clear hardware buffers
                }
            }

            // Called on run error, sets exception state to true
            @Override
            public void onRunError(Exception e) {
                exceptState = true; // we are in an exception state
            }
        }

        /* Constructor for IOProcessor
         * param device The USBDevice to open a connection with
         * throws IOException passed through from library
         */
        public IOProcessor(UsbDevice device) throws IOException{
            mSendBuffer = new ArrayDeque<>();

            // Tweaked rom serial library example
            UsbDeviceConnection connection = mManager.openDevice(device);

            mSerialPort = mDriver.getPorts().get(0);
            mSerialPort.open(connection);
            mSerialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE); // Arduino default settings
            mListener = new InputListener();
            mSerialIoManager = new SerialInputOutputManager(mSerialPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }

        /* Add an entry to the send queue
         * param entry      The entry to add
         * param stateAtAdd The logState the entry is expected to be added at
         */
        public void addEntry(LogEntry entry, LogState stateAtAdd) {
            if (mState == stateAtAdd){
                synchronized (mSendBuffer) {
                    mSendBuffer.add(entry);
                }
            }
        }

        // Destructor, clean up thread (must be called manually)
        public void destroyObj(){
            mSerialIoManager.stop();
        }

        // Run thread
        public void run(){


            // While not synchronized
            while(!isReady){
                try {
                    // Read byte from serial
                    byte queryByte = mListener.readByte(1000);

                    // Initiate sync or NAK
                    if (queryByte == ENQ){
                        sync();
                    }else{
                        sendAndFlush(NAK);
                    }
                }catch (IOException ex){
                    Log.e("Sync","Exception",ex);
                    return;
                }catch (InterruptedException ex){
                    break;
                }catch (ProtocolException ex){
                    // this is expected if no ENQ is received, so carry on
                }
            }

            // Main loop
            while(!interrupted()){
                try {

                    // If there's a message waiting, read it
                    if (mListener.getNumAvailableBytes() > 0) readMessage();

                    // if there's a message to send, send it.
                    if (!mSendBuffer.isEmpty()) sendShortMessage(mSendBuffer.remove());
                }catch(InterruptedException ex){
                    break;
                }
            }

            try {
                // Clean up
                destroyObj();
                mSerialPort.close();
            }catch (Exception ex){
                return; // We're closing anyway...
            }
        }

        // Sync with Arduino
        private void sync() {
            boolean doNAK = false; // Do we just flush or do we send NAK?

            if (interrupted()) return;
            try{
                try {
                    mListener.flushBuffer(); // initial flush
                    isReady = false;         // reset ready flag
                    mSendBuffer.clear();

                    // Do send protocol
                    sendByte(SYN);
                    byte resp = mListener.readByte(1000);
                    if (resp != ACK) throw new ProtocolException();
                    doNAK = true;
                    byte[] msg = mListener.readBytes(3, 500);

                    if (msg[0] != SOH || msg[2] != EOT) throw new ProtocolException();
                    lineLen = (int)msg[1];
                    sendByte(ACK);

                    // Set states
                    isReady = true;
                    mState = LogState.SCROLL;

                    mListener.flushBuffer();
                    Log.d("Sync","Synced");
                } catch (IOException ex) {
                    Log.e("Sync", "exception", ex);
                    return;
                } catch (ProtocolException ex) { // Thrown when protocol breached
                    Log.e("Sync", "Fail");
                    try {
                        if (doNAK){
                            Log.e("Sync","NAK");
                            sendAndFlush(NAK);
                        }
                        else mListener.flushBuffer();
                        sleep(500);
                        sync();
                    } catch (IOException ex1) {
                        return;
                    }
                }
            }catch(InterruptedException ex) {
                // nested trys because even the catch can throw InterruptedException
                return;
            }
        }

        /* Send a single byte
         * param toSend byte to send
         * throws IOException on serial problem
         */
        private void sendByte(byte toSend) throws IOException{
            byte data[] = {toSend};
            int written = mSerialPort.write(data,100);
            if (written < 1) throw new IOException("Write Failed to Complete");
        }

        /* Send a byte and flush serial
         * param toSend byte to send
         * throws IOException on serial problem
         */
        private void sendAndFlush(byte toSend) throws IOException{
            sendByte(toSend);
            mListener.flushBuffer();
        }


        /* Read and process a message from Arduino
         * throws InterruptedException on thread interrupt
         */
        private void readMessage() throws InterruptedException{
            byte start;
            try {

                // Read a byte from Arduino
                start = mListener.readByte(100);

                // Mid-cycle sync
                if (start == ENQ) {
                    sync();
                    return;
                }

                // Anything other than a SOH is off-protocol
                else if (start != SOH) throw new ProtocolException();


                // Good start byte, so read ahead
                byte[] header = mListener.readBytes(4, 500);

                // Read header
                byte version = header[0];
                char cmd = (char)header[1];
                int len = new UINT8(header[2]).getValue();
                if (header[3] != ETB) throw new ProtocolException();

                // ACK the data
                sendByte(ACK);


                // Read payload
                byte[] data = mListener.readBytes(len + 3, 500);// len+3 to encompass start/end bytes


                // Check known bytes
                if (data[0] != STX || data[len+1] != ETX || data[len+2] != EOT) throw new ProtocolException();
                sendByte(ACK);

                // Handle received data
                processCommand(cmd, Arrays.copyOfRange(data,1,len+1));
            }catch(IOException ex) {
                Log.e("Read","Exception",ex);
                return;
            }catch(ProtocolException ex){
                try {
                    sendAndFlush(NAK);
                }catch (Exception exc){
                    // do nothing
                }
                return;
            }
        }

        /* Handle a received command from Arduino
         * param cmd    The type of command received
         * param data   The message's containing data
         * throws InterruptedException on interrupt
         */
        private void processCommand(char cmd, byte[] data) throws InterruptedException{

            // Get page starting at...
            if (cmd == 'P') {
                mState = LogState.PAGE;

                // Read count and ID
                int count = new UINT8(data[2]).getValue();
                int id = new UINT16(data).getValue();

                // Clear send buffer and queue entries on page
                mSendBuffer.clear();
                for (int c = id; c < Math.min(mEntries.size(), id + count); c++) {
                    addEntry(mEntries.get(c), LogState.PAGE);
                }
            }

            // Get expanded entry for given ID
            else if (cmd == 'E'){
                mState = LogState.EXPANDED;

                // Read ID
                int id = new UINT16(data).getValue();

                // Send entry to device
                mSendBuffer.clear();
                LogEntry entry;
                if (id < mEntries.size()) entry = mEntries.get(id);
                else if (id < 0) entry = mEntries.getFirst();
                else entry = mEntries.getLast();

                sendExtendedMessage(entry);
            }


            // Resume scrolling
            else if (cmd == 'R'){
                // data written is dummy byte
                mState = LogState.SCROLL;
                mSendBuffer.clear();
                if (!mEntries.isEmpty()) mSendBuffer.add(mEntries.getLast()); // start off with last log message
            }
        }

        /* Send an extended message with a given LogEntry
         * param entry The entry to send
         * throws InterruptedException on interrupt
         */
        private void sendExtendedMessage(LogEntry entry) throws InterruptedException{
            if (isInterrupted()) return;
            sendMessage(entry.getTag() + "-" + entry.getShortMsg() + ": " + entry.getLongMsg(),entry.getType(),entry.getId(),(byte)0x02);
        }

        /* Send a short message with a given LogEntry
         * param entry The entry to send
         * throws InterruptedException on interrupt
         */
        private void sendShortMessage(LogEntry entry) throws InterruptedException{
            if (isInterrupted()) return;
            sendMessage(truncate(entry.getTag() + "-" + entry.shortMsg, lineLen), entry.t, entry.id, (byte) 0x01);
        }

        /* Sends a message
         * param msg The message to send
         * param t The type of the message
         * param id The ID of the message
         * param msgType The type (expanded or short)
         * throws InterruptedException on interrupt
         */
        private void sendMessage(String msg, LogType t, UINT16 id, byte msgType) throws InterruptedException{


            // Build header
            byte[] header = new byte[9];
            header[0] = SOH;
            header[1] = VERSION;
            switch(t){
                case E:
                    header[2] = 'E';
                    break;
                case D:
                    header[2] = 'D';
                    break;
                case I:
                    header[2] = 'I';
                    break;
                case W:
                    header[2] = 'W';
                    break;
            }
            header[3] = msgType;
            id.addToArray(header, 4);
            new UINT16(msg.length()).addToArray(header, 6);
            header[8] = ETB;
            try {

                // Send header
                mSerialPort.write(header, 500);

                // Process response
                byte response = mListener.readByte(500);
                if (response == CAN) return;
                if (response != ACK) throw new ProtocolException(); // For code compactness

                // Write message if good response
                ByteArrayOutputStream msgStream = new ByteArrayOutputStream();
                msgStream.write(STX);
                msgStream.write(msg.getBytes());
                msgStream.write(ETX);
                msgStream.write(EOT);
                mSerialPort.write(msgStream.toByteArray(),500);

                response = mListener.readByte(500);

                if(response == CAN) return;
                else if (response != ACK) throw new ProtocolException(); // For code compactness

            }catch(IOException ex) {
                return;
            }catch(ProtocolException ex){
                // Retry send TODO: Put limit on retries
                sendMessage(msg,t,id,msgType);
                return;
            }

        }

        /* Truncates a string to maximum length
         * param msg    The string to truncate
         * param maxLen The maximum allowable length for the string
         */
        private String truncate(String msg, int maxLen){
            if (msg.length() <= maxLen) return msg;
            else return msg.substring(0,maxLen);
        }
    }

    // Exception indicating the communication drifted from protocol
    private class ProtocolException extends Exception{

        public ProtocolException() {
            super();
        }

        public ProtocolException(String detailMessage) {
            super(detailMessage);
        }

        public ProtocolException(String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
        }

        public ProtocolException(Throwable throwable) {
            super(throwable);
        }
    }

    /*********************************************************************************
     * Class methods
     *********************************************************************************/

    // Create new LogKeeper with Context
    private LogKeeper(Context ctx){
        mContext = ctx;
        mEntries = new LinkedList<>();

        // From serial driver
        mManager = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(mManager);

        // Collect relevant intents
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        mContext.registerReceiver(mUsbReceiver, filter);

        // Handle if already connected
        if (!availableDrivers.isEmpty()) {
            connectDevice();
        }
    }

    // Handle intents this object consumes
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {

        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            // permission provided/denied
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {

                    // All this is based on stuff from the serial library

                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if(device != null){
                            try {

                                // Spin up a new IOProcessor
                                mIO = new IOProcessor(device);
                                mIO.start();
                            }catch(IOException ex){
                                Log.e("IOProcessor","Exception",ex);
                            }
                        }
                    }
                    else {
                        Log.d("Denied", "permission denied for device " + device);
                    }
                }

             // Device is attached, so connect it
            }else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)){
                Toast.makeText(mContext,"attached",Toast.LENGTH_SHORT).show();
                connectDevice();

            // Device disconnected
            }else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)){
                disconnectDevice();
            }
        }
    };

    // Handle device connected, request permissions
    private void connectDevice(){
        List<UsbSerialDriver> availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(mManager);
        PendingIntent permissionIntent = PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_USB_PERMISSION), 0);
        mDriver = availableDrivers.get(0);
        mManager.requestPermission(mDriver.getDevice(),permissionIntent);
    }

    // Handle/initiate disconnect
    private void disconnectDevice(){
        isReady = false;
        Toast.makeText(mContext,"Disconnected",Toast.LENGTH_SHORT).show();
        try {
            mIO.interrupt();
            mIO.join(1000);
        }catch (Exception ex){
            Log.d("Serial_Close",ex.getLocalizedMessage());
        }
        mDriver = null;

    }

    // Destroy this object
    public void destroy(){
        mContext.unregisterReceiver(mUsbReceiver);
        disconnectDevice();
        instance = null;
    }

    // Send log to device and add to list
    public void sendLog(String tag, String shortMsg, String longMsg, LogType t){
        LogEntry entry = new LogEntry(tag,shortMsg,longMsg,t);
        entry.setID(mEntries.size());
        mEntries.add(entry);

        // Send to device if serial open
        if (isReady){
            mIO.addEntry(entry,LogState.SCROLL);
        }
    }

}