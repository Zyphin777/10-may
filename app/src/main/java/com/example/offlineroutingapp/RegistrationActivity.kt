package com.example.offlineroutingapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.offlineroutingapp.data.AppDatabase
import com.example.offlineroutingapp.data.entities.UserEntity
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.*
import com.example.offlineroutingapp.nativebridge.MasaarBridge


class RegistrationActivity : AppCompatActivity() {
    private lateinit var profileImageView: ImageView
    private lateinit var displayNameInput: EditText
    private lateinit var registerButton: Button
    private lateinit var selectPhotoButton: Button
    private lateinit var generatedIdText: TextView

    private var selectedImageUri: Uri? = null
    private var profilePhotoPath: String? = null
    private var generatedUserId: String = ""

    private val database by lazy { AppDatabase.getDatabase(this) }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedImageUri = it
            profileImageView.setImageURI(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_registration)

        initializeViews()
        generateUniqueId()
        setupListeners()
       // checkExistingUser()
    }

    private fun initializeViews() {
        profileImageView = findViewById(R.id.profileImageView)
        displayNameInput = findViewById(R.id.displayNameInput)
        registerButton = findViewById(R.id.registerButton)
        selectPhotoButton = findViewById(R.id.selectPhotoButton)
        generatedIdText = findViewById(R.id.generatedIdText)
    }

    private fun generateUniqueId() {
        val uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8)
        generatedUserId = "user_$uuid"
        generatedIdText.text = "Your ID: $generatedUserId"

        // Test the C++ bridge
        MasaarBridge.setNodeId(generatedUserId)
        val testMsg = MasaarBridge.buildMessage("Hello from Kotlin!", "user_test123")
        android.util.Log.d("MasaarTest", "Generated message: $testMsg")
    }
    private fun setupListeners() {
        selectPhotoButton.setOnClickListener {
            if (hasStoragePermission()) {
                imagePickerLauncher.launch("image/*")
            } else {
                requestStoragePermission()
            }
        }

        registerButton.setOnClickListener {
            validateAndRegister()
        }
    }

    private fun checkExistingUser() {
        lifecycleScope.launch {
            val user = database.userDao().getUser()
            if (user != null) {
                navigateToMain()
            }
        }
    }

    private fun validateAndRegister() {
        val displayName = displayNameInput.text.toString().trim()

        when {
            displayName.isEmpty() -> {
                displayNameInput.error = "Display name is required"
                return
            }
            displayName.length < 2 -> {
                displayNameInput.error = "Display name must be at least 2 characters"
                return
            }
        }

        selectedImageUri?.let { uri ->
            profilePhotoPath = saveProfilePhoto(uri)
        }

        val user = UserEntity(
            userId = generatedUserId,
            displayName = displayName,
            profilePhotoPath = profilePhotoPath
        )

        lifecycleScope.launch {
            database.userDao().insertUser(user)
            MasaarBridge.setNodeId(generatedUserId)
            Toast.makeText(this@RegistrationActivity, "Profile created successfully!", Toast.LENGTH_SHORT).show()
            navigateToMain()
        }
    }

    private fun saveProfilePhoto(uri: Uri): String {
        val inputStream = contentResolver.openInputStream(uri)
        val bitmap = BitmapFactory.decodeStream(inputStream)
        inputStream?.close()

        val profileDir = File(filesDir, "profiles")
        if (!profileDir.exists()) {
            profileDir.mkdirs()
        }

        val fileName = "profile_${generatedUserId}.jpg"
        val file = File(profileDir, fileName)

        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        outputStream.flush()
        outputStream.close()

        return file.absolutePath
    }

    private fun navigateToMain() {
        // After registration, show the welcome screen first.
        // WelcomeActivity will read the saved user and then open MainActivity.
        val intent = Intent(this, WelcomeActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStoragePermission() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        ActivityCompat.requestPermissions(this, arrayOf(permission), 1003)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1003 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            imagePickerLauncher.launch("image/*")
        }
    }
}
