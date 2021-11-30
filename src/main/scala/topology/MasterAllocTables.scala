package constellation.topology

import scala.math.pow

object MasterAllocTables {

  implicit class MasterAllocTableOps(private val a1: MasterAllocTable) {
    def unary_!(): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => !a1(nodeId)(p)
    def ||(a2: MasterAllocTable): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
      a1(nodeId)(p) || a2(nodeId)(p)
    }
    def ||(a2: Boolean): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
      a1(nodeId)(p) || a2
    }
    def &&(a2: MasterAllocTable): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
      a1(nodeId)(p) && a2(nodeId)(p)
    }
    def &&(a2: Boolean): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
      a1(nodeId)(p) && a2
    }
  }


  // Utility functions
  val srcIsIngress: MasterAllocTable = (_: Int) => (p: AllocParams) => p.srcId == -1
  val nxtIsVC0    : MasterAllocTable = (_: Int) => (p: AllocParams) => p.nxtV == 0
  val srcIsVC0    : MasterAllocTable = (_: Int) => (p: AllocParams) => p.srcV == 0
  val nxtVLTSrcV  : MasterAllocTable = (_: Int) => (p: AllocParams) => p.nxtV < p.srcV
  val nxtVLESrcV  : MasterAllocTable = (_: Int) => (p: AllocParams) => p.nxtV <= p.srcV

  // Usable policies
  val allLegal: MasterAllocTable = (_: Int) => (_: AllocParams) => true

  val bidirectionalLine: MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    if (nodeId < p.nxtId) p.destId >= p.nxtId else p.destId <= p.nxtId
  }

  def unidirectionalTorus1DDateline(nNodes: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    (if (srcIsIngress(nodeId)(p)) {
      !nxtIsVC0
    } else if (srcIsVC0(nodeId)(p)) {
      nxtIsVC0
    } else if (nodeId == nNodes - 1) {
      nxtVLTSrcV
    } else {
      nxtVLESrcV && !nxtIsVC0
    })(nodeId)(p)

    // if (p.srcId == -1)  {
    //   p.nxtV != 0
    // } else if (p.srcV == 0) {
    //   p.nxtV == 0
    // } else if (nodeId == nNodes - 1) {
    //   p.nxtV < p.srcV
    // } else {
    //   p.nxtV <= p.srcV && p.nxtV != 0
    // }
  }



  def bidirectionalTorus1DDateline(nNodes: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    if (p.srcId == -1)  {
      p.nxtV != 0
    } else if (p.srcV == 0) {
      p.nxtV == 0
    } else if ((p.nxtId + nNodes - nodeId) % nNodes == 1) {
      if (nodeId == nNodes - 1) {
        p.nxtV < p.srcV
      } else {
        p.nxtV <= p.srcV && p.nxtV != 0
      }
    } else if ((nodeId + nNodes - p.nxtId) % nNodes == 1) {
      if (nodeId == 0) {
        p.nxtV < p.srcV
      } else {
        p.nxtV <= p.srcV && p.nxtV != 0
      }
    } else {
      false
    }
  }

  def bidirectionalTorus1DShortest(nNodes: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val cwDist = (p.destId + nNodes - nodeId) % nNodes
    val ccwDist = (nodeId + nNodes - p.destId) % nNodes
    val distSel = if (cwDist < ccwDist) {
      (p.nxtId + nNodes - nodeId) % nNodes == 1
    } else if (cwDist > ccwDist) {
      (nodeId + nNodes - p.nxtId) % nNodes == 1
    } else {
      true
    }
    distSel && bidirectionalTorus1DDateline(nNodes)(nodeId)(p)
  }

  def bidirectionalTorus1DRandom(nNodes: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val sel = if (p.srcId == -1) {
      true
    } else if ((nodeId + nNodes - p.srcId) % nNodes == 1) {
      (p.nxtId + nNodes - nodeId) % nNodes == 1
    } else {
      (nodeId + nNodes - p.nxtId) % nNodes == 1
    }
    sel && bidirectionalTorus1DDateline(nNodes)(nodeId)(p)
  }

  def butterfly(kAry: Int, nFly: Int): MasterAllocTable = {
    require(kAry >= 2 && nFly >= 2)
    val height = pow(kAry, nFly-1).toInt
    def digitsToNum(dig: Seq[Int]) = dig.zipWithIndex.map { case (d,i) => d * pow(kAry,i).toInt }.sum
    val table = (0 until pow(kAry, nFly).toInt).map { i =>
      (0 until nFly).map { n => (i / pow(kAry, n).toInt) % kAry }
    }
    val channels = (1 until nFly).map { i =>
      table.map { e => (digitsToNum(e.drop(1)), digitsToNum(e.updated(i, e(0)).drop(1))) }
    }

    (nodeId: Int) => (p: AllocParams) => {
      val (nxtX, nxtY) = (p.nxtId / height, p.nxtId % height)
      val (nodeX, nodeY) = (nodeId / height, nodeId % height)
      val (dstX, dstY) = (p.destId / height, p.destId % height)
      if (dstX <= nodeX) {
        false
      } else if (nodeX == nFly-1) {
        true
      } else {
        val dsts = (nxtX until nFly-1).foldRight((0 until height).map { i => Seq(i) }) {
          case (i,l) => (0 until height).map { s => channels(i).filter(_._1 == s).map { case (_,d) =>
            l(d)
          }.flatten }
        }
        dsts(nxtY).contains(p.destId % height)
      }
    }
  }


  def mesh2DDimensionOrdered(firstDim: Int = 0)(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    if (firstDim == 0) {
      if (dstX != nodeX) {
        (if (nodeX < nxtX) dstX >= nxtX else dstX <= nxtX) && nxtY == nodeY
      } else {
        (if (nodeY < nxtY) dstY >= nxtY else dstY <= nxtY) && nxtX == nodeX
      }
    } else {
      if (dstY != nodeY) {
        (if (nodeY < nxtY) dstY >= nxtY else dstY <= nxtY) && nxtX == nodeX
      } else {
        (if (nodeX < nxtX) dstX >= nxtX else dstX <= nxtX) && nxtY == nodeY
      }
    }
  }

  // WARNING: Not deadlock free
  def mesh2DMinimal(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX, p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    val xR = (if (nodeX < nxtX) dstX >= nxtX else if (nodeX > nxtX) dstX <= nxtX else nodeX == nxtX)
    val yR = (if (nodeY < nxtY) dstY >= nxtY else if (nodeY > nxtY) dstY <= nxtY else nodeY == nxtY)
    xR && yR
  }


  def mesh2DWestFirst(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    if (dstX < nodeX) {
      nxtX == nodeX - 1
    } else {
      mesh2DMinimal(nX, nY)(nodeId)(p)
    }
  }

  def mesh2DNorthLast(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    if (dstY > nodeY && dstX != nodeX) {
      mesh2DMinimal(nX, nY)(nodeId)(p) && nxtY != nodeY + 1
    } else if (dstY > nodeY) {
      nxtY == nodeY + 1
    } else {
      mesh2DMinimal(nX, nY)(nodeId)(p)
    }
  }



  def mesh2DAlternatingDimensionOrdered(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    val turn = nxtX != srcX && nxtY != srcY
    val canRouteThis = mesh2DDimensionOrdered(p.srcV % 2)(nX, nY)(nodeId)(p)
    val canRouteNext = mesh2DDimensionOrdered(p.nxtV % 2)(nX, nY)(nodeId)(p)

    val sel = if (p.srcId == -1) {
      canRouteNext
    } else {
      (canRouteThis && p.nxtV % 2 == p.srcV % 2 && p.nxtV <= p.srcV) || (canRouteNext && p.nxtV % 2 != p.srcV % 2 && p.nxtV <= p.srcV)
    }
    sel && mesh2DMinimal(nX, nY)(nodeId)(p)
  }

  def mesh2DDimensionOrderedHighest(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    if (p.nxtV == 0) {
      mesh2DDimensionOrdered()(nX, nY)(nodeId)(p)
    } else if (p.srcId == -1) {
      p.nxtV != 0 && mesh2DMinimal(nX, nY)(nodeId)(p)
    } else {
      p.nxtV <= p.srcV && mesh2DMinimal(nX, nY)(nodeId)(p)
    }
  }

  def unidirectionalTorus2DDateline(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    val turn = nxtX != srcX && nxtY != srcY
    if (p.srcId == -1 || turn) {
      p.nxtV != 0
    } else if (srcX == nxtX) {
      unidirectionalTorus1DDateline(nY)(nodeY)(p.copy(srcId=srcY, nxtId=nxtY, destId=dstY))
    } else if (srcY == nxtY) {
      unidirectionalTorus1DDateline(nX)(nodeX)(p.copy(srcId=srcX, nxtId=nxtX, destId=dstX))
    } else {
      false
    }
  }

  def bidirectionalTorus2DDateline(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    if (p.srcId == -1) {
      p.nxtV != 0
    } else if (nodeX == nxtX) {
      bidirectionalTorus1DDateline(nY)(nodeY)(p.copy(srcId=srcY, nxtId=nxtY, destId=dstY))
    } else if (nodeY == nxtY) {
      bidirectionalTorus1DDateline(nX)(nodeX)(p.copy(srcId=srcX, nxtId=nxtX, destId=dstX))
    } else {
      false
    }
  }



  def dimensionOrderedUnidirectionalTorus2DDateline(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    def sel = if (dstX != nodeX) {
      nxtY == nodeY
    } else {
      nxtX == nodeX
    }
    sel && unidirectionalTorus2DDateline(nX, nY)(nodeId)(p)
  }

  def dimensionOrderedBidirectionalTorus2DDateline(nX: Int, nY: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val (nxtX, nxtY)   = (p.nxtId % nX , p.nxtId / nX)
    val (nodeX, nodeY) = (nodeId % nX, nodeId / nX)
    val (dstX, dstY)   = (p.destId % nX , p.destId / nX)
    val (srcX, srcY)   = (p.srcId % nX , p.srcId / nX)

    val xdir = bidirectionalTorus1DShortest(nX)(nodeX)(p.copy(srcId=(if (p.srcId == -1) -1 else srcX), nxtId=nxtX, destId=dstX))
    val ydir = bidirectionalTorus1DShortest(nY)(nodeY)(p.copy(srcId=(if (p.srcId == -1) -1 else srcY), nxtId=nxtY, destId=dstY))
    val base = bidirectionalTorus2DDateline(nX, nY)(nodeId)(p)
    val sel = if (dstX != nodeX) xdir else ydir

    sel && base
  }


  // The below tables implement support for virtual subnetworks in a variety of ways
  // NOTE: The topology must have sufficient virtual channels for these to work correctly
  // TODO: Write assertions to check this

  // Independent virtual subnets with no resource sharing
  def nonblockingVirtualSubnetworks(f: MasterAllocTable, n: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    (p.nxtV % n == p.vNetId) && f(nodeId)(p.copy(srcV=p.srcV / n, nxtV=p.nxtV / n, vNetId=0))
  }

  // Virtual subnets with 1 dedicated virtual channel each, and some number of shared channels
  def sharedNonblockingVirtualSubnetworks(f: MasterAllocTable, n: Int, nSharedChannels: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    def trueVIdToVirtualVId(vId: Int) = if (vId < n) 0 else vId - n
    f(nodeId)(p.copy(srcV=trueVIdToVirtualVId(p.srcV), nxtV=trueVIdToVirtualVId(p.nxtV), vNetId=0))
  }

  def blockingVirtualSubnetworks(f: MasterAllocTable, n: Int): MasterAllocTable = (nodeId: Int) => (p: AllocParams) => {
    val lNxtV = p.nxtV - p.vNetId
    if (lNxtV < 0) {
      false
    } else {
      f(nodeId)(p.copy(nxtV=lNxtV, vNetId=0))
    }
  }
}
