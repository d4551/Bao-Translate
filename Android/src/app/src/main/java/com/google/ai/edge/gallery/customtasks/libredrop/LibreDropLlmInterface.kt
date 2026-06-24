package com.google.ai.edge.gallery.customtasks.libredrop

import com.google.ai.edge.litertlm.Contents

data class FileDescription(
  val fileName: String,
  val description: String,
  val suggestedRecipients: List<String>,
)

data class FileMetadata(
  val name: String,
  val mimeType: String,
  val sizeBytes: Long,
)

class LibreDropLlmInterface {

  fun buildFileDescriptionPrompt(metadata: FileMetadata): Contents {
    val prompt = """
      |Describe this file for sharing purposes:
      |File name: ${metadata.name}
      |Type: ${metadata.mimeType}
      |Size: ${formatSize(metadata.sizeBytes)}
      |
      |Provide a brief one-sentence description and suggest who might want to receive this file.
    """.trimMargin()

    return Contents.of(prompt)
  }

  fun buildSmartSuggestionPrompt(
    files: List<FileMetadata>,
    recentPeers: List<String>,
  ): Contents {
    val fileList = files.joinToString("\n") { "  - ${it.name} (${it.mimeType})" }
    val peerList = recentPeers.joinToString("\n") { "  - $it" }

    val prompt = """
      |Given these files:
      |$fileList
      |
      |And these recent sharing peers:
      |$peerList
      |
      |Suggest which files to share with which peers and why.
    """.trimMargin()

    return Contents.of(prompt)
  }

  private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "${bytes / (1024 * 1024 * 1024)} GB"
  }
}
