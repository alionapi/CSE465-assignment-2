# CSE465-assignment-2
CSE465: Mobile Computing | Spring 2026 | programming assignment 2

## Overview

This project was developed for the Mobile Computing (CSE465) course at UNIST.

The assignment required the implementation of a smartphone-based object detection system capable of:

* Running object detection directly on a mobile device
* Supporting multiple model precision formats (FP32, FP16, INT8)
* Providing voice-based interaction for target selection
* Measuring and comparing model performance
* Exporting benchmarking results for further analysis

The application uses TensorFlow Lite versions of the Ultralytics YOLO26n object detection model and allows users to switch between precision variants at runtime.

## Main Features

### Live Object Detection

* Real-time object detection using the device camera
* Bounding-box visualization for detected objects
* Voice-controlled target object selection
* Highlighting of selected target objects

### Voice Interaction

Supported commands include:

* Find a target object
* Clear target selection
* Switch precision (FP32, FP16, INT8)
* Pause and resume detection
* Screenshot capture
* Filter detections by class
* Announce target detection using text-to-speech
* Automatically switch to the fastest precision mode

### Performance Dashboard

The dashboard provides:

* Rolling FPS
* Average latency
* P95 latency
* Memory usage
* Battery statistics
* Model size information
* Detection confidence statistics

### Data Export

Benchmarking results can be exported as:

* CSV
* JSON

for later analysis.


## Repository Structure

```text
.
├── CSE465_PA2.pdf
│   └── Assignment specification and requirements
│
├── PA2_report.pdf
│   └── Final project report
│
├── pa2/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   ├── proguard-rules.pro
│   │   │
│   │   └── src/
│   │       ├── androidTest/
│   │       │   └── java/
│   │       │
│   │       ├── main/
│   │       │   ├── AndroidManifest.xml
│   │       │   │
│   │       │   ├── assets/
│   │       │   │   ├── yolo26n_float32.tflite
│   │       │   │   ├── yolo26n_float16.tflite
│   │       │   │   └── yolo26n_int8.tflite
│   │       │   │
│   │       │   ├── java/
│   │       │   │   └── Application source code
│   │       │   │
│   │       │   └── res/
│   │       │       ├── layout/
│   │       │       ├── values/
│   │       │       └── xml/
│   │       │
│   │       └── test/
│   │           └── java/
│   │
│   ├── gradle/
│   │   ├── gradle-daemon-jvm.properties
│   │   ├── libs.versions.toml
│   │   └── wrapper/
│   │       ├── gradle-wrapper.jar
│   │       └── gradle-wrapper.properties
│   │
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── settings.gradle.kts
│   ├── gradlew
│   └── gradlew.bat
│
└── README.md
```

## Technologies

* Kotlin
* Android Studio
* CameraX
* TensorFlow Lite
* Ultralytics YOLO26n
* Android SpeechRecognizer
* Text-to-Speech (TTS)
* Gradle

## Model Variants

The application includes three TensorFlow Lite model variants:

| Model                  | Precision |
| ---------------------- | --------- |
| yolo26n_float32.tflite | FP32      |
| yolo26n_float16.tflite | FP16      |
| yolo26n_int8.tflite    | INT8      |

These models can be switched dynamically during runtime for benchmarking and performance comparison.

## Building and Running

1. Clone the repository.
2. Open the project in Android Studio.
3. Allow Gradle synchronization to complete.
4. Connect an Android device with camera support.
5. Build and run the application.
