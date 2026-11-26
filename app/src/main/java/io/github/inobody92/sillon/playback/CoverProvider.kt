package io.github.inobody92.sillon.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.github.inobody92.sillon.data.Album
import io.github.inobody92.sillon.data.AppSessionAccess
import io.github.inobody92.sillon.data.CoverRequest
import io.github.inobody92.sillon.data.CoverSource
import io.github.inobody92.sillon.data.OfflineStore
import io.github.inobody92.sillon.data.SessionGate
import io.github.inobody92.sillon.data.Song
import java.io.File
import java.io.FileNotFoundException

/**
 * Adresses des pochettes publiées hors de l'application (Android Auto, notification, écran verrouillé, widget).
 * Format `content://` demandé par Android Auto ; aucune ne contient le sid du NAS.
 */
object CoverUris {
    private fun base(context: Context) = Uri.Builder().scheme("content").authority(context.packageName + ".covers")

    fun song(context: Context, song: Song): Uri = base(context)
        .appendPath(CoverRequest.PATH_SONG)
        .appendQueryParameter(CoverRequest.PARAM_ID, song.id)
        .appendQueryParameter(CoverRequest.PARAM_KEY, OfflineStore.coverKey(song))
        .build()

    /** Null si l'album n'a pas de nom : on prend alors la pochette du titre. */
    fun album(context: Context, album: Album): Uri? {
        if (album.name.isBlank()) return null
        val builder = base(context)
            .appendPath(CoverRequest.PATH_ALBUM)
            .appendQueryParameter(CoverRequest.PARAM_NAME, album.name)
            .appendQueryParameter(CoverRequest.PARAM_ARTIST, album.displayArtist)
        OfflineStore.coverKey(album)?.let { builder.appendQueryParameter(CoverRequest.PARAM_KEY, it) }
        return builder.build()
    }
}

/**
 * Sert les pochettes aux autres applications. Le sid est ajouté ici, dans l'application, quand il faut aller
 * chercher l'image sur le NAS (voir [CoverSource]) : l'adresse qu'on donne aux autres ne permet pas d'accéder au NAS.
 *
 * Exporté en lecture seule, comme dans l'exemple de Google (UAMP) : le pire qu'une autre application puisse en
 * tirer est une pochette.
 */
class CoverProvider : ContentProvider() {

    private val source: CoverSource by lazy {
        val ctx = requireNotNull(context).applicationContext
        // Le fournisseur peut être le premier composant lancé (Android Auto, application fermée).
        OfflineStore.init(ctx)
        val access = AppSessionAccess(ctx)
        CoverSource(
            offlineCover = OfflineStore::coverFileForKey,
            cacheDir = File(ctx.cacheDir, "covers"),
            gate = SessionGate(access),
        )
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Pochettes en lecture seule")
        val request = CoverRequest.parse(uri.pathSegments.singleOrNull(), uri::getQueryParameter)
            ?: throw FileNotFoundException("Adresse de pochette invalide")
        val file = source.open(request) ?: throw FileNotFoundException("Pochette indisponible")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Le format exact n'est connu qu'en lisant l'image ; un type générique d'image laisse passer les demandes
    // « une image, quel que soit son format ».
    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
