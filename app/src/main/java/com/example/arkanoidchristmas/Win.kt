package com.example.arkanoidchristmas

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import com.example.arkanoidchristmas.databinding.ActivityWinBinding

class Win : AppCompatActivity() {
    private lateinit var binding: ActivityWinBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWinBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        val score = intent.getStringExtra("value")
        binding.textView2.text = score
    }

    fun restart(view: View) {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
