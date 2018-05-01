package org.maialinux.oldgoatnewtricks;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.util.Log;
import android.widget.Toast;


import org.joda.time.DateTime;
import org.joda.time.LocalDate;
import org.joda.time.LocalDateTime;
import org.joda.time.LocalTime;
import org.joda.time.Period;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.joda.time.format.PeriodFormatter;
import org.joda.time.format.PeriodFormatterBuilder;


public class AlertService extends Service {


    public static final long INTERVAL = 3600000; // One hour
    private static final long ALERT_INTERVAL = 600000; // ten minutes
    private static final String SLEEP_TIME = "22:00";
    private static final String WAKE_TIME = "08:00";
    private static final int MAX_ALERTS = 3;
    private static final long ALERT_DELAY = Math.round(ALERT_INTERVAL/MAX_ALERTS);
    public static final String BROADCAST_ACTION = "org.maialinux.oldgoatnewtricks.alert_service_broadcast";
    public static final String RESET_MESSAGE = "reset";
    public static final String END_MESSAGE = "stop";



    //private final IBinder mBinder = new LocalBinder();
    private final String TAG = "AlertService";
    Ringtone ringtone;
    long expirationTime;
    int alertCounts = 0;
    long sleepDelay;

    Intent broadCastIntent;
    Intent serviceIntent;
    Intent resetIntent;
    boolean accelerometerServiceStarted = false;
    LocalDateTime wakeUpDateTime;
    LocalDateTime sleepDateTime;

    NotificationManagerCompat notificationManager;
    NotificationCompat.Builder mBuilder;
    Notification notification;


    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            stopRingtone();
            long delay;
            if (isSleepTime() == false) {
                long millis = expirationTime - System.currentTimeMillis();

                long minutes = millis / 60000;
                long seconds = millis / 1000 - (minutes * 60); // Remainder
                String message = String.format("Alert timer: %sm %ss remaining", minutes, seconds);
                logEntry(message, false);
                if (millis <= 0) {
                    alertCounts++;
                    delay = 10000; // Rings for about ten seconds
                    stopRingtone();
                    playRingtone();
                    expirationTime = System.currentTimeMillis() + ALERT_INTERVAL; // Reset the expiration time
                    logEntry(String.format("Alert number %d", alertCounts), true);


                } else {
                    if (alertCounts == 0) {
                        delay = Math.round(INTERVAL / 4);
                    } else {
                        delay = ALERT_DELAY;
                    }
                }
                if (accelerometerServiceStarted == false) {
                    startAccelerometerService();
                }
            } else {
                delay = sleepDelay;
                logEntry("Sleep time", false);
                logEntry(String.format("Sleeping for %s seconds", String.valueOf(delay/1000)), false);
                stopAccelerometerService();
            }
            if (alertCounts >= MAX_ALERTS) {
                stopRingtone();
                logEntry("Send SMS", true);
                timerHandler.removeCallbacks(timerRunnable);
                stopAccelerometerService();
                stopSelf();
            }
            updateNotificationText();
            timerHandler.postDelayed(this, delay);

        }

    };

    /*
    public class LocalBinder extends Binder {

        AlertService getService() {
            return AlertService.this;
        }
    }
    */

    public AlertService() {

    }

    @Override
    public void onCreate() {

        alertCounts = 0;
        Uri ringtoneURI = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        ringtone = RingtoneManager.getRingtone(getBaseContext(), ringtoneURI);
        expirationTime = System.currentTimeMillis() + INTERVAL;

        broadCastIntent = new Intent(BROADCAST_ACTION);
        registerReceiver(broadcastReceiver, new IntentFilter(BROADCAST_ACTION));
        serviceIntent = new Intent(this, AccelerometerService.class);
        parseSleepingTimes();
        DateTimeFormatter formatter = ISODateTimeFormat.dateTimeNoMillis();
        logEntry(String.format("Application will be sleeping from %s to %s", formatter.print(sleepDateTime), formatter.print(wakeUpDateTime)), false);
        resetIntent = new Intent(AlertService.BROADCAST_ACTION);
        resetIntent.putExtra(AlertService.RESET_MESSAGE, true);
        Intent stopIntent = new Intent(AlertService.BROADCAST_ACTION);
        stopIntent.putExtra(AlertService.END_MESSAGE, true);
        PendingIntent resetPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, resetIntent, 0);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(getApplicationContext(), 1, stopIntent, 0);
        mBuilder = new NotificationCompat.Builder(this, "Something")
                .setContentText("")
                .setContentTitle("Dead-man notification")
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_stat_notification)
                .addAction(R.drawable.ic_stat_reset, "Reset", resetPendingIntent)
                .addAction(R.drawable.ic_stat_stop, "Stop", stopPendingIntent);
        notificationManager = NotificationManagerCompat.from(this);
        notification = mBuilder.build();
        startForeground(99, notification);

    }


    @Override
    public IBinder onBind(Intent intent) {
        logEntry("Service bound", true);
        //return mBinder;
        return null;
    }


    public void playRingtone() {
        //Log.d(TAG, "play ringtone");
        if (! ringtone.isPlaying()) {
            ringtone.play();
        }
    }

    public void stopRingtone() {
        //Log.d(TAG, "stop ringtone");
        ringtone.stop();
    }

    public void startTimer() {
        alertCounts = 0;
        expirationTime = System.currentTimeMillis() + INTERVAL;
        timerHandler.removeCallbacks(timerRunnable);
        timerRunnable.run();
    }

    private void logEntry(String message, boolean toast) {
        if (toast) {
            Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
        }
        Log.d(TAG, message);
        broadCastIntent.putExtra("message", message);
        sendBroadcast(broadCastIntent);
    }

    private boolean isSleepTime() {
        boolean sleep = false;
        if (alertCounts == 0) { /* Never go to sleep if there are alerts */
            LocalDateTime now = LocalDateTime.now();
            /* We are between sleep time and wake-up time */
            if (now.isAfter(sleepDateTime)) {
                /* This is to correctly calculate the delay time till wake-up */
                if (wakeUpDateTime.isBefore(now)) {
                    wakeUpDateTime = wakeUpDateTime.plusDays(1);
                }
                sleepDateTime = sleepDateTime.plusDays(1);
                sleep = true;
            } else if (now.isBefore(wakeUpDateTime)) {
                if (sleepDateTime.isAfter(wakeUpDateTime)) {
                    sleep = true;
                }
            }
            if (sleep == true) {
                Period sleepPeriod = new Period(now, wakeUpDateTime);
                sleepDelay = sleepPeriod.toStandardDuration().getMillis();
                DateTimeFormatter format = ISODateTimeFormat.dateTimeNoMillis();
                logEntry(String.format("Sleep time %s", format.print(sleepDateTime)), false);
                logEntry(String.format("Wakeup time %s", format.print(wakeUpDateTime)), false);
                PeriodFormatter formatter = new PeriodFormatterBuilder()
                        .appendHours()
                        .appendSuffix("h")
                        .appendMinutes()
                        .appendSuffix("m")
                        .toFormatter();
                logEntry(String.format("Sleeping for %s", formatter.print(sleepPeriod)), false);
                expirationTime = System.currentTimeMillis() + sleepDelay + INTERVAL;
            } else {
                sleepDelay = 0;
            }

        }
        return sleep;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        this.startTimer();
        logEntry("Start alert service", true);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy()
    {
        logEntry("Destroy alert service", true);
        this.stopRingtone();
        timerHandler.removeCallbacks(timerRunnable);
        unregisterReceiver(broadcastReceiver);
        stopAccelerometerService();
        notificationManager.cancel(0);
    }

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
           boolean resetMessage = intent.getBooleanExtra(RESET_MESSAGE, false);
           boolean endMessage = intent.getBooleanExtra(END_MESSAGE, false);
           if (resetMessage == true) {
               expirationTime = System.currentTimeMillis() + INTERVAL;
               stopRingtone();
               logEntry("Reset timer", false);
               alertCounts = 0;
               timerHandler.removeCallbacks(timerRunnable);
               timerRunnable.run();
           }
           if (endMessage == true) {
               logEntry("Stop service", false);
               stopRingtone();
               stopSelf();
           }
        }
    };

    private void startAccelerometerService()
    {
        startService(serviceIntent);
        accelerometerServiceStarted = true;
    }

    private void stopAccelerometerService()
    {
        stopService(serviceIntent);
        accelerometerServiceStarted = false;
    }

    private void parseSleepingTimes()
    {
        DateTimeFormatter formatter = ISODateTimeFormat.hourMinute();
        LocalTime wakeUpTime = LocalTime.parse(WAKE_TIME, formatter);
        LocalTime sleepTime = LocalTime.parse(SLEEP_TIME, formatter);
        LocalDate today = new LocalDate();
        sleepDateTime = new LocalDateTime()
                .withDate(today.getYear(), today.getMonthOfYear(), today.getDayOfMonth())
                .withMillisOfDay(sleepTime.getMillisOfDay());
        wakeUpDateTime = new LocalDateTime()
                .withDate(today.getYear(), today.getMonthOfYear(), today.getDayOfMonth())
                .withMillisOfDay(wakeUpTime.getMillisOfDay());
        /* If the application is started during the wake up period, shift the wake up a day forwards */
        if (wakeUpDateTime.isBefore(LocalDateTime.now())) {
            wakeUpDateTime = wakeUpDateTime.plusDays(1);
        }
    }

    private void updateNotificationText()
    {
        DateTimeFormatter formatter = ISODateTimeFormat.hourMinuteSecond();
        String text = String.format("Timer expiring at %s", formatter.print(new DateTime(expirationTime)));
        mBuilder.setContentText(text);
        notification = mBuilder.build();
        notificationManager.notify(99, notification);
    }

}
