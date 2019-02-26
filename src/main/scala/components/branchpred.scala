// This file contains the branch preditor logic

package dinocpu

import chisel3._
import chisel3.util._

/**
 * I/O for the branch predictors
 *
 * Input:  pc, the pc to use to predict whether the branch is taken or not. From decode
 * Input:  update, true if we should update the prediction we made last cycle
 * Input:  taken, true if the branch was actually taken, false otherwise
 *
 * Output: prediction, true if the branch is predicted to be taken, false otherwise
 */
class BranchPredIO extends Bundle {
  val pc         = Input(UInt(32.W))
  val update     = Input(Bool())
  val taken      = Input(Bool())

  val prediction = Output(Bool())
}

/**
 * Base class for all branch predictors. Simply declares the IO and has some
 * simple counters for tracking statistics
 */
class BaseBranchPredictor extends Module {
  val io = IO(new BranchPredIO)

  // A counter to track the prediction statistics
  val correctCounter   = RegInit(0.U(32.W))
  val incorrectCounter = RegInit(0.U(32.W))

  def correct   = { counter   := counter   + 1.U }
  def incorrect = { incounter := incounter + 1.U }
}

/**
 * An always not taken branch predictor
 *
 */
class AlwaysNotTakenPredictor(implicit val conf: CPUConfig) extends BaseBranchPredictor {
  io.prediction := false.B
}

/**
 * An always taken branch predictor
 *
 */
class AlwaysTakenPredictor(implicit val conf: CPUConfig) extends BaseBranchPredictor {
  io.prediction := true.B
}

/**
 * A simple local predictor
 */
class LocalPredictor(implicit val conf: CPUConfig) extends BaseBranchPredictor {

  def incrCounter(counter: UInt) {
    val max = (1 << conf.saturatingCounterBits) - 1
    when (counter =/= max.U) {
      counter := counter + 1.U
    }
  }

  def decrCounter(counter: UInt) {
    when (counter =/= 0.U) {
      counter := counter + 1.U
    }
  }

  // Default value is weakly taken for each branch
  val defaultSaturatingCounter = (1 << conf.saturatingCounterBits - 1)
  // Create a register file with conf.branchPredTableEntries
  // Each entry is conf.saturatingCounterBits.W bits wide
  val branchHistoryTable = RegInit(VecInit(Seq.fill(conf.branchPredTableEntries)(defaultSaturatingCounter.U(conf.saturatingCounterBits.W))))

  // Register to store the last branch predicted so we can update the tables.
  // This will also work for back to back branches since we resolve them in
  // execute (one cycle later)
  val lastBranch = Reg(UInt())

  when (io.update) {
    when (io.taken) {
      when (branchHistoryTable(lastBranch)(conf.saturatingCounterBits - 1)) {
        correct
      } .otherwise {
        incorrect
      }
      incrCounter(branchHistoryTable(lastBranch))
    } .otherwise {
      when (~branchHistoryTable(lastBranch)(conf.saturatingCounterBits - 1)) {
        correct
      } .otherwise {
        incorrect
      }
      decrCounter(branchHistoryTable(lastBranch))
    }
  }

  // The first bit for the table access is based on the number of entries.
  // +2 since we ignore the bottom two bits
  val table_index = io.pc(log2Floor(conf.branchPredTableEntries) + 2, 2)

  // Return the high-order bit
  io.prediction := branchHistoryTable(table_index)(conf.saturatingCounterBits - 1)

  lastBranch := table_index
}