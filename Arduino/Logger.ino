#include <TFTv2.h>

#include <SeeedTouchScreen.h>

#include <stdint.h>

#include <SPI.h>

// Touchscreen defines

#define YP A2   // must be an analog pin, use "An" notation!
#define XM A1   // must be an analog pin, use "An" notation!
#define YM 14   // can be a digital pin, this is A0
#define XP 17   // can be a digital pin, this is A3 

#define TS_MINX 116*2
#define TS_MAXX 890*2
#define TS_MINY 83*2
#define TS_MAXY 913*2

// Screen vars (self-explanatory)

#define SCREEN_HEIGHT 320
#define SCREEN_WIDTH 240
#define BUTTON_HEIGHT 40
#define VERTICAL_SPACING 20

#define MAX_MESSAGES (SCREEN_HEIGHT-BUTTON_HEIGHT)/VERTICAL_SPACING
#define DISP_WIDTH 19
#define BUTTON_TOP MAX_MESSAGES*VERTICAL_SPACING


// Protocol defines
#define ENQ 0x05
#define SYN 0x16
#define ACK 0x06
#define NAK 0x15
#define CAN 0x18

#define SOH 0x01
#define ETB 0x17
#define STX 0x02
#define ETX 0x03
#define EOT 0x04

#define SHORT_TYPE 0x01
#define EXPANDED_TYPE 0x02
#define VERSION 0x01

// TODO: Debounce still doesn't work, paging up has unexpected results

// Device modes. Scroll displays most current, page stays on a page, expanded shows a single message
typedef enum{SCROLL,PAGE,EXPANDED} mode;

TouchScreen ts = TouchScreen(XP, YP, XM, YM);

short topCoord =        0;          // The top coordinate of the current line

short numMessages =     0;          // The number of messages on screen
unsigned int topID =    0;          // The ID of the top message
unsigned int ids[MAX_MESSAGES];     // All the IDs on screen (only valid up to NUM_MESSAGES)
mode myMode =           SCROLL;     // The current device mode
bool isSynced =         false;      // Are we synchronized with the device?
bool debounce =         false;      // True if there has not been a non-touch since the previous touch

// For expanded mode
String expandedMessage;             // Holds the entire message to be displayed in expanded mode
short expandedPos;                  // Current line position in the expanded message
INT16U expandedColor;               // Color of the expanded message

void setup() {
    // Start the screen and serial port
    Tft.TFTinit();
    Serial.begin(115200);
    Serial.setTimeout(500);
}

void loop() {
    
    if (!isTouching) debounce = false; // Fix debounce flag if not touching screen
    
    if (!isSynced){
        Serial.write(ENQ);  // Request a sync
        delay(250);         // So we don't spam
    }
  
    // Wait for a message
    if (Serial.available()){
        
        // Read the first message byte
        byte intro = readByte();
        
        // SYN causes synchronization
        if (intro == SYN){
            sync();
            
        // SOH indicates a standard message
        } else if (intro == SOH && isSynced){
            readSerial();
            
        // Otherwise, something went wrong, so flush (NAK if synced)
        }else if (isSynced){
            sendAndFlush(NAK);
        }else{
            flushInput();
        }
    }
  
    // Only process touch on stylus down
    if (!debounce && isSynced) handleTouch();
}

// Flush the serial input buffer
void flushInput(){
    while (readByte() != -1); 
}

/* Send a single byte and flush the input buffer
 * param cmd Command byte to read
 */
void sendAndFlush(byte cmd){
    Serial.write(cmd); 
    flushInput();
}

// Synchronize with Android
void sync(){
    
    // Conforms to protocol, write start packet
    byte start_packet[] = {ACK,SOH,DISP_WIDTH&0xff,EOT};
  
    Serial.write(start_packet,4);
  
    // Get response and check
    byte resp = readByte();
    
    // Null or NAK, stop trying to sync and go around again
    if (resp == 0x00 || resp == NAK) return; 
    
    // Successful, so go ahead
    else if (resp == ACK){
        myMode = SCROLL;    // Reset mode
        
        // Prep the screen
        clearScreen();
        drawNavbar();    
        
        // We are synchronized   
        isSynced = true;
    } else flushInput();    // No idea what happened here, major failure. 
}


// True if someone is touching the screen, false otherwise
bool isTouching(){
    return ts.getPoint().z > __PRESURE;
}


// Process a touch event
void handleTouch(){
    
    // Get touched point on screen (from library example)
    Point p = ts.getPoint();
    p.x = map(p.x, TS_MINX, TS_MAXX, 0, 240);
    p.y = map(p.y, TS_MINY, TS_MAXY, 0, 320);
    if (p.z > __PRESURE) {
        
        if (debounce) return;    // Check for debounce
        
        // If a touch on a button
        if (p.y > BUTTON_TOP){  
            int buttonNum = (int)(p.x/(SCREEN_WIDTH/5.0));  // What button was pressed?  
            
            
            // Handle the press (pretty self-explanatory)
            switch(buttonNum){
                case 0:
                    if (myMode != EXPANDED) requestPreviousPage();
                    else prevExpandedPage();
                break;
                case 1:
                    if (myMode == PAGE)requestNextPage();
                    else if (myMode == EXPANDED) nextExpandedPage();
                break;
                case 2:
                    if (myMode == PAGE || myMode == EXPANDED) resumeScrolling();
                break;
                default:
                // do nothing for remaining buttons
                break; 
            }
            
            
        // Touches above the navbar are ignored in EXPANDED mode
        }else if (myMode != EXPANDED){
            
            // Determine which item was touched
            int itemTouched = p.y/VERTICAL_SPACING;
            constrain(itemTouched, 0,MAX_MESSAGES-1);
            
            // Only process touch if on an actual entry
            if (itemTouched < numMessages){
                requestExpandedEntry(ids[itemTouched]);
            }
        }
    
    // If not touched, then clear debounce flag
    }else{
        debounce = false;
    }
}


// Get the previous page to that displayed on screen
void requestPreviousPage(){
    if (topID == 0) return;
    myMode = PAGE;
    unsigned int id;
    if (topID >= MAX_MESSAGES) id = topID - MAX_MESSAGES;
    else id = 0;
    getPageAt(id); 
}


// Get the next page to that displayed on screen (Android handles flowing off the end)
void requestNextPage(){
    if (myMode != PAGE) return; // Useless in scroll mode
    getPageAt(topID + MAX_MESSAGES);
}

// Go back to scroll mode
void resumeScrolling(){
    byte dummy[1] = {0xab}; // need a dummy byte for protocol compliance
    if(sendCommand('R',dummy,1)){
        myMode = SCROLL;
        clearScreen();
    }
}

/* Request an expanded version of selected entry
 *  param id the ID of the message to request
 *  returns true if successful, false otherwise
 */
bool requestExpandedEntry(unsigned int id){
    byte data[2];
    intToBytes(id,data,0);
    if(sendCommand('E',data,2)){
        myMode = EXPANDED;
        numMessages = 0;
    }
}

/* Retrieve the page starting at <id>
 *  param   id the ID of the message at the top of the page
 *  returns true if successful, false otherwise
 */
bool getPageAt(unsigned int id){
    byte payload[3];
    topID = id;
    intToBytes(id,payload,0);
    payload[2] = MAX_MESSAGES & 0xff;
    if (sendCommand('P',payload,3)){
        topID = id;
        clearScreen();
    }
}

// Clear the message part of the screen
void clearScreen(){
    Tft.fillScreen(0,239,0,BUTTON_TOP-1,BLACK); // Clear screen
    // Reset message vars
    topCoord = 0;
    numMessages = 0; 
}

// Display the navigation bar
void drawNavbar(){
    Tft.fillScreen(0,239,BUTTON_TOP,SCREEN_HEIGHT-1,BLACK); // Clear portion of screen
    
    
    Tft.drawHorizontalLine(0,BUTTON_TOP,240,WHITE);
    
    // Calculate button params
    int spacing = (SCREEN_WIDTH/5.0);
    int buttonHeight = SCREEN_HEIGHT-BUTTON_TOP;
    int centerY = (BUTTON_TOP + buttonHeight/2);
    
    // Loop over buttons
    for (int i = 1; i <= 3; i++){
        
        // Delineate button
        Tft.drawVerticalLine(i*spacing,BUTTON_TOP,buttonHeight,WHITE);
        int centerX = (int)(spacing * (i-0.5));
        
        switch(i){
            case 1:
                // Page Down
                Tft.drawTriangle(centerX-spacing/3,centerY+buttonHeight/3,centerX+spacing/3,centerY+buttonHeight/3,centerX,centerY-buttonHeight/3, WHITE);
            break;
            case 2:
                // Page Up
                Tft.drawTriangle(centerX-spacing/3,centerY-buttonHeight/3,centerX+spacing/3,centerY-buttonHeight/3,centerX,centerY+buttonHeight/3, WHITE);
            break;
            case 3:
                // Switch to scroll 
                if (myMode != SCROLL){
                    Tft.drawTriangle(centerX-spacing/3,centerY-buttonHeight/3,centerX+spacing/3,centerY-buttonHeight/3,centerX,centerY+buttonHeight/3, WHITE);
                    Tft.drawHorizontalLine(centerX-spacing/3,centerY+buttonHeight/3,2*spacing/3,WHITE);
                }
            break;
            
        }
    }
}

// Read a message from serial
void readSerial(){
  
    // Read the header
    byte header[8];
    int numRead = Serial.readBytes(header,8);
    
    // Check protocol requirements
    if (numRead != 8 || header[7] != ETB){
        sendAndFlush(NAK);
        return;
    }
 
    // Parse header
    byte version = header[0]; // ignore for now
    char logtype = header[1];
    char msgtype = header[2];
    unsigned int id = bytesToInt(header,3);
    unsigned int len = bytesToInt(header,5);
    
    // Cancel if inappropriate type for current mode
    if ((msgtype == SHORT_TYPE && myMode == EXPANDED) || (msgtype == EXPANDED_TYPE && myMode != EXPANDED)){
        Serial.write(CAN);
        return;
    }
    
    // Acknowledge
    Serial.write(ACK);
   
    
    char data[len+1];
    
    // Look for first byte
    if (readByte() != STX){
        sendAndFlush(NAK);
        return;   
    }
   
    // Read data and check it
    unsigned int numread = Serial.readBytes(data,len);
    data[len] = 0;
    if (numread != len){
        sendAndFlush(NAK);
        return;
    }
  
    // Check for terminating bytes
    byte t1 = readByte();
    byte t2 = readByte();
  
    if (t1 != ETX || t2 != EOT){
        sendAndFlush(NAK);
        return;
    }
    
    // Send final confirmation
    Serial.write(ACK);
    
    
    // Display message appropriately
    switch(msgtype){
        case SHORT_TYPE:
            displayShortMessage(data,logtype,id);
        break;
        case EXPANDED_TYPE:
            displayExpandedMessage(data,logtype,id);
        break;
    }
}

/* Take the expanded message and display it
 * param message    The message to display
 * param typecode   The type of message to display
 * param id         The ID of the message to display
 */
void displayExpandedMessage(char* message, char typecode, int id){
    if (myMode != EXPANDED) return; // sanity check
    
    // Set persistent vars
    expandedMessage = String(message);
    expandedPos = 0;
    expandedColor = typeToColor(typecode);
    
    // Initial display
    showExpanded();
}

// Display previous page in expanded message
void nextExpandedPage(){
    if (expandedPos*DISP_WIDTH < expandedMessage.length()) showExpanded();
}

// Display next page in expanded message
void prevExpandedPage(){
    expandedPos -= MAX_MESSAGES;
    if (expandedPos < 0) expandedPos = 0;
    showExpanded();
}

// Show expanded entry, starting at expandedPos
void showExpanded(){
    if (myMode != EXPANDED) return; // sanity check
    
    int currentLine = 0; // Current line on screen
    
    clearScreen(); // Clear out the screen
    
    // Loop until we run out of message or lines
    while(expandedPos*DISP_WIDTH < expandedMessage.length() && currentLine < MAX_MESSAGES){
        
        // Get char* of current line and draw
        short startpoint = expandedPos*DISP_WIDTH;
        short endpoint = min(startpoint + DISP_WIDTH,expandedMessage.length());
        short linelen = endpoint-startpoint+1;
        char line[linelen];
        expandedMessage.toCharArray(line,linelen,startpoint);
        Tft.drawString(line,0,topCoord,2,expandedColor);
        
        // Update count vars
        topCoord += VERTICAL_SPACING;
        expandedPos++;
        currentLine++;
    } 
}

/* Draw short message to screen
 * param message    The message to display
 * param typecode   The type of message to display
 * param id         The ID of the message to display
 */
void displayShortMessage(char* message, char typecode, unsigned int id){
    
    // Clear screen if this one will run off the end
    if (numMessages == MAX_MESSAGES){
        clearScreen();
    }
    if (topCoord == 0) topID = id;  // Set topID
    ids[numMessages] = id;          // Assign current ID
    
    // Draw to screen
    Tft.drawString(message,0,topCoord,2,typeToColor(typecode));
    
    // Update count vars
    topCoord += VERTICAL_SPACING;
    numMessages++;
}

/* Converts typecode to color of text
 * param typecode    The type of message being displayed
 * return            The color to display the message
 */
INT16U typeToColor(char typecode){
    INT16U msg_color;
    switch(typecode){
        case 'E':
            msg_color = RED;
        break;
        case 'D':
            msg_color = YELLOW;
        break;
        case 'W':
            msg_color = BLUE;
        break;
        default:
            msg_color = WHITE;
    }
    return msg_color; 
}

/* Read a single byte from serial, respecting timeouts
 * returns the byte read
 */
byte readByte(){
    byte data[1];
    unsigned int len = Serial.readBytes(data,1);
    if (len == 0) return 0x00; // signals failure
    else return data[0];
}

/* Send a command to the Android device
 * param cmd        The command to send
 * param payload    The payload of the command
 * param len        The length of the payload
 * return           True if successful, false if cancelled
 */

bool sendCommand(char cmd, byte *payload, short len){
    
    // Build and write header according to spec
    byte header[] = {SOH,VERSION,cmd,len&0xff,ETB};
    Serial.write(header,5);
  
    // Process response
    byte resp = readByte();
    if (resp == CAN) return false;
    else if (resp != ACK) return sendCommand(cmd,payload,len);
  
    // Write message body
    Serial.write(STX);
    Serial.write(payload,len);
    byte closer[] = {ETX,EOT};
    Serial.write(closer,2);
    
    // Handle end
    byte respA = readByte();
    if (respA == CAN) return false;
    else if (respA == ACK) return  true;
    
    // NAK or anything else triggers resend TODO: Limit retries?
    else sendCommand(cmd,payload,len);
}

/* Write an int into a byte array NO BOUNDS CHECK
 * param i          The int to write
 * param bArray     The array to write into
 * param offset     The start index of the write
 */
void intToBytes(unsigned int i, byte* bArray, int offset){
    bArray[offset] = i & 0xff;
    bArray[offset + 1] = (i >> 8) & 0xff;
}

/* Convert a portion of a byte array into an integer
 * param bArray     The byte array to read from
 * param offset     The point to start reading from
 * returns          The integer value
 */
unsigned int bytesToInt(byte* bArray, int offset){
    return (unsigned int)(((bArray[offset+1]&0xff) << 8) | (bArray[offset]&0xff));
}
