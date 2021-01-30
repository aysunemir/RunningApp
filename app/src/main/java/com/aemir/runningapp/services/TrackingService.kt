package com.aemir.runningapp.services

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.MutableLiveData
import com.aemir.runningapp.R
import com.aemir.runningapp.other.Constants.ACTION_PAUSE_SERVICE
import com.aemir.runningapp.other.Constants.ACTION_START_OR_RESUME_SERVICE
import com.aemir.runningapp.other.Constants.ACTION_STOP_SERVICE
import com.aemir.runningapp.other.Constants.FASTEST_LOCATION_INTERVAL
import com.aemir.runningapp.other.Constants.LOCATION_UPDATE_INTERVAL
import com.aemir.runningapp.other.Constants.NOTIFICATION_CHANNEL_ID
import com.aemir.runningapp.other.Constants.NOTIFICATION_CHANNEL_NAME
import com.aemir.runningapp.other.Constants.NOTIFICATION_ID
import com.aemir.runningapp.other.Constants.TIMER_UPDATE_INTERVAL
import com.aemir.runningapp.other.TrackingUtility
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationRequest.PRIORITY_HIGH_ACCURACY
import com.google.android.gms.location.LocationResult
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

typealias Polyline = MutableList<LatLng>
typealias Polylines = MutableList<Polyline>

/**
 * We are going to listen LiveData objects from this service.
 * For this reason, this class should be a LifecycleService.
 */

@AndroidEntryPoint
class TrackingService : LifecycleService() {

    var isFirstRun = true
    var serviceKilled = false

    @Inject
    lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private val timeRunInSeconds = MutableLiveData<Long>() // for updating notification text

    @Inject
    lateinit var baseNotificationBuilder: NotificationCompat.Builder

    lateinit var curNotificationBuilder: NotificationCompat.Builder

    // Listen from fragment
    companion object {
        val isTracking = MutableLiveData<Boolean>()
        val pathPoints = MutableLiveData<Polylines>()
        val timeRunInMillis = MutableLiveData<Long>() // for adding polyline to map and timer in ui
    }

    /** Resets values before start and after stop. */
    private fun postInitialValues() {
        isTracking.postValue(false)
        pathPoints.postValue(mutableListOf())
        timeRunInSeconds.postValue(0L)
        timeRunInMillis.postValue(0L)
    }

    override fun onCreate() {
        super.onCreate()
        curNotificationBuilder = baseNotificationBuilder
        postInitialValues()
        fusedLocationProviderClient = FusedLocationProviderClient(this)

        isTracking.observe(this, {
            updateLocationTracking(it)
            updateNotificationTrackingState(it)
        })
    }

    private fun killService() {
        serviceKilled = true
        isFirstRun = true
        isTimerEnabled = false
        postInitialValues()
        stopForeground(true) // Removes notification
        stopSelf() // Stops service
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.action) {
                ACTION_START_OR_RESUME_SERVICE -> {
                    if (isFirstRun) {
                        // Starts service if it's first run.
                        startForegroundService()
                        isFirstRun = false
                    } else {
                        // Resumes service if it has started before.
                        Timber.d("Resuming service")
                        startTimer()
                    }
                    Timber.d("Started or resumed service")
                }
                ACTION_PAUSE_SERVICE -> { // When clicked STOP button
                    Timber.d("Paused service")
                    pauseService()
                }
                ACTION_STOP_SERVICE -> { // When clicked FINISH RUN button
                    Timber.d("Stopped service")
                    killService()
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private var isTimerEnabled = false
    private var lapTime = 0L
    private var timeRun = 0L
    private var timeStarted = 0L
    private var lastSecondTimestamp = 0L

    /** Starts timer and updates time variables during tracking to update ui and notification texts. */
    private fun startTimer() {
        addEmptyPolyline()
        isTracking.postValue(true)
        timeStarted = System.currentTimeMillis()
        isTimerEnabled = true
        CoroutineScope(Dispatchers.Main).launch {
            while (isTracking.value!!) {
                lapTime = System.currentTimeMillis() - timeStarted
                timeRunInMillis.postValue(timeRun + lapTime)
                if (timeRunInMillis.value!! >= lastSecondTimestamp + 1000L) {
                    timeRunInSeconds.postValue(timeRunInSeconds.value!! + 1)
                    lastSecondTimestamp += 1000L
                }
                delay(TIMER_UPDATE_INTERVAL)
            }
            timeRun += lapTime
        }
    }

    /** Updates isTracking to trigger fragment and service to take actions. */
    private fun pauseService() {
        isTracking.postValue(false)
        isTimerEnabled = false
    }

    /** Updates notification actions and text according to isTracking value. Creates pending intent
     *  for pause or resume for corresponding actions. */
    private fun updateNotificationTrackingState(isTracking: Boolean) {
        val notificationActionText = if (isTracking) "Pause" else "Resume"
        val pendingIntent = if (isTracking) {
            val pauseIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_PAUSE_SERVICE
            }
            PendingIntent.getService(this, 1, pauseIntent, FLAG_UPDATE_CURRENT)
        } else {
            val resumeIntent = Intent(this, TrackingService::class.java).apply {
                action = ACTION_START_OR_RESUME_SERVICE
            }
            PendingIntent.getService(this, 1, resumeIntent, FLAG_UPDATE_CURRENT)
        }
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        curNotificationBuilder.javaClass.getDeclaredField("mActions").apply {
            isAccessible = true
            set(curNotificationBuilder, arrayListOf<NotificationCompat.Action>())
        }
        if (!serviceKilled) {
            curNotificationBuilder = baseNotificationBuilder.addAction(
                R.drawable.ic_pause_black_24dp,
                notificationActionText,
                pendingIntent
            )
            notificationManager.notify(NOTIFICATION_ID, curNotificationBuilder.build())
        }
    }

    /** Get location updates according to button clicks (request or remove) */
    @SuppressLint("MissingPermission")
    private fun updateLocationTracking(isTracking: Boolean) {
        if (isTracking) {
            if (TrackingUtility.hasLocationPermissions(this)) {
                val request = LocationRequest().apply {
                    interval = LOCATION_UPDATE_INTERVAL
                    fastestInterval = FASTEST_LOCATION_INTERVAL
                    priority = PRIORITY_HIGH_ACCURACY
                }
                fusedLocationProviderClient.requestLocationUpdates(
                    request,
                    locationCallback,
                    Looper.getMainLooper()
                )
            }
        } else {
            fusedLocationProviderClient.removeLocationUpdates(locationCallback)
        }
    }

    /** Callback for listening location updates and adds path points. */
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult?) {
            super.onLocationResult(result)
            if (isTracking.value!!) {
                result?.locations?.map { location ->
                    addPathPoint(location)
                    Timber.d("NEW LOCATION: ${location.latitude}, ${location.longitude}")
                }
            }
        }
    }

    private fun addPathPoint(location: Location?) {
        location?.let {
            val position = LatLng(it.latitude, it.longitude)
            pathPoints.value?.apply {
                last().add(position)
                pathPoints.postValue(this)
            }
        }
    }

    private fun addEmptyPolyline() = pathPoints.value?.apply {
        add(mutableListOf())
        pathPoints.postValue(this)
    } ?: pathPoints.postValue(mutableListOf(mutableListOf()))

    /** Starts timer, shows notification updates notification text */
    private fun startForegroundService() {
        startTimer()
        isTracking.postValue(true)

        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(notificationManager)
        }

        startForeground(NOTIFICATION_ID, baseNotificationBuilder.build())

        timeRunInSeconds.observe(this, {
            if (!serviceKilled) {
                val notification = curNotificationBuilder.setContentText(
                    TrackingUtility.getFormattedStopWatchTime(it * 1000L)
                )
                notificationManager.notify(NOTIFICATION_ID, notification.build())
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel(notificationManager: NotificationManager) {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_NAME,
            IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}