package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PhotoDetectionService extends Service {

    private ContentObserver mObserver;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private long lastProcessedId = -1; // To prevent duplicate uploads

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundService(); // Keep service alive

        // Register the observer to watch external images
        mObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                // When a change is detected, look for the latest image
                checkForNewPhoto();
            }
        };

        getContentResolver().registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                mObserver
        );
    }

    private void checkForNewPhoto() {
        // Query the media store for the most recent image
        String[] projection = {
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
        };

        // Sort by Date Added Descending
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                MediaStore.Images.Media.DATE_ADDED + " DESC")) {

            if (cursor != null && cursor.moveToFirst()) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID);
                long id = cursor.getLong(idColumn);

                // If this is a new ID we haven't seen yet
                if (id != lastProcessedId) {
                    lastProcessedId = id;

                    // Construct the URI for the specific image
                    Uri contentUri = Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "" + id);

                    Log.d("PhotoDetector", "New Photo Detected: " + contentUri.toString());
                    uploadImage(contentUri);
                }
            }
        } catch (Exception e) {
            Log.e("PhotoDetector", "Error querying media", e);
        }
    }

    private void uploadImage(Uri imageUri) {
        OkHttpClient client = new OkHttpClient();

        try {
            // Read the file data from URI
            byte[] fileBytes = getBytesFromUri(imageUri);
            if (fileBytes == null) return;

            RequestBody requestBody = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("image", "detected.jpg",
                            RequestBody.create(fileBytes, MediaType.parse("image/jpeg")))
                    .build();

            // REPLACE THIS URL
            Request request = new Request.Builder()
                    .url("https://face-detection-test-1.onrender.com/detect-face")
                    .post(requestBody)
                    .build();

            client.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {
                    Log.e("PhotoDetector", "Upload failed", e);
                }

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    if (response.isSuccessful()) {
                        String responseText = response.body().string();
                        Log.d("PhotoDetector", "Server says: " + responseText);
                        // You can create a Notification here to show the text to the user
                        saveTextToFile(responseText);

                        NotificationManager manager = getSystemService(NotificationManager.class);
                        String channelId = "photo_detector_channel";

                        // Ensure channel priority is high enough to pop up
                        NotificationChannel channel = manager.getNotificationChannel(channelId);
                        if (channel != null) {
                            channel.setImportance(NotificationManager.IMPORTANCE_HIGH);
                            manager.createNotificationChannel(channel);
                        }

                        Notification notification = new NotificationCompat.Builder(PhotoDetectionService.this, channelId)
                                .setContentTitle("Photo Analysis Complete")
                                .setContentText(responseText) // The text file content from website
                                .setStyle(new NotificationCompat.BigTextStyle().bigText(responseText)) // Expandable text
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .build();

                        manager.notify(2, notification); // ID 2 makes it a separate notification
                        // -
                    }
                }
            });

        } catch (Exception e) {
            Log.e("PhotoDetector", "Prepare upload failed", e);
        }
    }

    private byte[] getBytesFromUri(Uri uri) throws IOException {
        InputStream iStream = getContentResolver().openInputStream(uri);
        ByteArrayOutputStream byteBuffer = new ByteArrayOutputStream();
        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];
        int len;
        while ((len = iStream.read(buffer)) != -1) {
            byteBuffer.write(buffer, 0, len);
        }
        return byteBuffer.toByteArray();
    }

    // Boilerplate for Foreground Service (Required for Android 8+)
    private void startForegroundService() {
        String channelId = "photo_detector_channel";
        NotificationChannel channel = new NotificationChannel(
                channelId, "Photo Detection", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);

        Notification notification = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("Photo Detector Running")
                .setContentText("Watching for new photos...")
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .build();

        startForeground(1, notification);
    }

    private void saveTextToFile(String text) {
        try {
            // Create a file in the app's internal files directory
            File file = new File(getFilesDir(), "server_response.txt");

            // Or use this if you want a unique name every time:
            // File file = new File(getFilesDir(), "response_" + System.currentTimeMillis() + ".txt");

            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.append(text);
            writer.flush();
            writer.close();

            Log.d("PhotoDetector", "File saved to: " + file.getAbsolutePath());
        } catch (IOException e) {
            Log.e("PhotoDetector", "Error saving file", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        getContentResolver().unregisterContentObserver(mObserver);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
