package com.example.offlineroutingapp.fragments

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.offlineroutingapp.R
import com.example.offlineroutingapp.RegistrationActivity
import com.example.offlineroutingapp.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.util.Log

class ProfileFragment : Fragment() {
    private lateinit var profileImage: ImageView
    private lateinit var userIdText: TextView
    private lateinit var displayNameText: TextView
    private lateinit var changePhotoBtn: Button
    private lateinit var logoutBtn: Button

    private val database by lazy { AppDatabase.getDatabase(requireContext()) }
    private var currentPhotoPath: String? = null
    private var userId: String? = null

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            updateProfilePhoto(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_profile, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        profileImage = view.findViewById(R.id.profileImage)
        userIdText = view.findViewById(R.id.userIdText)
        displayNameText = view.findViewById(R.id.displayNameText)
        changePhotoBtn = view.findViewById(R.id.changePhotoBtn)
        logoutBtn = view.findViewById(R.id.logoutBtn)

        loadUserProfile()

        changePhotoBtn.setOnClickListener {
            if (hasStoragePermission()) {
                imagePickerLauncher.launch("image/*")
            } else {
                requestStoragePermission()
            }
        }

        logoutBtn.setOnClickListener {
            showLogoutConfirmation()
        }
    }

    private fun loadUserProfile() {
        lifecycleScope.launch {
            val user = database.userDao().getUser()
            user?.let {
                userId = it.userId
                userIdText.text = "ID: ${it.userId}"
                displayNameText.text = it.displayName
                currentPhotoPath = it.profilePhotoPath

                if (!it.profilePhotoPath.isNullOrEmpty() && File(it.profilePhotoPath).exists()) {
                    val bitmap = BitmapFactory.decodeFile(it.profilePhotoPath)
                    profileImage.setImageBitmap(bitmap)
                } else {
                    profileImage.setImageResource(android.R.drawable.ic_menu_camera)
                }
            }
        }
    }

    private fun updateProfilePhoto(uri: Uri) {
        lifecycleScope.launch {
            val user = database.userDao().getUser()
            user?.let {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()

                val profileDir = File(requireContext().filesDir, "profiles")
                if (!profileDir.exists()) {
                    profileDir.mkdirs()
                }

                val fileName = "profile_${it.userId}.jpg"
                val file = File(profileDir, fileName)

                val outputStream = FileOutputStream(file)
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
                outputStream.flush()
                outputStream.close()

                val updatedUser = it.copy(profilePhotoPath = file.absolutePath)
                database.userDao().insertUser(updatedUser)

                profileImage.setImageBitmap(bitmap)
                currentPhotoPath = file.absolutePath
            }
        }
    }

    private fun showLogoutConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout? This will delete all your chats and profile data.")
            .setPositiveButton("Logout") { _, _ ->
                performLogout()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Stop any active services
                requireActivity().stopService(Intent(requireContext(), com.example.offlineroutingapp.nearby.NearbyConnectionsService::class.java))

                // Delete database
                requireContext().deleteDatabase("offline_chat_database")

                // Delete profile photos
                val profileDir = File(requireContext().filesDir, "profiles")
                if (profileDir.exists()) {
                    profileDir.deleteRecursively()
                }

                val receivedProfileDir = File(requireContext().filesDir, "received_profiles")
                if (receivedProfileDir.exists()) {
                    receivedProfileDir.deleteRecursively()
                }

                Log.d("ProfileFragment", "Logout completed successfully")

                withContext(Dispatchers.Main) {
                    // Navigate to registration
                    val intent = Intent(requireContext(), RegistrationActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)
                    requireActivity().finish()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error during logout: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Logout failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(requireActivity(), arrayOf(permission), 1004)
    }
}