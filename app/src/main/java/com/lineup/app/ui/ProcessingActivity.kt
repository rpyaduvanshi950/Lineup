package com.lineup.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
                // Superseded by Step2/Step3Complete in the normal flow; kept for the
                // Step1-only checkpoint path.
                binding.stageIcon.setImageResource(R.drawable.ic_check_circle)
                binding.stageLabel.text = getString(R.string.stage_detecting_faces)
                binding.progressBar.isIndeterminate = false
                binding.progressBar.progress = binding.progressBar.max
                binding.countLabel.text =
                    "${state.frames.size} frames, ${state.detections.size} detections"
            }

            is ProcessingState.Step2Complete -> {
                // Transient: Step 3 (collage) is still running: keep showing progress.
                binding.stageIcon.setImageResource(iconFor(ProcessingState.Stage.COMPOSING))
                binding.stageLabel.setText(R.string.stage_composing)
                binding.progressBar.isIndeterminate = true
                binding.countLabel.text = ""
            }

            is ProcessingState.Step3Complete -> {
                val intent = Intent(this, ResultsActivity::class.java).apply {
                    putExtra(ResultsActivity.EXTRA_COLLAGE_PATH, state.collageFilePath)
                    putExtra(
                        ResultsActivity.EXTRA_PERSON_IDS,
                        state.appearanceCounts.map { it.first }.toIntArray(),
                    )
                    putExtra(
                        ResultsActivity.EXTRA_APPEARANCE_COUNTS,
                        state.appearanceCounts.map { it.second }.toIntArray(),
                    )
                    putExtra(ResultsActivity.EXTRA_TOTAL_APPEARANCES, state.appearances.size)
                    putExtra(ResultsActivity.EXTRA_THRESHOLD, state.similarityThreshold)
                }
                startActivity(intent)
                finish()
            }

            is ProcessingState.Failed -> {
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
        ProcessingState.Stage.COMPOSING -> R.drawable.ic_check_circle
    }

    private fun labelFor(stage: ProcessingState.Stage) = when (stage) {
        ProcessingState.Stage.EXTRACTING_FRAMES -> R.string.stage_extracting_frames
        ProcessingState.Stage.DETECTING_FACES -> R.string.stage_detecting_faces
        ProcessingState.Stage.EMBEDDING_FACES -> R.string.stage_embedding_faces
        ProcessingState.Stage.CLUSTERING -> R.string.stage_clustering
        ProcessingState.Stage.COMPOSING -> R.string.stage_composing
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }
}
