package com.lineup.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lineup.app.data.ProcessingState
import com.lineup.app.databinding.ActivityProcessingBinding
import kotlinx.coroutines.launch

class ProcessingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessingBinding
    private val viewModel: ProcessingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriStr == null) {
            finish()
            return
        }

        viewModel.start(Uri.parse(uriStr))

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun render(state: ProcessingState) {
        when (state) {
            is ProcessingState.Idle -> Unit
            is ProcessingState.Running -> {
                binding.stageLabel.text = state.stage.label
                binding.progressBar.isIndeterminate = state.total <= 0
                if (state.total > 0) {
                    binding.progressBar.max = state.total
                    binding.progressBar.progress = state.done
                }
                binding.countLabel.text = "${state.done} / ${state.total}"
            }
            is ProcessingState.Step1Complete -> {
                val framesWithFaces = state.detections.map { it.frameIndex }.distinct().size
                binding.stageLabel.text = "Step 1 complete"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = binding.progressBar.max
                binding.countLabel.text = buildString {
                    appendLine("Frames extracted: ${state.frames.size}")
                    appendLine("Face detections: ${state.detections.size}")
                    appendLine("Frames with >=1 face: $framesWithFaces")
                    val multi = state.detections
                        .groupingBy { it.frameIndex }.eachCount()
                        .count { it.value >= 2 }
                    appendLine("Frames with >=2 faces: $multi")
                }
            }
            is ProcessingState.Step2Complete -> {
                binding.stageLabel.text = "${state.clusters.size} people found"
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = binding.progressBar.max
                binding.countLabel.text = buildString {
                    appendLine("Similarity threshold: ${state.similarityThreshold}")
                    appendLine("People found: ${state.clusters.size}")
                    appendLine("Total appearances: ${state.appearances.size}")
                    for ((personId, count) in state.appearanceCounts) {
                        appendLine("  person $personId: $count appearance(s)")
                    }
                }
            }
            is ProcessingState.Failed -> {
                binding.stageLabel.text = "Failed"
                binding.countLabel.text = state.message
            }
        }
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }
}
