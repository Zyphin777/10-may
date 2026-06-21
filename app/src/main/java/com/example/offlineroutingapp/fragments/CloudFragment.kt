package com.example.offlineroutingapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.example.offlineroutingapp.R

class CloudFragment : Fragment() {

    private val publicRequestsFragment = PublicRequestsFragment()
    private val sharedFilesFragment = SharedFilesFragment()

    private lateinit var requestsTabBtn: Button
    private lateinit var filesTabBtn: Button

    private var selectedSection = SECTION_REQUESTS

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_cloud, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requestsTabBtn = view.findViewById(R.id.cloudRequestsBtn)
        filesTabBtn = view.findViewById(R.id.cloudFilesBtn)

        requestsTabBtn.setOnClickListener { showRequests() }
        filesTabBtn.setOnClickListener { showFiles() }

        if (childFragmentManager.findFragmentById(R.id.cloudContentContainer) == null) {
            showRequests()
        } else {
            updateSectionButtons()
        }
    }

    fun handleIncomingOfflinePacket(packetJson: String, fromNodeId: String?) {
        publicRequestsFragment.handleIncomingOfflinePacket(packetJson, fromNodeId)
    }

    fun syncActivePublicRequestsToPeers(): Int {
        return publicRequestsFragment.syncActivePublicRequestsToPeers()
    }

    private fun showRequests() {
        selectedSection = SECTION_REQUESTS
        childFragmentManager.commit {
            replace(R.id.cloudContentContainer, publicRequestsFragment, REQUESTS_TAG)
        }
        updateSectionButtons()
    }

    private fun showFiles() {
        selectedSection = SECTION_FILES
        childFragmentManager.commit {
            replace(R.id.cloudContentContainer, sharedFilesFragment, FILES_TAG)
        }
        updateSectionButtons()
    }

    private fun updateSectionButtons() {
        if (!::requestsTabBtn.isInitialized || !::filesTabBtn.isInitialized) return

        val selectedColor = ContextCompat.getColor(requireContext(), android.R.color.white)
        val unselectedTextColor = ContextCompat.getColor(requireContext(), android.R.color.white)
        val selectedTextColor = ContextCompat.getColor(requireContext(), R.color.purple_700)
        val transparentColor = ContextCompat.getColor(requireContext(), android.R.color.transparent)

        if (selectedSection == SECTION_REQUESTS) {
            requestsTabBtn.setTextColor(selectedTextColor)
            requestsTabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            filesTabBtn.setTextColor(unselectedTextColor)
            filesTabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(transparentColor)
        } else {
            filesTabBtn.setTextColor(selectedTextColor)
            filesTabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(selectedColor)
            requestsTabBtn.setTextColor(unselectedTextColor)
            requestsTabBtn.backgroundTintList = android.content.res.ColorStateList.valueOf(transparentColor)
        }
    }

    companion object {
        private const val SECTION_REQUESTS = "requests"
        private const val SECTION_FILES = "files"
        private const val REQUESTS_TAG = "cloud_public_requests"
        private const val FILES_TAG = "cloud_shared_files"
    }
}
