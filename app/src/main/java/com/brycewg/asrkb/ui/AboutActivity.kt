package com.brycewg.asrkb.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import android.os.Build
import android.content.pm.PackageManager
import com.brycewg.asrkb.R

class AboutActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_about)

    val tvAppName = findViewById<TextView>(R.id.tvAppName)
    val tvVersion = findViewById<TextView>(R.id.tvVersion)
    val tvPackage = findViewById<TextView>(R.id.tvPackage)
    val btnGithub = findViewById<Button>(R.id.btnOpenGithub)

    tvAppName.text = getString(R.string.about_app_name, getString(R.string.app_name))
    val pm = packageManager
    val pInfo = try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0)
      }
    } catch (_: Exception) { null }

    val versionName = pInfo?.versionName ?: ""
    val versionCodeLong = if (pInfo != null) {
        pInfo.longVersionCode
    } else 0L
    tvVersion.text = getString(R.string.about_version, "$versionName ($versionCodeLong)")
    tvPackage.text = getString(R.string.about_package, packageName)

    btnGithub.setOnClickListener {
      try {
        val url = getString(R.string.about_project_url)
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
      } catch (_: Throwable) {}
    }

    // 返回箭头点击关闭（若布局中设置了导航图标）
    findViewById<androidx.appcompat.widget.Toolbar?>(R.id.toolbar)?.setNavigationOnClickListener {
      finish()
    }
  }
}
