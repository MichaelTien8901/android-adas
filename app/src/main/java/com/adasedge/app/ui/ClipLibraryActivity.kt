package com.adasedge.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.adasedge.app.R
import com.adasedge.app.core.Prefs
import com.adasedge.app.databinding.ActivityClipLibraryBinding
import com.adasedge.app.recording.ClipRepository
import com.adasedge.app.recording.ClipRepository.Clip
import java.util.concurrent.Executors

/**
 * Clip library (clip-library spec): browse/search recordings, play them, set one as the ADAS
 * replay source, share/export, and delete (single / multi-select / a whole day). All MediaStore
 * I/O goes through [ClipRepository] off the main thread.
 */
class ClipLibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityClipLibraryBinding
    private lateinit var prefs: Prefs
    private lateinit var repo: ClipRepository
    private lateinit var adapter: ClipAdapter

    private val io = Executors.newSingleThreadExecutor()
    private val thumbExec = Executors.newFixedThreadPool(2)

    private var newestFirst = true
    private var dayFilter: String? = null      // null = all days
    private var days: List<String> = emptyList()
    private var spinnerSettingUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = ClipRepository(this)
        binding = ActivityClipLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = ClipAdapter(
            thumbnailExecutor = thumbExec,
            loadThumb = { repo.thumbnail(it, THUMB_PX) },
            onClick = { clip -> if (adapter.selectionMode) { adapter.toggle(clip); updateSelectionBar() } else showActions(clip) },
            onLongClick = { clip -> adapter.enterSelection(clip); updateSelectionBar() },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.sortButton.setOnClickListener {
            newestFirst = !newestFirst
            binding.sortButton.setText(if (newestFirst) R.string.clips_sort_newest else R.string.clips_sort_oldest)
            loadClips()
        }
        binding.deleteDayButton.setOnClickListener { confirmDeleteDay() }
        binding.shareSel.setOnClickListener { share(adapter.selectedClips()) }
        binding.deleteSel.setOnClickListener { confirmDelete(adapter.selectedClips()) }
        binding.cancelSel.setOnClickListener { adapter.clearSelection(); updateSelectionBar() }

        binding.dayFilter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (spinnerSettingUp) return
                dayFilter = if (pos == 0) null else days.getOrNull(pos - 1)
                binding.deleteDayButton.visibility = if (dayFilter != null) View.VISIBLE else View.GONE
                loadClips()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adapter.selectionMode) { adapter.clearSelection(); updateSelectionBar() }
                else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        loadClips()
    }

    override fun onDestroy() {
        io.shutdown(); thumbExec.shutdown()
        super.onDestroy()
    }

    private fun loadClips() {
        io.execute {
            if (!prefs.clipMigrationDone) {
                if (runCatching { repo.migrateLegacyComplete() }.getOrDefault(false)) prefs.clipMigrationDone = true
            }
            val recordings = repo.clips(ClipRepository.ClipFilter(day = dayFilter, newestFirst = newestFirst))
            // Pushed replay clips are pinned at the top (only in the unfiltered "All days" view).
            val replays = if (dayFilter == null) repo.replayClips() else emptyList()
            val allDays = repo.days()
            runOnUiThread { render(replays + recordings, allDays) }
        }
    }

    private fun render(clips: List<Clip>, allDays: List<String>) {
        if (isFinishing || isDestroyed) return
        adapter.submit(clips)
        binding.empty.visibility = if (clips.isEmpty()) View.VISIBLE else View.GONE
        binding.empty.setText(if (dayFilter == null) R.string.clips_empty else R.string.clips_empty_filter)
        updateSelectionBar()
        if (allDays != days) {
            days = allDays
            spinnerSettingUp = true
            val labels = listOf(getString(R.string.clips_filter_all)) + days
            binding.dayFilter.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            val idx = dayFilter?.let { days.indexOf(it) + 1 }?.takeIf { it > 0 } ?: 0
            binding.dayFilter.setSelection(idx)
            spinnerSettingUp = false
            if (idx == 0 && dayFilter != null) { dayFilter = null; binding.deleteDayButton.visibility = View.GONE }
        }
    }

    private fun updateSelectionBar() {
        binding.selectionBar.visibility = if (adapter.selectionMode) View.VISIBLE else View.GONE
        binding.selCount.text = getString(R.string.clip_selected, adapter.selectedCount())
    }

    // ---- Per-item actions -------------------------------------------------------------------

    private fun showActions(clip: Clip) {
        // The pushed replay clip is a plain file (not a recording): play + delete only.
        if (clip.isReplay) {
            val actions = arrayOf(getString(R.string.clip_play), getString(R.string.clip_delete))
            AlertDialog.Builder(this)
                .setTitle(clip.name)
                .setItems(actions) { _, which -> if (which == 0) play(clip) else confirmDelete(listOf(clip)) }
                .show()
            return
        }
        val actions = arrayOf(
            getString(R.string.clip_play),
            getString(R.string.clip_set_replay),
            getString(R.string.clip_share),
            getString(R.string.clip_delete),
        )
        AlertDialog.Builder(this)
            .setTitle(clip.name)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> play(clip)
                    1 -> setReplaySource(clip)
                    2 -> share(listOf(clip))
                    3 -> confirmDelete(listOf(clip))
                }
            }
            .show()
    }

    private fun play(clip: Clip) {
        startActivity(Intent(this, ClipPlayerActivity::class.java).putExtra(ClipPlayerActivity.EXTRA_URI, clip.uri.toString()))
    }

    private fun setReplaySource(clip: Clip) {
        prefs.selectedReplayClip = clip.uri.toString()
        Toast.makeText(this, getString(R.string.clip_replay_set, clip.name), Toast.LENGTH_SHORT).show()
    }

    private fun share(clips: List<Clip>) {
        // Only MediaStore-backed recordings have shareable content URIs (file:// can't cross apps).
        val shareable = clips.filterNot { it.isReplay }
        if (shareable.isEmpty()) return
        val uris = ArrayList(shareable.map { it.uri })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        intent.type = "video/*"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(intent, getString(R.string.clip_share_title)))
        if (adapter.selectionMode) { adapter.clearSelection(); updateSelectionBar() }
    }

    private fun confirmDelete(clips: List<Clip>) {
        if (clips.isEmpty()) return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clip_delete_confirm, clips.size))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                io.execute {
                    val n = repo.deleteClips(clips)
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.clip_deleted, n), Toast.LENGTH_SHORT).show()
                        adapter.clearSelection()
                        loadClips()
                    }
                }
            }
            .show()
    }

    private fun confirmDeleteDay() {
        val day = dayFilter ?: return
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.clip_delete_day_confirm, adapter.itemCount, day))
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                io.execute {
                    val n = repo.deleteDay(day)
                    runOnUiThread {
                        Toast.makeText(this, getString(R.string.clip_deleted, n), Toast.LENGTH_SHORT).show()
                        dayFilter = null
                        binding.deleteDayButton.visibility = View.GONE
                        loadClips()
                    }
                }
            }
            .show()
    }

    companion object {
        private const val THUMB_PX = 256
    }
}
