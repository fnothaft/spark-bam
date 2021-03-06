package org.hammerlab.bam.check

import hammerlab.show._
import htsjdk.samtools.{ BAMRecord, SAMFileHeader, SAMRecord, ValidationStringency }
import org.apache.spark.broadcast.Broadcast
import org.hammerlab.bam.check.full.error.Flags
import org.hammerlab.bam.header.{ ContigLengths, Header }
import org.hammerlab.bam.iterator.RecordStream
import org.hammerlab.bam.spark.FindRecordStart
import org.hammerlab.bgzf.Pos
import org.hammerlab.bgzf.block.SeekableUncompressedBytes

case class PosMetadata(pos: Pos,
                       recordOpt: Option[NextRecord],
                       flags: Flags)

object PosMetadata {

  implicit def defaultShow(implicit showRecord: Show[SAMRecord]): Show[PosMetadata] =
    Show {
      case PosMetadata(pos, recordOpt, flags) ⇒
        show"$pos:\t$recordOpt. Failing checks: $flags"
    }

  implicit def showNextRecordOpt(implicit showNextRecord: Show[NextRecord]): Show[Option[NextRecord]] =
    Show {
      case Some(nextRecord) ⇒ nextRecord.show
      case None ⇒ "no next record"
    }

  def recordPos(record: SAMRecord)(implicit contigLengths: ContigLengths): String =
    s"${contigLengths(record.getReferenceIndex)._1}:${record.getStart}"

  implicit def showRecord(implicit contigLengths: ContigLengths): Show[SAMRecord] =
    Show {
      record ⇒
        record
          .toString
          .dropRight(1) +  // remove trailing period
            (
              // Append info about mapped/placed location
              if (
                record.getReadUnmappedFlag &&
                record.getStart >= 0 &&
                record.getReferenceIndex >= 0 &&
                record.getReferenceIndex < contigLengths.size
              )
                s" (placed at ${recordPos(record)})"
              else if (!record.getReadUnmappedFlag)
                s" @ ${recordPos(record)}"
              else
                ""
            )
    }

  def apply(pos: Pos,
            flags: Flags)(
      implicit
      uncompressedBytes: SeekableUncompressedBytes,
      header: Broadcast[Header],
      readsToCheck: ReadsToCheck,
      maxReadSize: MaxReadSize
  ): PosMetadata = {
    implicit val contigLengths = header.value.contigLengths
    PosMetadata(
      pos,
      {
        FindRecordStart
          .withDelta(pos)
          .map {
            case (nextRecordPos, delta) ⇒

              uncompressedBytes.seek(nextRecordPos)

              NextRecord(
                RecordStream(
                  uncompressedBytes,
                  header.value
                )
                .next()
                ._2,
                delta
              )
          }
      },
      flags
    )
  }

  import org.hammerlab.kryo._
  import org.hammerlab.bam.kryo.registerSAMFileHeader

  implicit val alsoRegister: AlsoRegister[PosMetadata] =
    AlsoRegister(
      cls[NextRecord],
      cls[BAMRecord],
      cls[ValidationStringency],
      cls[SAMFileHeader]
    )
}
