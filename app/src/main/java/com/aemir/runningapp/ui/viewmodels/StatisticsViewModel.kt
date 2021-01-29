package com.aemir.runningapp.ui.viewmodels

import androidx.hilt.lifecycle.ViewModelInject
import androidx.lifecycle.ViewModel
import com.aemir.runningapp.repositories.MainRepository

class StatisticsViewModel @ViewModelInject constructor(
    private val repository: MainRepository
) : ViewModel() {

    val totalTimeRun = repository.getTotalTimeInMillis()
    val totalDistance = repository.getTotalDistance()
    val totalCaloriesBurned = repository.getTotalCaloriesBurned()
    val totalAvgSpeed = repository.getTotalAvgSpeed()

    val runSortedByDate = repository.getAllRunsSortedByDate()
}