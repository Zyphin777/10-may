package com.example.offlineroutingapp

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.offlineroutingapp.data.AppDatabase
import kotlinx.coroutines.launch

class WelcomeActivity : AppCompatActivity() {

    private val database by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_welcome)

        val userNameText = findViewById<TextView>(R.id.welcomeUserName)

        lifecycleScope.launch {
            // Fetch the existing user from the database
            val user = database.userDao().getUser()

            if (user != null) {
                userNameText.text = user.displayName

                // Show the welcome screen for 2.5 seconds then go to MainActivity
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(Intent(this@WelcomeActivity, MainActivity::class.java))
                    finish()
                }, 2500)
            } else {
                // If no user exists, go straight to registration
                startActivity(Intent(this@WelcomeActivity, RegistrationActivity::class.java))
                finish()
            }
        }
    }
}