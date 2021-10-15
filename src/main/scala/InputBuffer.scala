package astronoc

import chisel3._
import chisel3.util._

import freechips.rocketchip.config.{Field, Parameters}


class InputBuffer(inParam: ChannelParams)(implicit val p: Parameters) extends Module with HasAstroNoCParams {
  val maxBufferSize = inParam.virtualChannelParams.map(_.bufferSize).max
  val io = IO(new Bundle {
    val in = Flipped(Valid(new Flit))

    val head = Output(UInt(log2Up(maxBufferSize).W))

    val read_req = Input(Valid(new Bundle {
      val addr = UInt(log2Up(maxBufferSize).W)
      val channel = UInt(log2Up(inParam.virtualChannelParams.size).W)
    }))
    val read_resp = Output(new Flit)
    val read_resp_tail = Output(Bool())
  })
  val bufferSz = inParam.virtualChannelParams.map(_.bufferSize).sum
  val (buffer, read, write) = if (inParam.useSyncReadBuffer) {
    val mem = SyncReadMem(bufferSz, new Flit)
    def read(x: UInt, en: Bool): Flit = mem.read(x, en)
    def write(x: UInt, d: Flit): Unit = mem.write(x, d)
    (mem, read(_,_), write(_,_))
  } else {
    val mem = Reg(Vec(bufferSz, new Flit))
    def read(x: UInt, en: Bool): Flit = RegEnable(mem(x), en)
    def write(x: UInt, d: Flit): Unit = mem(x) := d
    (mem, read(_,_), write(_,_))
  }
  val tails = Reg(Vec(bufferSz, Bool()))

  val heads = Reg(Vec(inParam.nVirtualChannels, UInt(log2Up(inParam.virtualChannelParams.map(_.bufferSize).max).W)))
  val bases = VecInit(inParam.virtualChannelParams.map(_.bufferSize).scanLeft(0)(_+_).dropRight(1).map(_.U))

  val in_virt_id = io.in.bits.virt_channel_id
  when (io.in.valid) {
    val base = bases(in_virt_id)
    val head = heads(in_virt_id)
    val waddr = base +& head
    write(waddr, io.in.bits)
    tails(waddr) := io.in.bits.tail
    heads(in_virt_id) := WrapInc(heads(in_virt_id),
      VecInit(inParam.virtualChannelParams.map(_.bufferSize.U))(in_virt_id))
  }

  io.head := heads(in_virt_id)

  val raddr = bases(io.read_req.bits.channel) +& io.read_req.bits.addr
  io.read_resp := read(raddr, io.read_req.valid)
  io.read_resp_tail := tails(raddr)

  when (reset.asBool) { heads.foreach(_ := 0.U) }
}
