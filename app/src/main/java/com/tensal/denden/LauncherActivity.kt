package com.tensal.denden

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Public launcher that deliberately drops untrusted extras before entering the private app UI. */
class LauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
