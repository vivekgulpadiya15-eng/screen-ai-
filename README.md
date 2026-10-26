# Screen AI — Android floating screenshot question solver

An original Android source project inspired by floating on-screen AI tools. It is not a clone of Floaty, and it does not include Floaty's branding or code.

## What the app is designed to do

1. The user configures a backend URL in Screen AI.
2. The user grants Android's overlay permission and explicitly approves screen capture.
3. Screen AI starts a visible media-projection foreground service and shows a movable floating **AI** bubble above other apps.
4. Tap the bubble to capture the latest visible screen and submit the screenshot to the configured backend. The answer appears in a floating panel without returning to the Screen AI activity.
5. Tap **Next screen** to hide the panel, navigate to a different question in the other app, then tap the bubble again.

The app does not automatically click answers or advance the app underneath it. It only captures a screen after an explicit user action.

## Requirements

- Android Studio with a JDK compatible with Android Gradle Plugin 8.7.3 (JDK 17 is the standard choice).
- Android SDK Platform 35 and Gradle support through Android Studio.
- Node.js 20 or later for the backend.
- An OpenAI API account/key and network access. API usage can incur charges.

## Run the backend

1. Copy `backend/.env.example` to `backend/.env`.
2. Set `OPENAI_API_KEY` to your own key and choose a long random `SCREEN_AI_SHARED_TOKEN`.
3. For local use, `npm start` automatically loads `backend/.env` with Node 20+. For deployment, set the same values in your hosting provider's environment-variable settings.
4. Run `cd backend` then `npm start`.
5. Check `GET /health` returns `{ "ok": true, "service": "screen-ai-backend" }`.
6. Deploy behind HTTPS for use from a physical phone. Do not publish the API key, `.env` file, or an unprotected backend. Add rate limits and authentication before public release.

For local backend testing on the Android Emulator, use `http://10.0.2.2:3000` as the backend URL. A physical Android phone cannot use that emulator-only address; it needs an HTTPS URL or a reachable address on the same local network. The app allows HTTP for local development; HTTPS is strongly recommended for non-local use.

## Build and install the Android app

1. Open this folder in Android Studio (`File → Open`).
2. Let Gradle sync finish. If Android Studio asks to install SDK Platform 35, allow it.
3. Run the `app` configuration on a device or emulator, or use **Build → Build APK(s)**.
4. Open Screen AI and enter your backend URL and the same `SCREEN_AI_SHARED_TOKEN` configured on the server, then save.
5. Grant **Display over other apps** permission.
6. Tap **Start / authorize screen capture** and approve Android's system prompt. On Android versions that offer a choice, select the entire screen if the bubble needs to work across apps.
7. Tap **Show floating Screen AI bubble**.
8. Navigate to the app containing the question, tap the AI bubble, and read the answer in the overlay.

Android requires the user to approve screen capture, may show a persistent system indicator, and can stop capture when the user ends sharing, locks the device, or another projection begins. The user must re-authorize after a stopped session. Secure/DRM apps may block capture or return a blank image.

## Important security and privacy notes

- The OpenAI API key belongs only in the backend environment, never in the APK.
- The screenshot can contain private messages, account details, or other sensitive data. The app sends it to the configured backend and then to OpenAI for analysis. Do not capture sensitive screens.
- Set `SCREEN_AI_SHARED_TOKEN` on any internet-reachable backend. Use HTTPS, authentication, request limits, monitoring, and deployment secrets management before sharing with others.
- The manifest currently permits cleartext HTTP to support local development. For a production store release, remove `android:usesCleartextTraffic="true"` and use HTTPS only.
- This is a source project, not a prebuilt or device-tested APK. The current environment did not include Android SDK/Gradle, so the Android project could not be compiled here. Build and verify it in Android Studio before relying on it.

## Troubleshooting

- **Bubble absent:** grant overlay permission, then tap Show floating Screen AI bubble again.
- **Capture unavailable:** open Screen AI and approve screen capture again, then restart the bubble.
- **Backend not found:** check the server is reachable from the phone and the URL contains only the scheme/host/port (the app appends `/solve`).
- **401 response:** ensure the app's shared token matches `SCREEN_AI_SHARED_TOKEN` on the server.
- **AI provider error:** check server logs, account API access/billing, and that the configured model is available for the API project.
- **Blank screenshot:** the target app may use secure/DRM content, or Android's screen-sharing mode may be set to a single app window rather than the whole screen.

## Build the APK using only an Android phone (GitHub Actions)

This repository includes `.github/workflows/android-apk.yml` to build a debug APK in GitHub's cloud. You still need to upload the **contents** of this project to a GitHub repository; uploading the ZIP as a single file is not enough for the workflow to find the Android project.

1. In your phone browser, sign in at https://github.com and create a new repository named `ScreenAI`.
2. Extract this ZIP with your phone's Files app.
3. In the repository, use **Add file → Upload files** and upload the extracted project files and folders (including the `.github/workflows/android-apk.yml` workflow). If your browser hides dot-folders, enable hidden files or create the workflow through GitHub's **Add file → Create new file** using the path `.github/workflows/android-apk.yml`.
4. Commit the files to the `main` branch.
5. Open the repository's **Actions** tab. Select **Build Screen AI APK** and press **Run workflow** if it hasn't started automatically.
6. Wait for the run to finish successfully, open that run, and download the artifact named **ScreenAI-debug-APK**. GitHub downloads the artifact as a ZIP; extract it to get `app-debug.apk`.
7. Tap `app-debug.apk` in your Files app to install it. Android may ask you to allow your browser or Files app to install unknown apps. Only install APKs you built from source you trust.

A debug APK is for testing, not Play Store distribution. The app's on-screen AI answers will not work until you configure and deploy the backend described above; do not put an OpenAI API key directly in the Android app.
