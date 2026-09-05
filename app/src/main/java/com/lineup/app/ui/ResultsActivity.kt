package com.lineup.app.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.lineup.app.R
import com.lineup.app.databinding.ActivityResultsBinding
import com.lineup.app.pipeline.CollageComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Shows the composed collage, per-person avatar chips, and lets the user save/share it. */
class ResultsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultsBinding
    private var collageFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val path = intent.getStringExtra(EXTRA_COLLAGE_PATH)
        val personIds = intent.getIntArrayExtra(EXTRA_PERSON_IDS) ?: intArrayOf()
        val counts = intent.getIntArrayExtra(EXTRA_APPEARANCE_COUNTS) ?: intArrayOf()
        val avatarPaths = intent.getStringArrayExtra(EXTRA_AVATAR_PATHS) ?: emptyArray()
        val totalAppearances = intent.getIntExtra(EXTRA_TOTAL_APPEARANCES, 0)
        val threshold = intent.getFloatExtra(EXTRA_THRESHOLD, 0f)

        if (path == null) {
            finish()
            return
        }
        collageFile = File(path)

        binding.resultsCount.text = personIds.size.toString()
        binding.resultsSubtitle.text = getString(
            R.string.results_subtitle_format, totalAppearances, "%.2f".format(threshold),
        )

        // One-shot fade + scale-up entrance for the hero collage once it's decoded.
        binding.collageImage.setImageBitmap(BitmapFactory.decodeFile(path))
        binding.collageImage.alpha = 0f
        binding.collageImage.scaleX = 0.94f
        binding.collageImage.scaleY = 0.94f
        binding.collageImage.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(360).start()

        binding.avatarRecycler.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.avatarRecycler.adapter = AvatarChipAdapter(
            personIds.indices.map { i ->
                AvatarChipRow(personIds[i], counts.getOrElse(i) { 0 }, avatarPaths.getOrNull(i))
            },
        )

        binding.saveButton.applyPressScale()
        binding.shareButton.applyPressScale()
        binding.saveButton.setOnClickListener { saveToGallery() }
        binding.shareButton.setOnClickListener { shareCollage() }

        // TalkBack: announce the headline result once the screen settles.
        binding.root.post {
            binding.root.announceForAccessibility(
                "${personIds.size} people found, $totalAppearances appearances in total",
            )
        }
    }

    private fun saveToGallery() {
        val file = collageFile ?: return
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return@withContext null
                CollageComposer.saveToGallery(this@ResultsActivity, bitmap, file.name)
            }
            Toast.makeText(
                this@ResultsActivity,
                if (uri != null) R.string.saved_to_gallery else R.string.save_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun shareCollage() {
        val file = collageFile ?: return
        val intent = CollageComposer.shareIntent(this, file)
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    companion object {
        const val EXTRA_COLLAGE_PATH = "collage_path"
        const val EXTRA_PERSON_IDS = "person_ids"
        const val EXTRA_APPEARANCE_COUNTS = "appearance_counts"
        const val EXTRA_AVATAR_PATHS = "avatar_paths"
        const val EXTRA_TOTAL_APPEARANCES = "total_appearances"
        const val EXTRA_THRESHOLD = "threshold"
    }
}
