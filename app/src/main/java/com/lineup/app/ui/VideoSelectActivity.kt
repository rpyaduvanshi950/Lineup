package com.lineup.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lineup.app.databinding.ActivityVideoSelectBinding

class VideoSelectActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoSelectBinding

    private val picker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            startActivity(
                Intent(this, ProcessingActivity::class.java)
                    .putExtra(ProcessingActivity.EXTRA_VIDEO_URI, uri.toString()),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoSelectBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.selectButton.setOnClickListener {
            picker.launch(arrayOf("video/*"))
        }
    }
}
