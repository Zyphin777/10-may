package com.example.offlineroutingapp.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.offlineroutingapp.fragments.ChatsFragment
import com.example.offlineroutingapp.fragments.CloudFragment
import com.example.offlineroutingapp.fragments.DiscoverFragment
import com.example.offlineroutingapp.fragments.ProfileFragment

class ViewPagerAdapter(fragmentActivity: FragmentActivity) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 4

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatsFragment()
            1 -> CloudFragment()
            2 -> DiscoverFragment()
            3 -> ProfileFragment()
            else -> ChatsFragment()
        }
    }
}
