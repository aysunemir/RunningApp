package com.aemir.runningapp.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.aemir.runningapp.R
import com.aemir.runningapp.databinding.FragmentTrackingBinding
import com.aemir.runningapp.db.Run
import com.aemir.runningapp.other.Constants.ACTION_PAUSE_SERVICE
import com.aemir.runningapp.other.Constants.ACTION_START_OR_RESUME_SERVICE
import com.aemir.runningapp.other.Constants.ACTION_STOP_SERVICE
import com.aemir.runningapp.other.Constants.MAP_ZOOM
import com.aemir.runningapp.other.Constants.POLYLINE_COLOR
import com.aemir.runningapp.other.Constants.POLYLINE_WIDTH
import com.aemir.runningapp.other.TrackingUtility
import com.aemir.runningapp.services.Polyline
import com.aemir.runningapp.services.TrackingService
import com.aemir.runningapp.ui.viewmodels.MainViewModel
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import java.util.*
import javax.inject.Inject
import kotlin.math.round

const val CANCEL_TRACKING_DIALOG_TAG = "CancelDialog"

@AndroidEntryPoint
class TrackingFragment : Fragment(R.layout.fragment_tracking) {

    private lateinit var binding: FragmentTrackingBinding
    private val viewModel: MainViewModel by viewModels()

    private var isTracking = false
    private var pathPoints = mutableListOf<Polyline>()

    private var map: GoogleMap? = null

    private var curTimeInMillis = 0L

    private var menu: Menu? = null

    @set:Inject
    var weight = 68f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        setHasOptionsMenu(true)
        return super.onCreateView(inflater, container, savedInstanceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = FragmentTrackingBinding.bind(view).apply {
            mapView.onCreate(savedInstanceState)
            btnToggleRun.setOnClickListener {
                toggleRun()
            }
            btnFinishRun.setOnClickListener {
                zoomToSeeWholeTrack()
                endRunAndSaveToDb()
            }
            mapView.getMapAsync {
                map = it
                addAllPolylines()
            }
        }
        // Handle configuration changes
        if (savedInstanceState != null) {
            val cancelTrackingDialogFragment = parentFragmentManager.findFragmentByTag(
                CANCEL_TRACKING_DIALOG_TAG
            ) as CancelTrackingDialogFragment?
            cancelTrackingDialogFragment?.setYesListener {
                stopRun()
            }
        }
        subscribeToObservers()
    }

    private fun subscribeToObservers() {
        TrackingService.isTracking.observe(viewLifecycleOwner, {
            updateTracking(it)
        })

        TrackingService.pathPoints.observe(viewLifecycleOwner, {
            pathPoints = it
            addLatestPolyline()
            moveCameraToUser()
        })

        TrackingService.timeRunInMillis.observe(viewLifecycleOwner, {
            curTimeInMillis = it
            val formattedTime = TrackingUtility.getFormattedStopWatchTime(curTimeInMillis, true)
            binding.tvTimer.text = formattedTime
        })
    }

    /**
     * This method triggers service to start/resume or pause timer and listening location updates
     */
    private fun toggleRun() {
        if (isTracking) {
            // Pause service
            menu?.getItem(0)?.isVisible = true
            sendCommandToService(ACTION_PAUSE_SERVICE)
        } else {
            // Start service
            sendCommandToService(ACTION_START_OR_RESUME_SERVICE)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.toolbar_tracking_menu, menu)
        this.menu = menu
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        if (curTimeInMillis > 0L) {
            this.menu?.getItem(0)?.isVisible = true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.miCancelTracking -> showCancelTrackingDialog()
        }
        return super.onOptionsItemSelected(item)
    }

    /** Shows cancel tracking dialog when user clicks cancel button from menu. */
    private fun showCancelTrackingDialog() {
        CancelTrackingDialogFragment().apply {
            setYesListener {
                stopRun()
            }
        }.show(parentFragmentManager, CANCEL_TRACKING_DIALOG_TAG)
    }

    /** Stops run and returns to run fragment. */
    private fun stopRun() {
        binding.tvTimer.text = "00:00:00:00"
        sendCommandToService(ACTION_STOP_SERVICE)
        findNavController().navigate(R.id.action_trackingFragment_to_runFragment)
    }

    /** Listens service state and updates ui. */
    private fun updateTracking(isTracking: Boolean) {
        this.isTracking = isTracking
        if (!isTracking && curTimeInMillis > 0L) {
            with(binding) {
                btnToggleRun.text = "Start"
                btnFinishRun.visibility = View.VISIBLE
            }
        } else if (isTracking) {
            with(binding) {
                btnToggleRun.text = "Stop"
                menu?.getItem(0)?.isVisible = true
                btnFinishRun.visibility = View.GONE
            }
        }
    }

    /** Moves camera to user's location when location is updated. */
    private fun moveCameraToUser() {
        if (pathPoints.isNotEmpty() && pathPoints.last().isNotEmpty()) {
            map?.animateCamera(
                CameraUpdateFactory.newLatLngZoom(
                    pathPoints.last().last(),
                    MAP_ZOOM
                )
            )
        }
    }

    /** When run is finished, zooms out map to cover all tracking paths. */
    private fun zoomToSeeWholeTrack() {
        val bounds = LatLngBounds.Builder()
        pathPoints.map { polyline ->
            polyline.map { pos ->
                bounds.include(pos)
            }
        }

        map?.moveCamera(
            CameraUpdateFactory.newLatLngBounds(
                bounds.build(),
                binding.mapView.width,
                binding.mapView.height,
                (binding.mapView.height * 0.05f).toInt(),
            )
        )
    }

    /** When run is finished, saves run data with map's snapshot to db. */
    private fun endRunAndSaveToDb() {
        map?.snapshot { bmp ->
            var distanceInMeters = 0
            pathPoints.map {
                distanceInMeters += TrackingUtility.calculatePolylineLength(it).toInt()
            }
            val avgSpeed =
                round((distanceInMeters / 1000f) / (curTimeInMillis / 1000f / 60 / 60) * 10) / 10f
            val dateTimestamp = Calendar.getInstance().timeInMillis
            val caloriesBurned = ((distanceInMeters / 1000f) * weight).toInt()
            val run =
                Run(bmp, dateTimestamp, avgSpeed, distanceInMeters, curTimeInMillis, caloriesBurned)
            viewModel.insertRun(run)
            Snackbar.make(
                requireActivity().findViewById(R.id.rootView),
                "Run saved successfully",
                Snackbar.LENGTH_LONG
            ).show()
            stopRun()
        }
    }

    /** Draw path on map when page is brought to foreground (notification click). */
    private fun addAllPolylines() {
        pathPoints.map {
            val polylineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .addAll(it)
            map?.addPolyline(polylineOptions)
        }
    }

    /** Draws latest path on map when location is updated*/
    private fun addLatestPolyline() {
        if (pathPoints.isNotEmpty() && pathPoints.last().size > 1) {
            val preLastLatLng = pathPoints.last()[pathPoints.last().size - 2]
            val lastLatLng = pathPoints.last().last()
            val polylineOptions = PolylineOptions()
                .color(POLYLINE_COLOR)
                .width(POLYLINE_WIDTH)
                .add(preLastLatLng)
                .add(lastLatLng)
            map?.addPolyline(polylineOptions)
        }
    }

    /** Send action to service to start or stop service */
    private fun sendCommandToService(action: String) =
        Intent(requireContext(), TrackingService::class.java).also {
            it.action = action
            requireContext().startService(it)
        }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onStart() {
        super.onStart()
        binding.mapView.onStart()
    }

    override fun onStop() {
        super.onStop()
        binding.mapView.onStop()
    }

    override fun onPause() {
        super.onPause()
        binding.mapView.onPause()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        binding.mapView.onLowMemory()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.mapView.onSaveInstanceState(outState)
    }
}