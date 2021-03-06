package org.jetbrains.builtInWebServer

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtilRt
import com.intellij.util.PathUtilRt
import com.intellij.util.isDirectory
import io.netty.buffer.ByteBufUtf8Writer
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.handler.codec.http.*
import io.netty.handler.stream.ChunkedStream
import org.jetbrains.builtInWebServer.ssi.SsiExternalResolver
import org.jetbrains.builtInWebServer.ssi.SsiProcessor
import org.jetbrains.io.FileResponses
import org.jetbrains.io.Responses
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private class StaticFileHandler : WebServerFileHandler() {
  private var ssiProcessor: SsiProcessor? = null

  override fun process(pathInfo: PathInfo, canonicalPath: CharSequence, project: Project, request: FullHttpRequest, channel: Channel, projectNameIfNotCustomHost: String?): Boolean {
    if (pathInfo.ioFile != null || pathInfo.file!!.isInLocalFileSystem) {
      val ioFile = pathInfo.ioFile ?: Paths.get(pathInfo.file!!.path)

      val nameSequence = pathInfo.name
      //noinspection SpellCheckingInspection
      if (StringUtilRt.endsWithIgnoreCase(nameSequence, ".shtml") || StringUtilRt.endsWithIgnoreCase(nameSequence, ".stm") || StringUtilRt.endsWithIgnoreCase(nameSequence, ".shtm")) {
        processSsi(ioFile, PathUtilRt.getParentPath(canonicalPath.toString()), project, request, channel)
        return true
      }

      sendIoFile(channel, ioFile, request)
    }
    else {
      val file = pathInfo.file!!
      val response = FileResponses.prepareSend(request, channel, file.timeStamp, file.name) ?: return true

      val keepAlive = Responses.addKeepAliveIfNeed(response, request)
      if (request.method() != HttpMethod.HEAD) {
        HttpUtil.setContentLength(response, file.length)
      }

      channel.write(response)

      if (request.method() != HttpMethod.HEAD) {
        channel.write(ChunkedStream(file.inputStream))
      }

      val future = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
      if (!keepAlive) {
        future.addListener(ChannelFutureListener.CLOSE)
      }
    }

    return true
  }

  private fun processSsi(file: Path, path: String, project: Project, request: FullHttpRequest, channel: Channel) {
    if (ssiProcessor == null) {
      ssiProcessor = SsiProcessor(false)
    }

    val buffer = channel.alloc().ioBuffer()
    val keepAlive: Boolean
    var releaseBuffer = true
    try {
      val lastModified = ssiProcessor!!.process(SsiExternalResolver(project, request, path, file.parent), file, ByteBufUtf8Writer(buffer))
      val response = FileResponses.prepareSend(request, channel, lastModified, file.fileName.toString()) ?: return
      keepAlive = Responses.addKeepAliveIfNeed(response, request)
      if (request.method() != HttpMethod.HEAD) {
        HttpUtil.setContentLength(response, buffer.readableBytes().toLong())
      }

      channel.write(response)

      if (request.method() != HttpMethod.HEAD) {
        releaseBuffer = false
        channel.write(buffer)
      }
    }
    finally {
      if (releaseBuffer) {
        buffer.release()
      }
    }

    val future = channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
    if (!keepAlive) {
      future.addListener(ChannelFutureListener.CLOSE)
    }
  }
}

fun sendIoFile(channel: Channel, ioFile: Path, request: HttpRequest) {
  if (hasAccess(ioFile)) {
    FileResponses.sendFile(request, channel, ioFile)
  }
  else {
    Responses.sendStatus(HttpResponseStatus.FORBIDDEN, channel, request)
  }
}

// deny access to .htaccess files
private fun hasAccess(result: Path) = !result.isDirectory() && Files.isReadable(result) && !(Files.isHidden(result) || result.fileName.toString().startsWith(".ht"))