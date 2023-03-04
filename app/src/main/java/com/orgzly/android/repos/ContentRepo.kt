package com.orgzly.android.repos

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.orgzly.BuildConfig
import com.orgzly.android.BookName
import com.orgzly.android.util.LogUtils.d
import com.orgzly.android.util.MiscUtils
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Using DocumentFile, for devices running Lollipop or later.
 */
class ContentRepo(repoWithProps: RepoWithProps, context: Context) : SyncRepo {
    private val repoId: Long
    private val repoUri: Uri
    private val context: Context
    private val repoDocumentFile: DocumentFile?

    init {
        val (id, _, url) = repoWithProps.repo
        repoId = id
        repoUri = Uri.parse(url)
        this.context = context
        repoDocumentFile = DocumentFile.fromTreeUri(context, repoUri)
    }

    override fun isConnectionRequired(): Boolean {
        return false
    }

    override fun isAutoSyncSupported(): Boolean {
        return true
    }

    override fun getUri(): Uri {
        return repoUri
    }

    @Throws(IOException::class)
    override fun getBooks(): List<VersionedRook> {
        return getBooksRecursive(repoDocumentFile!!)
    }

    private fun getBooksRecursive(dir: DocumentFile): List<VersionedRook> {
        val result: MutableList<VersionedRook> = ArrayList()
        val files = dir.listFiles()
        if (files != null) {
            // Can't compare TreeDocumentFile
            // Arrays.sort(files);
            for (file in files) {
                if (file.isDirectory) {
                    result.addAll(getBooksRecursive(file))
                } else if (BookName.isSupportedFormatFileName(file.name)) {
                    if (BuildConfig.LOG_DEBUG) {
                        d(
                            TAG,
                            "file.getName()", file.name,
                            "getUri()", uri,
                            "repoDocumentFile.getUri()", repoDocumentFile!!.uri,
                            "file", file,
                            "file.getUri()", file.uri,
                            "file.getParentFile()", file.parentFile!!.uri
                        )
                    }
                    result.add(
                        VersionedRook(
                            repoId,
                            RepoType.DOCUMENT,
                            uri,
                            file.uri, file.lastModified().toString(),
                            file.lastModified()
                        )
                    )
                }
            }
        } else {
            Log.e(TAG, "Listing files in $uri returned null.")
        }
        return result
    }

    @Throws(IOException::class)
    override fun retrieveBook(fileName: String, destinationFile: File): VersionedRook {
        val sourceFile = repoDocumentFile!!.findFile(fileName)
        if (sourceFile == null) {
            throw FileNotFoundException("Book $fileName not found in $repoUri")
        } else {
            if (BuildConfig.LOG_DEBUG) {
                d(TAG, "Found DocumentFile for " + fileName + ": " + sourceFile.uri)
            }
        }
        context.contentResolver.openInputStream(sourceFile.uri)
            .use { `is` -> MiscUtils.writeStreamToFile(`is`, destinationFile) }
        val rev = sourceFile.lastModified().toString()
        val mtime = sourceFile.lastModified()
        return VersionedRook(repoId, RepoType.DOCUMENT, repoUri, sourceFile.uri, rev, mtime)
    }

    @Throws(IOException::class)
    override fun storeBook(file: File, fileName: String): VersionedRook {
        if (!file.exists()) {
            throw FileNotFoundException("File $file does not exist")
        }

        /* Delete existing file. */
        val existingFile = repoDocumentFile!!.findFile(fileName)
        existingFile?.delete()

        /* Create new file. */
        val destinationFile = repoDocumentFile.createFile("text/*", fileName)
            ?: throw IOException("Failed creating $fileName in $repoUri")
        val uri = destinationFile.uri

        /* Write file content to uri. */
        val out = context.contentResolver.openOutputStream(uri)
        try {
            MiscUtils.writeFileToStream(file, out)
        } finally {
            out?.close()
        }
        val rev = destinationFile.lastModified().toString()
        val mtime = System.currentTimeMillis()
        return VersionedRook(repoId, RepoType.DOCUMENT, getUri(), uri, rev, mtime)
    }

    @Throws(IOException::class)
    override fun renameBook(from: Uri, name: String): VersionedRook {
        val fromDocFile = DocumentFile.fromSingleUri(context, from)
        val bookName = BookName.fromFileName(fromDocFile!!.name)
        val newFileName = BookName.fileName(name, bookName.format)

        /* Check if document already exists. */
        val existingFile = repoDocumentFile!!.findFile(newFileName)
        if (existingFile != null) {
            throw IOException("File at " + existingFile.uri + " already exists")
        }
        val newUri = DocumentsContract.renameDocument(context.contentResolver, from, newFileName)
        val mtime = fromDocFile.lastModified()
        val rev = mtime.toString()
        return VersionedRook(repoId, RepoType.DOCUMENT, uri, newUri!!, rev, mtime)
    }

    @Throws(IOException::class)
    override fun delete(uri: Uri) {
        val docFile = DocumentFile.fromSingleUri(context, uri)
        if (docFile != null && docFile.exists()) {
            if (!docFile.delete()) {
                throw IOException("Failed deleting document $uri")
            }
        }
    }

    override fun toString(): String {
        return uri.toString()
    }

    companion object {
        private val TAG = ContentRepo::class.java.name
        const val SCHEME = "content"
    }
}