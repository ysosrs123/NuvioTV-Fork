package com.nuvio.iptv.fixture

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Button
import java.io.File

class FixturePickerActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(Button(this).apply {
            text = "Use synthetic XMLTV fixture"
            setOnClickListener {
                setResult(RESULT_OK, Intent().setData(Uri.parse("content://com.nuvio.iptv.validation.fixture/guide.xml"))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION))
                finish()
            }
            requestFocus()
        })
    }
}
class FixtureGuideProvider : ContentProvider() {
    private val xml = """<tv><channel id="fixture.sliding"><display-name>HLS Sliding</display-name></channel><programme channel="fixture.sliding" start="20261005060000 +0000" stop="20261005090000 +0000"><title>Local XMLTV fixture</title></programme></tv>"""
    override fun onCreate() = true
    override fun getType(uri: Uri) = "application/xml"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("Nuvio synthetic guide.xml", xml.toByteArray().size)) }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(uri.path == "/guide.xml" && mode == "r")
        val file = File(requireNotNull(context).cacheDir,"synthetic-guide.xml")
        file.writeText(xml)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = throw UnsupportedOperationException()
}
