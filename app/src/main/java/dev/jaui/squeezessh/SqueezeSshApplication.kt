package dev.jaui.squeezessh

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.jaui.squeezessh.ssh.BouncyCastleSetup

@HiltAndroidApp
class SqueezeSshApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Muss vor der ersten SSH-Verbindung passieren und ist billig genug, um es hier
        // zu erledigen statt es an jeder Aufrufstelle zu wiederholen.
        BouncyCastleSetup.install()
    }
}
