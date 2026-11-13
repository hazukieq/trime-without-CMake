package com.hazukieq.hkrime.settings

import android.Manifest
import android.content.Intent
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.blankj.utilcode.util.BarUtils
import com.blankj.utilcode.util.ToastUtils
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.databinding.PrefActivityBinding
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.settings.fragments.PrefFragment
import com.hazukieq.hkrime.setup.SetupActivity
import com.hazukieq.hkrime.util.RimeUtils
import com.hazukieq.hkrime.util.createLoadingDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import timber.log.Timber

internal const val FRAGMENT_TAG = "FRAGMENT_TAG"
//const val PERMISSION_REQUEST_EXTERNAL_STORAGE = 0

class PrefMainActivity :
    AppCompatActivity(),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback,
    //ActivityCompat.OnRequestPermissionsResultCallback,
    CoroutineScope by MainScope() {
    private val prefs get() = AppPrefs.defaultInstance()

    lateinit var binding: PrefActivityBinding
    lateinit var imeManager: InputMethodManager

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs.sync()
        val uiMode = when (prefs.other.uiMode) {
            "auto" -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_UNSPECIFIED
        }
        AppCompatDelegate.setDefaultNightMode(uiMode)

        super.onCreate(savedInstanceState)
        binding = PrefActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            BarUtils.setNavBarColor(this, getColor(R.color.modern_bg))
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = window.decorView.systemUiVisibility
        }

        // 狀態欄融入頁面底色：淺色模式用深色圖標，深色模式用淺色圖標。
        window.statusBarColor = getColor(R.color.modern_bg)
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (!night) {
                window.decorView.systemUiVisibility =
                    window.decorView.systemUiVisibility or
                        android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }

        // 現代化大標題頭：標題/返回/部署/關於都在頭部，不再用 Material Toolbar。
        headerTitle = findViewById(R.id.header_title)
        headerBack = findViewById(R.id.header_back)
        findViewById<TextView>(R.id.header_subtitle).text = getString(R.string.trime_app_name)
        headerBack.setOnClickListener { onBackPressed() }
        findViewById<android.view.View>(R.id.header_deploy).setOnClickListener { deployRime() }
        findViewById<android.view.View>(R.id.header_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        imeManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager

        if (savedInstanceState == null) {
            loadFragment(PrefFragment())
        } else {
            title = savedInstanceState.getCharSequence(FRAGMENT_TAG)
        }
        supportFragmentManager.addOnBackStackChangedListener {
            updateHeader()
        }
        updateHeader()
        /*if (SetupActivity.shouldSetup()) {
            startActivity(Intent(this, SetupActivity::class.java))
        }*/

        // 啟動頁由 LauncherAlias -> LaunchActivity 負責，設定頁不再自動打開它，避免閃白。

        //requestPermission()
        //requestExternalStoragePermission()
        //requestAlertWindowPermission()
    }

    private lateinit var headerTitle: android.widget.TextView
    private lateinit var headerBack: android.view.View

    private fun updateHeader() {
        val nested = supportFragmentManager.backStackEntryCount > 0
        headerBack.visibility = if (nested) android.view.View.VISIBLE else android.view.View.GONE
        if (!nested) headerTitle.text = getString(R.string.pref__title)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            updateHeader()
            return
        }
        super.onBackPressed()
    }

    override fun setTitle(title: CharSequence?) {
        super.setTitle(title)
        if (this::headerTitle.isInitialized) {
            headerTitle.text = title ?: getString(R.string.pref__title)
        }
    }

    private fun deployRime() {
        val progressDialog = createLoadingDialog(this, R.string.deploy_progress)
        progressDialog.show()
        Trime.getServiceOrNull()?.initKeyboard()
        launch {
            try {
                RimeUtils.deploy(this@PrefMainActivity)
            } catch (ex: Exception) {
                Timber.e(ex, "Deploy Exception")
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putCharSequence(FRAGMENT_TAG, title)
    }

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.popBackStackImmediate()) {
            return true
        }
        return super.onSupportNavigateUp()
    }

    override fun onPreferenceStartFragment(
        caller: PreferenceFragmentCompat,
        pref: Preference
    ): Boolean {
        // Instantiate the new Fragment
        val args = pref.extras
        val fragment = pref.fragment?.let {
            supportFragmentManager.fragmentFactory.instantiate(
                classLoader,
                it
            ).apply {
                arguments = args
                @Suppress("DEPRECATION")
                setTargetFragment(caller, 0)
            }
        }
        // Replace the existing Fragment with the new Fragment
        if (fragment != null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.preference, fragment)
                .addToBackStack(null)
                .commit()
        }
        title = pref.title
        return true
    }

    private fun loadFragment(fragment: Fragment) {
        supportFragmentManager
            .beginTransaction()
            .replace(binding.preference.id, fragment, FRAGMENT_TAG)
            .commit()
    }

    /*override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == PERMISSION_REQUEST_EXTERNAL_STORAGE) {
            // Request for external storage permission
            if (grantResults.size == 2 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launch {
                    ToastUtils.showShort(R.string.external_storage_permission_granted)
                    delay(500)
                    RimeUtils.deploy(this@PrefMainActivity)
                }
            } else {
                // Permission request was denied
                ToastUtils.showShort(R.string.external_storage_permission_denied)
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }*/

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.preference_main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            R.id.preference__menu_deploy -> {
                deployRime()
                true
            }
            R.id.preference__menu_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /*private fun requestExternalStoragePermission() {
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is already available, return
                return
            } else {
                if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    AlertDialog.Builder(this)
                        .setMessage(R.string.external_storage_access_required)
                        .setCancelable(true)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            ActivityCompat.requestPermissions(
                                this,
                                arrayOf(
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                ),
                                PERMISSION_REQUEST_EXTERNAL_STORAGE
                            )
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                } else {
                    ToastUtils.showShort(R.string.external_storage_permission_not_available)

                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(
                            Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            Manifest.permission.READ_EXTERNAL_STORAGE
                        ),
                        PERMISSION_REQUEST_EXTERNAL_STORAGE
                    )
                }
            }
        }
    }*/


}
