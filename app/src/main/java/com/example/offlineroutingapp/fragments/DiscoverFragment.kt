package com.example.offlineroutingapp.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ListView
import androidx.fragment.app.Fragment
import com.example.offlineroutingapp.R

class DiscoverFragment : Fragment() {
    private lateinit var discoverBtn: Button
    private lateinit var peersList: ListView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_discover, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        discoverBtn = view.findViewById(R.id.discoverBtn)
        peersList = view.findViewById(R.id.peersList)

        // MainActivity will handle the actual discovery logic
        // This fragment just displays the UI
    }

    fun getDiscoverButton(): Button = discoverBtn
    fun getPeersList(): ListView = peersList
}
