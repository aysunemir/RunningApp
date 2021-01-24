package com.aemir.runningapp.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.aemir.runningapp.databinding.ItemRunBinding
import com.aemir.runningapp.db.Run
import com.aemir.runningapp.other.TrackingUtility
import com.bumptech.glide.RequestManager
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

class RunAdapter @Inject constructor(val requestManager: RequestManager) :
    ListAdapter<Run, RunAdapter.RunViewHolder>(RunDiffCallback()) {

    inner class RunViewHolder(private val binding: ItemRunBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bindRun(run: Run) {
            binding.apply {
                requestManager.load(run.img).into(ivRunImage)

                val calendar = Calendar.getInstance().apply {
                    timeInMillis = run.timestamp
                }
                val dateFormat = SimpleDateFormat("dd.MM.yy", Locale.getDefault())
                tvDate.text = dateFormat.format(calendar.time)

                val avgSpeed = "${run.avgSpeedInKMH}km/h"
                tvAvgSpeed.text = avgSpeed

                val distanceInKm = "${run.distanceInMeters / 1000f}km"
                tvDistance.text = distanceInKm

                tvTime.text = TrackingUtility.getFormattedStopWatchTime(run.timeInMillis)

                val caloriesBurned = "${run.caloriesBurned}kcal"
                tvCalories.text = caloriesBurned
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RunViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = ItemRunBinding.inflate(layoutInflater, parent, false)
        return RunViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RunViewHolder, position: Int) {
        holder.bindRun(getItem(position))
    }

}

class RunDiffCallback : DiffUtil.ItemCallback<Run>() {
    override fun areItemsTheSame(oldItem: Run, newItem: Run): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(oldItem: Run, newItem: Run): Boolean =
        oldItem.hashCode() == newItem.hashCode()
}