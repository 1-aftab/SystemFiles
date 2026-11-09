package com.example.systemfiles

import java.io.File
import java.io.IOException

/**
 * Copy / move helpers shared by the file browser.
 *
 * All methods work on plain [File] objects, so they need full storage access
 * (MANAGE_EXTERNAL_STORAGE or the legacy read/write permission).
 */
internal object FileOperations {

    /** Returns a free name inside [directory], e.g. "photo (1).jpg". */
    fun uniqueDestination(directory: File, name: String): File {
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate

        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""

        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$base ($index)$extension")
            index++
        }
        return candidate
    }

    /** Copies [source] into [destinationDirectory] and returns the new file. */
    fun copy(source: File, destinationDirectory: File): File {
        if (isInside(destinationDirectory, source)) {
            throw IOException("cannot copy a folder into itself")
        }
        val destination = uniqueDestination(destinationDirectory, source.name)
        copyRecursive(source, destination)
        return destination
    }

    /** Moves [source] into [destinationDirectory] and returns the new file. */
    fun move(source: File, destinationDirectory: File): File {
        if (isInside(destinationDirectory, source)) {
            throw IOException("cannot move a folder into itself")
        }
        val destination = uniqueDestination(destinationDirectory, source.name)
        if (source.renameTo(destination)) return destination

        // Different volumes or a file system that refuses renames: copy, then delete.
        copyRecursive(source, destination)
        if (!source.deleteRecursively() && source.exists()) {
            throw IOException("copied, but the original could not be removed")
        }
        return destination
    }

    /** True when [candidate] is [parent] itself or lives somewhere below it. */
    fun isInside(candidate: File, parent: File): Boolean {
        val candidatePath = candidate.canonicalPath
        val parentPath = parent.canonicalPath
        return candidatePath == parentPath || candidatePath.startsWith(parentPath + File.separator)
    }

    private fun copyRecursive(source: File, destination: File) {
        if (source.isDirectory) {
            if (destination.exists() && !destination.isDirectory) {
                throw IOException("${destination.name} already exists")
            }
            if (!destination.exists() && !destination.mkdirs()) {
                throw IOException("could not create ${destination.absolutePath}")
            }
            val children = source.listFiles()
                ?: throw IOException("could not read ${source.absolutePath}")
            for (child in children) {
                copyRecursive(child, File(destination, child.name))
            }
        } else {
            destination.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException("could not create ${parent.absolutePath}")
                }
            }
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
            destination.setLastModified(source.lastModified())
        }
    }
}
