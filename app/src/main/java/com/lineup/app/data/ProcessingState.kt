package com.lineup.app.data

import com.lineup.app.model.Appearance
import com.lineup.app.model.FaceDetection
import com.lineup.app.model.FrameRef
import com.lineup.app.model.PersonCluster

/** Progress + result surface for the whole pipeline, observed by the UI. */
sealed class ProcessingState {
    data object Idle : ProcessingState()

    data class Running(
        val stage: Stage,
        val done: Int,
        val total: Int,
        val peopleFound: Int = 0,
    ) : ProcessingState() {
        val fraction: Float get() = if (total <= 0) 0f else done.toFloat() / total
    }

    /** Step 1 result. Superseded by [Step2Complete] once identification finishes. */
    data class Step1Complete(
        val frames: List<FrameRef>,
        val detections: List<FaceDetection>,
    ) : ProcessingState()

    /** Step 2 result: identities resolved and appearances counted. */
    data class Step2Complete(
        val frames: List<FrameRef>,
        val clusters: List<PersonCluster>,
        val appearances: List<Appearance>,
        val similarityThreshold: Float,
    ) : ProcessingState() {
        /** personId -> appearance count, in cluster order. */
        val appearanceCounts: List<Pair<Int, Int>>
            get() = clusters.map { c -> c.id to appearances.count { it.personId == c.id } }
    }

    data class Failed(val message: String, val cause: Throwable? = null) : ProcessingState()

    enum class Stage(val label: String) {
        EXTRACTING_FRAMES("Extracting frames"),
        DETECTING_FACES("Detecting faces"),
        EMBEDDING_FACES("Identifying people"),
        CLUSTERING("Grouping appearances"),
    }
}
