package `fun`.tryliner.android.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener

/**
 * Routes `file://` URIs (offline downloads) straight to [FileDataSource] and
 * everything else to the network/cache [remoteFactory].
 */
@OptIn(UnstableApi::class)
class LocalFirstDataSource private constructor(
    private val local: DataSource,
    private val remote: DataSource,
) : DataSource {

    class Factory(private val remoteFactory: DataSource.Factory) : DataSource.Factory {
        private val localFactory = FileDataSource.Factory()
        override fun createDataSource(): DataSource =
            LocalFirstDataSource(localFactory.createDataSource(), remoteFactory.createDataSource())
    }

    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = if (dataSpec.uri.scheme.equals("file", ignoreCase = true)) local else remote
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IllegalStateException("Not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> =
        active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }
}
