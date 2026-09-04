package com.lineup.app.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.lineup.app.R
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

        binding.peopleRecycler.layoutManager = LinearLayoutManager(this)

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
                binding.progressGroup.visibility = android.view.View.VISIBLE
                binding.resultsGroup.visibility = android.view.View.GONE
                binding.stageIcon.setImageResource(iconFor(state.stage))
                binding.stageLabel.setText(labelFor(state.stage))
                binding.progressBar.isIndeterminate = state.total <= 0
                if (state.total > 0) {
                    binding.progressBar.max = state.total
                    binding.progressBar.progress = state.done
                }
                binding.countLabel.text = if (state.total > 0) "${state.done} / ${state.total}" else ""
            }

            is ProcessingState.Step1Complete -> {
                // Superseded by Step2Complete in the normal flow; kept for the Step1-only
                // checkpoint path. Show a minimal completed state.
                binding.progressGroup.visibility = android.view.View.VISIBLE
                binding.resultsGroup.visibility = android.view.View.GONE
                binding.stageIcon.setImageResource(R.drawable.ic_check_circle)
                binding.stageLabel.text = getString(R.string.stage_detecting_faces)
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = binding.progressBar.max
                binding.countLabel.text =
                    "${state.frames.size} frames, ${state.detections.size} detections"
            }

            is ProcessingState.Step2Complete -> {
                binding.progressGroup.visibility = android.view.View.GONE
                binding.resultsGroup.visibility = android.view.View.VISIBLE
                binding.resultsTitle.text =
                    getString(R.string.results_title_format, state.clusters.size)
                binding.resultsSubtitle.text = getString(
                    R.string.results_subtitle_format,
                    state.appearances.size,
                    "%.2f".format(state.similarityThreshold),
                )
                val rows = state.appearanceCounts.map { (id, count) -> PersonRow(id, count) }
                binding.peopleRecycler.adapter = PersonAdapter(rows)
            }

            is ProcessingState.Failed -> {
                binding.progressGroup.visibility = android.view.View.VISIBLE
                binding.resultsGroup.visibility = android.view.View.GONE
                binding.stageLabel.text = getString(R.string.processing_failed)
                binding.progressBar.isIndeterminate = false
                binding.countLabel.text = state.message
            }
        }
    }

    private fun iconFor(stage: ProcessingState.Stage) = when (stage) {
        ProcessingState.Stage.EXTRACTING_FRAMES -> R.drawable.ic_stage_frames
        ProcessingState.Stage.DETECTING_FACES -> R.drawable.ic_stage_face
        ProcessingState.Stage.EMBEDDING_FACES -> R.drawable.ic_stage_identify
        ProcessingState.Stage.CLUSTERING -> R.drawable.ic_stage_cluster
    }

    private fun labelFor(stage: ProcessingState.Stage) = when (stage) {
        ProcessingState.Stage.EXTRACTING_FRAMES -> R.string.stage_extracting_frames
        ProcessingState.Stage.DETECTING_FACES -> R.string.stage_detecting_faces
        ProcessingState.Stage.EMBEDDING_FACES -> R.string.stage_embedding_faces
        ProcessingState.Stage.CLUSTERING -> R.string.stage_clustering
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }
}
