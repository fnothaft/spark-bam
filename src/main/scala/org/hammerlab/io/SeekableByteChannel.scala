package org.hammerlab.io

import java.io.{ IOException, InputStream }
import java.nio.channels.FileChannel
import java.nio.{ ByteBuffer, channels }

import org.hammerlab.paths
import org.apache.hadoop.fs.Seekable
import org.hammerlab.hadoop.{ Configuration, Path }
import org.hammerlab.io.ByteChannel.InputStreamByteChannel

trait SeekableByteChannel
  extends ByteChannel {

  def seek(newPos: Long): Unit = {
    _position = newPos
    _seek(newPos)
  }

  override def _skip(n: Int): Unit = _seek(position() + n)

  def size: Long

  def _seek(newPos: Long): Unit
}

object SeekableByteChannel {
  case class ChannelByteChannel(ch: channels.SeekableByteChannel)
    extends SeekableByteChannel {

    override def _read(dst: ByteBuffer): Unit = {
      val n = dst.remaining()
      var read = ch.read(dst)

      if (read < n)
        read += ch.read(dst)

      if (read < n)
        throw new IOException(
          s"Only read $read of $n bytes in 2 tries from position ${position()}"
        )
    }
    override def size: Long = ch.size
    override def close(): Unit = ch.close()
    override def position(): Long = ch.position()

    override def _skip(n: Int): Unit = ch.position(ch.position() + n)
    override def _seek(newPos: Long): Unit = ch.position(newPos)
  }

  implicit def makeChannelByteChannel(ch: channels.SeekableByteChannel): ChannelByteChannel =
    ChannelByteChannel(ch)

  implicit def makeChannelByteChannel(path: paths.Path): ChannelByteChannel =
    ChannelByteChannel(
      FileChannel.open(
        path
      )
    )

  case class SeekableHadoopByteChannel(is: InputStream with Seekable,
                                       size: Long)
    extends InputStreamByteChannel(is)
      with SeekableByteChannel {
    override def _seek(newPos: Long): Unit =
      is.seek(newPos)
  }

  implicit def apply(path: Path)(
      implicit conf: Configuration
  ): SeekableHadoopByteChannel =
    SeekableHadoopByteChannel(
      path.open,
      path.length
    )
}
