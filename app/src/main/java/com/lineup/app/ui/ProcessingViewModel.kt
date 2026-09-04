package com.lineup.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lineup.app.data.ProcessingState
import com.lineup.app.data.VideoRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ProcessingViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = VideoRepository(app)
    val state: StateFlow<ProcessingState> = repository.state

    private var job: Job? = null

    fun start(uri: Uri) {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            repository.run(uri)
        }
    }

    fun cancel() {
        job?.cancel()
        repository.reset()
    }
}
