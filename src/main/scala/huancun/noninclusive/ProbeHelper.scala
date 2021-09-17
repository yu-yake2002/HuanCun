package huancun.noninclusive

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import freechips.rocketchip.tilelink.{TLMessages, TLPermissions}
import huancun.{HuanCunModule, MSHRRequest, MetaData}

class ProbeHelper(entries: Int = 4, enqDelay: Int = 1)(implicit p: Parameters)
  extends HuanCunModule with HasClientInfo
{
  val io = IO(new Bundle() {
    val dirResult = Flipped(Valid(new DirResult()))
    val probe = DecoupledIO(new MSHRRequest)
    val full = Output(Bool())
  })

  val queue = Module(new Queue(new MSHRRequest, entries = entries, pipe = false, flow = false))

  io.full := queue.io.count >= (entries - enqDelay).U

  val dir = io.dirResult.bits
  val req_client = OHToUInt(getClientBitOH(dir.sourceId))
  val req = Wire(new MSHRRequest)

  val full_addr = Cat(dir.clients(req_client).tag, dir.set(clientSetBits - 1, 0))
  val tgt_tag = full_addr.head(tagBits)
  val tgt_set = full_addr.tail(tagBits).head(setBits)
  val tgt_off = full_addr.tail(tagBits).tail(setBits)

  req.fromProbeHelper := true.B
  req.opcode := TLMessages.Probe
  req.param := TLPermissions.toN
  req.channel := "b010".U
  req.size := log2Up(blockBytes).U
  req.source := dir.sourceId
  req.tag := tgt_tag
  req.set := tgt_set
  req.off := tgt_off
  req.bufIdx := DontCare
  req.needHint.foreach(_ := false.B)
  req.isPrefetch.foreach(_ := false.B)
  req.alias.foreach(_ := 0.U)
  req.preferCache := true.B

  val client_dir = dir.clients(req_client)
  val dir_conflict = !client_dir.hit && client_dir.state =/= MetaData.INVALID
  val formA = dir.replacerInfo.channel === 1.U
  val req_acquire = formA && (dir.replacerInfo.opcode === TLMessages.AcquirePerm ||
    dir.replacerInfo.opcode === TLMessages.AcquireBlock)
  queue.io.enq.valid := req_acquire && io.dirResult.valid && dir_conflict
  queue.io.enq.bits := req
  when(queue.io.enq.valid){ assert(queue.io.enq.ready) }

  io.probe <> queue.io.deq
}