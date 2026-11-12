package studio.acmenra.control

import android.app.Application
import org.koin.android.ext.koin.androidContext
import studio.acmenra.control.di.initKoin

class ControlApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        initKoin {
            androidContext(this@ControlApplication)
        }
    }
}
