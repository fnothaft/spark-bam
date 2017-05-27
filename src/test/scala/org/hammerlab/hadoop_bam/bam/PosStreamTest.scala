package org.hammerlab.hadoop_bam.bam

import org.hammerlab.hadoop_bam.bgzf.Pos
import org.hammerlab.test.Suite
import org.hammerlab.test.resources.File

class PosStreamTest
  extends Suite {

  def checkFirstPositions(implicit stream: PosStreamI): Unit = {
    stream.take(10).toList should be(
      Seq(
        Pos(2454,    0),
        Pos(2454,  624),
        Pos(2454, 1244),
        Pos(2454, 1883),
        Pos(2454, 2520),
        Pos(2454, 3088),
        Pos(2454, 3734),
        Pos(2454, 4368),
        Pos(2454, 4987),
        Pos(2454, 5668)
      )
    )
  }

  test("PosStream") {
    implicit val stream = PosStream(File("test5k.bam"))

    checkFirstPositions
  }

  test("SeekablePosStream") {
    implicit val stream = SeekablePosStream(File("test5k.bam"))

    checkFirstPositions

    stream.seek(Pos(970754, 61671))

    stream.take(10).toList should be(
      Seq(
        Pos(970754, 61671),
        Pos(970754, 62334),
        Pos(970754, 62999),
        Pos(970754, 63664),
        Pos(970754, 64326),
        Pos(988320,     0),
        Pos(988320,   664),
        Pos(988320,  1326),
        Pos(988320,  1987),
        Pos(988320,  2652)
      )
    )

    stream.seek(Pos(988320, 64141))

    stream.take(3).toList should be(
      Seq(
        Pos( 988320, 64141),
        Pos(1006167,     0),
        Pos(1006167,   663)
      )
    )
  }
}
