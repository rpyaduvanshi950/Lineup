package com.lineup.app.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
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
import com.lineup.app.databinding.ItemStepperNodeBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProcessingBinding
    private val viewModel: ProcessingViewModel by viewModels()

    private lateinit var stepNodes: List<ItemStepperNodeBinding>
    private var shownPeopleCount = 0
    private var lastPreviewPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProcessingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        stepNodes = listOf(binding.step1, binding.step2, binding.step3, binding.step4, binding.step5)
        val stepMeta = listOf(
            R.drawable.ic_stage_frames to "Extract",
            R.drawable.ic_stage_face to "Detect",
            R.drawable.ic_stage_identify to "Embed",
            R.drawable.ic_stage_cluster to "Cluster",
            R.drawable.ic_stage_compose to "Compose",
        )
        stepNodes.forEachIndexed { i, node ->
            node.stepIcon.setImageResource(stepMeta[i].first)
            node.stepLabel.text = stepMeta[i].second
        }

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
                updateStepper(state.stage.ordinal)
                binding.stageLabel.setText(labelFor(state.stage))
                binding.progressRing.max = state.total.coerceAtLeast(1)
                binding.progressRing.setProgressCompat(state.done.coerceAtLeast(0), true)
                binding.countLabel.text = if (state.total > 0) "${state.done} / ${state.total}" else ""
                animateCounterTo(state.peopleFound)
                updatePreview(state.previewFramePath)
            }

            is ProcessingState.Step1Complete -> {
                // Superseded by Step2/Step3Complete in the normal flow; kept for the
                // Step1-only checkpoint path.
                updateStepper(1)
                binding.stageLabel.text = getString(R.string.stage_detecting_faces)
                binding.countLabel.text =
                    "${state.frames.size} frames, ${state.detections.size} detections"
            }

            is ProcessingState.Step2Complete -> {
                // Transient: Step 3 (collage) is still running; keep showing progress.
                updateStepper(ProcessingState.Stage.COMPOSING.ordinal)
                binding.stageLabel.setText(R.string.stage_composing)
                animateCounterTo(state.clusters.size)
            }

            is ProcessingState.Step3Complete -> {
                val personIds = state.appearanceCounts.map { it.first }
                val intent = Intent(this, ResultsActivity::class.java).apply {
                    putExtra(ResultsActivity.EXTRA_COLLAGE_PATH, state.collageFilePath)
                    putExtra(ResultsActivity.EXTRA_PERSON_IDS, personIds.toIntArray())
                    putExtra(
                        ResultsActivity.EXTRA_APPEARANCE_COUNTS,
                        state.appearanceCounts.map { it.second }.toIntArray(),
                    )
                    putExtra(
                        ResultsActivity.EXTRA_AVATAR_PATHS,
                        personIds.map { state.avatarPaths[it] }.toTypedArray(),
                    )
                    putExtra(ResultsActivity.EXTRA_TOTAL_APPEARANCES, state.appearances.size)
                    putExtra(ResultsActivity.EXTRA_THRESHOLD, state.similarityThreshold)
                }
                startActivity(intent)
                finish()
            }

            is ProcessingState.Failed -> {
                binding.stageLabel.text = getString(R.string.processing_failed)
                binding.countLabel.text = state.message
            }
        }
    }

    /** Amber for done/active stages, muted surface for pending -- "icons that light up amber
     * as each completes." */
    private fun updateStepper(currentStageIndex: Int) {
        stepNodes.forEachIndexed { i, node ->
            val bg = node.stepIconFrame.background.mutate() as GradientDrawable
            val done = i <= currentStageIndex
            bg.setColor(getColor(if (done) R.color.accent_amber else R.color.color_surface_alt))
            node.stepIcon.imageTintList = android.content.res.ColorStateList.valueOf(
                getColor(if (done) R.color.color_on_amber else R.color.accent_amber),
            )
            node.stepLabel.setTextColor(
                getColor(if (done) R.color.color_text_primary else R.color.color_text_secondary),
            )
        }
    }

    private fun labelFor(stage: ProcessingState.Stage) = when (stage) {
        ProcessingState.Stage.EXTRACTING_FRAMES -> R.string.stage_extracting_frames
        ProcessingState.Stage.DETECTING_FACES -> R.string.stage_detecting_faces
        ProcessingState.Stage.EMBEDDING_FACES -> R.string.stage_embedding_faces
        ProcessingState.Stage.CLUSTERING -> R.string.stage_clustering
        ProcessingState.Stage.COMPOSING -> R.string.stage_composing
    }

    /** Big hero counter: only animates when the real count changes (0 for most of the run,
     * then a satisfying count-up the moment clustering resolves). */
    private fun animateCounterTo(target: Int) {
        if (target == shownPeopleCount) return
        ValueAnimator.ofInt(shownPeopleCount, target).apply {
            duration = 700
            addUpdateListener { binding.peopleCounter.text = (it.animatedValue as Int).toString() }
            start()
        }
        shownPeopleCount = target
    }

    /** Cheap "blur": drastically downscale then let the ImageView upscale it with bilinear
     * filtering -- a standard low-cost approximation, decoded off the main thread. */
    private fun updatePreview(path: String?) {
        if (path == null || path == lastPreviewPath) return
        lastPreviewPath = path
        lifecycleScope.launch {
            val blurred = withContext(Dispatchers.Default) { decodeAndBlur(path) } ?: return@launch
            binding.previewImage.setImageBitmap(blurred)
            if (binding.previewImage.visibility != android.view.View.VISIBLE) {
                binding.previewImage.alpha = 0f
                binding.previewImage.visibility = android.view.View.VISIBLE
                binding.previewImage.animate().alpha(1f).setDuration(400).start()
            }
        }
    }

    private fun decodeAndBlur(path: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inSampleSize = 8 }
        val small = BitmapFactory.decodeFile(path, opts) ?: return null
        val tiny = Bitmap.createScaledBitmap(small, 24, 42, true)
        if (tiny !== small) small.recycle()
        return tiny
    }

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }
}
