package com.example.arkanoidchristmas

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import kotlinx.android.synthetic.main.activity_win.*

class win : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_win)
        val score = intent.getStringExtra("value")
        textView2.text = score
}

    fun restart(view: View) {
        val intent = Intent(this,MainActivity::class.java)
        startActivity(intent)
    finish()
    }
}
