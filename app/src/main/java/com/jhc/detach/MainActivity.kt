package com.jhc.detach

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.jhc.detach.ui.theme.ZygiskdetachTheme
import com.topjohnwu.superuser.Shell

class MainActivity : ComponentActivity() {
    companion object {
        init {
            Shell.setDefaultBuilder(Shell.Builder.create().setFlags(Shell.FLAG_MOUNT_MASTER))
        }
    }

    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ZygiskdetachTheme {
                DetachScreen(vm)
            }
        }
    }
}
