# ETS2HeadTracker 2.0
> *Standalone Android Head-Tracking for Euro Truck Simulator 2*

---

## Overview
HeadTracker 2.0 is the successor to HeadTracker 1.0, which previously relied on the Google Chrome web browser and has now transitioned into a standalone Android application. This app acts as the head movement processing medium, replacing Google Chrome from HeadTracker 1.0. The application is built using Native UI, CameraX SDK, and Google's MediaPipe Face Landmarker processing.

---

## Setup
1. Launch the Receiver on your PC.
2. Type N on the Receiver panel if you didn't want to change current settings
3. Open the Android App.
4. Connect both devices by using the same Wi-Fi network or a portable mobile hotspot.
5. Once connected, the receiver panel will show a green "CONNECTED" status.
6. Launch ETS2 and enable head tracking.
7. Turn on HeadTracker by pressing the Start button.

---

## Requirements
Requires the **OpenTrack** application to be installed, with **"facetrack 2.0 Enhanced"** selected in the Output menu and the **"Both"** dropdown option chosen within the Output settings.
You also need to edit controls.sii inside your ETS2 profile folder (located in Documents) and change constant c_ht_roll 0.000000 to constant c_ht_roll 1.000000.
