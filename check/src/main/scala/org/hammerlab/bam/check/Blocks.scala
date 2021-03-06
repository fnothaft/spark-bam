package org.hammerlab.bam.check

import java.lang.{ Long ⇒ JLong }

import caseapp.{ Recurse, ValueDescription, HelpMessage ⇒ M, Name ⇒ O }
import cats.implicits.catsKernelStdGroupForLong
import hammerlab.bytes._
import hammerlab.iterator._
import hammerlab.path._
import magic_rdds._
import org.apache.spark.SparkContext
import org.apache.spark.rdd.RDD
import org.hammerlab.args.{ ByteRanges, FindBlockArgs, SplitSize }
import org.hammerlab.bgzf.block.{ FindBlockStart, Metadata, MetadataStream }
import org.hammerlab.channel.SeekableByteChannel
import org.hammerlab.guava.collect.Range.closedOpen
import org.hammerlab.kryo._
import org.hammerlab.magic.rdd.ordered.SortedRDD.Bounds
import org.hammerlab.magic.rdd.scan.ScanValuesRDD
import org.hammerlab.math.ceil

case class Blocks(blocks: RDD[Metadata],
                  bounds: Bounds[Long])

object Blocks
  extends Registrar {

  implicit def toBlocks(blocks: Blocks): RDD[Metadata] = blocks.blocks

  case class Args(
    @Recurse findBlockArgs: FindBlockArgs,

    @O("intervals") @O("i")
    @ValueDescription("intervals")
    @M("Comma-separated list of byte-ranges to restrict computation to; when specified, only BGZF blocks whose starts are in this set will be considered. Allowed formats: <start>-<end>, <start>+<length>, <position>. All values can take integer values or byte-size shorthands (e.g. \"10m\")")
    ranges: Option[ByteRanges] = None,

    @O("b")
    @ValueDescription("path")
    @M("File with bgzf-block-start positions as output by index-blocks; If unset, the BAM path with a \".blocks\" extension appended is used. If this path doesn't exist, use a parallel search for BGZF blocks (see --bgzf-block-headers-to-check)")
    blocksPath: Option[Path] = None,

    @Recurse
    splits: SplitSize.Args
  )

  def apply()(
      implicit
      sc: SparkContext,
      path: Path,
      args: Args
  ): Blocks = {

    val blocksPath: Path =
      args
        .blocksPath
        .getOrElse(
          path + ".blocks"
        )

    val splitSize =
      args
        .splits
        .maxSplitSize(2 MB)
        .size

    val rangeSetBroadcast = sc.broadcast(args.ranges)

    /** Parse BGZF-block [[Metadata]] emitted by [[org.hammerlab.bgzf.index.IndexBlocks]] */
    if (blocksPath.exists) {
      val blocks =
        sc
          .textFile(blocksPath.toString)
          .map(
            line ⇒
              line.split(",") match {
                case Array(start, compressedSize, uncompressedSize) ⇒
                  Metadata(
                    start.toLong,
                    compressedSize.toInt,
                    uncompressedSize.toInt
                  )
                case _ ⇒
                  throw new IllegalArgumentException(
                    s"Bad blocks-index line: $line"
                  )
              }
          )
          .filter {
            case Metadata(start, _, _) ⇒
              rangeSetBroadcast
                .value
                .forall(
                  _.contains(start)
                )
          }

      val ScanValuesRDD(scanRDD, _, total) =
        blocks
          .map {
            block ⇒
              block →
                block
                  .compressedSize
                  .toLong
          }
          .scanLeftValues

      val numPartitions =
        ceil(
          total,
          splitSize
        )
        .toInt

      val repartitionedBlocks =
        scanRDD
          .map {
            case (block, offset) ⇒
              (offset / splitSize).toInt →
                block.start →
                block
          }
          .partitionByKey(numPartitions)

      Blocks(
        repartitionedBlocks,
        Bounds(
          (0 until numPartitions)
            .map {
              i ⇒
                Some(
                  (
                    i * splitSize,
                    Some((i + 1) * splitSize)
                  )
                )
            }
        )
      )
    } else {
      val numPartitions = ceil(path.size, splitSize).toInt
      val splitIdxs =
        0 until numPartitions filter {
          idx ⇒
            val start = idx * splitSize
            val end = (idx + 1) * splitSize
            val range = closedOpen[JLong](start, end)
            rangeSetBroadcast
              .value
              .forall(
                !_
                  .subRangeSet(range)
                  .isEmpty
              )
        }

      val blocks =
        sc
          .parallelize(
            splitIdxs,
            splitIdxs.length
          )
          .flatMap {
            idx ⇒
              val start = idx * splitSize
              val end = (idx + 1) * splitSize
              val in = SeekableByteChannel(path)
              val blockStart =
                FindBlockStart(
                  path,
                  start,
                  in,
                  args.findBlockArgs.bgzfBlocksToCheck
                )

              in.seek(blockStart)

              MetadataStream(in)
                .takeWhile(_.start < end)
                .filter {
                  case Metadata(start, _, _) ⇒
                    rangeSetBroadcast
                      .value
                      .forall(
                        _.contains(start)
                      )
                }
                .finish(in.close())
          }

      Blocks(
        blocks,
        Bounds(
          splitIdxs
            .map(
              i ⇒
                Some(
                  (
                    i * splitSize,
                    Some((i + 1) * splitSize)
                  )
                )
            )
        )
      )
    }
  }

  register(
    cls[ByteRanges],  // broadcast
    cls[Metadata]     // scanLeftValues
  )
}
