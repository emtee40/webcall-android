<div align="center">
  <a href="https://timur.mobi/webcall/android"><img src="WebCall-for-Android.png" alt="WebCall for Android"></a>
</div>

# WebCall for Android

[Webcall](https://github.com/mehrvarz/webcall) is a telephony server and a set of web applications for making and receive calls based on WebRTC. WebCall let's you create low latency audio connections with very high quality. You can add a video stream at any time during the call. And you can transfer files in both directions. All connections are encrypted over P2P and do not make use of 3rd party services. If you run your own WebCall server, you end up with the most private telephony solution possible.

[WebCall for Android](https://timur.mobi/webcall/android) offers additional features on top of the core package. 

## NFC Connect

NFC Connect allows you to create phone calls by touching two devices. Once connected the two parties can walk apart and continue the call. The other device does not need any special software. It must support NFC, have a browser and an internet connection. So even if you use the Android version, you will benefit from the Web application, because anybody on the Web is able to call you. All they need to know is your WebCall link.

## 24/7 Operation

WebCall for Android lets you receive calls when your device is in deep sleep mode. This is a feature the Web application can not offer. It makes WebCall for Android a better solution for 24/7 operation.

## Ring on Speaker

WebCall for Android can play back the ringtone on the loud speaker, even if you have a headset connected to the device. This lets you receive calls quicker.

# Building the APK

You need Java 11 and gradle 7.3.3. You can build this project by running: gradle build --info

If you only want to build an unsigned debug APK, simply outcomment the "release" section under "buildTypes" in "build.gradle". If you want to build a signed release APK, add two files "keystore.properties" and "releasekey.keystore" to the base directory. The "keystore.properties" should have three entries:
```
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=...
RELEASE_KEY_PASSWORD=...
```

# License

This program is Free Software: You can use, study share and improve it at your will. Specifically you can redistribute and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.

GPL3.0 - see: [LICENSE](LICENSE)

## 3rd party

- github.com/TooTallNate/Java-WebSocket, licensed under an MIT license
- some icons made by Icomoon from Flaticon, licensed by Creative Commons BY 3.0

