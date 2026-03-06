package com.example.androidtranscribe

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.androidtranscribe.ui.batch.BatchFragment
import com.example.androidtranscribe.ui.realtime.RealtimeFragment
import com.example.androidtranscribe.ui.settings.SettingsFragment
import com.google.android.material.navigation.NavigationBarView

class MainActivity : AppCompatActivity() {

    private val realtimeFragment = RealtimeFragment()
    private val batchFragment = BatchFragment()
    private val settingsFragment = SettingsFragment()
    private var activeFragment: Fragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, settingsFragment, "settings")
                .hide(settingsFragment)
                .add(R.id.fragmentContainer, batchFragment, "batch")
                .hide(batchFragment)
                .add(R.id.fragmentContainer, realtimeFragment, "realtime")
                .commit()
            activeFragment = realtimeFragment
        }

        findViewById<NavigationBarView>(R.id.navigation).setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_realtime -> showFragment(realtimeFragment)
                R.id.nav_batch -> showFragment(batchFragment)
                R.id.nav_settings -> showFragment(settingsFragment)
                else -> false
            }
        }
    }

    private fun showFragment(target: Fragment): Boolean {
        if (target == activeFragment) return true
        supportFragmentManager.beginTransaction()
            .hide(activeFragment!!)
            .show(target)
            .commit()
        activeFragment = target
        return true
    }
}
