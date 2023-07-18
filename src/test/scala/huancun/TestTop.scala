package huancun

import chisel3._
import chisel3.util._
import huancun.utils.TLClientsMerger
import org.chipsalliance.cde.config._
import chisel3.stage.{ChiselGeneratorAnnotation, ChiselStage}
import freechips.rocketchip.util._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._

import scala.collection.mutable.ArrayBuffer

class TestTop_L2()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  \    /
   *    L2
   */

  val delayFactor = 0.5
  val cacheParams = p(HCCacheParamsKey)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Seq(),
        requestFields = Seq(PrefetchField(), PreferCacheField(), DirtyField(), AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l1d_nodes = (0 until 2) map( i => createClientNode(s"l1d$i", 32))
  val master_nodes = l1d_nodes

  val l2 = LazyModule(new HuanCun())
  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xffffL), beatBytes = 32))

  for(l1d <- l1d_nodes){
    xbar := TLBuffer() := l1d
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(32, 64) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      l2.node :=* xbar

  lazy val module = new LazyModuleImp(this){
    master_nodes.zipWithIndex.foreach{
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
  }
}

class TestTop_L2L3()(implicit p: Parameters) extends LazyModule {

  /* L1D   L1D
   *  |     |
   * L2    L2
   *  \    /
   *    L3
   */

  val delayFactor = 0.2
  val cacheParams = p(HCCacheParamsKey)

  def createClientNode(name: String, sources: Int) = {
    val masterNode = TLClientNode(Seq(
      TLMasterPortParameters.v2(
        masters = Seq(
          TLMasterParameters.v1(
            name = name,
            sourceId = IdRange(0, sources),
            supportsProbe = TransferSizes(cacheParams.blockBytes)
          )
        ),
        channelBytes = TLChannelBeatBytes(cacheParams.blockBytes),
        minLatency = 1,
        echoFields = Seq(DirtyField()),
        requestFields = Seq(PrefetchField(), PreferCacheField(), DirtyField(), AliasField(2)),
        responseKeys = cacheParams.respKey
      )
    ))
    masterNode
  }

  val l1d_nodes = (0 until 2) map( i => createClientNode(s"l1d$i", 32))
  val master_nodes = l1d_nodes

  val l2_nodes = (0 until 2) map( i => LazyModule(new HuanCun()(new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = s"L2",
      level = 2,
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 8, blockGranularity = 5, name = "L2")),
      prefetch = Some(huancun.prefetch.BOPParameters()),
      reqField = Seq(PreferCacheField()),
      echoField = Seq(DirtyField())
    )
  }))).node)

  val l3 = LazyModule(new HuanCun()(new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 8, blockGranularity = 5, name = "L3")),
      simulation = true
    )
  })))

  val xbar = TLXbar()
  val ram = LazyModule(new TLRAM(AddressSet(0, 0xffffL), beatBytes = 32))

  l1d_nodes.zip(l2_nodes).map {
    case (l1d,l2) => l2 := TLBuffer() := l1d
  }

  for(l2 <- l2_nodes){
    xbar := TLBuffer() := l2
  }

  ram.node :=
    TLXbar() :=*
      TLFragmenter(32, 64) :=*
      TLCacheCork() :=*
      TLDelayer(delayFactor) :=*
      l3.node :=* xbar

  lazy val module = new LazyModuleImp(this){
    master_nodes.zipWithIndex.foreach{
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
  }
}

class TestTop_FullSys()(implicit p: Parameters) extends LazyModule {
  val cacheParams: HCCacheParameters = p(HCCacheParamsKey)
  val nrL2 = 1
  val L2NBanks = 1
  val L3NBanks = 1
  val L3BlockSize = 64
  val L3OuterBusWidth = 256

  def createL1Node(name: String, sources: Int): TLClientNode = {
    val aliasBitsOpt = Some(2)
    val reqFields: Seq[BundleFieldBase] = Seq(
      PrefetchField(),
      PreferCacheField()
    ) ++ aliasBitsOpt.map(AliasField)
    val echoFields: Seq[BundleFieldBase] = Seq(DirtyField())

    val clientParameters = TLMasterPortParameters.v1(
      Seq(TLMasterParameters.v1(
        name = name,
        sourceId = IdRange(0, sources),
        supportsProbe = TransferSizes(cacheParams.blockBytes),
      )),
      requestFields = reqFields,
      echoFields = echoFields
    )
    val clientNode = TLClientNode(Seq(clientParameters))
    clientNode
  }

  def createDMANode(name: String, sources: Int): TLClientNode = {
    val clientParameters = TLMasterPortParameters.v1(
      Seq(TLMasterParameters.v1(
        name = name,
        sourceId = IdRange(0, sources),
        supportsProbe = TransferSizes.none
      ))
    )
    val clientNode = TLClientNode(Seq(clientParameters))
    clientNode
  }

  val l2_nodes = (0 until nrL2) map (i => LazyModule(new HuanCun()(new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L2",
      level = 2,
      ways = 4,
      sets = 128,
      inclusive = false,
      alwaysReleaseData = true,
      clientCaches = Seq(CacheParameters("dcache", sets = 32, ways = 8, blockGranularity = 5)),
      reqField = Seq(PreferCacheField()),
      echoField = Seq(DirtyField()),
      prefetch = Some(huancun.prefetch.BOPParameters()),
      sramDepthDiv = 2,
      simulation = true
    )
  }))).node)

  val l3 = LazyModule(new HuanCun()(new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      name = "L3",
      level = 3,
      ways = 4,
      sets = 256,
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 128, ways = 4, blockGranularity = log2Ceil(128), name = "L2")),
      sramClkDivBy2 = true,
      sramDepthDiv = 4,
      simulation = true,
    )
  })))

  val l1d_nodes = (0 until 2) map (i => createL1Node(s"l1d$i", 32))
  val dma_node = createDMANode(s"dma", 16)
  val master_nodes = l1d_nodes ++ Seq(dma_node)

  val ram = LazyModule(new TLRAM(AddressSet(0, 0xFFFFFL), beatBytes = 32))
  val l3_binder = BankBinder(L3NBanks, L3BlockSize)
  val mem_xbar = TLXbar()
  val l3_banked_xbar = TLXbar()
  val l3_xbar = TLXbar()
  val l3_out = TLTempNode()
  val core_to_l3_ports = Array.fill(nrL2) { TLTempNode() }
  val l2_binder = BankBinder(L2NBanks, 64)
  val l2_xbar = TLXbar()

  ram.node :=
    TLBuffer.chainNode(100) :=
    TLFragmenter(32, 64) :=
    TLSourceShrinker(64) :=
    TLWidthWidget(L3OuterBusWidth / 8) :=
    TLBuffer.chainNode(2) :=
    mem_xbar :=*
    TLXbar() :=*
    TLBuffer.chainNode(2) :=*
    TLCacheCork() :=*
    l3_binder :*=
    l3_out :*=
    l3.node :*=
    TLBuffer.chainNode(2) :*=
    l3_banked_xbar

  l3_banked_xbar :=
    TLBuffer.chainNode(2) :=
    l3_xbar

  for (core_out <- core_to_l3_ports) {
    l3_banked_xbar :=*
      TLBuffer() :=
      core_out
  }

  l3_xbar :=
    TLFIFOFixer() :=
    TLWidthWidget(32) :=
    dma_node

  for (i <- 0 until nrL2) {
    core_to_l3_ports(i) :=*
      TLBuffer.chainNode(2) :=
      TLClientsMerger() :=
      TLXbar() :=*
      l2_binder :*=
      l2_nodes(i)
  }

  require(nrL2 == 1)
  l2_nodes.head :=* l2_xbar
  for (l1d <- l1d_nodes) {
    l2_xbar := TLBuffer() := l1d
  }

  lazy val module = new LazyModuleImp(this) {
    master_nodes.zipWithIndex.foreach {
      case (node, i) =>
        node.makeIOs()(ValName(s"master_port_$i"))
    }
  }
}

object TestTop_L2 extends App {
  val config = new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 8, blockGranularity = 5, name = "L2", aliasBitsOpt = Some(2))),
      sramClkDivBy2 = true,
    )
  })
  val top = DisableMonitors(p => LazyModule(new TestTop_L2()(p)) )(config)

  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => top.module)
  ))
}

object TestTop_L2L3 extends App {
  val config = new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 8, blockGranularity = 5, name = "L2", aliasBitsOpt = Some(2))),
    )
  })
  val top = DisableMonitors(p => LazyModule(new TestTop_L2L3()(p)) )(config)
   

  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => top.module)
  ))
}

object TestTop_FullSys extends App {
  val config = new Config((_, _, _) => {
    case HCCacheParamsKey => HCCacheParameters(
      inclusive = false,
      clientCaches = Seq(CacheParameters(sets = 32, ways = 8, blockGranularity = 5, name = "L2", aliasBitsOpt = Some(2))),
    )
  })
  val top = DisableMonitors( p => LazyModule(new TestTop_FullSys()(p)) )(config)

  (new ChiselStage).execute(args, Seq(
    ChiselGeneratorAnnotation(() => top.module)
  ))
}
