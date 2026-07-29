package dev.jaui.squeezessh.data.importer

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException

/**
 * Liest `com.sonelli.juicessh_preferences.xml`.
 *
 * Darin steckt der Schluessel zu allen Geheimnissen der Datenbank:
 *
 * - `account_hash` benennt zugleich die Datenbankdatei,
 * - der Eintrag mit genau diesem Namen enthaelt den Master-Key, verschluesselt mit
 *   `account_hash` als Passwort.
 *
 * Ein gesetzter PIN-Lock aendert daran nichts — er liegt unter `md5(account_hash)` und
 * ist mit einer im Programmcode stehenden Konstanten verschluesselt. Der Import braucht
 * deshalb kein Geheimnis vom Nutzer.
 */
internal object JuiceSshPreferences {

    data class Parsed(
        val accountHash: String,
        val accountName: String?,
        val masterKey: String?,
    )

    /**
     * @return `null` wenn die Datei keine JuiceSSH-Preferences sind.
     * @throws JuiceSshDecryptionException wenn der Master-Key da ist, aber nicht aufgeht.
     */
    fun parse(file: File): Parsed? {
        val entries = try {
            readStringEntries(file)
        } catch (e: IOException) {
            return null
        }

        val accountHash = entries["account_hash"]?.takeIf { it.isNotBlank() } ?: return null
        val masterBlob = entries[accountHash]
            ?: entries["password"] // Schema vor der Migration in JuiceSSH 2.x.

        return Parsed(
            accountHash = accountHash,
            accountName = entries["account"],
            masterKey = JuiceSshCrypto.decrypt(masterBlob, accountHash),
        )
    }

    private fun readStringEntries(file: File): Map<String, String> {
        val result = mutableMapOf<String, String>()
        file.inputStream().use { input ->
            val parser = Xml.newPullParser()
            parser.setInput(input, null)
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                if (parser.eventType != XmlPullParser.START_TAG || parser.name != "string") continue
                val name = parser.getAttributeValue(null, "name") ?: continue
                // <string name="x">wert</string> — der Wert ist der Textknoten.
                result[name] = if (parser.next() == XmlPullParser.TEXT) parser.text else ""
            }
        }
        return result
    }
}
