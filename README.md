<div align="center">
<img width="256" height="256" alt="Gemini_Generated_Image_25r8pd25r8pd25r8" src="https://github.com/user-attachments/assets/61ee8b89-24ac-4357-bbf1-83ea8c304990" />
</div>

# ETS2HeadTracker 2.0
> *Standalone Android Head-Tracking for Euro Truck Simulator 2*

---

## Overview
HeadTracker 2.0 is the successor to [HeadTracker 1.0](https://github.com/pebbian/ETS2HeadTracker-1.0), which previously relied on the Google Chrome web browser and has now transitioned into a standalone Android application. This app acts as the head movement processing medium, replacing Google Chrome from [HeadTracker 1.0](https://github.com/pebbian/ETS2HeadTracker-1.0). The application is built using Native UI, CameraX SDK, and Google's MediaPipe Face Landmarker processing.

---

## Setup
1. Launch the Receiver on your PC.
2. Type N on the Receiver panel if you didn't want to change current settings.
3. Open the Android App.
4. Connect both devices by using the same Wi-Fi network or a portable mobile hotspot (in my case, it didn't want to connect until i restarted WiFi on my phone).
5. Once connected, the receiver panel will show a green "CONNECTED" status.
6. Launch ETS2 and enable head tracking.
7. Turn on HeadTracker by pressing the Start button.

---

### Tracking Filter

<div align="center">
<img width="480" alt="WhatsApp Image 2026-10-11 at 11 17 45" src="https://github.com/user-attachments/assets/be639e45-3038-4ace-8fd6-20083639fb7e" />
</div>

Besides the sensitivity curves displayed at the top of the UI, the app utilizes an adaptive filter (One Euro Filter) to balance stability and responsiveness in real time:

* **Smoothing (`min cutoff`)**
  Acts as the primary jitter stabilizer. Lower values apply heavier smoothing to filter out subtle facial tremors, micromovements, and sensor noise, resulting in a rock-solid, jitter-free camera output.
  
* **Responsiveness (`beta`)**
  Acts as the smoothing cutoff threshold during rapid movements. Higher values dynamically bypass the smoothing filter the moment sudden, sharp head turns are detected, eliminating input lag so quick snaps (e.g., checking side mirrors) feel instantaneous and raw rather than delayed or muddy.

* **Send position (x, y, z)**
  Tracks forward/backward, lateral, and vertical head movement (currently not functional). 

> **Tip:** Keep **Smoothing** low for a cinematic and stable view, but bump up **Responsiveness** if fast head turns feel sluggish or smoothed out.

---

## Requirements
Requires **Android 10** Phone and [OpenTrack](https://github.com/opentrack/opentrack) installed on your PC, with **"facetrack 2.0 Enhanced"** selected in the Output menu and the **"Both"** dropdown option chosen within the Output settings.
You also need to edit controls.sii inside your ETS2 profile folder (located in Documents) and change constant c_ht_roll 0.000000 to constant c_ht_roll 1.000000.

Enjoying the project? You can support me here: https://ko-fi.com/pebbian. Thanks for having my back!
