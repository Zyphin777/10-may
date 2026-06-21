package com.example.offlineroutingapp.fragments

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.MimeTypeMap
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.adapters.SharedFileAdapter
import com.example.offlineroutingapp.offloading.CachedSharedFile
import com.example.offlineroutingapp.offloading.SharedFileCacheStore
import java.io.File
import java.util.Locale

class SharedFilesFragment : Fragment() {

    private lateinit var searchInput: EditText
    private lateinit var sharedFilesRecyclerView: RecyclerView
    private lateinit var emptySharedFilesText: TextView
    private lateinit var cacheSummaryText: TextView
    private lateinit var adapter: SharedFileAdapter

    private val files = mutableListOf<CachedSharedFile>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_shared_files, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchInput = view.findViewById(R.id.sharedFilesSearchInput)
        sharedFilesRecyclerView = view.findViewById(R.id.sharedFilesRecyclerView)
        emptySharedFilesText = view.findViewById(R.id.emptySharedFilesText)
        cacheSummaryText = view.findViewById(R.id.cacheSummaryText)

        adapter = SharedFileAdapter(
            onOpenClick = { file -> openSharedFile(file) },
            onDeleteClick = { file -> deleteSharedFile(file) }
        )

        sharedFilesRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        sharedFilesRecyclerView.adapter = adapter

        searchInput.addTextChangedListener {
            applySearch(it?.toString().orEmpty())
        }

        loadFiles()
    }

    override fun onResume() {
        super.onResume()
        loadFiles()
    }

    private fun loadFiles() {
        files.clear()
        files.addAll(SharedFileCacheStore.load(requireContext()))
        applySearch(searchInput.text?.toString().orEmpty())
        updateSummary()
    }

    private fun applySearch(query: String) {
        val normalizedQuery = normalize(query)
        val filtered = if (normalizedQuery.isBlank()) {
            files
        } else {
            val queryTokens = normalizedQuery.split(" ").filter { it.isNotBlank() }.toSet()
            files.filter { file ->
                val searchable = normalize(
                    listOf(
                        file.fileName,
                        file.category,
                        file.description,
                        file.tags,
                        file.fileHash.orEmpty(),
                        file.sourceType
                    ).joinToString(" ")
                )
                queryTokens.all { token -> searchable.contains(token) }
            }
        }

        adapter.submitList(filtered.toList())
        emptySharedFilesText.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        sharedFilesRecyclerView.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun updateSummary() {
        val downloadedSize = files
            .filter { it.sourceType != "LOCAL" }
            .sumOf { it.fileSizeBytes.coerceAtLeast(0L) }
        cacheSummaryText.text = "${files.size} shared file(s) • cache ${formatFileSize(downloadedSize)} / ${formatFileSize(SharedFileCacheStore.MAX_CACHE_SIZE_BYTES)}"
    }

    private fun openSharedFile(file: CachedSharedFile) {
        try {
            val storedUri = Uri.parse(file.localUri)
            val openUri = if (storedUri.scheme == "file") {
                val path = storedUri.path ?: throw IllegalArgumentException("Invalid file path")
                val localFile = File(path)
                if (!localFile.exists()) throw IllegalArgumentException("File not found")
                FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    localFile
                )
            } else {
                storedUri
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(openUri, getMimeType(file.fileName))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Open shared file"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(requireContext(), "No app found to open this file", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), e.message ?: "Could not open file", Toast.LENGTH_LONG).show()
        }
    }

    private fun deleteSharedFile(file: CachedSharedFile) {
        files.clear()
        files.addAll(SharedFileCacheStore.remove(requireContext(), file.fileId))
        applySearch(searchInput.text?.toString().orEmpty())
        updateSummary()
        Toast.makeText(requireContext(), "Removed ${file.fileName} from shared files", Toast.LENGTH_SHORT).show()
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
    }

    private fun normalize(text: String): String {
        return text.lowercase(Locale.getDefault())
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
    }

    private fun formatFileSize(sizeBytes: Long): String {
        val kb = sizeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.getDefault(), "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.getDefault(), "%.2f MB", mb)
            else -> String.format(Locale.getDefault(), "%.1f KB", kb)
        }
    }
}
