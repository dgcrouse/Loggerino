# Loggerino
An Arduino-based debug logger for Android

Loggerino is a simple way of viewing your Android debug messages without using a computer or cluttering your screen. It uses an Arduino with a TFT touch shield to display messages in an interactive manner.

Loggerino is a library that has drop-in compatibility with Android `Log`. Add it to a project to see your logs externally. It also passes all log calls on to Logcat, so you don't lose a thing.

Loggerino uses a simple Serial protocol outlined in protocol.txt to communicate.

#Setup:

After cloning the repo, also clone my fork of mik3y's usb-serial-for-android here: https://github.com/dgcrouse/usb-serial-for-android. My fork is Gradle 1.1 compliant and works with Loggerino

Copy the contents of the directory usbSerialForAndroid to the corresponding directory in the Android subfolder

Upload Logger.ino to the Arduino. This is currently configured to work with the Seeed Studio TFT Touch Shield 2.0 (http://www.seeedstudio.com/wiki/2.8%27%27_TFT_Touch_Shield_V2.0) but can be changed to work with other shields easily.

Inside the Android folder is the application LoggerinoTest. This is the test application for the Loggerino library. Right now, it just sends a simple message over and over. This will be updated in the future.

Add and launch the app, then plug the Arduino into your device with an OTG adapter.

If anything goes wrong, just reset the Arduino and all will be fine.

#Adding to Your Application

To add Loggerino to your application, copy the usbSerialForAndroid and loggerino folders to your application. To your main app (in the app folder) build.gradle, add the following line under 'Dependencies'
    compile project(':loggerino')

Make sure your settings.gradle includes the module:
    include ':app', ':loggerino', ':usbSerialForAndroid'

The Logger class is drop-in compatible with Android `Log`, just make sure to get an instance of the singleton with `getLogger(Context)`. `Log.*` calls can now be replaced with `<logger instance>.*` calls. Look around Logger, you can also specify different short and long messages to send to Arduino.

On Arduino, the arrows page up and down. You have to touch the arrow with the line at the bottom to resume scrolling. Tapping a message opens the extended log message, the paging buttons now page the message, and the resume scrolling button retains its functionality. Holding a touch too long causes an Arduino reboot. Bonus points if anyone figures out why before I do.

I still have a long way to go on this, but thought I'd get an initial 0.1 version out there.