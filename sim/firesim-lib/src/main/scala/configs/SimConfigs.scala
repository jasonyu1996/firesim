//See LICENSE for license details.
package firesim.configs

import freechips.rocketchip.config.{Parameters, Config, Field}

import midas.{EndpointKey}
import midas.widgets.{EndpointMap}
import midas.models._

import firesim.endpoints._

object BaseParamsKey extends Field[BaseParams]
object LlcKey extends Field[Option[LLCParams]]
object DramOrganizationKey extends Field[DramOrganizationParams]

// Removes default endpoints from the MIDAS-provided config
class BasePlatformConfig extends Config(new Config((site, here, up) => {
    case EndpointKey => EndpointMap(Seq.empty)
}) ++ new midas.F1Config)

// Experimental: mixing this in will enable assertion synthesis
class WithSynthAsserts extends Config((site, here, up) => {
  case midas.SynthAsserts => true
  case EndpointKey => EndpointMap(Seq(new midas.widgets.AssertBundleEndpoint)) ++ up(EndpointKey)
})

// Experimental: mixing this in will enable print synthesis
class WithPrintfSynthesis extends Config((site, here, up) => {
  case midas.SynthPrints => true
  case EndpointKey => EndpointMap(Seq(new midas.widgets.PrintRecordEndpoint)) ++ up(EndpointKey)
})

class WithSerialWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimSerialIO))
})

class WithUARTWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimUART))
})

class WithSimpleNICWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimSimpleNIC))
  case LoopbackNIC => false
})

class WithBlockDevWidget extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(new SimBlockDev))
})

class WithTracerVWidget extends Config((site, here, up) => {
  case midas.EndpointKey => up(midas.EndpointKey) ++
    EndpointMap(Seq(new SimTracerV))
})

// MIDAS 2.0 Switches
class WithMultiCycleRamModels extends Config((site, here, up) => {
  case midas.GenerateMultiCycleRamModels => true
})
// Short name alias for above
class MCRams extends WithMultiCycleRamModels

// Instantiates an AXI4 memory model that executes (1 / clockDivision) of the frequency
// of the RTL transformed model (Rocket Chip)
class WithDefaultMemModel(clockDivision: Int = 1) extends Config((site, here, up) => {
  case EndpointKey => up(EndpointKey) ++ EndpointMap(Seq(
    new FASEDAXI4Endpoint(midas.core.ReciprocalClockRatio(clockDivision))))
  case LlcKey => None
  // Only used if a DRAM model is requested
  case DramOrganizationKey => DramOrganizationParams(maxBanks = 8, maxRanks = 4, dramSize = BigInt(1) << 34)
  // Default to a Latency-Bandwidth Pipe without and LLC model
  case BaseParamsKey => new BaseParams(
    maxReads = 16,
    maxWrites = 16,
    beatCounters = true,
    llcKey = site(LlcKey))

	case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(new
		LatencyPipeConfig(site(BaseParamsKey))(p))(p)
})


/*******************************************************************************
* Memory-timing model configuration modifiers
*******************************************************************************/
// Adds a LLC model with at most <maxSets> sets with <maxWays> ways
class WithLLCModel(maxSets: Int, maxWays: Int) extends Config((site, here, up) => {
  case LlcKey => Some(LLCParams().copy(
    ways = WRange(1, maxWays),
    sets = WRange(1, maxSets)
  ))
})

// Changes the default DRAM memory organization.
class WithDramOrganization(maxRanks: Int, maxBanks: Int, dramSize: BigInt)
    extends Config((site, here, up) => {
  case DramOrganizationKey => up(DramOrganizationKey, site).copy(
    maxBanks = maxBanks,
    maxRanks = maxRanks,
    dramSize = dramSize
  )
})


// Instantiates a DDR3 model with a FCFS memory access scheduler
class WithDDR3FIFOMAS(queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(
    new FIFOMASConfig(
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey))(p))(p)
})

// Instantiates a DDR3 model with a FR-FCFS memory access scheduler
// windowSize = Maximum number of references the MAS can schedule across
class WithDDR3FRFCFS(windowSize: Int, queueDepth: Int) extends Config((site, here, up) => {
  case MemModelKey => (p: Parameters) => new FASEDMemoryTimingModel(
    new FirstReadyFCFSConfig(
      schedulerWindowSize = windowSize,
      transactionQueueDepth = queueDepth,
      dramKey = site(DramOrganizationKey),
      baseParams = site(BaseParamsKey))(p))(p)
  }
)

// Changes the functional model capacity limits
class WithFuncModelLimits(maxReads: Int, maxWrites: Int) extends Config((site, here, up) => {
  case BaseParamsKey => up(BaseParamsKey, site).copy(
    maxReads = maxReads,
    maxWrites = maxWrites
  )
})

/*******************************************************************************
* Complete Memory-Timing Model Configurations
*******************************************************************************/
// Latency Bandwidth Pipes
class LBP32R32W extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel)
class LBP32R32WLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel)

// An LBP that runs at 1/3 the frequency of the cores + uncore
// This is 1067 MHz for default core frequency of 3.2 GHz
class LBP32R32W3Div extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDefaultMemModel(3))

// DDR3 - FCFS models.
class FCFS16GBQuadRank extends Config(new WithDDR3FIFOMAS(8) ++ new WithDefaultMemModel)
class FCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FCFS16GBQuadRank)

// DDR3 - First-Ready FCFS models
class FRFCFS16GBQuadRank(clockDiv: Int = 1) extends Config(
  new WithFuncModelLimits(32,32) ++
  new WithDDR3FRFCFS(8, 8) ++
  new WithDefaultMemModel(clockDiv)
)
class FRFCFS16GBQuadRankLLC4MB extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank
)

class FRFCFS16GBQuadRankLLC4MB3Div extends Config(
  new WithLLCModel(4096, 8) ++
  new FRFCFS16GBQuadRank(3)
)

////Midas 2.0 Configs
//class Midas2Config extends Config(
//   new WithMultiCycleRamModels ++
//   new FireSimConfig)
